package io.vidocq.verne.compilation;

import java.math.BigInteger;

/**
 * Arbre syntaxique d'une expression de gabarit, selon la grammaire du contrat des expressions.
 * L'analyse est purement syntaxique et bornée ; le typage et la génération appartiennent au compilateur.
 */
sealed interface Expression {
    int OPERATEURS_MAXIMAUX = 64;
    int PARENTHESES_MAXIMALES = 32;
    int LONGUEUR_MAXIMALE_CHAINE = 4_000;

    /** Littéral déjà validé ; java est sa forme source Java, sans signe. */
    record Litteral(Sorte sorte, String java) implements Expression {}
    /** Paramètre ou variable de boucle suivi de composants de records, écrit sans espace. */
    record Chemin(String texte) implements Expression {}
    record Unaire(String operateur, Expression operande) implements Expression {}
    record Binaire(String operateur, Expression gauche, Expression droite) implements Expression {}

    enum Sorte { INT, LONG, DOUBLE, CHAINE, BOOLEEN }

    /** Refus syntaxique ou de limite ; le message ne contient aucune valeur métier. */
    final class Invalide extends RuntimeException {
        final String code;
        Invalide(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }

    /** Analyse le contenu complet d'une directive ; lève Invalide si la grammaire ou une limite n'est pas respectée. */
    static Expression analyser(String source) {
        var analyseur = new Analyseur(source);
        Expression expression = analyseur.ou();
        analyseur.espaces();
        if (analyseur.position != source.length()) throw Analyseur.syntaxe();
        return expression;
    }

    /** Pondérations du budget, en plus des 48 unités de la directive : accès, chemins supplémentaires, opérateurs, littéraux. */
    static long cout(Expression expression) {
        var compte = new long[2];
        long cout = cout(expression, compte);
        return cout + 16 * Math.max(0, compte[0] - 1);
    }

    private static long cout(Expression expression, long[] compte) {
        return switch (expression) {
            case Litteral litteral -> 8;
            case Chemin chemin -> {
                compte[0]++;
                yield 16L * chemin.texte().chars().filter(caractere -> caractere == '.').count();
            }
            case Unaire unaire -> 24 + cout(unaire.operande(), compte);
            case Binaire binaire -> 24 + cout(binaire.gauche(), compte) + cout(binaire.droite(), compte);
        };
    }

    /** Analyse descendante récursive ; la récursion est bornée par les nombres d'opérateurs et de parenthèses. */
    final class Analyseur {
        private final String source;
        private int position;
        private int operateurs;
        private int parentheses;

        private Analyseur(String source) {
            this.source = source;
        }

        private static Invalide syntaxe() {
            return new Invalide("VERNE-SYN-002", "expression ou directive non prise en charge.");
        }

        private void espaces() {
            while (position < source.length() && " \t\r\n".indexOf(source.charAt(position)) >= 0) position++;
        }

        /** Consomme le symbole s'il est le prochain élément, en comptant les opérateurs. */
        private boolean accepter(String symbole) {
            espaces();
            if (!source.startsWith(symbole, position)) return false;
            position += symbole.length();
            if (!symbole.equals("(") && !symbole.equals(")") && ++operateurs > OPERATEURS_MAXIMAUX) {
                throw new Invalide("VERNE-LIM-010", "plus de 64 opérateurs ou de 32 niveaux de parenthèses dans une expression.");
            }
            return true;
        }

        private boolean regarder(String... symboles) {
            espaces();
            for (String symbole : symboles) {
                if (source.startsWith(symbole, position)) return true;
            }
            return false;
        }

        private Expression ou() {
            Expression expression = et();
            while (accepter("||")) expression = new Binaire("||", expression, et());
            return expression;
        }

        private Expression et() {
            Expression expression = egalite();
            while (accepter("&&")) expression = new Binaire("&&", expression, egalite());
            return expression;
        }

        /** Égalités et comparaisons ne s'enchaînent pas. */
        private Expression egalite() {
            Expression expression = comparaison();
            for (String operateur : new String[] {"==", "!="}) {
                if (accepter(operateur)) {
                    expression = new Binaire(operateur, expression, comparaison());
                    if (regarder("==", "!=")) throw syntaxe();
                    break;
                }
            }
            return expression;
        }

        private Expression comparaison() {
            Expression expression = additif();
            for (String operateur : new String[] {"<=", ">=", "<", ">"}) {
                if (accepter(operateur)) {
                    expression = new Binaire(operateur, expression, additif());
                    if (regarder("<", ">")) throw syntaxe();
                    break;
                }
            }
            return expression;
        }

        private Expression additif() {
            Expression expression = multiplicatif();
            while (true) {
                if (accepter("+")) expression = new Binaire("+", expression, multiplicatif());
                else if (accepter("-")) expression = new Binaire("-", expression, multiplicatif());
                else return expression;
            }
        }

        private Expression multiplicatif() {
            Expression expression = unaire();
            while (true) {
                if (accepter("*")) expression = new Binaire("*", expression, unaire());
                else if (accepter("/")) expression = new Binaire("/", expression, unaire());
                else if (accepter("%")) expression = new Binaire("%", expression, unaire());
                else return expression;
            }
        }

        private Expression unaire() {
            if (regarder("!=")) throw syntaxe();
            if (accepter("!")) return new Unaire("!", unaire());
            if (accepter("-")) return new Unaire("-", unaire());
            return primaire();
        }

        private Expression primaire() {
            espaces();
            if (position >= source.length()) throw syntaxe();
            char caractere = source.charAt(position);
            if (accepter("(")) {
                if (++parentheses > PARENTHESES_MAXIMALES) {
                    throw new Invalide("VERNE-LIM-010", "plus de 64 opérateurs ou de 32 niveaux de parenthèses dans une expression.");
                }
                Expression expression = ou();
                if (!accepter(")")) throw syntaxe();
                parentheses--;
                return expression;
            }
            if (caractere >= '0' && caractere <= '9') return nombre();
            if (caractere == '"') return chaine();
            if (Character.isJavaIdentifierStart(source.codePointAt(position))) return chemin();
            throw syntaxe();
        }

        /** Décimal sans signe, sans zéro de tête, séparateur, base, exposant ni suffixe autre que L pour un entier. */
        private Expression nombre() {
            int debut = position;
            while (position < source.length() && Character.isDigit(source.charAt(position))
                    && source.charAt(position) < 0x80) position++;
            String entier = source.substring(debut, position);
            if (entier.length() > 1 && entier.charAt(0) == '0') throw syntaxe();
            Expression litteral;
            if (position < source.length() && source.charAt(position) == '.') {
                int virgule = ++position;
                while (position < source.length() && source.charAt(position) >= '0' && source.charAt(position) <= '9') position++;
                if (position == virgule) throw syntaxe();
                String texte = source.substring(debut, position);
                double valeur = Double.parseDouble(texte);
                // Ni infinité, ni valeur non nulle arrondie à zéro.
                if (Double.isInfinite(valeur) || valeur == 0 && texte.chars().anyMatch(chiffre -> chiffre >= '1' && chiffre <= '9')) {
                    throw syntaxe();
                }
                litteral = new Litteral(Sorte.DOUBLE, Double.toString(valeur));
            } else if (position < source.length() && source.charAt(position) == 'L') {
                position++;
                if (new BigInteger(entier).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) throw syntaxe();
                litteral = new Litteral(Sorte.LONG, entier + "L");
            } else {
                if (new BigInteger(entier).compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) throw syntaxe();
                litteral = new Litteral(Sorte.INT, entier);
            }
            if (position < source.length() && (Character.isJavaIdentifierPart(source.codePointAt(position))
                    || source.charAt(position) == '.')) {
                throw syntaxe();
            }
            return litteral;
        }

        private Expression chaine() {
            var valeur = new StringBuilder();
            position++;
            while (true) {
                if (position >= source.length()) throw syntaxe();
                char caractere = source.charAt(position++);
                if (caractere == '"') break;
                if (caractere == '\n' || caractere == '\r') throw syntaxe();
                if (caractere == '\\') {
                    if (position >= source.length()) throw syntaxe();
                    char echappe = source.charAt(position++);
                    switch (echappe) {
                        case '"', '\\' -> valeur.append(echappe);
                        case 'n' -> valeur.append('\n');
                        case 't' -> valeur.append('\t');
                        case 'r' -> valeur.append('\r');
                        default -> throw syntaxe();
                    }
                } else {
                    valeur.append(caractere);
                }
            }
            // Même borne que les fragments statiques : la constante Java reste sous 65 535 octets, quel que soit l'encodage.
            if (valeur.length() > LONGUEUR_MAXIMALE_CHAINE) {
                throw new Invalide("VERNE-LIM-011", "littéral de chaîne de plus de 4 000 unités UTF-16.");
            }
            return new Litteral(Sorte.CHAINE, "\"" + ProcesseurVerne.echapper(valeur.toString()) + "\"");
        }

        private Expression chemin() {
            int debut = position;
            while (position < source.length()) {
                int point = source.codePointAt(position);
                if (Character.isJavaIdentifierPart(point) || point == '.') position += Character.charCount(point);
                else break;
            }
            String texte = source.substring(debut, position);
            if (texte.equals("true") || texte.equals("false")) return new Litteral(Sorte.BOOLEEN, texte);
            if (!CompilateurGabarit.nomQualifie(texte)) throw syntaxe();
            return new Chemin(texte);
        }
    }
}
