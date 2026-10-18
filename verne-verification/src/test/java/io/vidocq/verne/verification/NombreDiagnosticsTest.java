package io.vidocq.verne.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Budget commun aux diagnostics de découverte et d'analyse, sans troncature silencieuse. */
@Timeout(30)
class NombreDiagnosticsTest {
    @TempDir Path repertoire;

    @Test void conserveSoixanteTroisErreursEtVerifieLeGabaritValideSuivant() throws Exception {
        verifierDansLaBorne(compiler(repertoire, erreursAnalyse(63)), 63);
    }

    @Test void conserveExactementSoixanteQuatreErreursAvecLeursPositions() throws Exception {
        var compilation = compiler(repertoire, erreursAnalyse(64));
        verifierDansLaBorne(compilation, 64);
        assertTrue(compilation.diagnostics().contains("gabarit063.verne:2:2"), compilation.diagnostics());
    }

    @Test void remplaceLaSoixanteCinquiemeErreurEtArreteLaGenerationSuivante() throws Exception {
        var compilation = compiler(repertoire, erreursAnalyse(65));
        verifierSaturation(compilation, "VERNE-TYP-001", 64);
        assertFalse(compilation.diagnostics().contains("gabarit064.verne"), compilation.diagnostics());
    }

    @Test void partageLeBudgetEntreLesErreursDuProcesseurEtCellesDeLanalyseur() throws Exception {
        var gabarits = new HashMap<String, String>();
        for (int index = 0; index < 32; index++) {
            gabarits.put(String.format(Locale.ROOT, "a%03d-invalide.verne", index), "texte");
        }
        for (int index = 0; index < 33; index++) {
            gabarits.put(String.format(Locale.ROOT, "b%03d.verne", index), "{inconnu}");
        }
        gabarits.put("z.verne", "texte");
        var compilation = compiler(repertoire, gabarits);
        verifierSaturation(compilation, "VERNE-TYP-001", 32);
        assertEquals(32, compter(compilation, "VERNE-LIA-001"));
    }

    @Test void borneMilleErreursDeNommageAvantLaTroncatureParJavac() throws Exception {
        var gabarits = new HashMap<String, String>();
        for (int index = 0; index < 1_000; index++) {
            gabarits.put(String.format(Locale.ROOT, "gabarit%03d-invalide.verne", index), "texte");
        }
        verifierSaturation(compiler(repertoire, gabarits), "VERNE-LIA-001", 64);
    }

    @Test void isoleLeBudgetDeDeuxCompilationsConcurrentes() throws Exception {
        var gabarits = erreursAnalyse(64);
        try (var executeur = Executors.newVirtualThreadPerTaskExecutor()) {
            var premiere = executeur.submit(() -> compiler(repertoire.resolve("premiere"), gabarits));
            var seconde = executeur.submit(() -> compiler(repertoire.resolve("seconde"), gabarits));
            verifierDansLaBorne(premiere.get(), 64);
            verifierDansLaBorne(seconde.get(), 64);
        }
    }

    private Map<String, String> erreursAnalyse(int nombre) {
        var gabarits = new HashMap<String, String>();
        for (int index = 0; index < nombre; index++) {
            gabarits.put(String.format(Locale.ROOT, "gabarit%03d.verne", index), "\r\n🚀{inconnu}");
        }
        gabarits.put("z.verne", "texte");
        return gabarits;
    }

    private BancCompilation.Compilation compiler(Path racine, Map<String, String> gabarits) throws Exception {
        return BancCompilation.compiler(racine, gabarits, BancCompilation.application("\"témoin\""), true);
    }

    private long compter(BancCompilation.Compilation compilation, String code) {
        return compilation.diagnostics().lines().filter(ligne -> ligne.contains(code)).count();
    }

    private void verifierDansLaBorne(BancCompilation.Compilation compilation, int nombre) {
        assertAll(() -> assertFalse(compilation.reussie()),
                () -> assertEquals(nombre, compter(compilation, "VERNE-TYP-001")),
                () -> assertEquals(0, compter(compilation, "VERNE-LIM-007")),
                () -> assertTrue(Files.exists(compilation.sourcesGenerees()
                        .resolve("io/vidocq/verne/genere/Z.java"))));
    }

    private void verifierSaturation(BancCompilation.Compilation compilation, String code,
                                    int nombre) throws Exception {
        assertAll(() -> assertFalse(compilation.reussie()),
                () -> assertEquals(nombre, compter(compilation, code), compilation.diagnostics()),
                () -> assertEquals(1, compter(compilation, "VERNE-LIM-007"), compilation.diagnostics()),
                () -> assertEquals(65, compter(compilation, "VERNE-")),
                () -> assertFalse(compilation.diagnostics().contains(repertoire.toString())),
                () -> assertFalse(compilation.diagnostics().contains("🚀")));
        try (var sources = Files.walk(compilation.sourcesGenerees())) {
            assertFalse(sources.anyMatch(chemin -> chemin.toString().endsWith(".java")));
        }
    }
}
