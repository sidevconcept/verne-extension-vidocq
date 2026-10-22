package io.vidocq.verne.compilation;

/** Profil de sortie d'une invocation, choisi par l'option verne.profil.sortie. */
enum Profil {
    TEXTE, HTML, XML;

    /** Valeur exacte de l'option, sensible à la casse ; null pour une valeur absente ou inconnue. */
    static Profil depuis(String option) {
        if (option == null) return null;
        return switch (option) {
            case "texte" -> TEXTE;
            case "html" -> HTML;
            case "xml" -> XML;
            default -> null;
        };
    }
}
