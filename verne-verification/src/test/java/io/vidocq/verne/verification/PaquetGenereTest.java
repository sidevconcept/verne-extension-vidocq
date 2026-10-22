package io.vidocq.verne.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Le paquet généré appartient à l'application : obligatoire, valide et propre à chaque module. */
@Timeout(30)
class PaquetGenereTest {
    @TempDir Path repertoire;

    @Test void genereDansLePaquetDeclare() throws Exception {
        var compilation = compiler(repertoire, "-Averne.paquet=exemple.vues",
                BancCompilation.application("exemple.vues.Bonjour.rendre()"));
        assertTrue(compilation.reussie(), compilation.diagnostics());
        assertTrue(Files.exists(compilation.sourcesGenerees().resolve("exemple/vues/Bonjour.java")));
        assertFalse(Files.exists(compilation.sourcesGenerees().resolve("io/vidocq/verne/genere")));
        var execution = BancCompilation.executer(compilation);
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        assertEquals("Bonjour 🚀", execution.sortie());
    }

    @Test void refuseUnPaquetAbsent() throws Exception {
        refuser(compiler(repertoire, "-Averne.paquet", BancCompilation.application("\"témoin\"")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "exemple..vues", ".exemple", "exemple.", "exemple.class", "1exemple", "exemple.vu es"})
    void refuseUnPaquetInvalide(String paquet) throws Exception {
        refuser(compiler(repertoire, "-Averne.paquet=" + paquet, BancCompilation.application("\"témoin\"")));
    }

    /** Deux modules chargés dans la même couche doivent pouvoir utiliser Verne chacun de leur côté. */
    @Test void chargeEnsembleDeuxModulesAvecLeursPropresGabarits() throws Exception {
        var alpha = compiler(repertoire.resolve("alpha"), "-Averne.paquet=alpha.vues", Map.of(
                "module-info.java", "module alpha { exports alpha; }",
                "alpha/Salut.java", "package alpha; public final class Salut { public static String texte() { return alpha.vues.Bonjour.rendre(); } }"));
        assertTrue(alpha.reussie(), alpha.diagnostics());
        var options = BancCompilation.chargementVerne("-Averne.paquet=exemple.vues",
                "--module-path", alpha.classes().toString());
        var application = BancCompilation.compiler(repertoire.resolve("application"),
                Map.of("bonjour.verne", "Salut"), Map.of(
                        "module-info.java", "module application.verne { requires alpha; }",
                        "exemple/Application.java", """
                                package exemple;
                                public final class Application {
                                    public static void main(String[] arguments) {
                                        System.out.print(alpha.Salut.texte() + " / " + exemple.vues.Bonjour.rendre());
                                    }
                                }
                                """), true, repertoire.resolve("application/gabarits"), options);
        assertTrue(application.reussie(), application.diagnostics());
        var execution = BancCompilation.executer(application, alpha.classes());
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        assertEquals("Bonjour 🚀 / Salut", execution.sortie());
    }

    private static BancCompilation.Compilation compiler(Path racine, String optionPaquet,
            Map<String, String> sources) throws Exception {
        return BancCompilation.compiler(racine, Map.of("bonjour.verne", "Bonjour 🚀"), sources, true,
                racine.resolve("gabarits"), BancCompilation.chargementVerne(optionPaquet));
    }

    private static void refuser(BancCompilation.Compilation compilation) throws Exception {
        assertFalse(compilation.reussie(), compilation.diagnostics());
        assertEquals(1, compilation.diagnostics().lines().filter(ligne -> ligne.contains("VERNE-CFG-001")).count(),
                compilation.diagnostics());
        try (var fichiers = Files.walk(compilation.sourcesGenerees())) {
            assertTrue(fichiers.noneMatch(Files::isRegularFile), "Aucune source ne doit être générée.");
        }
    }
}
