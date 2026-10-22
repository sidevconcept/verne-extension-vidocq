package io.vidocq.verne.verification;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Charge le vrai processeur par les deux mécanismes standard de javac, sans le nommer. */
@Timeout(30)
final class DecouverteProcesseurTest {
    @TempDir Path repertoire;

    @Test void decouvreDepuisUnRepertoireSurLeCheminDesProcesseurs() throws Exception {
        verifierRendu("-processorpath", cheminProcesseur(), repertoire.resolve("application"));
    }

    @Test void decouvreDepuisUnRepertoireSurLeCheminDesModulesProcesseurs() throws Exception {
        verifierRendu("--processor-module-path", cheminProcesseur(), repertoire.resolve("application"));
    }

    @Test void decouvreDepuisUneArchiveSurLeCheminDesProcesseurs() throws Exception {
        verifierRendu("-processorpath", archiverProcesseur(), repertoire.resolve("application"));
    }

    @Test void decouvreDepuisUneArchiveSurLeCheminDesModulesProcesseurs() throws Exception {
        verifierRendu("--processor-module-path", archiverProcesseur(), repertoire.resolve("application"));
    }

    @Test void verifieAussiUnGabaritInutiliseDepuisLeCheminDesProcesseurs() throws Exception {
        verifierRefus("-processorpath");
    }

    @Test void verifieAussiUnGabaritInutiliseDepuisLeCheminDesModulesProcesseurs() throws Exception {
        verifierRefus("--processor-module-path");
    }

    @Test void laCommandeJavacActiveLeTraitementParLesCheminsExplicites() throws Exception {
        Path archive = archiverProcesseur();
        for (String option : List.of("-processorpath", "--processor-module-path")) {
            Path racine = Files.createDirectories(repertoire.resolve(option.substring(1)));
            Path gabarits = Files.createDirectories(racine.resolve("gabarits"));
            Files.writeString(gabarits.resolve("inutilise.verne"), "{nom");
            Path temoin = Files.writeString(racine.resolve("Temoin.java"), "final class Temoin {}");
            Path journal = racine.resolve("javac.log");
            String executable = System.getProperty("os.name").startsWith("Windows") ? "javac.exe" : "javac";
            Process processus = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-J-Dstderr.encoding=UTF-8", "-J-Dstdout.encoding=UTF-8",
                    "--release", "25", "-encoding", "UTF-8", option, archive.toString(),
                    "-Averne.racine.gabarits=" + gabarits, "-Averne.profil.sortie=texte",
                    "-Averne.paquet=" + BancCompilation.PAQUET,
                    "-d", Files.createDirectories(racine.resolve("classes")).toString(), temoin.toString())
                    .redirectErrorStream(true).redirectOutput(journal.toFile()).start();
            try {
                assertTrue(processus.waitFor(15, TimeUnit.SECONDS), "La commande javac doit terminer.");
                String diagnostics = Files.readString(journal);
                assertEquals(1, processus.exitValue(), diagnostics);
                assertTrue(diagnostics.contains("VERNE-SYN-001"), diagnostics);
                assertTrue(diagnostics.contains("inutilise.verne:1:1"), diagnostics);
            } finally {
                if (processus.isAlive()) {
                    processus.destroyForcibly();
                    processus.waitFor(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test void desactiverLeTraitementEmpecheLaDecouverteSurLesDeuxChemins() throws Exception {
        Path archive = archiverProcesseur();
        for (String option : List.of("-processorpath", "--processor-module-path")) {
            Path racine = repertoire.resolve(option.substring(1));
            verifierAbsenceDeTraitement(racine, List.of("-proc:none", option, archive.toString()));
        }
    }

    @Test void leSimpleCheminDesClassesNactivePasLeTraitement() throws Exception {
        verifierAbsenceDeTraitement(repertoire.resolve("application"),
                List.of("--class-path", archiverProcesseur().toString()));
    }

    private void verifierRendu(String option, Path processeur, Path racine) throws Exception {
        var options = List.of("-proc:full", option, processeur.toString());
        var sources = new java.util.HashMap<>(BancCompilation.application(
                "exemple.gabarits.Bonjour.rendre(new Personne(\"Léa 🚀\"))"));
        sources.put("exemple/Personne.java", "package exemple; public record Personne(String nom) {}");
        var compilation = BancCompilation.compiler(racine,
                Map.of("bonjour.verne", "{@exemple.Personne personne}Bonjour {personne.nom}\r\n"),
                sources, true, racine.resolve("gabarits"), options);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        assertTrue(Files.exists(compilation.sourcesGenerees().resolve("exemple/gabarits/Bonjour.java")));
        var execution = BancCompilation.executer(compilation);
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        assertEquals("Bonjour Léa 🚀\r\n", execution.sortie());
    }

    private void verifierRefus(String option) throws Exception {
        var compilation = BancCompilation.compiler(repertoire.resolve("application"),
                Map.of("inutilise.verne", "Ligne\r\n{nom"), BancCompilation.application("\"Témoin\""),
                true, repertoire.resolve("application/gabarits"),
                List.of("-proc:full", option, archiverProcesseur().toString()));
        assertFalse(compilation.reussie(), "Un processeur absent ne valide pas le gabarit inutilisé.");
        assertTrue(compilation.diagnostics().contains("VERNE-SYN-001"), compilation.diagnostics());
        assertTrue(compilation.diagnostics().contains("inutilise.verne:2:1"), compilation.diagnostics());
        assertFalse(Files.exists(compilation.sourcesGenerees().resolve("exemple/gabarits/Inutilise.java")));
    }

    private void verifierAbsenceDeTraitement(Path racine, List<String> options) throws Exception {
        var compilation = BancCompilation.compiler(racine, Map.of("inutilise.verne", "{nom"),
                BancCompilation.application("\"Témoin\""), false, racine.resolve("gabarits"), options);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        assertFalse(compilation.diagnostics().contains("VERNE-"), compilation.diagnostics());
        try (var sources = Files.walk(compilation.sourcesGenerees())) {
            assertEquals(0, sources.filter(Files::isRegularFile).count());
        }
        var execution = BancCompilation.executer(compilation);
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        assertEquals("Témoin", execution.sortie());
    }

    private static Path cheminProcesseur() {
        Path projet = Path.of(System.getProperty("verne.racine.projet"));
        return Path.of(System.getProperty("verne.chemin.processeur",
                projet.resolve("verne-compilation/target/classes").toString()));
    }

    /** Archive les vraies classes et ressources compilées, y compris le descripteur modulaire. */
    private Path archiverProcesseur() throws IOException {
        Path classes = cheminProcesseur();
        if (Files.isRegularFile(classes)) return classes;
        Path archive = repertoire.resolve("processeur compilé.jar");
        try (var sortie = new JarOutputStream(Files.newOutputStream(archive));
             var parcours = Files.walk(classes)) {
            for (Path fichier : parcours.filter(Files::isRegularFile).sorted().toList()) {
                sortie.putNextEntry(new JarEntry(classes.relativize(fichier).toString().replace('\\', '/')));
                Files.copy(fichier, sortie);
                sortie.closeEntry();
            }
        }
        return archive;
    }
}
