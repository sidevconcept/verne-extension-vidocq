package io.vidocq.verne.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Borne d'inventaire vérifiée par compilation réelle et comptage des sources produites. */
@Timeout(30)
class NombreGabaritsTest {
    @TempDir Path repertoire;

    @Test void accepteUneRacineSansGabarit() throws Exception {
        accepter(0);
    }

    @Test void accepteUnGabaritDeMoinsQueLaLimite() throws Exception {
        accepter(1_023);
    }

    @Test void accepteExactementMilleVingtQuatreGabarits() throws Exception {
        accepter(1_024);
    }

    @Test void refuseLeDepassementAvantAnalyseEtGeneration() throws Exception {
        var gabarits = gabarits(1_025, "{inconnu}");
        gabarits.put("gabarit0.verne", "texte");
        var compilation = BancCompilation.compiler(repertoire, gabarits,
                BancCompilation.application("\"témoin\""), true);
        assertAll(() -> assertFalse(compilation.reussie()),
                () -> assertTrue(compilation.diagnostics().contains("VERNE-LIM-002"), compilation.diagnostics()),
                () -> assertFalse(compilation.diagnostics().contains("VERNE-TYP-001"), compilation.diagnostics()),
                () -> assertFalse(compilation.diagnostics().contains(repertoire.toString())),
                () -> assertEquals(0, compterSources(compilation)));
    }

    private void accepter(int nombre) throws Exception {
        var compilation = BancCompilation.compiler(repertoire, gabarits(nombre, "texte"),
                BancCompilation.application("\"témoin\""), true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        assertEquals(nombre, compterSources(compilation), "Chaque gabarit doit être généré");
    }

    private Map<String, String> gabarits(int nombre, String contenu) {
        var gabarits = new HashMap<String, String>();
        for (int index = 0; index < nombre; index++) gabarits.put("gabarit" + index + ".verne", contenu);
        return gabarits;
    }

    private long compterSources(BancCompilation.Compilation compilation) throws Exception {
        try (var fichiers = Files.walk(compilation.sourcesGenerees())) {
            return fichiers.filter(chemin -> chemin.toString().endsWith(".java")).count();
        }
    }
}
