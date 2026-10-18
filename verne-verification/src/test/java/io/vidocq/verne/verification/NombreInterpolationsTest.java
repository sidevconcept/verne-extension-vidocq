package io.vidocq.verne.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Borne par fichier : rendu exact, position Unicode et absence de génération au dépassement. */
@Timeout(30)
class NombreInterpolationsTest {
    @TempDir Path repertoire;

    @Test void accepteUneInterpolationDeMoinsQueLaLimite() throws Exception {
        rendre(gabarit(1_023), "é".repeat(1_023));
    }

    @Test void accepteExactementMilleVingtQuatreInterpolations() throws Exception {
        rendre(gabarit(1_024), "é".repeat(1_024));
    }

    @Test void refuseLeDepassementAvecPositionApresCrLfEtEmoji() throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("bonjour.verne", gabarit(1_024) + "\r\n🚀{nom}"),
                BancCompilation.application("\"témoin\""), true);
        assertAll(() -> assertFalse(compilation.reussie()),
                () -> assertTrue(compilation.diagnostics().contains("VERNE-LIM-003"), compilation.diagnostics()),
                () -> assertTrue(compilation.diagnostics().contains("bonjour.verne:2:2"), compilation.diagnostics()),
                () -> assertFalse(compilation.diagnostics().contains(repertoire.toString())),
                () -> assertFalse(compilation.diagnostics().contains("🚀")),
                () -> assertFalse(Files.exists(compilation.sourcesGenerees()
                        .resolve("io/vidocq/verne/genere/Bonjour.java"))));
    }

    @Test void neComptePasLesCommentairesDeclarationsEtDelimiteursEchappes() throws Exception {
        String prefixe = "{! ignoré !}".repeat(1_025) + "\\{nom}".repeat(1_025);
        rendre(prefixe + gabarit(1_024), "{nom}".repeat(1_025) + "é".repeat(1_024));
    }

    @Test void appliqueLaBorneSeparementAChaqueGabarit() throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("bonjour.verne", gabarit(1_024), "salut.verne", gabarit(1_024)),
                BancCompilation.application("io.vidocq.verne.genere.Bonjour.rendre(\"é\")"
                        + " + io.vidocq.verne.genere.Salut.rendre(\"é\")"), true);
        verifierRendu(compilation, "é".repeat(2_048));
    }

    private String gabarit(int nombre) {
        return "{@java.lang.String nom}" + "{nom}".repeat(nombre);
    }

    private void rendre(String texte, String attendu) throws Exception {
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne", texte),
                BancCompilation.application("io.vidocq.verne.genere.Bonjour.rendre(\"é\")"), true);
        verifierRendu(compilation, attendu);
    }

    private void verifierRendu(BancCompilation.Compilation compilation, String attendu) throws Exception {
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals(attendu, execution.sortie()));
    }
}
