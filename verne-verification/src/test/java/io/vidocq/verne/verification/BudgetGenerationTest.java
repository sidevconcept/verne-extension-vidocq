package io.vidocq.verne.verification;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.CodeAttribute;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Budget pondéré vérifié aux bornes avec compilation et rendu modulaires réels. */
@Timeout(30)
class BudgetGenerationTest {
    @TempDir Path repertoire;

    @Test void accepteCinquanteNeufMilleNeufCentQuatreVingtSeizeUnites() throws Exception {
        // 128 + 4 * 12 + 106 * 48 + (105 * 32 + 30) * 16 + 41 * 12 = 59 996.
        rendre(gabarit(30, 41), appel("Bonjour"), "a".repeat(41) + "Léa🚀".repeat(106));
    }

    @Test void accepteExactementSoixanteMilleUnites() throws Exception {
        // 128 + 4 * 12 + 106 * 48 + (105 * 32 + 31) * 16 + 40 * 12 = 60 000.
        rendre(gabarit(31, 40), appel("Bonjour"), "a".repeat(40) + "Léa🚀".repeat(106));
    }

    @Test void refuseSoixanteMilleQuatreUnitesAvantGeneration() throws Exception {
        // 128 + 4 * 12 + 106 * 48 + 106 * 32 * 16 + 39 * 12 = 60 004.
        refuser(gabarit(32, 39));
    }

    @Test void remplaceLeDepassementDeTailleJavacParUnDiagnosticVerne() throws Exception {
        refuser("{@exemple.Maillon racine}" + interpolation(32).repeat(1_024));
    }

    @Test void compteLesFragmentsUnicodeSansCouperUnePaireDeSubstituts() throws Exception {
        String texte = declarations() + "a{! !}".repeat(38) + "a".repeat(3_999)
                + "🚀{! !}" + interpolation(32).repeat(105) + interpolation(31);
        // Le texte de 4 001 unités UTF-16 produit deux fragments, soit 40 au total.
        rendre(texte, appel("Bonjour"), "a".repeat(4_037) + "🚀" + "Léa🚀".repeat(106));
    }

    @Test void isoleLeBudgetDesGabaritsEtPoursuitApresUnRefus() throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("a.verne", gabarit(32, 39), "bonjour.verne", gabarit(31, 40),
                        "salut.verne", gabarit(31, 40)),
                sources("\"témoin\""), true);
        verifierRefus(compilation, "a.verne");
        for (String classe : new String[] {"Bonjour", "Salut"}) {
            assertTrue(Files.exists(compilation.sourcesGenerees()
                    .resolve("exemple/gabarits/" + classe + ".java")));
        }
    }

    @Test void conserveLesBornesDeParametresInterpolationsEtGrandTexteCombinees() throws Exception {
        String parametres = IntStream.range(0, 255)
                .mapToObj(index -> "{@java.lang.String nom" + index + "}").collect(Collectors.joining());
        String expressions = IntStream.range(0, 1_024)
                .mapToObj(index -> "{nom" + index % 255 + "}").collect(Collectors.joining());
        String arguments = IntStream.range(0, 255).mapToObj(index -> "\"é🚀\"")
                .collect(Collectors.joining(", "));
        // 128 + 255 * 12 + 225 * 12 + 1 024 * 48 = 55 040 unités.
        rendre(parametres + "a".repeat(900_000) + expressions,
                "exemple.gabarits.Bonjour.rendre(" + arguments + ")",
                "a".repeat(900_000) + "é🚀".repeat(1_024));
    }

    private String declarations() {
        return "{@exemple.Maillon racine}{@java.lang.String premier}"
                + "{@java.lang.String second}{@java.lang.String troisieme}";
    }

    private String interpolation(int profondeur) {
        return "{racine" + ".suivant".repeat(profondeur - 1) + ".valeur}";
    }

    private String gabarit(int profondeurFinale, int fragments) {
        return declarations() + "a{! !}".repeat(fragments)
                + interpolation(32).repeat(105) + interpolation(profondeurFinale);
    }

    private String appel(String classe) {
        return "exemple.gabarits." + classe + ".rendre(chaine(), \"\", \"\", \"\")";
    }

    private Map<String, String> sources(String expression) {
        var sources = new HashMap<String, String>();
        sources.put("exemple/Maillon.java", "package exemple; public record Maillon(Maillon suivant, String valeur) {}");
        sources.put("exemple/Application.java", """
                package exemple;
                public final class Application {
                    private static Maillon chaine() {
                        Maillon maillon = null;
                        for (int index = 0; index < 32; index++) maillon = new Maillon(maillon, "Léa🚀");
                        return maillon;
                    }
                    public static void main(String[] arguments) { System.out.print(%s); }
                }
                """.formatted(expression));
        return sources;
    }

    private void refuser(String texte) throws Exception {
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne", texte),
                sources("\"témoin\""), true);
        verifierRefus(compilation, "bonjour.verne");
    }

    private void verifierRefus(BancCompilation.Compilation compilation, String identifiant) {
        assertAll(() -> assertFalse(compilation.reussie()),
                () -> assertTrue(compilation.diagnostics().contains("VERNE-LIM-008"), compilation.diagnostics()),
                () -> assertTrue(compilation.diagnostics().contains(identifiant + ":1:1"), compilation.diagnostics()),
                () -> assertFalse(compilation.diagnostics().contains("compiler.err.limit.code")),
                () -> assertFalse(compilation.diagnostics().contains(repertoire.toString())),
                () -> assertFalse(Files.exists(compilation.sourcesGenerees()
                        .resolve("exemple/gabarits/" + (identifiant.startsWith("a.") ? "A" : "Bonjour") + ".java"))));
    }

    private void rendre(String texte, String expression, String attendu) throws Exception {
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne", texte),
                sources(expression), true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals(attendu, execution.sortie()));
        var modele = ClassFile.of().parse(compilation.classes().resolve("exemple/gabarits/Bonjour.class"));
        var methode = modele.methods().stream().filter(candidat -> candidat.methodName().equalsString("rendre"))
                .findFirst().orElseThrow();
        var code = (CodeAttribute) methode.code().orElseThrow();
        assertTrue(code.codeLength() <= 60_000, "Taille observée de la méthode : " + code.codeLength());
        System.out.println("Taille observée de la méthode de rendu : " + code.codeLength() + " octets.");
    }
}
