package io.vidocq.verne.verification;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Échappement exhaustif (VERNE-003, C1) : chaque caractère de U+0000 à U+00FF, U+2028, U+2029, substituts isolés
 * et emoji, dans chaque profil, en petit et en grand document. Le témoin est écrit d'après le contrat, sans le code
 * généré ; une seule compilation et une seule exécution servent les six gabarits.
 */
@Timeout(60)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EchappementExhaustifTest {
    /** Atteint le seuil de 4 000 caractères statiques des grands documents ; retiré de la sortie par l'application. */
    private static final String BLOC = "a".repeat(4_000);
    private static final String DECLARATION = "{@java.lang.String v}";
    private Map<String, List<String>> rendus;

    @BeforeAll void rendreTousLesCaracteres(@TempDir Path repertoire) throws Exception {
        var gabarits = Map.of(
                "page.html.verne", DECLARATION + "<p title=\"{v}\">{v}</p>",
                "grandepage.html.verne", DECLARATION + "<div>" + BLOC + "<p title=\"{v}\">{v}</p></div>",
                "flux.xml.verne", DECLARATION + "<e a=\"{v}\">{v}</e>",
                "grandflux.xml.verne", DECLARATION + "<r>" + BLOC + "<e a=\"{v}\">{v}</e></r>",
                "donnees.json.verne", DECLARATION + "\\{\"v\": \"{v}\"}",
                "grandesdonnees.json.verne", DECLARATION + "\\{\"bloc\": \"" + BLOC + "\", \"v\": \"{v}\"}");
        var sources = new HashMap<String, String>();
        sources.put("exemple/Application.java", """
                package exemple;
                import java.util.function.Function;
                public final class Application {
                    public static void main(String[] arguments) {
                        var gabarits = new java.util.LinkedHashMap<String, Function<String, String>>();
                        gabarits.put("Page", exemple.gabarits.Page::rendre);
                        gabarits.put("Grandepage", exemple.gabarits.Grandepage::rendre);
                        gabarits.put("Flux", exemple.gabarits.Flux::rendre);
                        gabarits.put("Grandflux", exemple.gabarits.Grandflux::rendre);
                        gabarits.put("Donnees", exemple.gabarits.Donnees::rendre);
                        gabarits.put("Grandesdonnees", exemple.gabarits.Grandesdonnees::rendre);
                        var sortie = new StringBuilder();
                        for (var gabarit : gabarits.entrySet()) {
                            for (String unite : %s) {
                                String rendu;
                                try {
                                    rendu = gabarit.getValue().apply("a" + unite + "b" + unite).replace("a".repeat(4_000), "#");
                                } catch (IllegalArgumentException refus) {
                                    rendu = "REFUS " + refus.getMessage();
                                }
                                sortie.append(gabarit.getKey()).append('\\t');
                                rendu.chars().forEach(c -> sortie.append(Integer.toHexString(c)).append(' '));
                                sortie.append('\\n');
                            }
                        }
                        System.out.print(sortie);
                    }
                }
                """.formatted(litteralUnites()));
        var compilation = BancCompilation.compiler(repertoire, gabarits, sources, true,
                repertoire.resolve("gabarits"), BancCompilation.chargementVerne());
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        rendus = new HashMap<>();
        for (String ligne : execution.sortie().split("\n")) {
            String[] champs = ligne.split("\t", -1);
            var rendu = new StringBuilder();
            for (String code : champs[1].trim().split(" ")) {
                if (!code.isEmpty()) rendu.append((char) Integer.parseInt(code, 16));
            }
            rendus.computeIfAbsent(champs[0], cle -> new ArrayList<>()).add(rendu.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Page", "Grandepage", "Flux", "Grandflux", "Donnees", "Grandesdonnees"})
    void echappeChaqueCaractereCommeLeContrat(String gabarit) {
        List<String> obtenus = rendus.get(gabarit);
        List<String> unites = unites();
        assertEquals(unites.size(), obtenus.size(), gabarit);
        for (int index = 0; index < unites.size(); index++) {
            String valeur = "a" + unites.get(index) + "b" + unites.get(index);
            String attendu = switch (gabarit) {
                case "Page" -> "<p title=\"" + html(valeur) + "\">" + html(valeur) + "</p>";
                case "Grandepage" -> "<div>#<p title=\"" + html(valeur) + "\">" + html(valeur) + "</p></div>";
                case "Flux" -> xml(valeur, "<e a=\"%s\">%s</e>");
                case "Grandflux" -> xml(valeur, "<r>#<e a=\"%s\">%s</e></r>");
                case "Donnees" -> "{\"v\": \"" + json(valeur) + "\"}";
                default -> "{\"bloc\": \"#\", \"v\": \"" + json(valeur) + "\"}";
            };
            assertEquals(attendu, obtenus.get(index), () -> gabarit + ", unité " + codes(valeur));
        }
    }

    /** U+0000 à U+00FF, séparateurs de ligne Unicode, substituts isolés haut et bas, emoji (paire valide). */
    private static List<String> unites() {
        var unites = new ArrayList<String>();
        for (char caractere = 0; caractere <= 0xFF; caractere++) unites.add(String.valueOf(caractere));
        unites.addAll(List.of("\u2028", "\u2029", "\uD800", "\uDC00", "🚀"));
        return unites;
    }

    /** Les mêmes unités pour l'application, en codes numériques : javac traduirait \u000a avant l'analyse. */
    private static String litteralUnites() {
        var litteraux = new ArrayList<String>();
        for (String unite : unites()) {
            var codes = new ArrayList<String>();
            unite.chars().forEach(c -> codes.add("(char) 0x%x".formatted(c)));
            litteraux.add("new String(new char[] {" + String.join(", ", codes) + "})");
        }
        return "new String[] {" + String.join(", ", litteraux) + "}";
    }

    private static String html(String valeur) {
        var resultat = new StringBuilder();
        valeur.chars().forEach(c -> resultat.append(switch (c) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '"' -> "&quot;";
            case '\'' -> "&#39;";
            default -> String.valueOf((char) c);
        }));
        return resultat.toString();
    }

    /** Refus REN-002 pour un caractère interdit par XML 1.0, sinon contenu puis attribut. */
    private static String xml(String valeur, String forme) {
        for (int index = 0; index < valeur.length(); index++) {
            char c = valeur.charAt(index);
            boolean paire = Character.isHighSurrogate(c) && index + 1 < valeur.length()
                    && Character.isLowSurrogate(valeur.charAt(index + 1));
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r' || c == 0xFFFE || c == 0xFFFF
                    || Character.isSurrogate(c) && !paire) {
                return "REFUS VERNE-REN-002 : caractère interdit par XML 1.0 dans une valeur interpolée.";
            }
            if (paire) index++;
        }
        return forme.formatted(xml(valeur, true), xml(valeur, false));
    }

    private static String xml(String valeur, boolean attribut) {
        var resultat = new StringBuilder();
        valeur.chars().forEach(c -> resultat.append(switch (c) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '"' -> "&quot;";
            case '\'' -> "&apos;";
            case '\r' -> "&#13;";
            case '\n' -> attribut ? "&#10;" : "\n";
            case '\t' -> attribut ? "&#9;" : "\t";
            default -> String.valueOf((char) c);
        }));
        return resultat.toString();
    }

    private static String json(String valeur) {
        var resultat = new StringBuilder();
        for (int index = 0; index < valeur.length(); index++) {
            char c = valeur.charAt(index);
            boolean paire = Character.isHighSurrogate(c) && index + 1 < valeur.length()
                    && Character.isLowSurrogate(valeur.charAt(index + 1));
            switch (c) {
                case '"' -> resultat.append("\\\"");
                case '\\' -> resultat.append("\\\\");
                case '\b' -> resultat.append("\\b");
                case '\f' -> resultat.append("\\f");
                case '\n' -> resultat.append("\\n");
                case '\r' -> resultat.append("\\r");
                case '\t' -> resultat.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029 || Character.isSurrogate(c) && !paire) {
                        resultat.append("\\u%04x".formatted((int) c));
                    } else {
                        resultat.append(c);
                        if (paire) resultat.append(valeur.charAt(++index));
                    }
                }
            }
        }
        return resultat.toString();
    }

    private static String codes(String valeur) {
        var codes = new StringBuilder();
        valeur.chars().forEach(c -> codes.append("U+%04X ".formatted(c)));
        return codes.toString().trim();
    }
}
