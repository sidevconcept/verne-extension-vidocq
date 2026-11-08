package io.vidocq.verne.compilation;

import java.io.IOException;
import java.util.ArrayDeque;
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
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;

/**
 * Analyse et génère un gabarit du sous-ensemble texte, paramètres et composants de records.
 * Les symboles proviennent du compilateur Java ; aucune classe métier n'est chargée.
 */
final class CompilateurGabarit {
    private static final int NOMBRE_MAXIMAL_INTERPOLATIONS = 1_024;
    private static final int NOMBRE_MAXIMAL_PARAMETRES = 255;
    private static final int PROFONDEUR_MAXIMALE_NAVIGATION = 32;
    private static final int PROFONDEUR_MAXIMALE_BLOCS = 32;
    private static final int COUT_MAXIMAL_GENERATION = 60_000;
    private final ProcessingEnvironment environnement;
    private final Consumer<String> rapporterErreur;
    private final String paquet;
    private final VisibiliteModules visibilite;
    private final Profil profil;
    private final Map<String, Parametre> parametres = new LinkedHashMap<>();
    private final LinkedHashSet<TypeElement> origines = new LinkedHashSet<>();
    private final List<Partie> parties = new ArrayList<>();
    /** Variables de boucle visibles pendant la génération, la plus intérieure en tête. */
    private final ArrayDeque<Variable> locales = new ArrayDeque<>();
    private String texte;
    private String identifiant;
    /** Vrai si la classe générée appelle l'arithmétique entière contrôlée. */
    private boolean arithmetique;

    /** Le type symbolique est conservé pour vérifier chaque navigation avant génération. */
    private record Parametre(String nom, TypeMirror type) {}
    /**
     * Texte, interpolation ou directive de bloc ; contenu porte le texte de l'expression ou le chemin d'une boucle,
     * variable le nom d'une boucle, expression l'arbre d'une interpolation ou d'une condition.
     */
    private record Partie(String contenu, Genre genre, int position, String variable, Expression expression) {
        boolean interpolation() {
            return genre == Genre.INTERPOLATION;
        }
        boolean bloc() {
            return genre.compareTo(Genre.SI) >= 0;
        }
    }
    private enum Genre { TEXTE, INTERPOLATION, SI, SINON, FIN_SI, POUR, FIN_POUR }
    /** Variable de boucle : nom du gabarit, nom Java généré et type de ses éléments. */
    private record Variable(String nom, String java, TypeMirror type) {}
    /** Expression Java d'un chemin navigué et son type final. */
    private record Chemin(String expression, TypeMirror type) {}
    /** Valeur typée d'une sous-expression : code Java déjà contrôlé non nul et déballé, et sa catégorie. */
    private record Valeur(String code, Categorie categorie) {}
    /** Catégories du contrat des expressions ; les numériques sont rangés par ordre de promotion. */
    private enum Categorie {
        INT("nombre"), LONG("nombre"), FLOAT("nombre"), DOUBLE("nombre"), BOOLEEN("booléen"), CARACTERE("caractère"),
        CHAINE("chaîne"), AUTRE("objet");
        private final String nom;
        Categorie(String nom) {
            this.nom = nom;
        }
        boolean numerique() {
            return compareTo(DOUBLE) <= 0;
        }
    }
    /** Expression Java d'une interpolation ; texte vaut vrai pour une chaîne ou un char, seuls échappés. */
    private record Resolution(String expression, boolean texte, boolean primitif, boolean flottant) {}

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
            boolean nombreFini = false;
            arithmetique = false;
            var instructions = new ArrayList<String>();
            var valeurs = new ArrayList<String>();
            var elements = new ArrayList<String>();
            var longueurs = new ArrayList<String>();
            int longueurStatique = parties.stream().filter(partie -> partie.genre() == Genre.TEXTE)
                    .mapToInt(partie -> partie.contenu().length()).sum();
            // Avec des blocs, une valeur n'est évaluée que si son chemin d'exécution est suivi : écriture au fil du rendu.
            boolean auFil = parties.stream().anyMatch(Partie::bloc);
            boolean grandDocument = !auFil && longueurStatique >= 4_000;
            String resultat = nomLibre("$verneResultat");
            int elementsBoucles = 0;
            for (Partie partie : parties) {
                if (partie.bloc()) {
                    instructions.add(switch (partie.genre()) {
                        case SI -> "if (" + condition(partie) + ") {\n";
                        case SINON -> "} else {\n";
                        case POUR -> boucle(partie, nomLibre("$verneElement" + elementsBoucles++));
                        default -> {
                            if (partie.genre() == Genre.FIN_POUR) locales.pop();
                            yield "}\n";
                        }
                    });
                } else if (partie.interpolation()) {
                    var resolution = resoudre(partie);
                    // Attribut entre guillemets en XML ; littéral de chaîne en JSON.
                    boolean attribut = emplacements.hasNext() && emplacements.next();
                    String brute = resolution.expression();
                    if (profil == Profil.JSON && !attribut) {
                        if (resolution.texte()) {
                            throw refus("VERNE-SEC-002", partie.position(), "interpolation interdite dans ce contexte JSON :"
                                    + " chaîne ou caractère hors d'un littéral entre guillemets.");
                        }
                        if (resolution.flottant()) {
                            brute = "fini(" + brute + ")";
                            nombreFini = true;
                        }
                    }
                    boolean aEchapper = profil != Profil.TEXTE && resolution.texte();
                    echappement |= aEchapper;
                    String valeur = nomLibre("$verneValeur" + valeurs.size());
                    String suffixe = profil == Profil.XML ? ", " + attribut : "";
                    if (auFil) {
                        instructions.add(aEchapper ? "echapper(" + resultat + ", " + brute + suffixe + ");\n"
                                : resultat + ".append(" + brute + ");\n");
                    } else if (grandDocument) {
                        String expression = "valueOf(" + brute + ")";
                        if (aEchapper) expression = "echapper(" + expression + suffixe + ")";
                        valeurs.add("var " + valeur + " = " + expression + ";\n");
                        elements.add(valeur);
                    } else {
                        // Valeurs évaluées d'abord pour dimensionner le tampon ; l'échappement écrit directement dedans.
                        valeurs.add("var " + valeur + " = " + brute + ";\n");
                        longueurs.add(resolution.primitif() ? "24" : valeur + ".length()");
                        instructions.add(aEchapper ? "echapper(" + resultat + ", " + valeur + suffixe + ");\n"
                                : resultat + ".append(" + valeur + ");\n");
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
                        + (grandDocument ? "import static java.lang.String.join;\nimport static java.lang.String.valueOf;\n"
                            : longueurs.isEmpty() ? "" : "import static java.lang.Math.min;\n")
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
                    String capacite = longueurs.isEmpty() ? String.valueOf(longueurStatique)
                            : "(int) min(2_147_483_647L, " + longueurStatique + "L + " + String.join(" + ", longueurs) + ")";
                    sortie.write("var " + resultat + " = new java.lang.StringBuilder(" + capacite + ");\n");
                    for (String instruction : instructions) sortie.write(instruction);
                    sortie.write("return " + resultat + ".toString();\n}\n");
                }
                if (echappement) {
                    sortie.write(switch (profil) {
                        case XML -> grandDocument ? ECHAPPEMENT_XML : ECHAPPEMENT_XML_TAMPON;
                        case JSON -> grandDocument ? ECHAPPEMENT_JSON_TAMPON + ECHAPPEMENT_JSON_CHAINE : ECHAPPEMENT_JSON_TAMPON;
                        default -> grandDocument ? ECHAPPEMENT_HTML : ECHAPPEMENT_HTML_TAMPON;
                    });
                }
                if (nombreFini) sortie.write(NOMBRE_FINI_JSON);
                if (arithmetique) sortie.write(ARITHMETIQUE_EXACTE);
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
            if (partie.interpolation() || partie.genre() == Genre.SI) {
                cout += 48 + Expression.cout(partie.expression());
            } else if (partie.genre() == Genre.POUR) {
                cout += 48 + 16L * partie.contenu().chars()
                        .filter(caractere -> caractere == '.').count();
            } else if (partie.bloc()) {
                cout += 8;
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
     * Profils HTML, XML et JSON : caractères interdits du gabarit XML, puis emplacement de chaque interpolation.
     * Renvoie, dans l'ordre des interpolations, vrai pour une valeur d'attribut (HTML, XML) ou un littéral de chaîne
     * (JSON) ; liste vide en profil texte.
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
        if (profil == Profil.JSON) {
            var json = new ContexteJson();
            for (Partie partie : parties) {
                if (partie.bloc()) {
                    if (json.dansChaine()) {
                        throw refus("VERNE-SEC-003", partie.position(), "directive de bloc dans un littéral de chaîne JSON.");
                    }
                    continue;
                }
                if (!partie.interpolation()) {
                    json.avancer(partie.contenu());
                    continue;
                }
                String interdit = json.refus();
                if (interdit != null) {
                    throw refus("VERNE-SEC-002", partie.position(), "interpolation interdite dans ce contexte JSON : " + interdit + ".");
                }
                emplacements.add(json.dansChaine());
            }
            return emplacements;
        }
        var contexte = new ContexteBalisage(profil);
        String nomProfil = profil == Profil.XML ? "XML" : "HTML";
        for (Partie partie : parties) {
            if (partie.bloc()) {
                // Bornes toutes dans le contenu : chaque chemin d'exécution traverse les mêmes états de contexte.
                if (!contexte.dansContenu()) {
                    throw refus("VERNE-SEC-003", partie.position(), "directive de bloc hors du contenu " + nomProfil + ".");
                }
            } else if (partie.interpolation()) {
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

    /**
     * Table HTML partagée par les deux formes : remplacement indexé par le caractère, null s'il est conservé.
     * Immuable après l'initialisation de la classe, donc lue sans synchronisation par des rendus concurrents.
     */
    private static final String TABLE_HTML = """
            private static final java.lang.String[] ECHAPPEMENTS = new java.lang.String[63];
            static {
            ECHAPPEMENTS['&'] = "&amp;";
            ECHAPPEMENTS['<'] = "&lt;";
            ECHAPPEMENTS['>'] = "&gt;";
            ECHAPPEMENTS['"'] = "&quot;";
            ECHAPPEMENTS['\\''] = "&#39;";
            }
            """;

    /** Échappement HTML généré dans chaque classe : aucune copie sans caractère à remplacer. */
    private static final String ECHAPPEMENT_HTML = TABLE_HTML + """
            private static java.lang.String echapper(char caractere) {
            return echapper(java.lang.String.valueOf(caractere));
            }
            private static java.lang.String echapper(java.lang.String valeur) {
            java.lang.StringBuilder resultat = null;
            for (int index = 0; index < valeur.length(); index++) {
            char caractere = valeur.charAt(index);
            java.lang.String remplacement = caractere < 63 ? ECHAPPEMENTS[caractere] : null;
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

    /**
     * Échappement HTML des petits documents, dans le tampon : copie en bloc jusqu'au premier remplacement
     * (debut vaut alors -1), puis caractère par caractère, ce qui reste efficace aux fortes densités.
     */
    private static final String ECHAPPEMENT_HTML_TAMPON = TABLE_HTML + """
            private static void echapper(java.lang.StringBuilder destination, char caractere) {
            echapper(destination, java.lang.String.valueOf(caractere));
            }
            private static void echapper(java.lang.StringBuilder destination, java.lang.String valeur) {
            int debut = 0;
            for (int index = 0; index < valeur.length(); index++) {
            char caractere = valeur.charAt(index);
            java.lang.String remplacement = caractere < 63 ? ECHAPPEMENTS[caractere] : null;
            if (remplacement != null) {
            if (debut >= 0) destination.append(valeur, debut, index);
            destination.append(remplacement);
            debut = -1;
            } else if (debut < 0) {
            destination.append(caractere);
            }
            }
            if (debut >= 0) destination.append(valeur, debut, valeur.length());
            }
            """;

    /**
     * Tables XML du contenu et des attributs ; les contrôles interdits restent nuls et sont refusés après la table.
     * Les caractères inférieurs à U+D800 et non remplacés n'exigent aucun autre contrôle.
     */
    private static final String TABLES_XML = """
            private static final java.lang.String[] ECHAPPEMENTS_CONTENU = new java.lang.String[63];
            private static final java.lang.String[] ECHAPPEMENTS_ATTRIBUT = new java.lang.String[63];
            static {
            for (java.lang.String[] table : new java.lang.String[][] {ECHAPPEMENTS_CONTENU, ECHAPPEMENTS_ATTRIBUT}) {
            table['&'] = "&amp;";
            table['<'] = "&lt;";
            table['>'] = "&gt;";
            table['"'] = "&quot;";
            table['\\''] = "&apos;";
            table['\\r'] = "&#13;";
            }
            ECHAPPEMENTS_ATTRIBUT['\\n'] = "&#10;";
            ECHAPPEMENTS_ATTRIBUT['\\t'] = "&#9;";
            }
            """;

    /**
     * Échappement XML des petits documents, dans le tampon : copie en bloc jusqu'au premier remplacement
     * (debut vaut alors -1), puis caractère par caractère ; un caractère interdit abandonne le rendu.
     */
    private static final String ECHAPPEMENT_XML_TAMPON = TABLES_XML + """
            private static void echapper(java.lang.StringBuilder destination, char caractere, boolean attribut) {
            echapper(destination, java.lang.String.valueOf(caractere), attribut);
            }
            private static void echapper(java.lang.StringBuilder destination, java.lang.String valeur, boolean attribut) {
            java.lang.String[] table = attribut ? ECHAPPEMENTS_ATTRIBUT : ECHAPPEMENTS_CONTENU;
            int debut = 0;
            for (int index = 0; index < valeur.length(); index++) {
            char caractere = valeur.charAt(index);
            java.lang.String remplacement = caractere < 63 ? table[caractere] : null;
            if (remplacement != null) {
            if (debut >= 0) destination.append(valeur, debut, index);
            destination.append(remplacement);
            debut = -1;
            continue;
            }
            boolean paire = false;
            if (caractere < 0x20) {
            if (caractere != '\\n' && caractere != '\\t') {
            throw new java.lang.IllegalArgumentException("VERNE-REN-002 : caractère interdit par XML 1.0 dans une valeur interpolée.");
            }
            } else if (caractere >= 0xD800) {
            paire = java.lang.Character.isHighSurrogate(caractere) && index + 1 < valeur.length()
            && java.lang.Character.isLowSurrogate(valeur.charAt(index + 1));
            if (caractere == 0xFFFE || caractere == 0xFFFF || java.lang.Character.isSurrogate(caractere) && !paire) {
            throw new java.lang.IllegalArgumentException("VERNE-REN-002 : caractère interdit par XML 1.0 dans une valeur interpolée.");
            }
            }
            if (debut < 0) destination.append(caractere);
            if (paire) {
            index++;
            if (debut < 0) destination.append(valeur.charAt(index));
            }
            }
            if (debut >= 0) destination.append(valeur, debut, valeur.length());
            }
            """;

    /** Échappement XML généré : contenu ou attribut, refus des caractères interdits par XML 1.0. */
    private static final String ECHAPPEMENT_XML = TABLES_XML + """
            private static java.lang.String echapper(char caractere, boolean attribut) {
            return echapper(java.lang.String.valueOf(caractere), attribut);
            }
            private static java.lang.String echapper(java.lang.String valeur, boolean attribut) {
            java.lang.String[] table = attribut ? ECHAPPEMENTS_ATTRIBUT : ECHAPPEMENTS_CONTENU;
            java.lang.StringBuilder resultat = null;
            for (int index = 0; index < valeur.length(); index++) {
            char caractere = valeur.charAt(index);
            java.lang.String remplacement = caractere < 63 ? table[caractere] : null;
            if (remplacement != null) {
            if (resultat == null) resultat = new java.lang.StringBuilder(valeur.length() + 16).append(valeur, 0, index);
            resultat.append(remplacement);
            continue;
            }
            boolean paire = false;
            if (caractere < 0x20) {
            if (caractere != '\\n' && caractere != '\\t') {
            throw new java.lang.IllegalArgumentException("VERNE-REN-002 : caractère interdit par XML 1.0 dans une valeur interpolée.");
            }
            } else if (caractere >= 0xD800) {
            paire = java.lang.Character.isHighSurrogate(caractere) && index + 1 < valeur.length()
            && java.lang.Character.isLowSurrogate(valeur.charAt(index + 1));
            if (caractere == 0xFFFE || caractere == 0xFFFF || java.lang.Character.isSurrogate(caractere) && !paire) {
            throw new java.lang.IllegalArgumentException("VERNE-REN-002 : caractère interdit par XML 1.0 dans une valeur interpolée.");
            }
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

    /**
     * Échappement JSON des chaînes, dans le tampon : table des contrôles (formes courtes, sinon \\uxxxx), du guillemet
     * et de l'antislash ; U+2028, U+2029 et substituts isolés en \\uxxxx, seuls contrôlés au-delà de la table.
     * Copie en bloc jusqu'au premier remplacement (debut vaut alors -1), puis caractère par caractère.
     */
    private static final String ECHAPPEMENT_JSON_TAMPON = """
            private static final java.lang.String[] ECHAPPEMENTS = new java.lang.String[93];
            static {
            for (int caractere = 0; caractere < 0x20; caractere++) {
            ECHAPPEMENTS[caractere] = "\\\\u" + java.lang.Integer.toHexString(caractere | 0x10000).substring(1);
            }
            ECHAPPEMENTS['"'] = "\\\\\\"";
            ECHAPPEMENTS['\\\\'] = "\\\\\\\\";
            ECHAPPEMENTS['\\b'] = "\\\\b";
            ECHAPPEMENTS['\\f'] = "\\\\f";
            ECHAPPEMENTS['\\n'] = "\\\\n";
            ECHAPPEMENTS['\\r'] = "\\\\r";
            ECHAPPEMENTS['\\t'] = "\\\\t";
            }
            private static void echapper(java.lang.StringBuilder destination, char caractere) {
            echapper(destination, java.lang.String.valueOf(caractere));
            }
            private static void echapper(java.lang.StringBuilder destination, java.lang.String valeur) {
            int debut = 0;
            for (int index = 0; index < valeur.length(); index++) {
            char caractere = valeur.charAt(index);
            java.lang.String remplacement = caractere < 93 ? ECHAPPEMENTS[caractere] : null;
            boolean paire = false;
            if (caractere >= 0x2028) {
            paire = java.lang.Character.isHighSurrogate(caractere) && index + 1 < valeur.length()
            && java.lang.Character.isLowSurrogate(valeur.charAt(index + 1));
            if (caractere == 0x2028 || caractere == 0x2029 || java.lang.Character.isSurrogate(caractere) && !paire) {
            remplacement = "\\\\u" + java.lang.Integer.toHexString(caractere | 0x10000).substring(1);
            }
            }
            if (remplacement != null) {
            if (debut >= 0) destination.append(valeur, debut, index);
            destination.append(remplacement);
            debut = -1;
            } else if (debut < 0) {
            destination.append(caractere);
            if (paire) destination.append(valeur.charAt(index + 1));
            }
            if (paire) index++;
            }
            if (debut >= 0) destination.append(valeur, debut, valeur.length());
            }
            """;

    /** Échappement JSON en chaîne pour l'assemblage unique des grands documents ; pas de copie si rien n'est remplacé. */
    private static final String ECHAPPEMENT_JSON_CHAINE = """
            private static java.lang.String echapper(java.lang.String valeur) {
            var destination = new java.lang.StringBuilder(valeur.length() + 16);
            echapper(destination, valeur);
            return destination.length() == valeur.length() ? valeur : destination.toString();
            }
            """;

    /** Arithmétique entière du contrat : dépassement, division ou reste par zéro lèvent VERNE-REN-004. */
    private static final String ARITHMETIQUE_EXACTE = """
            private static java.lang.ArithmeticException depassement() {
            return new java.lang.ArithmeticException("VERNE-REN-004 : dépassement de capacité entier ou division entière par zéro.");
            }
            private static int ajouter(int a, int b) {
            int r = a + b;
            if (((a ^ r) & (b ^ r)) < 0) throw depassement();
            return r;
            }
            private static long ajouter(long a, long b) {
            long r = a + b;
            if (((a ^ r) & (b ^ r)) < 0) throw depassement();
            return r;
            }
            private static int soustraire(int a, int b) {
            int r = a - b;
            if (((a ^ b) & (a ^ r)) < 0) throw depassement();
            return r;
            }
            private static long soustraire(long a, long b) {
            long r = a - b;
            if (((a ^ b) & (a ^ r)) < 0) throw depassement();
            return r;
            }
            private static int multiplier(int a, int b) {
            long r = (long) a * b;
            if ((int) r != r) throw depassement();
            return (int) r;
            }
            private static long multiplier(long a, long b) {
            long haut = java.lang.Math.multiplyHigh(a, b);
            long r = a * b;
            if (haut != (r >> 63)) throw depassement();
            return r;
            }
            private static int diviser(int a, int b) {
            if (b == 0 || a == java.lang.Integer.MIN_VALUE && b == -1) throw depassement();
            return a / b;
            }
            private static long diviser(long a, long b) {
            if (b == 0 || a == java.lang.Long.MIN_VALUE && b == -1) throw depassement();
            return a / b;
            }
            private static int reste(int a, int b) {
            if (b == 0) throw depassement();
            return a % b;
            }
            private static long reste(long a, long b) {
            if (b == 0) throw depassement();
            return a % b;
            }
            private static int moins(int a) {
            if (a == java.lang.Integer.MIN_VALUE) throw depassement();
            return -a;
            }
            private static long moins(long a) {
            if (a == java.lang.Long.MIN_VALUE) throw depassement();
            return -a;
            }
            """;

    /** Hors d'un littéral JSON, un nombre infini ou NaN produirait un document invalide. */
    private static final String NOMBRE_FINI_JSON = """
            private static double fini(double valeur) {
            if (!java.lang.Double.isFinite(valeur)) throw new java.lang.IllegalArgumentException("VERNE-REN-003 : nombre non fini interdit hors d'une chaîne JSON.");
            return valeur;
            }
            private static float fini(float valeur) {
            if (!java.lang.Float.isFinite(valeur)) throw new java.lang.IllegalArgumentException("VERNE-REN-003 : nombre non fini interdit hors d'une chaîne JSON.");
            return valeur;
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
        var ouverts = new ArrayDeque<Partie>();
        var alternatives = new ArrayDeque<Boolean>();
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
                    int fin = finDirective(position);
                    if (fin < 0) throw refus("VERNE-SYN-001", position, "interpolation ou déclaration non fermée.");
                    String contenu = texte.substring(position + 1, fin).trim();
                    if (contenu.startsWith("@")) declarer(contenu.substring(1), position);
                    else if (contenu.startsWith("#") || contenu.startsWith("/")) {
                        analyserBloc(contenu, position, ouverts, alternatives);
                    } else {
                        Expression expression = expression(contenu, position);
                        if (++nombreInterpolations > NOMBRE_MAXIMAL_INTERPOLATIONS) {
                            throw refus("VERNE-LIM-003", position, "plus de 1 024 interpolations dans un gabarit.");
                        }
                        parties.add(new Partie(contenu, Genre.INTERPOLATION, position, null, expression));
                    }
                    position = fin + 1;
                }
            } else {
                fragment.append(caractere);
                position++;
            }
        }
        vider(fragment);
        if (!ouverts.isEmpty()) throw refus("VERNE-SYN-005", ouverts.peek().position(), "bloc non fermé.");
    }

    /** Reconnaît les cinq directives de bloc et vérifie leur imbrication ; les chemins sont résolus à la génération. */
    private void analyserBloc(String contenu, int position, ArrayDeque<Partie> ouverts, ArrayDeque<Boolean> alternatives) {
        String[] mots = contenu.substring(1).trim().split("\\s+");
        String mot = contenu.charAt(0) + mots[0];
        Partie partie;
        if (mot.equals("#if") && mots.length >= 2) {
            String source = contenu.substring(1).trim().substring(2).trim();
            partie = new Partie(source, Genre.SI, position, null, expression(source, position));
        } else if (mot.equals("#for") && mots.length == 4 && nomSimple(mots[1]) && mots[2].equals("in")
                && nomQualifie(mots[3])) {
            partie = new Partie(mots[3], Genre.POUR, position, mots[1], null);
        } else if (mots.length == 1 && (mot.equals("#else") || mot.equals("/if") || mot.equals("/for"))) {
            Genre attendu = mot.equals("/for") ? Genre.POUR : Genre.SI;
            if (ouverts.isEmpty() || ouverts.peek().genre() != attendu || mot.equals("#else") && alternatives.peek()) {
                throw refus("VERNE-SYN-005", position, mot.equals("#else") ? "{#else} hors d'une condition ou répété."
                        : "fermeture sans bloc ouvert correspondant.");
            }
            if (mot.equals("#else")) {
                alternatives.pop();
                alternatives.push(true);
                parties.add(new Partie("", Genre.SINON, position, null, null));
            } else {
                ouverts.pop();
                alternatives.pop();
                parties.add(new Partie("", attendu == Genre.POUR ? Genre.FIN_POUR : Genre.FIN_SI, position, null, null));
            }
            return;
        } else {
            throw refus("VERNE-SYN-002", position, "expression ou directive non prise en charge.");
        }
        if (ouverts.size() >= PROFONDEUR_MAXIMALE_BLOCS) {
            throw refus("VERNE-LIM-009", position, "plus de 32 blocs imbriqués.");
        }
        if (partie.genre() == Genre.POUR) verifierProfondeur(partie.contenu(), position);
        ouverts.push(partie);
        alternatives.push(false);
        parties.add(partie);
    }

    /** Première accolade fermante hors d'un littéral de chaîne, ou -1. */
    private int finDirective(int position) {
        boolean chaine = false;
        for (int index = position + 1; index < texte.length(); index++) {
            char caractere = texte.charAt(index);
            if (chaine) {
                if (caractere == '\\') index++;
                else if (caractere == '"') chaine = false;
            } else if (caractere == '"') {
                chaine = true;
            } else if (caractere == '}') {
                return index;
            }
        }
        return -1;
    }

    /** Analyse syntaxique bornée, puis profondeur de chaque chemin. */
    private Expression expression(String source, int position) {
        Expression expression;
        try {
            expression = Expression.analyser(source);
        } catch (Expression.Invalide invalide) {
            throw refus(invalide.code, position, invalide.getMessage());
        }
        verifierChemins(expression, position);
        return expression;
    }

    private void verifierChemins(Expression expression, int position) {
        switch (expression) {
            case Expression.Chemin chemin -> verifierProfondeur(chemin.texte(), position);
            case Expression.Unaire unaire -> verifierChemins(unaire.operande(), position);
            case Expression.Binaire binaire -> {
                verifierChemins(binaire.gauche(), position);
                verifierChemins(binaire.droite(), position);
            }
            case Expression.Litteral litteral -> { }
        }
    }

    private void verifierProfondeur(String chemin, int position) {
        if (chemin.chars().filter(caractere -> caractere == '.').count() > PROFONDEUR_MAXIMALE_NAVIGATION) {
            throw refus("VERNE-LIM-005", position, "plus de 32 accès de record dans une interpolation.");
        }
    }

    /** Condition stricte : boolean, ou Boolean contrôlé non nul ; aucune conversion depuis un autre type. */
    private String condition(Partie partie) {
        if (!(partie.expression() instanceof Expression.Chemin seul)) {
            Valeur valeur = typer(partie.expression(), partie.position());
            if (valeur.categorie() != Categorie.BOOLEEN) {
                throw refus("VERNE-TYP-006", partie.position(), "la condition doit être de type boolean ou java.lang.Boolean.");
            }
            return valeur.code();
        }
        // Chemin seul, éventuellement entre parenthèses : génération inchangée.
        Chemin chemin = naviguer(seul.texte(), partie.position());
        if (chemin.type().getKind() == TypeKind.BOOLEAN) return chemin.expression();
        var booleen = environnement.getElementUtils().getTypeElement("java.lang.Boolean").asType();
        if (!environnement.getTypeUtils().isSameType(chemin.type(), booleen)) {
            throw refus("VERNE-TYP-006", partie.position(), "la condition doit être de type boolean ou java.lang.Boolean.");
        }
        return "requireNonNull(" + chemin.expression() + ", \"VERNE-REN-001 : condition nulle.\")";
    }

    /** Ouvre la portée de la variable ; tableau parcouru par indices, Iterable par un unique itérateur. */
    private String boucle(Partie partie, String java) {
        if (parametres.containsKey(partie.variable())
                || locales.stream().anyMatch(variable -> variable.nom().equals(partie.variable()))) {
            throw refus("VERNE-TYP-002", partie.position(), "nom de variable de boucle déjà utilisé.");
        }
        Chemin chemin = naviguer(partie);
        TypeMirror element = typeElement(chemin.type());
        if (element == null) {
            throw refus("VERNE-TYP-007", partie.position(), "la boucle doit parcourir un tableau ou un java.lang.Iterable.");
        }
        enregistrerOrigine(element);
        locales.push(new Variable(partie.variable(), java, element));
        return "for (var " + java + " : requireNonNull(" + chemin.expression()
                + ", \"VERNE-REN-001 : collection nulle.\")) {\n";
    }

    /** Type des éléments d'un tableau ou d'un sous-type d'Iterable ; un joker ou un type brut sans borne donne Object. */
    private TypeMirror typeElement(TypeMirror type) {
        if (type instanceof ArrayType tableau) return tableau.getComponentType();
        var types = environnement.getTypeUtils();
        var iterable = types.erasure(environnement.getElementUtils().getTypeElement("java.lang.Iterable").asType());
        var aVisiter = new ArrayDeque<TypeMirror>(List.of(type));
        while (!aVisiter.isEmpty()) {
            TypeMirror candidat = aVisiter.pop();
            if (candidat instanceof DeclaredType declare && types.isSameType(types.erasure(candidat), iterable)) {
                if (declare.getTypeArguments().isEmpty()) return objet();
                return borne(declare.getTypeArguments().getFirst());
            }
            if (candidat instanceof DeclaredType) aVisiter.addAll(types.directSupertypes(candidat));
        }
        return null;
    }

    /**
     * Borne supérieure utilisable d'un joker ou d'une variable de type, avec ses arguments génériques ; Object sans
     * borne supérieure (joker libre ou inférieur). Les autres types sont rendus tels quels.
     */
    private TypeMirror borne(TypeMirror type) {
        if (type instanceof WildcardType joker) {
            return joker.getExtendsBound() == null ? objet() : borne(joker.getExtendsBound());
        }
        if (type instanceof TypeVariable variable && variable.getUpperBound().getKind() == TypeKind.DECLARED) {
            return variable.getUpperBound();
        }
        return type.getKind() == TypeKind.TYPEVAR ? environnement.getTypeUtils().erasure(type) : type;
    }

    private TypeMirror objet() {
        return environnement.getElementUtils().getTypeElement("java.lang.Object").asType();
    }

    /** Vide le tampon sans modifier les espaces ou fins de ligne qui l'entourent. */
    private void vider(StringBuilder fragment) {
        if (!fragment.isEmpty()) {
            parties.add(new Partie(fragment.toString(), Genre.TEXTE, 0, null, null));
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

    /** Vérifie le type scalaire final d'un chemin interpolé, ou type l'expression interpolée. */
    private Resolution resoudre(Partie partie) {
        if (!(partie.expression() instanceof Expression.Chemin seul)) {
            Valeur valeur = typer(partie.expression(), partie.position());
            Categorie categorie = valeur.categorie();
            if (categorie == Categorie.AUTRE) {
                throw refus("VERNE-TYP-004", partie.position(), "la valeur interpolée doit être une chaîne ou un scalaire primitif.");
            }
            return new Resolution(valeur.code(), categorie == Categorie.CHAINE || categorie == Categorie.CARACTERE,
                    categorie != Categorie.CHAINE, categorie == Categorie.DOUBLE || categorie == Categorie.FLOAT);
        }
        // Chemin seul, éventuellement entre parenthèses : génération inchangée.
        Chemin chemin = naviguer(seul.texte(), partie.position());
        TypeMirror type = chemin.type();
        String expression = chemin.expression();
        TypeMirror deballe = deballer(type);
        if (deballe != null) {
            // Enveloppe de primitif (D-006d) : contrôlée non nulle puis rendue comme son primitif.
            return new Resolution("((" + deballe + ") requireNonNull(" + expression
                    + ", \"VERNE-REN-001 : valeur interpolée nulle.\"))", deballe.getKind() == TypeKind.CHAR, true,
                    deballe.getKind() == TypeKind.DOUBLE || deballe.getKind() == TypeKind.FLOAT);
        }
        var chaine = environnement.getElementUtils().getTypeElement("java.lang.String").asType();
        if (!type.getKind().isPrimitive() && !environnement.getTypeUtils().isSameType(type, chaine)) {
            throw refus("VERNE-TYP-004", partie.position(), "la valeur interpolée doit être une chaîne ou un scalaire primitif.");
        }
        if (!type.getKind().isPrimitive()) {
            expression = "requireNonNull(" + expression + ", \"VERNE-REN-001 : valeur interpolée nulle.\")";
        }
        return new Resolution(expression, !type.getKind().isPrimitive() || type.getKind() == TypeKind.CHAR,
                type.getKind().isPrimitive(), type.getKind() == TypeKind.DOUBLE || type.getKind() == TypeKind.FLOAT);
    }

    /** Part d'une variable de boucle visible ou d'un paramètre, puis résout seulement les composants déclarés des records. */
    private Chemin naviguer(Partie partie) {
        return naviguer(partie.contenu(), partie.position());
    }

    private Chemin naviguer(String texteChemin, int position) {
        String[] chemin = texteChemin.split("\\.");
        var depart = locales.stream().filter(variable -> variable.nom().equals(chemin[0])).findFirst()
                .orElseGet(() -> {
                    Parametre parametre = parametres.get(chemin[0]);
                    return parametre == null ? null : new Variable(parametre.nom(), parametre.nom(), parametre.type());
                });
        if (depart == null) throw refus("VERNE-TYP-001", position, "paramètre inconnu.");
        TypeMirror type = depart.type();
        String expression = depart.java();
        for (int index = 1; index < chemin.length; index++) {
            if (type.getKind() == TypeKind.ERROR) throw attendreType(position);
            if (!(type instanceof DeclaredType declare)
                    || !(declare.asElement() instanceof TypeElement element)
                    || element.getKind() != ElementKind.RECORD || !visibilite.accessible(element)) {
                throw refus("VERNE-TYP-001", position, "propriété inconnue ou inaccessible.");
            }
            String nom = chemin[index];
            var composant = element.getRecordComponents().stream()
                    .filter(candidat -> candidat.getSimpleName().contentEquals(nom)).findFirst()
                    .filter(candidat -> candidat.getAccessor().getModifiers().contains(Modifier.PUBLIC))
                    .orElseThrow(() -> refus("VERNE-TYP-001", position, "propriété inconnue ou inaccessible."));
            // Un composant générique peut donner un joker : sa borne supérieure est le type navigué.
            type = borne(((ExecutableType) environnement.getTypeUtils().asMemberOf(
                    declare, composant.getAccessor())).getReturnType());
            enregistrerOrigine(type);
            expression = "requireNonNull(" + expression
                    + ", \"VERNE-REN-001 : objet de navigation nul.\")." + nom + "()";
        }
        if (type.getKind() == TypeKind.ERROR) throw attendreType(position);
        return new Chemin(expression, type);
    }

    /** Type primitif d'une enveloppe (Integer, Boolean…), ou null pour tout autre type. */
    private TypeMirror deballer(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return null;
        try {
            return environnement.getTypeUtils().unboxedType(type);
        } catch (IllegalArgumentException autre) {
            return null;
        }
    }

    /**
     * Type une sous-expression selon le contrat : opérandes évalués de gauche à droite, chaque référence contrôlée non
     * nulle au moment de son évaluation, arithmétique entière contrôlée, aucune conversion hors de la promotion de Java.
     */
    private Valeur typer(Expression expression, int position) {
        return switch (expression) {
            case Expression.Litteral litteral -> new Valeur(litteral.java(), switch (litteral.sorte()) {
                case INT -> Categorie.INT;
                case LONG -> Categorie.LONG;
                case DOUBLE -> Categorie.DOUBLE;
                case CHAINE -> Categorie.CHAINE;
                case BOOLEEN -> Categorie.BOOLEEN;
            });
            case Expression.Chemin chemin -> operande(naviguer(chemin.texte(), position));
            case Expression.Unaire unaire -> {
                Valeur operande = typer(unaire.operande(), position);
                if (unaire.operateur().equals("!") && operande.categorie() == Categorie.BOOLEEN) {
                    yield new Valeur("(!" + operande.code() + ")", Categorie.BOOLEEN);
                }
                if (unaire.operateur().equals("-") && operande.categorie().numerique()) {
                    if (operande.categorie().compareTo(Categorie.LONG) <= 0) {
                        arithmetique = true;
                        yield new Valeur("moins(" + operande.code() + ")", operande.categorie());
                    }
                    yield new Valeur("(-" + operande.code() + ")", operande.categorie());
                }
                throw inapplicable(unaire.operateur(), position, operande.categorie(), null);
            }
            case Expression.Binaire binaire -> binaire(binaire, position);
        };
    }

    private Valeur binaire(Expression.Binaire binaire, int position) {
        Valeur gauche = typer(binaire.gauche(), position);
        Valeur droite = typer(binaire.droite(), position);
        Categorie a = gauche.categorie();
        Categorie b = droite.categorie();
        String operateur = binaire.operateur();
        boolean numeriques = a.numerique() && b.numerique();
        Categorie promue = a.compareTo(b) >= 0 ? a : b;
        switch (operateur) {
            case "+", "-", "*", "/", "%" -> {
                if (numeriques) {
                    if (promue.compareTo(Categorie.LONG) <= 0) {
                        arithmetique = true;
                        String methode = switch (operateur) {
                            case "+" -> "ajouter";
                            case "-" -> "soustraire";
                            case "*" -> "multiplier";
                            case "/" -> "diviser";
                            default -> "reste";
                        };
                        return new Valeur(methode + "(" + gauche.code() + ", " + droite.code() + ")", promue);
                    }
                    return new Valeur("(" + gauche.code() + " " + operateur + " " + droite.code() + ")", promue);
                }
                if (operateur.equals("+") && (a == Categorie.CHAINE || b == Categorie.CHAINE)) {
                    throw refus("VERNE-TYP-008", position,
                            "concaténation non prise en charge : juxtaposer les interpolations, par exemple {a}{b}.");
                }
            }
            case "<", "<=", ">", ">=" -> {
                if (numeriques) return new Valeur("(" + gauche.code() + " " + operateur + " " + droite.code() + ")", Categorie.BOOLEEN);
            }
            case "==", "!=" -> {
                if (a == Categorie.CHAINE && b == Categorie.CHAINE) {
                    String egal = gauche.code() + ".equals(" + droite.code() + ")";
                    return new Valeur(operateur.equals("==") ? egal : "(!" + egal + ")", Categorie.BOOLEEN);
                }
                if (numeriques || a == b && (a == Categorie.BOOLEEN || a == Categorie.CARACTERE)) {
                    return new Valeur("(" + gauche.code() + " " + operateur + " " + droite.code() + ")", Categorie.BOOLEEN);
                }
            }
            default -> {
                if (a == Categorie.BOOLEEN && b == Categorie.BOOLEEN) {
                    return new Valeur("(" + gauche.code() + " " + operateur + " " + droite.code() + ")", Categorie.BOOLEEN);
                }
            }
        }
        throw inapplicable(operateur, position, a, b);
    }

    /** Opérande issu d'un chemin : primitif tel quel, enveloppe déballée et chaîne contrôlées non nulles. */
    private Valeur operande(Chemin chemin) {
        TypeMirror type = chemin.type();
        String code = chemin.expression();
        TypeMirror primitif = type.getKind().isPrimitive() ? type : deballer(type);
        if (primitif != null) {
            if (primitif != type) {
                code = "((" + primitif + ") requireNonNull(" + code + ", \"VERNE-REN-001 : valeur nulle dans une expression.\"))";
            }
            return new Valeur(code, switch (primitif.getKind()) {
                case BOOLEAN -> Categorie.BOOLEEN;
                case CHAR -> Categorie.CARACTERE;
                case LONG -> Categorie.LONG;
                case FLOAT -> Categorie.FLOAT;
                case DOUBLE -> Categorie.DOUBLE;
                default -> Categorie.INT;
            });
        }
        var chaine = environnement.getElementUtils().getTypeElement("java.lang.String").asType();
        if (environnement.getTypeUtils().isSameType(type, chaine)) {
            return new Valeur("requireNonNull(" + code + ", \"VERNE-REN-001 : chaîne nulle dans une expression.\")",
                    Categorie.CHAINE);
        }
        return new Valeur(code, Categorie.AUTRE);
    }

    private Refus inapplicable(String operateur, int position, Categorie a, Categorie b) {
        return refus("VERNE-TYP-008", position, "opérateur " + operateur + " inapplicable à : " + a.nom
                + (b == null ? "" : " et " + b.nom) + ".");
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
