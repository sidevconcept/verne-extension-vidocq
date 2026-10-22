package io.vidocq.verne.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Premiers contrats de construction : vrais appels au compilateur, sans moteur simulé. */
@Timeout(30)
class CompilationGabaritsTest {
    @TempDir Path repertoire;

    @Test
    void genereEtCompileUnGabaritSansAnnotationApplicative() throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("bonjour.verne", "Bonjour"),
                Map.of("exemple/Ancrage.java", "package exemple; public class Ancrage {}"), true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        assertAll(() -> assertTrue(Files.isRegularFile(compilation.sourcesGenerees()
                        .resolve("exemple/gabarits/Bonjour.java")), "Source du gabarit absente"),
                () -> assertTrue(Files.isRegularFile(compilation.classes()
                        .resolve("exemple/gabarits/Bonjour.class")), "Classe du gabarit absente"));
    }

    @Test
    void resoutUnRecordSourcePendantLaMemeCompilation() throws Exception {
        var sources = new java.util.HashMap<>(BancCompilation.application(
                "exemple.gabarits.Bonjour.rendre(new Personne(\"Léa\"))"));
        sources.put("exemple/Personne.java", "package exemple; public record Personne(String nom) {}");
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne",
                "{@exemple.Personne personne}Bonjour {personne.nom}"), sources, true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals("Bonjour Léa", execution.sortie()));
    }

    @Test
    void refuseUneInterpolationNonFermeeDansUnGabaritInutilise() throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("bonjour.verne", "Bonjour", "inutilise.verne", "ligne\n{nom"),
                BancCompilation.application("\"témoin\""), true);
        assertFalse(compilation.reussie(), "Le gabarit invalide doit faire échouer la construction");
        assertAll(() -> assertTrue(compilation.diagnostics().contains("VERNE-SYN-001"),
                        compilation.diagnostics()),
                () -> assertTrue(compilation.diagnostics().contains("inutilise.verne:2:1"),
                        compilation.diagnostics()));
    }

    @Test
    void refuseUneProprieteInconnueAvecPositionSource() throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("bonjour.verne", "{@exemple.Personne personne}\n{personne.inconnue}"),
                Map.of("exemple/Personne.java", "package exemple; public record Personne(String nom) {}"), true);
        assertFalse(compilation.reussie());
        assertAll(() -> assertTrue(compilation.diagnostics().contains("VERNE-TYP-001"),
                        compilation.diagnostics()),
                () -> assertTrue(compilation.diagnostics().contains("bonjour.verne:2:1"),
                        compilation.diagnostics()));
    }

    @Test
    void refuseUnParametreDeclareDeuxFois() throws Exception {
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne",
                "{@java.lang.String nom}{@java.lang.String nom}{nom}"),
                BancCompilation.application("\"témoin\""), true);
        assertFalse(compilation.reussie());
        assertTrue(compilation.diagnostics().contains("VERNE-TYP-002"), compilation.diagnostics());
    }

    @Test
    void refuseUnAccesAUneProprietePrivee() throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("bonjour.verne", "{@exemple.Personne personne}{personne.secret}"),
                Map.of("exemple/Personne.java",
                        "package exemple; public class Personne { private String secret; }"), true);
        assertFalse(compilation.reussie());
        assertTrue(compilation.diagnostics().contains("VERNE-TYP-001"), compilation.diagnostics());
    }

    @Test
    void produitLaMemeSourceDansDeuxRepertoiresDifferents() throws Exception {
        var gabarits = Map.of("bonjour.verne", "{@java.lang.String nom}Bonjour {nom}");
        var sources = BancCompilation.application("exemple.gabarits.Bonjour.rendre(\"Léa\")");
        var premiere = BancCompilation.compiler(repertoire.resolve("premier"), gabarits, sources, true);
        var seconde = BancCompilation.compiler(repertoire.resolve("second"), gabarits, sources, true);
        assertTrue(premiere.reussie(), premiere.diagnostics());
        assertTrue(seconde.reussie(), seconde.diagnostics());
        Path relatif = Path.of("exemple/gabarits/Bonjour.java");
        String source = Files.readString(premiere.sourcesGenerees().resolve(relatif));
        assertAll(() -> assertEquals(source, Files.readString(seconde.sourcesGenerees().resolve(relatif))),
                () -> assertFalse(source.contains(repertoire.toString()), "Chemin absolu dans le code généré"));
    }
}
