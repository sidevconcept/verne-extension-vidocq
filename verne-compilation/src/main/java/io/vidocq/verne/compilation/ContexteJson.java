package io.vidocq.verne.compilation;

/**
 * Suit les littéraux de chaîne JSON du texte statique, entre guillemets doubles non échappés.
 * L'instance appartient à une tentative de compilation ; une valeur interpolée, échappée, ne change pas l'état.
 */
final class ContexteJson {
    private boolean chaine;
    private boolean echappement;

    void avancer(String texte) {
        for (int index = 0; index < texte.length(); index++) {
            char caractere = texte.charAt(index);
            if (!chaine) {
                if (caractere == '"') chaine = true;
            } else if (echappement) {
                echappement = false;
            } else if (caractere == '\\') {
                echappement = true;
            } else if (caractere == '"') {
                chaine = false;
            }
        }
    }

    /** Une interpolation juste après un antislash statique compléterait une séquence d'échappement. */
    String refus() {
        return chaine && echappement ? "séquence d'échappement en cours dans un littéral" : null;
    }

    boolean dansChaine() {
        return chaine;
    }
}
