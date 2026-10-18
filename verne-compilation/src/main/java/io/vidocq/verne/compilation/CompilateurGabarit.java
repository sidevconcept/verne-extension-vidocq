package io.vidocq.verne.compilation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * Analyse et génère un gabarit du sous-ensemble texte, paramètres et composants de records.
 * Les symboles proviennent du compilateur Java ; aucune classe métier n'est chargée.
 */
final class CompilateurGabarit {
    private static final int NOMBRE_MAXIMAL_INTERPOLATIONS = 1_024;
    private final ProcessingEnvironment environnement;
    private final Map<String, Parametre> parametres = new LinkedHashMap<>();
    private final List<Partie> parties = new ArrayList<>();
    private String texte;
    private String identifiant;

    /** Le type symbolique est conservé pour vérifier chaque navigation avant génération. */
    private record Parametre(String nom, TypeMirror type) {}
    private record Partie(String contenu, boolean interpolation, int position) {}

    /** Une erreur de source arrête seulement l'analyse du fichier courant. */
    private static final class Refus extends RuntimeException {
        Refus(String message) { super(message, null, false, false); }
    }

    CompilateurGabarit(ProcessingEnvironment environnement) {
        this.environnement = environnement;
    }

    /** Vérifie entièrement le fichier avant d'en ouvrir la source Java générée. */
    void compiler(String classe, String texte, String identifiant) throws IOException {
        this.texte = texte;
        this.identifiant = identifiant;
        try {
            analyser();
            var instructions = new ArrayList<String>();
            var valeurs = new ArrayList<String>();
            var longueurs = new ArrayList<String>();
            int longueurStatique = parties.stream().filter(partie -> !partie.interpolation())
                    .mapToInt(partie -> partie.contenu().length()).sum();
            boolean grandDocument = longueurStatique >= 4_000;
            String resultat = nomLibre("$verneResultat");
            for (Partie partie : parties) {
                if (partie.interpolation()) {
                    String expression = resoudre(partie);
                    if (grandDocument) {
                        String valeur = nomLibre("$verneValeur" + valeurs.size());
                        valeurs.add("var " + valeur + " = valueOf(" + expression + ");\n");
                        longueurs.add(valeur + ".length()");
                        expression = valeur;
                    }
                    instructions.add(resultat + ".append(" + expression + ");\n");
                } else {
                    String contenu = partie.contenu();
                    for (int debut = 0; debut < contenu.length();) {
                        int fin = Math.min(debut + 4_000, contenu.length());
                        if (fin < contenu.length() && Character.isHighSurrogate(contenu.charAt(fin - 1))) fin--;
                        instructions.add(resultat + ".append(\""
                                + ProcesseurVerne.echapper(contenu.substring(debut, fin)) + "\");\n");
                        debut = fin;
                    }
                }
            }
            var source = environnement.getFiler().createSourceFile("io.vidocq.verne.genere." + classe);
            try (var sortie = source.openWriter()) {
                sortie.write("package io.vidocq.verne.genere;\n"
                        + "import static java.util.Objects.requireNonNull;\n"
                        + (grandDocument ? "import static java.lang.String.valueOf;\nimport static java.lang.Math.min;\n" : "")
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
                String capacite = grandDocument
                        ? "(int) min(2_147_483_647L, " + longueurStatique + "L"
                            + (longueurs.isEmpty() ? "" : " + " + String.join(" + ", longueurs)) + ")"
                        : "";
                sortie.write("var " + resultat + " = new java.lang.StringBuilder(" + capacite + ");\n");
                for (String instruction : instructions) sortie.write(instruction);
                sortie.write("return " + resultat + ".toString();\n}\n}\n");
            }
        } catch (Refus refus) {
            environnement.getMessager().printMessage(Diagnostic.Kind.ERROR, refus.getMessage());
        }
    }

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
                    refuser("VERNE-SYN-002", position, "échappement inconnu ou incomplet.");
                }
                fragment.append(texte.charAt(position + 1));
                position += 2;
            } else if (caractere == '{') {
                vider(fragment);
                if (texte.startsWith("{!", position)) {
                    int fin = texte.indexOf("!}", position + 2);
                    if (fin < 0) refuser("VERNE-SYN-001", position, "commentaire non fermé.");
                    position = fin + 2;
                } else {
                    int fin = texte.indexOf('}', position + 1);
                    if (fin < 0) refuser("VERNE-SYN-001", position, "interpolation ou déclaration non fermée.");
                    String contenu = texte.substring(position + 1, fin).trim();
                    if (contenu.startsWith("@")) declarer(contenu.substring(1), position);
                    else {
                        if (!nomQualifie(contenu)) {
                            refuser("VERNE-SYN-002", position, "expression ou directive non prise en charge.");
                        }
                        if (++nombreInterpolations > NOMBRE_MAXIMAL_INTERPOLATIONS) {
                            refuser("VERNE-LIM-003", position, "plus de 1 024 interpolations dans un gabarit.");
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

    /** Déclare un type public non générique ; seules les propriétés de records sont exposées. */
    private void declarer(String declaration, int position) {
        String[] morceaux = declaration.trim().split("\\s+");
        if (morceaux.length != 2 || !nomQualifie(morceaux[0]) || !nomSimple(morceaux[1])) {
            refuser("VERNE-SYN-002", position, "déclaration attendue : type Java qualifié et nom de paramètre.");
        }
        if (parametres.containsKey(morceaux[1])) {
            refuser("VERNE-TYP-002", position, "paramètre déclaré plusieurs fois.");
        }
        TypeElement type = environnement.getElementUtils().getTypeElement(morceaux[0]);
        if (type == null || !type.getTypeParameters().isEmpty()
                || !publicAvecParents(type)) {
            refuser("VERNE-TYP-003", position, "type inconnu, inaccessible ou non pris en charge.");
        }
        parametres.put(morceaux[1], new Parametre(morceaux[1], type.asType()));
    }

    /** Résout seulement les composants déclarés des records, puis vérifie le type scalaire final. */
    private String resoudre(Partie partie) {
        String[] chemin = partie.contenu().split("\\.");
        Parametre parametre = parametres.get(chemin[0]);
        if (parametre == null) refuser("VERNE-TYP-001", partie.position(), "paramètre inconnu.");
        TypeMirror type = parametre.type();
        String expression = parametre.nom();
        for (int index = 1; index < chemin.length; index++) {
            if (!(type instanceof DeclaredType declare)
                    || !(declare.asElement() instanceof TypeElement element)
                    || element.getKind() != ElementKind.RECORD || !publicAvecParents(element)) {
                refuser("VERNE-TYP-001", partie.position(), "propriété inconnue ou inaccessible.");
            }
            var declare = (DeclaredType) type;
            var element = (TypeElement) declare.asElement();
            String nom = chemin[index];
            var composant = element.getRecordComponents().stream()
                    .filter(candidat -> candidat.getSimpleName().contentEquals(nom)).findFirst();
            if (composant.isEmpty() || !composant.get().getAccessor().getModifiers().contains(Modifier.PUBLIC)) {
                refuser("VERNE-TYP-001", partie.position(), "propriété inconnue ou inaccessible.");
            }
            type = ((ExecutableType) environnement.getTypeUtils().asMemberOf(
                    declare, composant.get().getAccessor())).getReturnType();
            expression = "requireNonNull(" + expression
                    + ", \"VERNE-REN-001 : objet de navigation nul.\")." + nom + "()";
        }
        if (!type.toString().equals("java.lang.String") && !type.getKind().isPrimitive()) {
            refuser("VERNE-TYP-004", partie.position(), "la valeur interpolée doit être une chaîne ou un scalaire primitif.");
        }
        if (!type.getKind().isPrimitive()) {
            expression = "requireNonNull(" + expression + ", \"VERNE-REN-001 : valeur interpolée nulle.\")";
        }
        return expression;
    }

    /** Les types englobants doivent eux aussi être publics pour le paquet généré. */
    private static boolean publicAvecParents(TypeElement type) {
        for (Element element = type; element instanceof TypeElement; element = element.getEnclosingElement()) {
            if (!element.getModifiers().contains(Modifier.PUBLIC)) return false;
        }
        return true;
    }

    /** Les caractères ignorés par Java sont refusés pour éviter des noms visuellement distincts équivalents. */
    private static boolean nomSimple(String nom) {
        return SourceVersion.isIdentifier(nom) && !SourceVersion.isKeyword(nom)
                && nom.codePoints().noneMatch(Character::isIdentifierIgnorable);
    }

    private static boolean nomQualifie(String nom) {
        return !nom.isEmpty() && java.util.Arrays.stream(nom.split("\\.", -1)).allMatch(CompilateurGabarit::nomSimple);
    }

    /** Les colonnes comptent les points de code ; CRLF désigne une seule fin de ligne. */
    private void refuser(String code, int position, String message) {
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
        throw new Refus(code + " : " + identifiant + ":" + ligne + ":" + colonne + " : " + message);
    }
}
