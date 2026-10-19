package io.vidocq.verne.verification;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Charge les deux fournisseurs comme modules nommés, sans ouvrir leurs paquets. */
@Timeout(30)
final class ChargementConjointModulesTest {
    private static final String VERNE = "io.vidocq.verne.compilation.ProcesseurVerne";
    private static final String VOISIN = "exemple.voisin.ProcesseurVoisin";
    @TempDir Path repertoire;

    @Test void chargeLesRepertoiresModulairesDansLesDeuxOrdres() throws Exception {
        Path voisin = preparerVoisin(true);
        for (var ordre : List.of(List.of(VERNE, VOISIN), List.of(VOISIN, VERNE))) {
            verifierRendu(compiler(repertoire.resolve(ordre.getFirst()), voisin, cheminVerne(), ordre,
                    "{@exemple.PersonneGeneree personne}Bonjour {personne.nom} 🚀\r\n", true, "modules"));
        }
    }

    @Test void decouvreLesDeuxFournisseursDansDesArchivesModulaires() throws Exception {
        Path voisin = archiver(preparerVoisin(true), repertoire.resolve("voisin compilé.jar"));
        Path verne = archiver(cheminVerne(), repertoire.resolve("verne compilé.jar"));
        verifierRendu(compiler(repertoire.resolve("application"), voisin, verne, List.of(),
                "{@exemple.PersonneGeneree personne}Bonjour {personne.nom} 🚀\r\n", true, "modules"));
    }

    @Test void conserveLeDiagnosticDeProprieteAvecLeChargementModulaire() throws Exception {
        var compilation = compiler(repertoire.resolve("application"), preparerVoisin(true), cheminVerne(), List.of(),
                "{@exemple.PersonneGeneree personne}\n{personne.inconnue}", false, "modules");
        assertFalse(compilation.reussie());
        assertTrue(compilation.diagnostics().contains("VERNE-TYP-001"), compilation.diagnostics());
        assertTrue(compilation.diagnostics().contains("bonjour.verne:2:1"), compilation.diagnostics());
        assertFalse(Files.exists(compilation.sourcesGenerees().resolve("io/vidocq/verne/genere/Bonjour.java")));
    }

    @Test void leServiceClassiqueNeRemplacePasLaDeclarationDuModule() throws Exception {
        Path voisin = preparerVoisin(false);
        assertTrue(Files.exists(voisin.resolve("META-INF/services/javax.annotation.processing.Processor")));
        var compilation = compiler(repertoire.resolve("application"), voisin, cheminVerne(), List.of(),
                "{@exemple.PersonneGeneree personne}{personne.nom}", false, "modules");
        assertFalse(compilation.reussie());
        assertTrue(compilation.diagnostics().contains("VERNE-TYP-003"), compilation.diagnostics());
        assertTrue(compilation.diagnostics().contains("bonjour.verne:1:1"), compilation.diagnostics());
        assertFalse(compilation.diagnostics().contains("VOISIN-CFG"), compilation.diagnostics());
        assertFalse(Files.exists(compilation.sourcesGenerees().resolve("exemple/PersonneGeneree.java")));
    }

    @Test void leVoisinRefuseUnModeIncompatibleAvecSonModuleNomme() throws Exception {
        var compilation = compiler(repertoire.resolve("application"), preparerVoisin(true), cheminVerne(), List.of(),
                "{@exemple.PersonneGeneree personne}{personne.nom}", false, "classique");
        assertFalse(compilation.reussie());
        assertTrue(compilation.diagnostics().contains("VOISIN-CFG-001"), compilation.diagnostics());
        assertFalse(Files.exists(compilation.sourcesGenerees().resolve("exemple/PersonneGeneree.java")));
    }

    private BancCompilation.Compilation compiler(Path racine, Path voisin, Path verne, List<String> ordre,
                                                 String gabarit, boolean utiliseRendu, String mode) throws IOException {
        var sources = new HashMap<>(BancCompilation.application(utiliseRendu
                ? "io.vidocq.verne.genere.Bonjour.rendre(new PersonneGeneree(\"Léa\"))" : "\"Témoin\""));
        sources.computeIfPresent("exemple/Application.java", (nom, contenu) ->
                contenu.replace("public final class Application", "@Marque public final class Application"));
        sources.put("exemple/Marque.java", "package exemple; public @interface Marque {}");
        var options = new ArrayList<>(List.of("-proc:full", "--processor-module-path",
                verne + File.pathSeparator + voisin, "-Avoisin.chargement=" + mode));
        if (!ordre.isEmpty()) options.addAll(List.of("-processor", String.join(",", ordre)));
        return BancCompilation.compiler(racine, Map.of("bonjour.verne", gabarit), sources, true,
                racine.resolve("gabarits"), options);
    }

    private static void verifierRendu(BancCompilation.Compilation compilation) throws Exception {
        assertTrue(compilation.reussie(), compilation.diagnostics());
        assertTrue(Files.exists(compilation.sourcesGenerees().resolve("exemple/PersonneGeneree.java")));
        var execution = BancCompilation.executer(compilation);
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        assertEquals("Bonjour Léa 🚀\r\n", execution.sortie());
    }

    /** Compile les vraies sources de l'exemple ; le témoin négatif retire seulement le fournisseur modulaire. */
    private Path preparerVoisin(boolean declare) throws IOException {
        Path exemple = Path.of(System.getProperty("verne.racine.projet"), "exemples/processeur-voisin");
        Path java = exemple.resolve("src/main/java");
        var sources = new HashMap<String, String>();
        try (var parcours = Files.walk(java)) {
            for (Path fichier : parcours.filter(chemin -> chemin.toString().endsWith(".java")).toList()) {
                sources.put(java.relativize(fichier).toString(), Files.readString(fichier));
            }
        }
        if (!declare) sources.put("module-info.java", "module exemple.verne.voisin { requires java.compiler; }");
        var compilation = BancCompilation.compiler(repertoire.resolve(declare ? "voisin" : "voisin-non-declare"),
                Map.of(), sources, false);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        Path service = compilation.classes().resolve("META-INF/services/javax.annotation.processing.Processor");
        Files.createDirectories(service.getParent());
        Files.copy(exemple.resolve("src/main/resources/META-INF/services/javax.annotation.processing.Processor"), service);
        try (var entree = Files.newInputStream(compilation.classes().resolve("module-info.class"))) {
            var module = ModuleDescriptor.read(entree);
            assertEquals("exemple.verne.voisin", module.name());
            assertTrue(module.exports().isEmpty());
            assertTrue(module.opens().isEmpty());
        }
        return compilation.classes();
    }

    private static Path cheminVerne() {
        Path projet = Path.of(System.getProperty("verne.racine.projet"));
        return Path.of(System.getProperty("verne.chemin.processeur",
                projet.resolve("verne-compilation/target/classes").toString()));
    }

    private static Path archiver(Path classes, Path archive) throws IOException {
        if (Files.isRegularFile(classes)) return classes;
        try (var sortie = new JarOutputStream(Files.newOutputStream(archive)); var parcours = Files.walk(classes)) {
            for (Path fichier : parcours.filter(Files::isRegularFile).sorted().toList()) {
                sortie.putNextEntry(new JarEntry(classes.relativize(fichier).toString().replace('\\', '/')));
                Files.copy(fichier, sortie);
                sortie.closeEntry();
            }
        }
        return archive;
    }
}
