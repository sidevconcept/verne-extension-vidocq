package io.vidocq.verne.verification;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Budget d'octets réellement lus, vérifié avec le processeur et le système de fichiers réels. */
@Timeout(30)
class VolumeSourcesTest {
    private static final int MEBIOCTET = 1_048_576;
    @TempDir Path repertoire;

    @Test void accepteUnOctetDeMoinsQueHuitMebioctets() throws Exception {
        verifierAcceptation(compiler(repertoire, gabarits(1)), 8);
    }

    @Test void accepteExactementHuitMebioctetsEtUnFichierVide() throws Exception {
        var gabarits = gabarits(0);
        gabarits.put("z.verne", "");
        verifierAcceptation(compiler(repertoire, gabarits), 9);
    }

    @Test void refuseLePremierOctetSupplementaireAvantAnalyseEtArreteLesSuivants() throws Exception {
        var gabarits = gabarits(0);
        gabarits.put("z.verne", "{");
        gabarits.put("zz.verne", "{inconnu}");
        var compilation = compiler(repertoire, gabarits);
        verifierDepassement(compilation, "z.verne", 8);
        assertAll(() -> assertFalse(compilation.diagnostics().contains("VERNE-SYN-001")),
                () -> assertFalse(compilation.diagnostics().contains("VERNE-TYP-001")),
                () -> assertFalse(Files.exists(compilation.sourcesGenerees()
                        .resolve("io/vidocq/verne/genere/Zz.java"))));
    }

    @Test void refuseAvantDecodageDunPointDeCodeTronqueParLaBorne() throws Exception {
        var gabarits = gabarits(1);
        gabarits.put("z.verne", "🚀");
        var compilation = compiler(repertoire, gabarits);
        verifierDepassement(compilation, "z.verne", 8);
        assertFalse(compilation.diagnostics().contains("VERNE-SYN-003"), compilation.diagnostics());
    }

    @Test void compteChaqueAliasDeFichierDansLeBudget() throws Exception {
        Path racine = Files.createDirectory(repertoire.resolve("gabarits"));
        Files.writeString(racine.resolve("contenu.txt"), sourceDeTaille(MEBIOCTET));
        for (int index = 0; index < 9; index++) {
            Files.createSymbolicLink(racine.resolve("gabarit" + index + ".verne"), Path.of("contenu.txt"));
        }
        verifierDepassement(compiler(repertoire, Map.of()), "gabarit8.verne", 8);
    }

    @Test void compteLesOctetsDuneSourceUtf8Invalide() throws Exception {
        Path racine = Files.createDirectory(repertoire.resolve("gabarits"));
        Files.write(racine.resolve("a.verne"), new byte[] {(byte) 0xc3});
        var gabarits = gabarits(1);
        gabarits.put("z.verne", "x");
        var compilation = compiler(repertoire, gabarits);
        verifierDepassement(compilation, "z.verne", 8);
        assertTrue(compilation.diagnostics().contains("VERNE-SYN-003"), compilation.diagnostics());
    }

    @Test void compteLeSondageDunDepassementIndividuelEtConserveSonDiagnostic() throws Exception {
        var gabarits = new HashMap<String, String>();
        gabarits.put("a.verne", sourceDeTaille(MEBIOCTET + 1));
        for (int index = 0; index < 6; index++) {
            gabarits.put("gabarit" + index + ".verne", sourceDeTaille(MEBIOCTET));
        }
        gabarits.put("y.verne", sourceDeTaille(MEBIOCTET - 1));
        gabarits.put("z.verne", "x");
        var compilation = compiler(repertoire, gabarits);
        verifierDepassement(compilation, "z.verne", 7);
        assertTrue(compilation.diagnostics().contains("VERNE-LIM-001"), compilation.diagnostics());
    }

    @Test void donnePrioriteAuBudgetCumuleSiLesDeuxBornesSontFranchiesEnsemble() throws Exception {
        var gabarits = gabarits(0);
        gabarits.put("gabarit7.verne", sourceDeTaille(MEBIOCTET + 1));
        var compilation = compiler(repertoire, gabarits);
        verifierDepassement(compilation, "gabarit7.verne", 7);
        assertFalse(compilation.diagnostics().contains("VERNE-LIM-001"), compilation.diagnostics());
    }

    @Test void isoleLeBudgetDeDeuxCompilationsConcurrentes() throws Exception {
        var gabarits = gabarits(0);
        try (var executeur = Executors.newVirtualThreadPerTaskExecutor()) {
            var premiere = executeur.submit(() -> compiler(repertoire.resolve("premiere"), gabarits));
            var seconde = executeur.submit(() -> compiler(repertoire.resolve("seconde"), gabarits));
            verifierAcceptation(premiere.get(), 8);
            verifierAcceptation(seconde.get(), 8);
        }
    }

    private Map<String, String> gabarits(int retrait) {
        var gabarits = new HashMap<String, String>();
        String plein = sourceDeTaille(MEBIOCTET);
        for (int index = 0; index < 7; index++) gabarits.put("gabarit" + index + ".verne", plein);
        gabarits.put("gabarit7.verne", sourceDeTaille(MEBIOCTET - retrait));
        return gabarits;
    }

    private String sourceDeTaille(int taille) {
        String source = "{!" + "é".repeat((taille - 4) / 2) + "x".repeat((taille - 4) % 2) + "!}";
        assertEquals(taille, source.getBytes(StandardCharsets.UTF_8).length);
        return source;
    }

    private BancCompilation.Compilation compiler(Path racine, Map<String, String> gabarits) throws Exception {
        return BancCompilation.compiler(racine, gabarits,
                BancCompilation.application("io.vidocq.verne.genere.Gabarit0.rendre()"), true);
    }

    private void verifierAcceptation(BancCompilation.Compilation compilation, long nombre) throws Exception {
        assertTrue(compilation.reussie(), compilation.diagnostics());
        assertEquals(nombre, compterSources(compilation));
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals("", execution.sortie()));
    }

    private void verifierDepassement(BancCompilation.Compilation compilation, String identifiant,
                                    long nombre) throws Exception {
        assertAll(() -> assertFalse(compilation.reussie()),
                () -> assertTrue(compilation.diagnostics().contains("VERNE-LIM-006"), compilation.diagnostics()),
                () -> assertTrue(compilation.diagnostics().contains(identifiant + ":1:1"), compilation.diagnostics()),
                () -> assertFalse(compilation.diagnostics().contains(repertoire.toString())),
                () -> assertFalse(compilation.diagnostics().contains("🚀")),
                () -> assertEquals(nombre, compterSources(compilation)));
    }

    private long compterSources(BancCompilation.Compilation compilation) throws Exception {
        try (var sources = Files.walk(compilation.sourcesGenerees())) {
            return sources.filter(chemin -> chemin.toString().endsWith(".java")).count();
        }
    }
}
