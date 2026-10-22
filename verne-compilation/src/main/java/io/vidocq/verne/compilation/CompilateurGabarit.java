package io.vidocq.verne.compilation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Analyse et génère un gabarit du sous-ensemble texte, paramètres et composants de records.
 * Les symboles proviennent du compilateur Java ; aucune classe métier n'est chargée.
 */
final class CompilateurGabarit {
    private static final int NOMBRE_MAXIMAL_INTERPOLATIONS = 1_024;
    private static final int NOMBRE_MAXIMAL_PARAMETRES = 255;
    private static final int PROFONDEUR_MAXIMALE_NAVIGATION = 32;
    private static final int COUT_MAXIMAL_GENERATION = 60_000;
    private final ProcessingEnvironment environnement;
    private final Consumer<String> rapporterErreur;
    private final String paquet;
    private final VisibiliteModules visibilite;
    private final Profil profil;
    private final Map<String, Parametre> parametres = new LinkedHashMap<>();
    private final LinkedHashSet<TypeElement> origines = new LinkedHashSet<>();
    private final List<Partie> parties = new ArrayList<>();
    private String texte;
    private String identifiant;

    /** Le type symbolique est conservé pour vérifier chaque navigation avant génération. */
    private record Parametre(String nom, TypeMirror type) {}
    private record Partie(String contenu, boolean interpolation, int position) {}
    /** Expression Java d'une interpolation ; texte vaut vrai pour une chaîne ou un char, seuls échappés. */
    private record Resolution(String expression, boolean texte) {}

    /** Une tentative s'arrête sur un refus définitif ou un type encore absent. */
    private static final class Refus extends RuntimeException {
        private final boolean differe;
        Refus(String message, boolean differe) {
            super(message, null, false, false);
            this.differe = differe;
        }
    }

    /**
     * Les arguments non nuls sont propres à l'invocation et partagent son budget d'erreurs ;
     * le paquet est un nom qualifié et le profil une valeur déjà validés par le processeur.
     */
    CompilateurGabarit(ProcessingEnvironment environnement, Consumer<String> rapporterErreur,
                       String paquet, VisibiliteModules visibilite, Profil profil) {
        this.environnement = environnement;
        this.rapporterErreur = rapporterErreur;
        this.paquet = paquet;
        this.visibilite = visibilite;
        this.profil = profil;
    }

    /** Vérifie avant création ; diagnostic différé, ou null après génération ou refus définitif. */
    String compiler(String classe, String texte, String identifiant) throws IOException {
        this.texte = texte;
        this.identifiant = identifiant;
        try {
            analyser();
            verifierBudgetGeneration();
            var emplacements = verifierContextes().iterator();
            boolean echappement = false;
            var instructions = new ArrayList<String>();
            var valeurs = new ArrayList<String>();
            var elements = new ArrayList<String>();
            int longueurStatique = parties.stream().filter(partie -> !partie.interpolation())
                    .mapToInt(partie -> partie.contenu().length()).sum();
            boolean grandDocument = longueurStatique >= 4_000;
            String resultat = nomLibre("$verneResultat");
            for (Partie partie : parties) {
                if (partie.interpolation()) {
                    var resolution = resoudre(partie);
                    boolean attribut = emplacements.hasNext() && emplacements.next();
                    boolean aEchapper = profil != Profil.TEXTE && resolution.texte();
                    echappement |= aEchapper;
                    String expression = grandDocument ? "valueOf(" + resolution.expression() + ")" : resolution.expression();
                    if (aEchapper) expression = profil == Profil.XML
                            ? "echapper(" + expression + ", " + attribut + ")" : "echapper(" + expression + ")";
                    if (grandDocument) {
                        String valeur = nomLibre("$verneValeur" + valeurs.size());
                        valeurs.add("var " + valeur + " = " + expression + ";\n");
                        elements.add(valeur);
                    } else {
                        instructions.add(resultat + ".append(" + expression + ");\n");
                    }
                } else {
                    String contenu = partie.contenu();
                    for (int debut = 0; debut < contenu.length();) {
                        int fin = finFragment(contenu, debut);
                        String litteral = "\"" + ProcesseurVerne.echapper(contenu.substring(debut, fin)) + "\"";
                        if (grandDocument) elements.add(litteral);
                        else instructions.add(resultat + ".append(" + litteral + ");\n");
                        debut = fin;
                    }
                }
            }
            var source = environnement.getFiler().createSourceFile(paquet + "." + classe,
                    origines.toArray(TypeElement[]::new));
            try (var sortie = source.openWriter()) {
                sortie.write("package " + paquet + ";\n"
                        + "import static java.util.Objects.requireNonNull;\n"
                        + (grandDocument ? "import static java.lang.String.join;\nimport static java.lang.String.valueOf;\n" : "")
                        + "/** Gabarit textuel compilé, sans analyse à la demande. */\n"
                        + "public final class " + classe + " {\nprivate " + classe + "() {}\n");
                sortie.write("/** Produit le document texte ; aucun état partagé entre appels.\n");
                for (Parametre parametre : parametres.values()) {
                    sortie.write(" * @param " + parametre.nom() + " valeur obligatoire non nulle\n");
                }
                sortie.write(" * @return document textuel rendu\n");
                if (!parametres.isEmpty()) {
                    sortie.write(" * @throws NullPointerException si une valeur obligatoire est nulle\n");
                }
                sortie.write(" */\npublic static java.lang.String rendre(");
                sortie.write(String.join(", ", parametres.values().stream()
                        .map(parametre -> parametre.type() + " " + parametre.nom()).toList()));
                sortie.write(") {\n");
                for (Parametre parametre : parametres.values()) {
                    sortie.write("requireNonNull(" + parametre.nom()
                            + ", \"VERNE-REN-001 : paramètre obligatoire nul.\");\n");
                }
                for (String valeur : valeurs) sortie.write(valeur);
                if (grandDocument) {
                    // Longueur et encodage connus avant l'unique copie ; l'import statique évite qu'un paramètre masque java.
                    sortie.write("return join(\"\",\n" + String.join(",\n", elements) + ");\n}\n");
                } else {
                    sortie.write("var " + resultat + " = new java.lang.StringBuilder();\n");
                    for (String instruction : instructions) sortie.write(instruction);
                    sortie.write("return " + resultat + ".toString();\n}\n");
                }
                if (echappement) sortie.write(profil == Profil.XML ? ECHAPPEMENT_XML : ECHAPPEMENT_HTML);
                sortie.write("}\n");
            }
        } catch (Refus refus) {
            if (refus.differe) return refus.getMessage();
            rapporterErreur.accept(refus.getMessage());
        }
        return null;
    }

    /** Budget conventionnel du générateur actuel ; les pondérations sont fixées dans le contrat. */
    private void verifierBudgetGeneration() {
        long cout = 128 + 12L * parametres.size();
        for (Partie partie : parties) {
            if (partie.interpolation()) {
                cout += 48 + 16L * partie.contenu().chars()
                        .filter(caractere -> caractere == '.').count();
            } else {
                for (int debut = 0; debut < partie.contenu().length();
                        debut = finFragment(partie.contenu(), debut)) {
                    cout += 12;
                }
            }
            if (cout > COUT_MAXIMAL_GENERATION) {
                throw refus("VERNE-LIM-008", 0, "coût de génération de la méthode supérieur à 60 000 unités.");
            }
        }
    }

    /** Même découpage pour le budget et la génération, sans séparer une paire de substituts. */
    private static int finFragment(String contenu, int debut) {
        int fin = Math.min(debut + 4_000, contenu.length());
        if (fin < contenu.length() && Character.isHighSurrogate(contenu.charAt(fin - 1))) fin--;
        return fin;
    }

    /**
     * Profils HTML et XML : caractères interdits du gabarit XML, puis emplacement de chaque interpolation.
     * Renvoie, dans l'ordre des interpolations, vrai pour une valeur d'attribut ; liste vide en profil texte.
     */
    private List<Boolean> verifierContextes() {
        var emplacements = new ArrayList<Boolean>();
        if (profil == Profil.TEXTE) return emplacements;
        if (profil == Profil.XML) {
            for (int index = 0; index < texte.length(); index++) {
                char caractere = texte.charAt(index);
                if (caractere < 0x20 && caractere != '\t' && caractere != '\n' && caractere != '\r'
                        || caractere == 0xFFFE || caractere == 0xFFFF) {
                    throw refus("VERNE-SYN-004", index, "caractère interdit par XML 1.0 dans le texte du gabarit.");
                }
            }
        }
        var contexte = new ContexteBalisage(profil);
        String nomProfil = profil == Profil.XML ? "XML" : "HTML";
        for (Partie partie : parties) {
            if (partie.interpolation()) {
                String interdit = contexte.refus();
                if (interdit != null) {
                    throw refus("VERNE-SEC-002", partie.position(),
                            "interpolation interdite dans ce contexte " + nomProfil + " : " + interdit + ".");
                }
                emplacements.add(contexte.dansAttribut());
                contexte.noterInterpolation(partie.position());
            } else {
                var interdiction = contexte.avancer(partie.contenu());
                if (interdiction != null) {
                    throw refus("VERNE-SEC-002", interdiction.position(), "interpolation interdite dans ce contexte "
                            + nomProfil + " : " + interdiction.contexte() + ".");
                }
            }
        }
        return emplacements;
    }

    /** Échappement HTML généré dans chaque classe : aucune copie sans caractère à remplacer. */
    private static final String ECHAPPEMENT_HTML = """
            private static java.lang.String echapper(char caractere) {
            return echapper(java.lang.String.valueOf(caractere));
            }
            private static java.lang.String echapper(java.lang.String valeur) {
            java.lang.StringBuilder resultat = null;
            for (int index = 0; index < valeur.length(); index++) {
            char caractere = valeur.charAt(index);
            java.lang.String remplacement = switch (caractere) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '"' -> "&quot;";
            case '\\'' -> "&#39;";
            default -> null;
            };
            if (remplacement == null) {
            if (resultat != null) resultat.append(caractere);
            } else {
            if (resultat == null) resultat = new java.lang.StringBuilder(valeur.length() + 16).append(valeur, 0, index);
            resultat.append(remplacement);
            }
            }
            return resultat == null ? valeur : resultat.toString();
            }
            """;

    /** Échappement XML généré : contenu ou attribut, refus des caractères interdits par XML 1.0. */
    private static final String ECHAPPEMENT_XML = """
            private static java.lang.String echapper(char caractere, boolean attribut) {
            return echapper(java.lang.String.valueOf(caractere), attribut);
            }
            private static java.lang.String echapper(java.lang.String valeur, boolean attribut) {
            java.lang.StringBuilder resultat = null;
            for (int index = 0; index < valeur.length(); index++) {
            char caractere = valeur.charAt(index);
            java.lang.String remplacement = switch (caractere) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '"' -> "&quot;";
            case '\\'' -> "&apos;";
            case '\\r' -> "&#13;";
            case '\\n' -> attribut ? "&#10;" : null;
            case '\\t' -> attribut ? "&#9;" : null;
            default -> null;
            };
            if (remplacement != null) {
            if (resultat == null) resultat = new java.lang.StringBuilder(valeur.length() + 16).append(valeur, 0, index);
            resultat.append(remplacement);
            continue;
            }
            boolean paire = java.lang.Character.isHighSurrogate(caractere) && index + 1 < valeur.length()
            && java.lang.Character.isLowSurrogate(valeur.charAt(index + 1));
            if (caractere < 0x20 && caractere != '\\n' && caractere != '\\t' || caractere == 0xFFFE || caractere == 0xFFFF
            || java.lang.Character.isSurrogate(caractere) && !paire) {
            throw new java.lang.IllegalArgumentException("VERNE-REN-002 : caractère interdit par XML 1.0 dans une valeur interpolée.");
            }
            if (resultat != null) resultat.append(caractere);
            if (paire) {
            index++;
            if (resultat != null) resultat.append(valeur.charAt(index));
            }
            }
            return resultat == null ? valeur : resultat.toString();
            }
            """;

    /** Les deux familles de variables locales restent distinctes et évitent tous les paramètres. */
    private String nomLibre(String nom) {
        while (parametres.containsKey(nom)) nom += "_";
        return nom;
    }

    /** Reconnaît uniquement les directives du sous-ensemble annoncé ; les autres sont refusées. */
    private void analyser() {
        int nombreInterpolations = 0;
        var fragment = new StringBuilder();
        for (int position = 0; position < texte.length();) {
            char caractere = texte.charAt(position);
            if (caractere == '\\') {
                if (position + 1 >= texte.length()
                        || (texte.charAt(position + 1) != '{' && texte.charAt(position + 1) != '\\')) {
                    throw refus("VERNE-SYN-002", position, "échappement inconnu ou incomplet.");
                }
                fragment.append(texte.charAt(position + 1));
                position += 2;
            } else if (caractere == '{') {
                vider(fragment);
                if (texte.startsWith("{!", position)) {
                    int fin = texte.indexOf("!}", position + 2);
                    if (fin < 0) throw refus("VERNE-SYN-001", position, "commentaire non fermé.");
                    position = fin + 2;
                } else {
                    int fin = texte.indexOf('}', position + 1);
                    if (fin < 0) throw refus("VERNE-SYN-001", position, "interpolation ou déclaration non fermée.");
                    String contenu = texte.substring(position + 1, fin).trim();
                    if (contenu.startsWith("@")) declarer(contenu.substring(1), position);
                    else {
                        if (!nomQualifie(contenu)) {
                            throw refus("VERNE-SYN-002", position, "expression ou directive non prise en charge.");
                        }
                        if (++nombreInterpolations > NOMBRE_MAXIMAL_INTERPOLATIONS) {
                            throw refus("VERNE-LIM-003", position, "plus de 1 024 interpolations dans un gabarit.");
                        }
                        if (contenu.chars().filter(caractereExpression -> caractereExpression == '.').count()
                                > PROFONDEUR_MAXIMALE_NAVIGATION) {
                            throw refus("VERNE-LIM-005", position, "plus de 32 accès de record dans une interpolation.");
                        }
                        parties.add(new Partie(contenu, true, position));
                    }
                    position = fin + 1;
                }
            } else {
                fragment.append(caractere);
                position++;
            }
        }
        vider(fragment);
    }

    /** Vide le tampon sans modifier les espaces ou fins de ligne qui l'entourent. */
    private void vider(StringBuilder fragment) {
        if (!fragment.isEmpty()) {
            parties.add(new Partie(fragment.toString(), false, 0));
            fragment.setLength(0);
        }
    }

    /** Déclare un type accessible non générique ; seules les propriétés de records sont exposées. */
    private void declarer(String declaration, int position) {
        String[] morceaux = declaration.trim().split("\\s+");
        if (morceaux.length != 2 || !nomQualifie(morceaux[0]) || !nomSimple(morceaux[1])) {
            throw refus("VERNE-SYN-002", position, "déclaration attendue : type Java qualifié et nom de paramètre.");
        }
        if (parametres.containsKey(morceaux[1])) {
            throw refus("VERNE-TYP-002", position, "paramètre déclaré plusieurs fois.");
        }
        if (parametres.size() >= NOMBRE_MAXIMAL_PARAMETRES) {
            throw refus("VERNE-LIM-004", position, "plus de 255 paramètres déclarés dans un gabarit.");
        }
        TypeElement type = environnement.getElementUtils().getTypeElement(morceaux[0]);
        if (type == null || type.asType().getKind() == TypeKind.ERROR) throw attendreType(position);
        if (!type.getTypeParameters().isEmpty() || !visibilite.accessible(type)) {
            throw refus("VERNE-TYP-003", position, "type inconnu, inaccessible ou non pris en charge.");
        }
        enregistrerOrigine(type.asType());
        parametres.put(morceaux[1], new Parametre(morceaux[1], type.asType()));
    }

    /** Résout seulement les composants déclarés des records, puis vérifie le type scalaire final. */
    private Resolution resoudre(Partie partie) {
        String[] chemin = partie.contenu().split("\\.");
        Parametre parametre = parametres.get(chemin[0]);
        if (parametre == null) throw refus("VERNE-TYP-001", partie.position(), "paramètre inconnu.");
        TypeMirror type = parametre.type();
        String expression = parametre.nom();
        for (int index = 1; index < chemin.length; index++) {
            if (type.getKind() == TypeKind.ERROR) throw attendreType(partie.position());
            if (!(type instanceof DeclaredType declare)
                    || !(declare.asElement() instanceof TypeElement element)
                    || element.getKind() != ElementKind.RECORD || !visibilite.accessible(element)) {
                throw refus("VERNE-TYP-001", partie.position(), "propriété inconnue ou inaccessible.");
            }
            String nom = chemin[index];
            var composant = element.getRecordComponents().stream()
                    .filter(candidat -> candidat.getSimpleName().contentEquals(nom)).findFirst()
                    .filter(candidat -> candidat.getAccessor().getModifiers().contains(Modifier.PUBLIC))
                    .orElseThrow(() -> refus("VERNE-TYP-001", partie.position(), "propriété inconnue ou inaccessible."));
            type = ((ExecutableType) environnement.getTypeUtils().asMemberOf(
                    declare, composant.getAccessor())).getReturnType();
            enregistrerOrigine(type);
            expression = "requireNonNull(" + expression
                    + ", \"VERNE-REN-001 : objet de navigation nul.\")." + nom + "()";
        }
        if (type.getKind() == TypeKind.ERROR) throw attendreType(partie.position());
        var chaine = environnement.getElementUtils().getTypeElement("java.lang.String").asType();
        if (!type.getKind().isPrimitive() && !environnement.getTypeUtils().isSameType(type, chaine)) {
            throw refus("VERNE-TYP-004", partie.position(), "la valeur interpolée doit être une chaîne ou un scalaire primitif.");
        }
        if (!type.getKind().isPrimitive()) {
            expression = "requireNonNull(" + expression + ", \"VERNE-REN-001 : valeur interpolée nulle.\")";
        }
        return new Resolution(expression, !type.getKind().isPrimitive() || type.getKind() == TypeKind.CHAR);
    }

    /** Les origines standard permettent à l'outillage de relier une sortie à ses modèles Java. */
    private void enregistrerOrigine(TypeMirror type) {
        if (type instanceof DeclaredType declare && type.getKind() != TypeKind.ERROR) {
            for (Element element = declare.asElement(); element instanceof TypeElement origine;
                    element = element.getEnclosingElement()) {
                origines.add(origine);
            }
        }
    }

    /** Les caractères ignorés par Java sont refusés pour éviter des noms visuellement distincts équivalents. */
    private static boolean nomSimple(String nom) {
        return SourceVersion.isIdentifier(nom) && !SourceVersion.isKeyword(nom)
                && nom.codePoints().noneMatch(Character::isIdentifierIgnorable);
    }

    /** Nom qualifié Java dont chaque segment respecte les règles de nomSimple. */
    static boolean nomQualifie(String nom) {
        return !nom.isEmpty() && java.util.Arrays.stream(nom.split("\\.", -1)).allMatch(CompilateurGabarit::nomSimple);
    }

    /** Les colonnes comptent les points de code ; CRLF désigne une seule fin de ligne. */
    private Refus refus(String code, int position, String message) {
        return refus(code, position, message, false);
    }

    private Refus attendreType(int position) {
        return refus("VERNE-TYP-003", position, "type inconnu, inaccessible ou non pris en charge.", true);
    }

    private Refus refus(String code, int position, String message, boolean differe) {
        int ligne = 1;
        int debutLigne = 0;
        for (int index = 0; index < position; index++) {
            char caractere = texte.charAt(index);
            if (caractere == '\r') {
                if (index + 1 < position && texte.charAt(index + 1) == '\n') index++;
                ligne++;
                debutLigne = index + 1;
            } else if (caractere == '\n') {
                ligne++;
                debutLigne = index + 1;
            }
        }
        int colonne = texte.codePointCount(debutLigne, position) + 1;
        return new Refus(code + " : " + identifiant + ":" + ligne + ":" + colonne + " : " + message, differe);
    }
}
