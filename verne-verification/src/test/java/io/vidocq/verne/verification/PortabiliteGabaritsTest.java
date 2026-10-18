package io.vidocq.verne.verification;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Noms placés dans des répertoires distincts pour exercer aussi les volumes insensibles à la casse. */
@Timeout(30)
class PortabiliteGabaritsTest {
    @TempDir Path repertoire;

    @Test void refuseDeuxNomsNeDifferantQueParLaCasse() throws Exception {
        refuserCollision("bonjour", "BONJOUR");
    }

    @Test void refuseDeuxNomsCanoniquementEquivalents() throws Exception {
        refuserCollision("café", "cafe\u0301");
    }

    @Test void refuseUneCollisionCombinantCasseEtNormalisation() throws Exception {
        refuserCollision("CAFÉ", "cafe\u0301");
    }

    @Test void conserveDeuxNomsDistinctsEtLeurRenduUnicode() throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("premier/café.verne", "café", "second/cafes.verne", "cafe\u0301s"),
                BancCompilation.application("io.vidocq.verne.genere.Café.rendre() + \" / \""
                        + " + io.vidocq.verne.genere.Cafes.rendre()"), true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals("café / cafe\u0301s", execution.sortie()));
    }

    private void refuserCollision(String premier, String second) throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("premier/" + premier + ".verne", "un", "second/" + second + ".verne", "deux"),
                BancCompilation.application("\"témoin\""), true);
        assertAll(() -> assertFalse(compilation.reussie(), "La collision doit faire échouer la compilation"),
                () -> assertTrue(compilation.diagnostics().contains("VERNE-LIA-001"), compilation.diagnostics()),
                () -> assertTrue(compilation.diagnostics().contains("second/" + second + ".verne:1:1"),
                        compilation.diagnostics()),
                () -> assertFalse(compilation.diagnostics().contains(repertoire.toString()),
                        "Le diagnostic ne doit pas exposer le chemin du poste"));
    }
}
