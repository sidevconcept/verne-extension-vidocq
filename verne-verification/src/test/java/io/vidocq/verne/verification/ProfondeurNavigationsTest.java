package io.vidocq.verne.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Borne par occurrence, résolution statique et nullité dans une vraie application modulaire. */
@Timeout(30)
class ProfondeurNavigationsTest {
    @TempDir Path repertoire;

    @Test void accepteTrenteEtUnAcces() throws Exception {
        verifierRendu(gabarit(31), appel(31), "Léa🚀");
    }

    @Test void accepteExactementTrenteDeuxAcces() throws Exception {
        verifierRendu(gabarit(32), appel(32), "Léa🚀");
    }

    @Test void refuseTrenteTroisAccesSansGenererEtPoursuitLesAutresFichiers() throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("bonjour.verne", gabarit(33), "salut.verne", gabarit(32)),
                sources("\"témoin\""), true);
        assertAll(() -> assertFalse(compilation.reussie()),
                () -> assertTrue(compilation.diagnostics().contains("VERNE-LIM-005"), compilation.diagnostics()),
                () -> assertFalse(Files.exists(compilation.sourcesGenerees()
                        .resolve("exemple/gabarits/Bonjour.java"))),
                () -> assertTrue(Files.exists(compilation.sourcesGenerees()
                        .resolve("exemple/gabarits/Salut.java"))));
    }

    @Test void refuseAvantResolutionAvecPositionApresCrLfEtEmoji() throws Exception {
        var compilation = compiler("\r\n🚀{inconnu" + ".suivant".repeat(32) + ".confidentiel}",
                "\"témoin\"");
        assertAll(() -> assertFalse(compilation.reussie()),
                () -> assertTrue(compilation.diagnostics().contains("VERNE-LIM-005"), compilation.diagnostics()),
                () -> assertTrue(compilation.diagnostics().contains("bonjour.verne:2:2"), compilation.diagnostics()),
                () -> assertFalse(compilation.diagnostics().contains("VERNE-TYP-001")),
                () -> assertFalse(compilation.diagnostics().contains("confidentiel")),
                () -> assertFalse(compilation.diagnostics().contains(repertoire.toString())));
    }

    @Test void compteChaqueOccurrenceSeparementSansCompterLesTextesLitteraux() throws Exception {
        String litteral = "{racine" + ".suivant".repeat(33) + ".valeur}";
        String texte = "{! " + litteral + " !}\\" + litteral + gabarit(32)
                + "|{racine" + ".suivant".repeat(31) + ".valeur}";
        verifierRendu(texte, appel(32), litteral + "Léa🚀|Léa🚀");
    }

    @Test void refuseUnObjetIntermediaireNulALaBorne() throws Exception {
        verifierRendu(gabarit(32), "try { return " + appel(31)
                + "; } catch (NullPointerException erreur) { return erreur.getMessage(); }",
                "VERNE-REN-001 : objet de navigation nul.");
    }

    @Test void refuseUneValeurTerminaleNulleALaBorne() throws Exception {
        verifierRendu(gabarit(32), "try { return exemple.gabarits.Bonjour.rendre(chaine(32, null));"
                + " } catch (NullPointerException erreur) { return erreur.getMessage(); }",
                "VERNE-REN-001 : valeur interpolée nulle.");
    }

    @Test void conserveLeRefusDuneProprieteInconnueDansLaBorne() throws Exception {
        var compilation = compiler("{@exemple.Maillon racine}{racine" + ".suivant".repeat(31)
                + ".inconnue}", "\"témoin\"");
        assertAll(() -> assertFalse(compilation.reussie()),
                () -> assertTrue(compilation.diagnostics().contains("VERNE-TYP-001"), compilation.diagnostics()),
                () -> assertFalse(compilation.diagnostics().contains("VERNE-LIM-005")));
    }

    private String gabarit(int profondeur) {
        return "{@exemple.Maillon racine}{racine" + ".suivant".repeat(profondeur - 1) + ".valeur}";
    }

    private String appel(int profondeur) {
        return "exemple.gabarits.Bonjour.rendre(chaine(" + profondeur + ", \"Léa🚀\"))";
    }

    private Map<String, String> sources(String corps) {
        var sources = new HashMap<String, String>();
        sources.put("exemple/Maillon.java", "package exemple; public record Maillon(Maillon suivant, String valeur) {}");
        sources.put("exemple/Application.java", """
                package exemple;
                public final class Application {
                    private static Maillon chaine(int longueur, String valeur) {
                        Maillon maillon = null;
                        for (int index = 0; index < longueur; index++) maillon = new Maillon(maillon, valeur);
                        return maillon;
                    }
                    private static String document() { %s }
                    public static void main(String[] arguments) { System.out.print(document()); }
                }
                """.formatted(corps.startsWith("try") ? corps : "return " + corps + ";"));
        return sources;
    }

    private BancCompilation.Compilation compiler(String texte, String corps) throws Exception {
        return BancCompilation.compiler(repertoire, Map.of("bonjour.verne", texte), sources(corps), true);
    }

    private void verifierRendu(String texte, String corps, String attendu) throws Exception {
        var compilation = compiler(texte, corps);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals(attendu, execution.sortie()));
    }
}
