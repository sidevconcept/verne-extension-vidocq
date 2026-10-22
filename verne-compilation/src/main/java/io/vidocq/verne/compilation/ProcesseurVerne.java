package io.vidocq.verne.compilation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * Première tranche du compilateur : ressources locales UTF-8 et génération Java.
 * L'instance appartient à une compilation ; aucun contexte ne persiste entre deux tâches.
 */
public final class ProcesseurVerne extends AbstractProcessor {
    private boolean traite;
    private String paquet;
    private VisibiliteModules visibilite;
    private int nombreDiagnostics;
    private boolean diagnosticsSatures;
    private final List<GabaritEnAttente> enAttente = new ArrayList<>();
    private static final int TAILLE_MAXIMALE = 1_048_576;
    private static final int VOLUME_MAXIMAL_SOURCES = 8 * 1_048_576;
    private static final int NOMBRE_MAXIMAL_GABARITS = 1_024;
    private static final int NOMBRE_MAXIMAL_DIAGNOSTICS = 64;

    private record GabaritEnAttente(String classe, String texte, String identifiant, String diagnostic) {}

    /** Constructeur public exigé par le chargement des processeurs du compilateur Java. */
    public ProcesseurVerne() {}

    @Override public Set<String> getSupportedAnnotationTypes() { return Set.of("*"); }
    /** Java 25 reste le minimum ; un compilateur plus récent ne doit pas signaler une version dépassée. */
    @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
    @Override public Set<String> getSupportedOptions() {
        return Set.of("verne.racine.gabarits", "verne.paquet", "verne.profil.sortie");
    }

    /**
     * Inventorie une fois les ressources, puis reprend les gabarits dont les types étaient absents.
     * Si javac a interrompu les tours après une erreur, les attentes ne prouvent pas l'absence d'un type.
     */
    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment tour) {
        if (tour.processingOver()) {
            if (!enAttente.isEmpty() && tour.errorRaised()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "VERNE-TYP-005 : "
                        + "vérification interrompue par une erreur de compilation ; gabarits en attente non vérifiés : "
                        + enAttente.size() + ".");
            } else {
                for (GabaritEnAttente gabarit : enAttente) {
                    if (diagnosticsSatures) break;
                    signalerErreur(gabarit.diagnostic());
                }
            }
            enAttente.clear();
            return false;
        }
        try {
            if (traite) {
                var aReprendre = List.copyOf(enAttente);
                enAttente.clear();
                for (GabaritEnAttente gabarit : aReprendre) {
                    if (diagnosticsSatures) break;
                    compilerTexte(gabarit.classe(), gabarit.texte(), gabarit.identifiant());
                }
                return false;
            }
            traite = true;
            String racineDeclaree = processingEnv.getOptions().get("verne.racine.gabarits");
            paquet = processingEnv.getOptions().get("verne.paquet");
            if (racineDeclaree == null || paquet == null || !CompilateurGabarit.nomQualifie(paquet)
                    || !"texte".equals(processingEnv.getOptions().get("verne.profil.sortie"))) {
                erreur("VERNE-CFG-001", "déclarer une racine de gabarits, un paquet Java qualifié et le profil texte.");
                return false;
            }
            visibilite = new VisibiliteModules(processingEnv.getElementUtils(), moduleCourant(tour));
            Path racine = Path.of(racineDeclaree).toRealPath();
            if (!Files.isDirectory(racine)) {
                erreur("VERNE-CFG-001", "la racine des gabarits doit être un répertoire.");
                return false;
            }
            List<Path> fichiers;
            try (var parcours = Files.walk(racine)) {
                fichiers = parcours.filter(chemin -> chemin.toString().endsWith(".verne"))
                        .filter(chemin -> Files.isRegularFile(chemin)
                                || (Files.isSymbolicLink(chemin) && !Files.exists(chemin)))
                        .limit(NOMBRE_MAXIMAL_GABARITS + 1L).sorted().toList();
            }
            if (fichiers.size() > NOMBRE_MAXIMAL_GABARITS) {
                erreur("VERNE-LIM-002", "plus de 1 024 ressources de gabarits dans une compilation.");
                return false;
            }
            var noms = new HashSet<String>();
            int volumeLu = 0;
            for (Path fichier : fichiers) {
                if (diagnosticsSatures) break;
                String identifiant = racine.relativize(fichier).toString().replace('\\', '/');
                Path cible;
                try {
                    cible = fichier.toRealPath();
                } catch (IOException exception) {
                    erreur("VERNE-IO-001", identifiant + ":1:1 : impossible de résoudre la ressource déclarée.");
                    continue;
                }
                if (!cible.startsWith(racine)) {
                    erreur("VERNE-SEC-001", identifiant + ":1:1 : ressource hors de la racine autorisée.");
                    continue;
                }
                String base = Normalizer.normalize(fichier.getFileName().toString(), Normalizer.Form.NFC);
                base = base.substring(0, base.length() - ".verne".length());
                if (!SourceVersion.isIdentifier(base) || SourceVersion.isKeyword(base)) {
                    erreur("VERNE-LIA-001", identifiant + ":1:1 : nom de gabarit incompatible avec une classe Java.");
                    continue;
                }
                String classe = Character.toUpperCase(base.charAt(0)) + base.substring(1);
                String nomPortable = Normalizer.normalize(classe.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
                if (!noms.add(nomPortable)) {
                    erreur("VERNE-LIA-001", identifiant + ":1:1 : plusieurs ressources produisent des noms de classe équivalents.");
                    continue;
                }
                byte[] octets;
                try (var entree = Files.newInputStream(cible)) {
                    octets = entree.readNBytes(Math.min(TAILLE_MAXIMALE, VOLUME_MAXIMAL_SOURCES - volumeLu) + 1);
                }
                volumeLu += octets.length;
                if (volumeLu > VOLUME_MAXIMAL_SOURCES) {
                    erreur("VERNE-LIM-006", identifiant + ":1:1 : volume cumulé de lecture supérieur à huit mébioctets.");
                    return false;
                }
                if (octets.length > TAILLE_MAXIMALE) {
                    erreur("VERNE-LIM-001", identifiant + ":1:1 : source supérieure à un mébioctet.");
                    continue;
                }
                try {
                    String texte = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(octets)).toString();
                    compilerTexte(classe, texte, identifiant);
                } catch (CharacterCodingException exception) {
                    erreur("VERNE-SYN-003", identifiant + ":1:1 : source UTF-8 malformée.");
                }
            }
        } catch (IOException | UncheckedIOException | java.nio.file.InvalidPathException exception) {
            erreur("VERNE-IO-001", "impossible de lire ou de générer les ressources déclarées.");
        }
        return false;
    }

    /** Chaque ressource dispose de son analyseur ; un échec de création Java n'arrête pas les suivantes. */
    private void compilerTexte(String classe, String texte, String identifiant) {
        try {
            String diagnostic = new CompilateurGabarit(processingEnv, this::signalerErreur, paquet, visibilite)
                    .compiler(classe, texte, identifiant);
            if (diagnostic != null) enAttente.add(new GabaritEnAttente(classe, texte, identifiant, diagnostic));
        } catch (IOException exception) {
            erreur("VERNE-IO-001", identifiant + ":1:1 : impossible de générer la source Java.");
        }
    }

    /** Le module des sources compilées ; null si javac ne fournit aucun élément racine. */
    private ModuleElement moduleCourant(RoundEnvironment tour) {
        for (Element element : tour.getRootElements()) {
            ModuleElement module = processingEnv.getElementUtils().getModuleOf(element);
            if (module != null) return module;
        }
        return null;
    }

    /** Protège les littéraux Java, y compris les caractères de contrôle et les antislashs. */
    static String echapper(String texte) {
        var resultat = new StringBuilder();
        for (int index = 0; index < texte.length(); index++) {
            char caractere = texte.charAt(index);
            switch (caractere) {
                case '\\' -> resultat.append("\\\\");
                case '"' -> resultat.append("\\\"");
                case '\n' -> resultat.append("\\n");
                case '\r' -> resultat.append("\\r");
                case '\t' -> resultat.append("\\t");
                default -> {
                    if (caractere < 32 || caractere == 127) {
                        resultat.append('\\').append(String.format(java.util.Locale.ROOT, "%03o", (int) caractere));
                    } else resultat.append(caractere);
                }
            }
        }
        return resultat.toString();
    }

    /** Émet un code stable et un message français sans valeur métier ni chemin absolu. */
    private void erreur(String code, String message) {
        signalerErreur(code + " : " + message);
    }

    /** Les refus de ressources et d'analyse consomment le même budget, sans troncature silencieuse. */
    private void signalerErreur(String message) {
        if (diagnosticsSatures) return;
        if (nombreDiagnostics == NOMBRE_MAXIMAL_DIAGNOSTICS) {
            diagnosticsSatures = true;
            message = "VERNE-LIM-007 : plus de 64 erreurs Verne ; arrêt de la vérification des ressources suivantes.";
        } else {
            nombreDiagnostics++;
        }
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message);
    }
}
