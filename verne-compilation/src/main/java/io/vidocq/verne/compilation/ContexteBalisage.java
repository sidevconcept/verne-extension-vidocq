package io.vidocq.verne.compilation;

import java.util.Locale;
import java.util.Set;

/**
 * Suit le balisage HTML ou XML du texte statique d'un gabarit pour classer chaque interpolation.
 * Le texte statique est une source revue de l'application : seul l'emplacement des interpolations est contrôlé.
 * L'instance appartient à une tentative de compilation ; elle n'est ni partagée ni réutilisée.
 */
final class ContexteBalisage {
    /** Interpolation refusée après coup, à la fermeture de sa balise : position source et contexte sans donnée. */
    record Interdiction(int position, String contexte) {}

    private enum Etat {
        DONNEES, TEXTE_ECHAPPE, TEXTE_BRUT, CHEVRON, CHEVRON_BARRE, NOM_BALISE, NOM_FERMANT, DANS_BALISE,
        NOM_ATTRIBUT, APRES_NOM_ATTRIBUT, AVANT_VALEUR, VALEUR_GUILLEMETS, VALEUR_APOSTROPHES, VALEUR_NUE,
        EXCLAMATION, COMMENTAIRE, DECLARATION, CDATA, INSTRUCTION
    }

    private static final Set<String> TEXTE_BRUT_HTML = Set.of(
            "script", "style", "xmp", "iframe", "noembed", "noframes", "noscript", "plaintext");
    private static final Set<String> TEXTE_ECHAPPE_HTML = Set.of("textarea", "title");
    private static final Set<String> ATTRIBUTS_URL = Set.of(
            "href", "src", "action", "formaction", "srcset", "imagesrcset", "xlink:href", "data", "poster",
            "background", "cite", "longdesc", "lowsrc", "dynsrc", "usemap", "codebase", "classid", "archive",
            "profile", "manifest", "ping", "icon", "itemtype", "itemid");

    private final boolean html;
    private Etat etat = Etat.DONNEES;
    private final StringBuilder nomBalise = new StringBuilder();
    private final StringBuilder nomAttribut = new StringBuilder();
    private final StringBuilder exclamation = new StringBuilder();
    private String elementBrut;
    private int correspondance;
    private int fin;
    private int crochets;
    private boolean equivalentHttp;
    private int contenuMeta = -1;

    ContexteBalisage(Profil profil) {
        this.html = profil == Profil.HTML;
    }

    /** Avance dans un texte statique ; renvoie une interdiction révélée par la fermeture d'une balise, sinon null. */
    Interdiction avancer(String texte) {
        for (int index = 0; index < texte.length(); index++) {
            Interdiction interdiction = traiter(texte.charAt(index));
            if (interdiction != null) return interdiction;
        }
        return null;
    }

    /** Nom du contexte courant s'il refuse une interpolation, sinon null. */
    String refus() {
        return switch (etat) {
            case DONNEES -> null;
            case TEXTE_ECHAPPE -> correspondance > 0 ? "balise fermante en cours d'écriture" : null;
            case TEXTE_BRUT -> "contenu de l'élément " + elementBrut;
            case CHEVRON, NOM_BALISE -> "nom de balise";
            case CHEVRON_BARRE, NOM_FERMANT -> "nom de balise fermante";
            case DANS_BALISE, APRES_NOM_ATTRIBUT -> "emplacement entre les attributs";
            case NOM_ATTRIBUT -> "nom d'attribut";
            case AVANT_VALEUR, VALEUR_NUE -> "valeur d'attribut sans guillemets";
            case VALEUR_GUILLEMETS, VALEUR_APOSTROPHES -> html ? attributInterdit() : null;
            case EXCLAMATION, DECLARATION -> "déclaration";
            case COMMENTAIRE -> "commentaire";
            case CDATA -> "section CDATA";
            case INSTRUCTION -> "instruction de traitement";
        };
    }

    /** Vrai si l'interpolation acceptée se trouve dans une valeur d'attribut entre guillemets. */
    boolean dansAttribut() {
        return etat == Etat.VALEUR_GUILLEMETS || etat == Etat.VALEUR_APOSTROPHES;
    }

    /** Enregistre une interpolation acceptée ; content d'un meta reste en attente d'un http-equiv ultérieur. */
    void noterInterpolation(int position) {
        if (html && dansAttribut() && contenuMeta < 0 && attribut().equals("content") && balise().equals("meta")) {
            contenuMeta = position;
        }
    }

    private String attributInterdit() {
        String nom = attribut();
        if (nom.startsWith("on")) return "attribut d'événement";
        if (nom.equals("style") || nom.equals("srcdoc")) return "attribut " + nom;
        if (ATTRIBUTS_URL.contains(nom)) return "attribut portant une URL";
        if (nom.equals("http-equiv")) return "attribut http-equiv";
        if (nom.equals("content") && balise().equals("meta") && equivalentHttp) return "content d'un meta portant http-equiv";
        return null;
    }

    private Interdiction traiter(char caractere) {
        switch (etat) {
            case DONNEES -> { if (caractere == '<') etat = Etat.CHEVRON; }
            case TEXTE_ECHAPPE, TEXTE_BRUT -> chercherFin(caractere);
            case CHEVRON -> {
                if (debutNom(caractere)) {
                    etat = Etat.NOM_BALISE;
                    nomBalise.setLength(0);
                    nomBalise.append(caractere);
                    equivalentHttp = false;
                    contenuMeta = -1;
                } else if (caractere == '/') {
                    etat = Etat.CHEVRON_BARRE;
                } else if (caractere == '!') {
                    etat = Etat.EXCLAMATION;
                    exclamation.setLength(0);
                } else if (caractere == '?') {
                    etat = html ? Etat.DECLARATION : Etat.INSTRUCTION;
                    crochets = 0;
                    fin = 0;
                } else {
                    etat = Etat.DONNEES;
                    return traiter(caractere);
                }
            }
            case CHEVRON_BARRE -> {
                if (debutNom(caractere)) etat = Etat.NOM_FERMANT;
                else if (html && caractere != '>') { etat = Etat.DECLARATION; crochets = 0; }
                else etat = Etat.DONNEES;
            }
            case NOM_BALISE -> {
                if (espace(caractere) || caractere == '/') etat = Etat.DANS_BALISE;
                else if (caractere == '>') return finBalise();
                else nomBalise.append(caractere);
            }
            case NOM_FERMANT -> { if (caractere == '>') etat = Etat.DONNEES; }
            case DANS_BALISE -> {
                if (caractere == '>') return finBalise();
                if (!espace(caractere) && caractere != '/') commencerAttribut(caractere);
            }
            case NOM_ATTRIBUT -> {
                if (espace(caractere)) { noterAttribut(); etat = Etat.APRES_NOM_ATTRIBUT; }
                else if (caractere == '=') { noterAttribut(); etat = Etat.AVANT_VALEUR; }
                else if (caractere == '/') { noterAttribut(); etat = Etat.DANS_BALISE; }
                else if (caractere == '>') { noterAttribut(); return finBalise(); }
                else nomAttribut.append(caractere);
            }
            case APRES_NOM_ATTRIBUT -> {
                if (caractere == '=') etat = Etat.AVANT_VALEUR;
                else if (caractere == '/') etat = Etat.DANS_BALISE;
                else if (caractere == '>') return finBalise();
                else if (!espace(caractere)) commencerAttribut(caractere);
            }
            case AVANT_VALEUR -> {
                if (caractere == '"') etat = Etat.VALEUR_GUILLEMETS;
                else if (caractere == '\'') etat = Etat.VALEUR_APOSTROPHES;
                else if (caractere == '>') return finBalise();
                else if (!espace(caractere)) etat = Etat.VALEUR_NUE;
            }
            case VALEUR_GUILLEMETS -> { if (caractere == '"') etat = Etat.DANS_BALISE; }
            case VALEUR_APOSTROPHES -> { if (caractere == '\'') etat = Etat.DANS_BALISE; }
            case VALEUR_NUE -> {
                if (espace(caractere)) etat = Etat.DANS_BALISE;
                else if (caractere == '>') return finBalise();
            }
            case EXCLAMATION -> {
                exclamation.append(caractere);
                String debut = exclamation.toString();
                if (debut.equals("--")) { etat = Etat.COMMENTAIRE; fin = 0; }
                else if (debut.equals("[CDATA[")) { etat = html ? Etat.DECLARATION : Etat.CDATA; crochets = 0; fin = 0; }
                else if (!"--".startsWith(debut) && !"[CDATA[".startsWith(debut)) {
                    etat = Etat.DECLARATION;
                    crochets = 0;
                    return traiter(caractere);
                }
            }
            case COMMENTAIRE -> {
                if (caractere == '-') fin = Math.min(fin + 1, 2);
                else if (caractere == '>' && fin == 2) etat = Etat.DONNEES;
                else fin = 0;
            }
            case DECLARATION -> {
                if (caractere == '[') crochets++;
                else if (caractere == ']') crochets = Math.max(0, crochets - 1);
                else if (caractere == '>' && crochets == 0) etat = Etat.DONNEES;
            }
            case CDATA -> {
                if (caractere == ']') fin = Math.min(fin + 1, 2);
                else if (caractere == '>' && fin == 2) etat = Etat.DONNEES;
                else fin = 0;
            }
            case INSTRUCTION -> {
                if (caractere == '?') fin = 1;
                else if (caractere == '>' && fin == 1) etat = Etat.DONNEES;
                else fin = 0;
            }
        }
        return null;
    }

    /** Reconnaît la balise fermante de l'élément brut ou échappé, sans tenir compte de la casse. */
    private void chercherFin(char caractere) {
        if (elementBrut.equals("plaintext")) return;
        String attendue = "</" + elementBrut;
        if (correspondance == attendue.length()) {
            if (espace(caractere) || caractere == '/' || caractere == '>') {
                etat = caractere == '>' ? Etat.DONNEES : Etat.NOM_FERMANT;
                elementBrut = null;
                correspondance = 0;
                return;
            }
            correspondance = 0;
        }
        if (Character.toLowerCase(caractere) == attendue.charAt(correspondance)) correspondance++;
        else correspondance = caractere == '<' ? 1 : 0;
    }

    private Interdiction finBalise() {
        String nom = balise();
        Interdiction interdiction = html && nom.equals("meta") && equivalentHttp && contenuMeta >= 0
                ? new Interdiction(contenuMeta, "content d'un meta portant http-equiv") : null;
        etat = Etat.DONNEES;
        if (html && TEXTE_BRUT_HTML.contains(nom)) etat = Etat.TEXTE_BRUT;
        else if (html && TEXTE_ECHAPPE_HTML.contains(nom)) etat = Etat.TEXTE_ECHAPPE;
        if (etat != Etat.DONNEES) {
            elementBrut = nom;
            correspondance = 0;
        }
        return interdiction;
    }

    private void commencerAttribut(char caractere) {
        etat = Etat.NOM_ATTRIBUT;
        nomAttribut.setLength(0);
        nomAttribut.append(caractere);
    }

    private void noterAttribut() {
        if (html && attribut().equals("http-equiv")) equivalentHttp = true;
    }

    private String balise() {
        return html ? nomBalise.toString().toLowerCase(Locale.ROOT) : nomBalise.toString();
    }

    private String attribut() {
        return html ? nomAttribut.toString().toLowerCase(Locale.ROOT) : nomAttribut.toString();
    }

    /** HTML : lettre ASCII ; XML : début de nom, approché par lettre, souligné, deux-points ou hors ASCII. */
    private boolean debutNom(char caractere) {
        boolean lettre = caractere >= 'a' && caractere <= 'z' || caractere >= 'A' && caractere <= 'Z';
        return html ? lettre : lettre || caractere == '_' || caractere == ':' || caractere >= 0x80;
    }

    private static boolean espace(char caractere) {
        return caractere == ' ' || caractere == '\t' || caractere == '\n' || caractere == '\r' || caractere == '\f';
    }
}
