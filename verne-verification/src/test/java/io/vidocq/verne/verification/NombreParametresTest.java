package io.vidocq.verne.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Limite de signature JVM : ordre, rendu modulaire et refus avant génération. */
@Timeout(30)
class NombreParametresTest {
    @TempDir Path repertoire;

    @Test void accepteDeuxCentCinquanteQuatreParametres() throws Exception {
        verifierRendu(254, "");
    }

    @Test void accepteExactementDeuxCentCinquanteCinqParametres() throws Exception {
        verifierRendu(255, "");
    }

    @Test void refuseLeDepassementAvecPositionSansResoudreLeType() throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("bonjour.verne", declarations(255) + "\r\n🚀{@inconnu.Secret confidentiel}"),
                BancCompilation.application("\"témoin\""), true);
        assertAll(() -> assertFalse(compilation.reussie()),
                () -> assertTrue(compilation.diagnostics().contains("VERNE-LIM-004"), compilation.diagnostics()),
                () -> assertTrue(compilation.diagnostics().contains("bonjour.verne:2:2"), compilation.diagnostics()),
                () -> assertFalse(compilation.diagnostics().contains("VERNE-TYP-003")),
                () -> assertFalse(compilation.diagnostics().contains("confidentiel")),
                () -> assertFalse(compilation.diagnostics().contains(repertoire.toString())),
                () -> assertFalse(Files.exists(compilation.sourcesGenerees()
                        .resolve("io/vidocq/verne/genere/Bonjour.java"))));
    }

    @Test void conserveLeDiagnosticDeDeclarationDupliqueeALaLimite() throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("bonjour.verne", declarations(255) + "{@java.lang.String nom0}"),
                BancCompilation.application("\"témoin\""), true);
        assertAll(() -> assertFalse(compilation.reussie()),
                () -> assertTrue(compilation.diagnostics().contains("VERNE-TYP-002"), compilation.diagnostics()),
                () -> assertFalse(compilation.diagnostics().contains("VERNE-LIM-004")));
    }

    @Test void neComptePasLesCommentairesEtDelimiteursEchappes() throws Exception {
        verifierRendu(255, "{! {@java.lang.String ignore} !}".repeat(256)
                + "\\{@java.lang.String ignore}");
    }

    @Test void separeLesParametresDesGabaritsEtPoursuitApresUnRefus() throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("a.verne", declarations(256), "bonjour.verne", declarations(255),
                        "salut.verne", declarations(255)),
                BancCompilation.application("\"témoin\""), true);
        assertAll(() -> assertFalse(compilation.reussie()),
                () -> assertTrue(compilation.diagnostics().contains("VERNE-LIM-004"), compilation.diagnostics()),
                () -> assertFalse(Files.exists(compilation.sourcesGenerees()
                        .resolve("io/vidocq/verne/genere/A.java"))),
                () -> assertTrue(Files.exists(compilation.sourcesGenerees()
                        .resolve("io/vidocq/verne/genere/Bonjour.java"))),
                () -> assertTrue(Files.exists(compilation.sourcesGenerees()
                        .resolve("io/vidocq/verne/genere/Salut.java"))));
    }

    private String declarations(int nombre) {
        return IntStream.range(0, nombre).mapToObj(index -> "{@java.lang.String nom" + index + "}")
                .collect(Collectors.joining());
    }

    private void verifierRendu(int nombre, String prefixe) throws Exception {
        String interpolations = IntStream.range(0, nombre).mapToObj(index -> "{nom" + index + "}")
                .collect(Collectors.joining("|"));
        String arguments = IntStream.range(0, nombre).mapToObj(index -> "\"é" + index + "🚀\"")
                .collect(Collectors.joining(", "));
        String attendu = IntStream.range(0, nombre).mapToObj(index -> "é" + index + "🚀")
                .collect(Collectors.joining("|"));
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("bonjour.verne", prefixe + declarations(nombre) + interpolations),
                BancCompilation.application("io.vidocq.verne.genere.Bonjour.rendre(" + arguments + ")"), true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals((prefixe.isEmpty() ? "" : "{@java.lang.String ignore}") + attendu,
                        execution.sortie()));
    }
}
