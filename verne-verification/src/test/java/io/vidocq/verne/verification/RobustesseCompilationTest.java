package io.vidocq.verne.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import javax.lang.model.SourceVersion;
import io.vidocq.verne.compilation.ProcesseurVerne;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Cas relevés par la revue de code : annotations de type, parcours, normalisation et version source. */
@Timeout(30)
class RobustesseCompilationTest {
    @TempDir Path repertoire;

    @Test void accepteDesComposantsPortantDesAnnotationsDeType() throws Exception {
        var sources = new HashMap<>(BancCompilation.application(
                "exemple.gabarits.Bonjour.rendre(new Personne(\"Léa\", 30))"));
        sources.put("exemple/Nn.java", """
                package exemple;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.CLASS) public @interface Nn {}
                """);
        sources.put("exemple/Personne.java", "package exemple; public record Personne(@Nn String nom, @Nn int age) {}");
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("bonjour.verne", "{@exemple.Personne personne}{personne.nom} {personne.age}"), sources, true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        assertEquals("Léa 30", execution.sortie());
    }

    @Test void refuseUneChaineAnnoteeNulleAuRendu() throws Exception {
        var sources = new HashMap<String, String>();
        sources.put("exemple/Application.java", """
                package exemple;
                public final class Application {
                    public static void main(String[] arguments) {
                        try { exemple.gabarits.Bonjour.rendre(new Personne(null)); System.out.print("nullité ignorée"); }
                        catch (NullPointerException exception) { System.out.print(exception.getMessage()); }
                    }
                }
                """);
        sources.put("exemple/Nn.java", """
                package exemple;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE_USE) public @interface Nn {}
                """);
        sources.put("exemple/Personne.java", "package exemple; public record Personne(@Nn String nom) {}");
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("bonjour.verne", "{@exemple.Personne personne}{personne.nom}"), sources, true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        assertTrue(execution.sortie().contains("VERNE-REN-001"), execution.sortie());
    }

    /** Un sous-répertoire illisible ne doit ni interrompre javac par une exception ni exposer de chemin. */
    @Test void signaleUnSousRepertoireIllisibleSansExceptionNiChemin() throws Exception {
        assumeTrue(Files.getFileStore(repertoire).supportsFileAttributeView("posix"),
                "Les permissions POSIX sont nécessaires pour rendre un répertoire illisible.");
        Path prive = Files.createDirectories(repertoire.resolve("gabarits/prive"));
        Files.writeString(prive.resolve("cache.verne"), "texte");
        Files.setPosixFilePermissions(prive, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(prive), "Le compte de test contourne les permissions POSIX.");
            var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne", "Bonjour"),
                    BancCompilation.application("\"témoin\""), true);
            assertFalse(compilation.reussie(), compilation.diagnostics());
            assertEquals(1, compilation.diagnostics().lines().filter(ligne -> ligne.contains("VERNE-IO-001")).count(),
                    compilation.diagnostics());
            assertFalse(compilation.diagnostics().contains("Exception"), compilation.diagnostics());
            assertFalse(compilation.diagnostics().contains(repertoire.toString()), compilation.diagnostics());
            assertFalse(compilation.diagnostics().contains(repertoire.toRealPath().toString()), compilation.diagnostics());
            assertFalse(Files.exists(compilation.sourcesGenerees().resolve("exemple/gabarits/Bonjour.java")));
        } finally {
            Files.setPosixFilePermissions(prive, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /** Un nom de fichier NFD produit la même classe NFC que celle écrite dans les sources Java. */
    @Test void genereUnNomDeClasseNfcPourUnFichierNfd() throws Exception {
        String nfd = Normalizer.normalize("été", Normalizer.Form.NFD);
        String classe = Normalizer.normalize("Été", Normalizer.Form.NFC);
        var compilation = BancCompilation.compiler(repertoire, Map.of(nfd + ".verne", "Bonjour d'" + nfd),
                BancCompilation.application("exemple.gabarits." + classe + ".rendre()"), true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        assertTrue(Files.exists(compilation.sourcesGenerees().resolve("exemple/gabarits/" + classe + ".java")));
        var execution = BancCompilation.executer(compilation);
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        assertEquals("Bonjour d'" + nfd, execution.sortie(), "Le texte du document n'est pas normalisé.");
    }

    @Test void declareLaDerniereVersionSourceDuCompilateur() {
        assertEquals(SourceVersion.latestSupported(), new ProcesseurVerne().getSupportedSourceVersion());
    }
}
