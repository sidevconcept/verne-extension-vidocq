package io.vidocq.verne.compilation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * Première tranche du compilateur : ressources locales UTF-8 et génération Java.
 * L'instance appartient à une compilation ; aucun contexte ne persiste entre deux tâches.
 */
public final class ProcesseurVerne extends AbstractProcessor {
    private boolean traite;
    private static final int TAILLE_MAXIMALE = 1_048_576;
    private static final int VOLUME_MAXIMAL_SOURCES = 8 * 1_048_576;
    private static final int NOMBRE_MAXIMAL_GABARITS = 1_024;

    /** Constructeur public exigé par le chargement des processeurs du compilateur Java. */
    public ProcesseurVerne() {}

    @Override public Set<String> getSupportedAnnotationTypes() { return Set.of("*"); }
    @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.RELEASE_25; }
    @Override public Set<String> getSupportedOptions() {
        return Set.of("verne.racine.gabarits", "verne.profil.sortie");
    }

    /** Inventorie toutes les ressources, sans exiger d'annotation sur l'application. */
    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment tour) {
        if (traite || tour.processingOver()) return false;
        traite = true;
        String racineDeclaree = processingEnv.getOptions().get("verne.racine.gabarits");
        if (racineDeclaree == null || !"texte".equals(processingEnv.getOptions().get("verne.profil.sortie"))) {
            erreur("VERNE-CFG-001", "déclarer une racine de gabarits et le profil texte.");
            return false;
        }
        try {
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
                String base = fichier.getFileName().toString();
                base = base.substring(0, base.length() - ".verne".length());
                if (!SourceVersion.isIdentifier(base) || SourceVersion.isKeyword(base)) {
                    erreur("VERNE-LIA-001", identifiant + ":1:1 : nom de gabarit incompatible avec une classe Java.");
                    continue;
                }
                String classe = Character.toUpperCase(base.charAt(0)) + base.substring(1);
                String nomPortable = Normalizer.normalize(
                        Normalizer.normalize(classe, Normalizer.Form.NFC).toLowerCase(Locale.ROOT),
                        Normalizer.Form.NFC);
                if (!noms.add(nomPortable)) {
                    erreur("VERNE-LIA-001", identifiant + ":1:1 : plusieurs ressources produisent des noms de classe équivalents.");
                    continue;
                }
                byte[] octets;
                try (var entree = Files.newInputStream(fichier)) {
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
        } catch (IOException | java.nio.file.InvalidPathException exception) {
            erreur("VERNE-IO-001", "impossible de lire ou de générer les ressources déclarées.");
        }
        return false;
    }

    /** Chaque ressource dispose de son analyseur et de sa table de paramètres isolés. */
    private void compilerTexte(String classe, String texte, String identifiant) throws IOException {
        new CompilateurGabarit(processingEnv).compiler(classe, texte, identifiant);
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
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, code + " : " + message);
    }
}
