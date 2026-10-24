package io.vidocq.verne.mesures;

import io.vidocq.verne.mesures.gabarits.*;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/** Corpus du banc : données à densité contrôlée, témoin Java écrit à la main et rendus Verne équivalents. */
final class Corpus {
    private Corpus() {}

    static final List<String> CAS = List.of("fiche", "dense100", "grand", "facture", "ficheJson");
    static final List<Integer> DENSITES = List.of(0, 10, 50);
    static final int JEUX = 8;

    private static final String SPECIAUX_BALISAGE = "&<>\"'";
    private static final String SPECIAUX_JSON = "\"\\\n\t\u0001";
    private static final String LETTRES = "abcdefghijklmnopqrstuvwxyzéèàçù ";
    private static final String BLOC = ("<p>" + "a".repeat(124) + "</p>\n").repeat(500);

    /** Huit jeux déterministes : la graine dépend seulement du cas et de la densité. */
    static Object[] donnees(String cas, int densite) {
        var alea = new Random(1_000L * densite + cas.hashCode());
        String speciaux = cas.equals("ficheJson") ? SPECIAUX_JSON : SPECIAUX_BALISAGE;
        var jeux = new Object[JEUX];
        for (int i = 0; i < JEUX; i++) {
            jeux[i] = cas.equals("dense100")
                    ? new Ligne(texte(alea, 12, densite, speciaux), texte(alea, 12, densite, speciaux), texte(alea, 8, densite, speciaux))
                    : new Fiche(texte(alea, 10, densite, speciaux), texte(alea, 40, densite, speciaux), texte(alea, 20, densite, speciaux),
                            texte(alea, cas.equals("grand") ? 1_024 : 400, densite, speciaux),
                            texte(alea, 12, densite, speciaux), texte(alea, 12, densite, speciaux), texte(alea, 12, densite, speciaux));
        }
        return jeux;
    }

    private static String texte(Random alea, int longueur, int densite, String speciaux) {
        var resultat = new StringBuilder(longueur);
        for (int i = 0; i < longueur; i++) {
            resultat.append(alea.nextInt(100) < densite ? speciaux.charAt(alea.nextInt(speciaux.length()))
                    : LETTRES.charAt(alea.nextInt(LETTRES.length())));
        }
        return resultat.toString();
    }

    /** Témoin : concaténation directe, avec l'échappement du profil écrit à la main. */
    static Function<Object, String> temoin(String cas, boolean echappe) {
        Function<String, String> e = !echappe ? valeur -> valeur
                : switch (cas) { case "facture" -> Corpus::xml; case "ficheJson" -> Corpus::json; default -> Corpus::html; };
        return switch (cas) {
            case "fiche" -> o -> { var f = (Fiche) o;
                return "<article class=\"fiche\" data-ref=\"" + e.apply(f.reference()) + "\">\n<h1>" + e.apply(f.titre())
                        + "</h1>\n<p class=\"auteur\" title=\"" + e.apply(f.auteur()) + "\">Par " + e.apply(f.auteur())
                        + "</p>\n<p>" + e.apply(f.resume()) + "</p>\n<ul><li>" + e.apply(f.motCle1()) + "</li><li>"
                        + e.apply(f.motCle2()) + "</li><li>" + e.apply(f.motCle3()) + "</li></ul>\n</article>\n"; };
            case "dense100" -> o -> { var d = (Ligne) o; var sb = new StringBuilder("<table>\n");
                for (int i = 0; i < 33; i++) {
                    String r = e.apply(d.reference());
                    sb.append("<tr><td>").append(e.apply(d.nom())).append("</td><td title=\"").append(r).append("\">")
                            .append(r).append("</td><td>").append(e.apply(d.total())).append("</td></tr>\n");
                }
                return sb.append("<tr><td>").append(e.apply(d.nom())).append("</td></tr>\n</table>\n").toString(); };
            case "grand" -> o -> "<div>" + BLOC + "<p>" + e.apply(((Fiche) o).resume()) + "</p></div>\n";
            case "facture" -> o -> { var f = (Fiche) o;
                return "<facture ref=\"" + e.apply(f.reference()) + "\"><client nom=\"" + e.apply(f.auteur()) + "\">"
                        + e.apply(f.titre()) + "</client><resume>" + e.apply(f.resume()) + "</resume><motcles><mot>"
                        + e.apply(f.motCle1()) + "</mot><mot>" + e.apply(f.motCle2()) + "</mot><mot>"
                        + e.apply(f.motCle3()) + "</mot></motcles></facture>\n"; };
            case "ficheJson" -> o -> { var f = (Fiche) o;
                return "{\"reference\": \"" + e.apply(f.reference()) + "\", \"titre\": \"" + e.apply(f.titre()) + "\", \"auteur\": \""
                        + e.apply(f.auteur()) + "\", \"resume\": \"" + e.apply(f.resume()) + "\", \"motsCles\": [\""
                        + e.apply(f.motCle1()) + "\", \"" + e.apply(f.motCle2()) + "\", \"" + e.apply(f.motCle3()) + "\"]}\n"; };
            default -> throw new IllegalArgumentException(cas);
        };
    }

    /** Rendu Verne du cas : profil du gabarit échappé, ou même source compilée sous le profil texte. */
    static Function<Object, String> verne(String cas, boolean echappe) {
        return switch (cas) {
            case "fiche" -> echappe ? o -> FicheHtml.rendre((Fiche) o) : o -> FicheTexte.rendre((Fiche) o);
            case "dense100" -> echappe ? o -> Dense100Html.rendre((Ligne) o) : o -> Dense100Texte.rendre((Ligne) o);
            case "grand" -> echappe ? o -> GrandHtml.rendre((Fiche) o) : o -> GrandTexte.rendre((Fiche) o);
            case "facture" -> echappe ? o -> FactureXml.rendre((Fiche) o) : o -> FactureTexte.rendre((Fiche) o);
            case "ficheJson" -> echappe ? o -> FicheJson.rendre((Fiche) o) : o -> FicheJsonTexte.rendre((Fiche) o);
            default -> throw new IllegalArgumentException(cas);
        };
    }

    /** Oracle : égalité au témoin pour chaque jeu, avant toute mesure. */
    static void verifier(String cas, int densite, boolean echappe, Function<Object, String> rendu) {
        var temoin = temoin(cas, echappe);
        for (Object jeu : donnees(cas, densite)) {
            String attendu = temoin.apply(jeu), obtenu = rendu.apply(jeu);
            if (!attendu.equals(obtenu)) {
                int ecart = 0;
                while (ecart < Math.min(attendu.length(), obtenu.length()) && attendu.charAt(ecart) == obtenu.charAt(ecart)) ecart++;
                throw new IllegalStateException("Sortie différente : " + cas + ", densité " + densite + " %, échappement " + echappe
                        + ", premier écart au caractère " + ecart + " sur " + attendu.length() + " attendus et " + obtenu.length() + " obtenus.");
            }
        }
    }

    private static String html(String valeur) {
        StringBuilder resultat = null;
        for (int i = 0; i < valeur.length(); i++) {
            char c = valeur.charAt(i);
            String r = switch (c) { case '&' -> "&amp;"; case '<' -> "&lt;"; case '>' -> "&gt;";
                case '"' -> "&quot;"; case '\'' -> "&#39;"; default -> null; };
            if (r == null) { if (resultat != null) resultat.append(c); }
            else { if (resultat == null) resultat = new StringBuilder(valeur.length() + 16).append(valeur, 0, i); resultat.append(r); }
        }
        return resultat == null ? valeur : resultat.toString();
    }

    /** Les données ne contiennent ni contrôle ni retour chariot : la table se limite aux cinq caractères de balisage. */
    private static String xml(String valeur) {
        StringBuilder resultat = null;
        for (int i = 0; i < valeur.length(); i++) {
            char c = valeur.charAt(i);
            String r = switch (c) { case '&' -> "&amp;"; case '<' -> "&lt;"; case '>' -> "&gt;";
                case '"' -> "&quot;"; case '\'' -> "&apos;"; default -> null; };
            if (r == null) { if (resultat != null) resultat.append(c); }
            else { if (resultat == null) resultat = new StringBuilder(valeur.length() + 16).append(valeur, 0, i); resultat.append(r); }
        }
        return resultat == null ? valeur : resultat.toString();
    }

    private static String json(String valeur) {
        StringBuilder resultat = null;
        for (int i = 0; i < valeur.length(); i++) {
            char c = valeur.charAt(i);
            String r = switch (c) { case '"' -> "\\\""; case '\\' -> "\\\\"; case '\n' -> "\\n"; case '\t' -> "\\t";
                case '\r' -> "\\r"; case '\b' -> "\\b"; case '\f' -> "\\f";
                default -> c < 0x20 ? "\\u" + Integer.toHexString(c | 0x10000).substring(1) : null; };
            if (r == null) { if (resultat != null) resultat.append(c); }
            else { if (resultat == null) resultat = new StringBuilder(valeur.length() + 16).append(valeur, 0, i); resultat.append(r); }
        }
        return resultat == null ? valeur : resultat.toString();
    }
}
