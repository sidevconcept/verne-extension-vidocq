package io.vidocq.verne.verification;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Témoins verts exigés avant d'attribuer un échec à une capacité absente de Verne. */
@Timeout(30)
class OutillageCompilationTest {
    @TempDir Path repertoire;

    @Test
    void compileEtExecuteUnModuleJavaSansProcesseur() throws Exception {
        var compilation = BancCompilation.compiler(repertoire, Map.of(),
                BancCompilation.application("\"été 🚀\\r\\n\""), false);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals("été 🚀\r\n", execution.sortie()),
                () -> assertEquals("", execution.erreurs()));
    }

    @Test
    void distingueUneErreurJavaDuSuccesDeCompilation() throws Exception {
        var compilation = BancCompilation.compiler(repertoire, Map.of(),
                Map.of("exemple/Invalide.java", "package exemple; public class Invalide {"), false);
        assertAll(() -> assertFalse(compilation.reussie()),
                () -> assertTrue(compilation.diagnostics().contains("compiler.err.premature.eof"),
                        compilation.diagnostics()));
    }

    @Test
    void supporteUnCheminAvecEspacesEtAccents() throws Exception {
        var compilation = BancCompilation.compiler(repertoire.resolve("répertoire avec espaces"), Map.of(),
                BancCompilation.application("\"chemin correct\""), false);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals("chemin correct", execution.sortie()));
    }

    @Test
    void conserveLeCodeDeSortieEtLesErreursDuProcessus() throws Exception {
        var compilation = BancCompilation.compiler(repertoire, Map.of(), Map.of(
                "exemple/Application.java", """
                        package exemple;
                        public class Application {
                            public static void main(String[] arguments) {
                                System.err.print("échec témoin");
                                System.exit(7);
                            }
                        }
                        """), false);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(7, execution.codeSortie()),
                () -> assertEquals("", execution.sortie()),
                () -> assertEquals("échec témoin", execution.erreurs()));
    }
}
