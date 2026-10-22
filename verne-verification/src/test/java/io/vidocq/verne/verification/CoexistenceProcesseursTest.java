package io.vidocq.verne.verification;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Un vrai processeur voisin réclame son annotation et produit des modèles avec Filer. */
@Timeout(30)
final class CoexistenceProcesseursTest {
    private static final String VERNE = "io.vidocq.verne.compilation.ProcesseurVerne";
    private static final String VOISIN = "voisin.ProcesseurVoisin";
    @TempDir Path repertoire;

    @Test void conserveLAnnotationDuVoisinDansLesDeuxOrdres() throws Exception {
        Path voisin = preparerVoisin("case 1 -> " + emettre("Preuve", "public record Preuve() {}"));
        for (var ordre : List.of(List.of(VERNE, VOISIN), List.of(VOISIN, VERNE))) {
            var sources = application("exemple.gabarits.Bonjour.rendre(new PersonneSource(\"Léa\"))");
            sources.put("exemple/PersonneSource.java", "package exemple; public record PersonneSource(String nom) {}");
            var compilation = compiler(repertoire.resolve(ordre.getFirst()),
                    Map.of("bonjour.verne", "{@exemple.PersonneSource personne}Bonjour {personne.nom}"),
                    sources, voisin, ordre);
            verifierRendu(compilation, "Bonjour Léa");
            assertTrue(Files.exists(compilation.sourcesGenerees().resolve("exemple/Preuve.java")));
        }
    }

    @Test void accepteUnParametreGenereQuelQueSoitLOrdre() throws Exception {
        Path voisin = preparerVoisin("case 1 -> " + emettre("PersonneGeneree",
                "public record PersonneGeneree(String nom) {}"));
        for (var ordre : List.of(List.of(VERNE, VOISIN), List.of(VOISIN, VERNE))) {
            var compilation = compiler(repertoire.resolve(ordre.getFirst()),
                    Map.of("bonjour.verne", "{@exemple.PersonneGeneree personne}Bonjour {personne.nom} 🚀\r\n"),
                    application("exemple.gabarits.Bonjour.rendre(new PersonneGeneree(\"Léa\"))"), voisin, ordre);
            verifierRendu(compilation, "Bonjour Léa 🚀\r\n");
        }
    }

    @Test void attendUnModeleProduitAuDeuxiemeTour() throws Exception {
        Path voisin = preparerVoisin("case 1 -> " + emettre("Etape", "public record Etape() {}")
                + "case 2 -> " + emettre("PersonneGeneree", "public record PersonneGeneree(String nom) {}"));
        verifierRendu(compiler(repertoire.resolve("application"),
                Map.of("bonjour.verne", "{@exemple.PersonneGeneree personne}{personne.nom}"),
                application("exemple.gabarits.Bonjour.rendre(new PersonneGeneree(\"Léa\"))"),
                voisin, List.of(VERNE, VOISIN)), "Léa");
    }

    @Test void attendUnTypeDeNavigationGenerePourUnRecordSource() throws Exception {
        Path voisin = preparerVoisin("case 1 -> " + emettre("AdresseGeneree",
                "public record AdresseGeneree(String ville) {}"));
        var sources = application("exemple.gabarits.Bonjour.rendre(new PersonneSource(new AdresseGeneree(\"Paris\")))");
        sources.put("exemple/PersonneSource.java", "package exemple; public record PersonneSource(AdresseGeneree adresse) {}");
        verifierRendu(compiler(repertoire.resolve("application"),
                Map.of("bonjour.verne", "{@exemple.PersonneSource personne}{personne.adresse.ville}"),
                sources, voisin, List.of(VERNE, VOISIN)), "Paris");
    }

    @Test void attendLeTypeDeNavigationProduitApresLeModeleGenere() throws Exception {
        Path voisin = preparerVoisin("case 1 -> " + emettre("PersonneGeneree",
                "public record PersonneGeneree(AdresseGeneree adresse) {}")
                + "case 2 -> " + emettre("AdresseGeneree", "public record AdresseGeneree(String ville) {}"));
        verifierRendu(compiler(repertoire.resolve("application"),
                Map.of("bonjour.verne", "{@exemple.PersonneGeneree personne}{personne.adresse.ville}"),
                application("exemple.gabarits.Bonjour.rendre(new PersonneGeneree(new AdresseGeneree(\"Paris\")))"),
                voisin, List.of(VERNE, VOISIN)), "Paris");
    }

    @Test void signaleUneSeuleFoisUnTypeQuiResteAbsent() throws Exception {
        Path voisin = preparerVoisin(etapesSansModele());
        var compilation = compiler(repertoire.resolve("application"),
                Map.of("bonjour.verne", "{@exemple.Absent personne}{personne.nom}"),
                application("\"Témoin\""), voisin, List.of(VERNE, VOISIN));
        assertFalse(compilation.reussie());
        assertEquals(1, compilation.diagnostics().lines().filter(ligne -> ligne.contains("VERNE-TYP-003")).count(),
                compilation.diagnostics());
        assertTrue(compilation.diagnostics().contains("bonjour.verne:1:1"), compilation.diagnostics());
        assertFalse(Files.exists(compilation.sourcesGenerees().resolve("exemple/gabarits/Bonjour.java")));
    }

    @Test void conserveUneErreurDeProprieteApresApparitionDuType() throws Exception {
        Path voisin = preparerVoisin("case 1 -> " + emettre("PersonneGeneree",
                "public record PersonneGeneree(String prenom) {}"));
        var compilation = compiler(repertoire.resolve("application"),
                Map.of("bonjour.verne", "{@exemple.PersonneGeneree personne}\n{personne.inconnue}"),
                application("\"Témoin\""), voisin, List.of(VERNE, VOISIN));
        assertFalse(compilation.reussie());
        assertEquals(1, compilation.diagnostics().lines().filter(ligne -> ligne.contains("VERNE-TYP-001")).count(),
                compilation.diagnostics());
        assertTrue(compilation.diagnostics().contains("bonjour.verne:2:1"), compilation.diagnostics());
        assertFalse(Files.exists(compilation.sourcesGenerees().resolve("exemple/gabarits/Bonjour.java")));
    }

    @Test void lesRefusDifferesPartagentLeBudgetDeDiagnostics() throws Exception {
        Path voisin = preparerVoisin(etapesSansModele());
        var gabarits = new HashMap<String, String>();
        for (int index = 0; index < 65; index++) {
            gabarits.put("gabarit" + index + ".verne", "{@exemple.Absent personne}{personne.nom}");
        }
        var compilation = compiler(repertoire.resolve("application"), gabarits,
                application("\"Témoin\""), voisin, List.of(VERNE, VOISIN));
        assertFalse(compilation.reussie());
        assertEquals(64, compilation.diagnostics().lines().filter(ligne -> ligne.contains("VERNE-TYP-003")).count(),
                compilation.diagnostics());
        assertEquals(1, compilation.diagnostics().lines().filter(ligne -> ligne.contains("VERNE-LIM-007")).count(),
                compilation.diagnostics());
    }

    /** javac arrête les tours après une erreur : l'attente n'est pas une preuve d'absence du type. */
    @Test void neSignalePasDeTypeAbsentApresUneErreurInterrompantLesTours() throws Exception {
        Path voisin = preparerVoisin("case 1 -> " + emettre("PersonneGeneree",
                "public record PersonneGeneree(String nom) {}"));
        var compilation = compiler(repertoire.resolve("application"), Map.of(
                        "erreur.verne", "{inconnu}",
                        "attente.verne", "{@exemple.PersonneGeneree personne}{personne.nom}"),
                application("\"Témoin\""), voisin, List.of(VERNE, VOISIN));
        assertFalse(compilation.reussie());
        assertEquals(1, compilation.diagnostics().lines().filter(ligne -> ligne.contains("VERNE-TYP-001")
                && ligne.contains("erreur.verne:1:1")).count(), compilation.diagnostics());
        assertFalse(compilation.diagnostics().contains("VERNE-TYP-003"), compilation.diagnostics());
        assertEquals(1, compilation.diagnostics().lines().filter(ligne -> ligne.contains("VERNE-TYP-005")).count(),
                compilation.diagnostics());
        assertFalse(compilation.diagnostics().contains("attente.verne"), compilation.diagnostics());
    }

    /** Une création Java refusée par Filer est rattachée à son gabarit sans arrêter les suivants. */
    @Test void poursuitApresUneCreationJavaImpossible() throws Exception {
        Path voisin = preparerVoisin("case 1 -> { try (var sortie = processingEnv.getFiler()"
                + ".createSourceFile(\"exemple.gabarits.Collision\").openWriter()) {"
                + " sortie.write(\"package exemple.gabarits; public final class Collision {}\"); } }\n");
        var compilation = compiler(repertoire.resolve("application"),
                Map.of("collision.verne", "Collision", "suivant.verne", "Suivant"),
                application("\"Témoin\""), voisin, List.of(VOISIN, VERNE));
        assertFalse(compilation.reussie());
        assertEquals(1, compilation.diagnostics().lines().filter(ligne -> ligne.contains("VERNE-IO-001")
                && ligne.contains("collision.verne:1:1")).count(), compilation.diagnostics());
        assertFalse(compilation.diagnostics().contains(repertoire.toString()), compilation.diagnostics());
        assertTrue(Files.exists(compilation.sourcesGenerees().resolve("exemple/gabarits/Suivant.java")),
                compilation.diagnostics());
    }

    @Test void isoleLesAttentesEntreDeuxCompilationsConcurrentes() throws Exception {
        Path voisin = preparerVoisin("case 1 -> " + emettre("Etape", "public record Etape() {}")
                + "case 2 -> " + emettre("PersonneGeneree", "public record PersonneGeneree(String nom) {}"));
        var gabarits = Map.of("bonjour.verne", "{@exemple.PersonneGeneree personne}{personne.nom}");
        try (var groupe = Executors.newFixedThreadPool(2)) {
            var premiere = groupe.submit(() -> compiler(repertoire.resolve("premiere"), gabarits,
                    application("exemple.gabarits.Bonjour.rendre(new PersonneGeneree(\"Léa\"))"),
                    voisin, List.of(VERNE, VOISIN)));
            var seconde = groupe.submit(() -> compiler(repertoire.resolve("seconde"), gabarits,
                    application("exemple.gabarits.Bonjour.rendre(new PersonneGeneree(\"Émile\"))"),
                    voisin, List.of(VERNE, VOISIN)));
            verifierRendu(premiere.get(15, TimeUnit.SECONDS), "Léa");
            verifierRendu(seconde.get(15, TimeUnit.SECONDS), "Émile");
        }
    }

    @Test void decouvreLesDeuxServicesSansNomDeProcesseur() throws Exception {
        Path voisin = preparerVoisin("case 1 -> " + emettre("PersonneGeneree",
                "public record PersonneGeneree(String nom) {}"));
        verifierRendu(compiler(repertoire.resolve("application"),
                Map.of("bonjour.verne", "{@exemple.PersonneGeneree personne}{personne.nom}"),
                application("exemple.gabarits.Bonjour.rendre(new PersonneGeneree(\"Léa\"))"),
                voisin, List.of()), "Léa");
    }

    private BancCompilation.Compilation compiler(Path racine, Map<String, String> gabarits,
            Map<String, String> sources, Path voisin, List<String> ordre) throws IOException {
        Path projet = Path.of(System.getProperty("verne.racine.projet"));
        String chemin = System.getProperty("verne.chemin.processeur",
                projet.resolve("verne-compilation/target/classes").toString());
        var options = new ArrayList<>(List.of("-proc:full", "-processorpath", chemin + File.pathSeparator + voisin));
        if (!ordre.isEmpty()) options.addAll(List.of("-processor", String.join(",", ordre)));
        return BancCompilation.compiler(racine, gabarits, sources, true, racine.resolve("gabarits"), options);
    }

    private static Map<String, String> application(String expression) {
        var sources = new HashMap<>(BancCompilation.application(expression));
        sources.computeIfPresent("exemple/Application.java", (nom, contenu) ->
                contenu.replace("public final class Application", "@Marque public final class Application"));
        sources.put("exemple/Marque.java", "package exemple; public @interface Marque {}");
        return sources;
    }

    private static void verifierRendu(BancCompilation.Compilation compilation, String attendu) throws Exception {
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        assertEquals(attendu, execution.sortie());
    }

    private static String etapesSansModele() {
        return "case 1 -> " + emettre("EtapeUn", "public record EtapeUn() {}")
                + "case 2 -> " + emettre("EtapeDeux", "public record EtapeDeux() {}");
    }

    private static String emettre(String classe, String declaration) {
        return "{ try (var sortie = processingEnv.getFiler().createSourceFile(\"exemple." + classe
                + "\").openWriter()) { sortie.write(\"package exemple; " + declaration + "\"); } }\n";
    }

    /** Compile le voisin pour Java 25 puis déclare aussi son service classique. */
    private Path preparerVoisin(String productions) throws IOException {
        var compilation = BancCompilation.compiler(repertoire.resolve("voisin"), Map.of(), Map.of(
                "module-info.java", "module application.verne { requires java.compiler; }",
                "voisin/ProcesseurVoisin.java", """
                        package voisin;
                        import java.io.IOException;
                        import java.util.Set;
                        import javax.annotation.processing.*;
                        import javax.lang.model.SourceVersion;
                        import javax.lang.model.element.TypeElement;
                        public final class ProcesseurVoisin extends AbstractProcessor {
                            private int nombreTours;
                            public ProcesseurVoisin() {}
                            @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.RELEASE_25; }
                            @Override public Set<String> getSupportedAnnotationTypes() { return Set.of("exemple.Marque"); }
                            @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment tour) {
                                if (tour.processingOver()) return false;
                                if (nombreTours == 0 && annotations.stream().noneMatch(annotation ->
                                        annotation.getQualifiedName().contentEquals("exemple.Marque"))) {
                                    throw new IllegalStateException("Le voisin doit recevoir son annotation.");
                                }
                                try {
                                    switch (++nombreTours) {
                                        %s
                                        default -> {}
                                    }
                                } catch (IOException exception) {
                                    throw new IllegalStateException("Impossible de produire le modèle voisin.", exception);
                                }
                                return true;
                            }
                        }
                        """.formatted(productions)), false);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        Path service = compilation.classes().resolve("META-INF/services/javax.annotation.processing.Processor");
        Files.createDirectories(service.getParent());
        Files.writeString(service, VOISIN + "\n");
        return compilation.classes();
    }
}
