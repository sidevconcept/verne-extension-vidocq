package io.vidocq.verne.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Vérifie les frontières réelles avec des liens du système de fichiers, sans simulation. */
@Timeout(30)
class RacinesEtLiensGabaritsTest {
    @TempDir Path repertoire;

    @Test void refuseUneRacineQuiEstUnFichier() throws Exception {
        Path fichier = Files.writeString(repertoire.resolve("racine.verne"), "texte");
        var compilation = BancCompilation.compiler(repertoire, Map.of(),
                BancCompilation.application("\"témoin\""), true, fichier);
        refuser(compilation, "VERNE-CFG-001");
    }

    @Test void refuseUneRacineIntrouvable() throws Exception {
        var compilation = BancCompilation.compiler(repertoire, Map.of(),
                BancCompilation.application("\"témoin\""), true, repertoire.resolve("absente"));
        refuser(compilation, "VERNE-IO-001");
    }

    @Test void accepteUneRacineSymboliqueEtSonContenu() throws Exception {
        Path cible = Files.createDirectory(repertoire.resolve("cible"));
        Files.createSymbolicLink(repertoire.resolve("gabarits"), cible);
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne", "été 🚀"),
                BancCompilation.application("exemple.gabarits.Bonjour.rendre()"), true);
        rendre(compilation, "été 🚀");
    }

    @Test void accepteUnLienDeFichierInterneSousSonNomLogique() throws Exception {
        Path gabarits = Files.createDirectory(repertoire.resolve("gabarits"));
        Files.writeString(gabarits.resolve("contenu.txt"), "été 🚀");
        Files.createSymbolicLink(gabarits.resolve("bonjour.verne"), Path.of("contenu.txt"));
        var compilation = BancCompilation.compiler(repertoire, Map.of(),
                BancCompilation.application("exemple.gabarits.Bonjour.rendre()"), true);
        rendre(compilation, "été 🚀");
    }

    @Test void refuseUnLienDeFichierExterneAvantAnalyse() throws Exception {
        Path gabarits = Files.createDirectory(repertoire.resolve("gabarits"));
        Path cible = Files.writeString(repertoire.resolve("externe.txt"), "{inconnu}");
        Files.createSymbolicLink(gabarits.resolve("bonjour.verne"), cible);
        var compilation = compiler();
        refuser(compilation, "VERNE-SEC-001");
        assertAll(() -> assertTrue(compilation.diagnostics().contains("bonjour.verne:1:1")),
                () -> assertFalse(compilation.diagnostics().contains("VERNE-TYP-001")),
                () -> assertFalse(compilation.diagnostics().contains("{inconnu}")),
                () -> assertFalse(Files.exists(compilation.sourcesGenerees()
                        .resolve("exemple/gabarits/Bonjour.java"))));
    }

    @Test void refuseUnLienDeFichierCasse() throws Exception {
        Path gabarits = Files.createDirectory(repertoire.resolve("gabarits"));
        Files.createSymbolicLink(gabarits.resolve("bonjour.verne"), gabarits.resolve("absent.txt"));
        var compilation = compiler();
        refuser(compilation, "VERNE-IO-001");
        assertTrue(compilation.diagnostics().contains("bonjour.verne:1:1"), compilation.diagnostics());
    }

    @Test void verifieLesAutresRessourcesApresUnLienCasse() throws Exception {
        Path gabarits = Files.createDirectory(repertoire.resolve("gabarits"));
        Files.createSymbolicLink(gabarits.resolve("a.verne"), gabarits.resolve("absent.txt"));
        var compilation = BancCompilation.compiler(repertoire, Map.of("z.verne", "{inconnu}"),
                BancCompilation.application("\"témoin\""), true);
        refuser(compilation, "VERNE-IO-001");
        assertAll(() -> assertTrue(compilation.diagnostics().contains("a.verne:1:1")),
                () -> assertTrue(compilation.diagnostics().contains("VERNE-TYP-001")),
                () -> assertTrue(compilation.diagnostics().contains("z.verne:1:1")));
    }

    @Test void neParcourtPasUnRepertoireSymboliqueExterne() throws Exception {
        Path gabarits = Files.createDirectory(repertoire.resolve("gabarits"));
        Path cible = Files.createDirectory(repertoire.resolve("externe"));
        Files.writeString(cible.resolve("invalide.verne"), "{inconnu}");
        Files.createSymbolicLink(gabarits.resolve("repertoire.verne"), cible);
        var compilation = compiler();
        assertTrue(compilation.reussie(), compilation.diagnostics());
        try (var sources = Files.walk(compilation.sourcesGenerees())) {
            assertFalse(sources.anyMatch(chemin -> chemin.toString().endsWith(".java")));
        }
    }

    @Test void neParcourtPasUnCycleDeRepertoiresSymboliques() throws Exception {
        Path gabarits = Files.createDirectory(repertoire.resolve("gabarits"));
        Files.createSymbolicLink(gabarits.resolve("cycle.verne"), gabarits);
        var compilation = compiler();
        assertTrue(compilation.reussie(), compilation.diagnostics());
    }

    private BancCompilation.Compilation compiler() throws Exception {
        return BancCompilation.compiler(repertoire, Map.of(), BancCompilation.application("\"témoin\""), true);
    }

    private void refuser(BancCompilation.Compilation compilation, String code) {
        assertAll(() -> assertFalse(compilation.reussie()),
                () -> assertTrue(compilation.diagnostics().contains(code), compilation.diagnostics()),
                () -> assertFalse(compilation.diagnostics().contains(repertoire.toString()),
                        "Le diagnostic ne doit pas exposer un chemin du poste"));
    }

    private void rendre(BancCompilation.Compilation compilation, String attendu) throws Exception {
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals(attendu, execution.sortie()));
    }
}
