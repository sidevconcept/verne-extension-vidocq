package io.vidocq.verne.verification;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Construit une vraie application Maven, sans parent Verne ni dépendance applicative au processeur. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
final class ApplicationMavenTest {
    @TempDir Path repertoire;
    private Path preparation;
    private Path reglages;
    private final Path projet = Path.of(System.getProperty("verne.racine.projet"));

    private record Construction(int codeSortie, String journal) {}

    /** Le compilateur et son parent sont installés dans un dépôt temporaire, hors des sorties du réacteur actif. */
    @BeforeAll @Timeout(120)
    void preparerCompilateur(@TempDir Path temporaire) throws Exception {
        preparation = temporaire;
        Path depot = Files.createDirectories(preparation.resolve("depot"));
        String cache = Path.of(System.getProperty("verne.depot.maven")).toUri().toASCIIString();
        reglages = preparation.resolve("reglages.xml");
        Files.writeString(reglages, """
                <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                    <localRepository>%s</localRepository>
                    <interactiveMode>false</interactiveMode>
                    <profiles><profile>
                        <id>cache-verification</id>
                        <repositories><repository>
                            <id>cache-verification</id><url>%s</url>
                            <snapshots><enabled>false</enabled></snapshots>
                        </repository></repositories>
                        <pluginRepositories><pluginRepository>
                            <id>cache-verification</id><url>%s</url>
                            <snapshots><enabled>false</enabled></snapshots>
                        </pluginRepository></pluginRepositories>
                    </profile></profiles>
                    <activeProfiles><activeProfile>cache-verification</activeProfile></activeProfiles>
                </settings>
                """.formatted(echapperXml(depot.toString()), echapperXml(cache), echapperXml(cache)));
        Path copie = Files.createDirectories(preparation.resolve("compilateur"));
        copier(projet.resolve("pom.xml"), copie.resolve("pom.xml"));
        copier(projet.resolve("verne-compilation/pom.xml"), copie.resolve("verne-compilation/pom.xml"));
        copier(projet.resolve("verne-compilation/src"), copie.resolve("verne-compilation/src"));
        copier(projet.resolve("verne-verification/pom.xml"), copie.resolve("verne-verification/pom.xml"));
        var installation = construire(copie.resolve("pom.xml"), "-pl", "verne-compilation", "-am", "install");
        assertEquals(0, installation.codeSortie(), installation.journal());
        assertTrue(Files.isRegularFile(depot.resolve(
                "io/vidocq/verne/verne-compilation/0.4.0-SNAPSHOT/verne-compilation-0.4.0-SNAPSHOT.jar")));
        Path voisin = preparation.resolve("processeur-voisin");
        copier(projet.resolve("exemples/processeur-voisin"), voisin);
        var installationVoisin = construire(voisin.resolve("pom.xml"), "install");
        assertEquals(0, installationVoisin.codeSortie(), installationVoisin.journal());
    }

    @Test void compileEmpaquetteEtExecuteSansProcesseurAuLancement() throws Exception {
        Path application = preparerApplication();
        var construction = construire(application.resolve("pom.xml"), "clean", "package");
        assertEquals(0, construction.codeSortie(), construction.journal());
        Path archive = application.resolve("target/application-verne-1.0.0.jar");
        try (var jar = new JarFile(archive.toFile())) {
            assertNotNull(jar.getJarEntry("module-info.class"));
            assertNotNull(jar.getJarEntry("exemple/Personne.class"));
            assertNotNull(jar.getJarEntry("exemple/gabarits/Bonjour.class"));
            assertTrue(jar.stream().noneMatch(entree -> entree.getName().startsWith("io/vidocq/verne/compilation/")
                    || entree.getName().endsWith(".verne") || entree.getName().endsWith(".jar")));
        }
        var execution = BancCompilation.executer(new BancCompilation.Compilation(true, "", archive,
                application.resolve("target/generated-sources/annotations")));
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        assertEquals("Bonjour Léa 🚀\r\n", execution.sortie());
    }

    /** Les tests utilisent les classes principales : aucune copie des gabarits n'est générée pour eux. */
    @Test void neRegenerePasLesGabaritsPendantLaCompilationDesTests() throws Exception {
        Path application = preparerApplication();
        Path test = application.resolve("src/test/java/exemple/RenduTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, """
                package exemple;
                final class RenduTest {
                    String rendu() { return exemple.gabarits.Bonjour.rendre(new Personne("Léa")); }
                }
                """);
        var construction = construire(application.resolve("pom.xml"), "test-compile");
        assertEquals(0, construction.codeSortie(), construction.journal());
        assertTrue(Files.exists(application.resolve("target/test-classes/exemple/RenduTest.class")));
        assertTrue(Files.exists(application.resolve("target/classes/exemple/gabarits/Bonjour.class")));
        assertFalse(Files.exists(application.resolve("target/test-classes/exemple/gabarits")), construction.journal());
        assertFalse(Files.exists(application.resolve(
                "target/generated-test-sources/test-annotations/exemple/gabarits")), construction.journal());
    }

    @Test void refuseUnGabaritInutiliseInvalide() throws Exception {
        Path application = preparerApplication();
        Files.writeString(application.resolve("src/main/verne/inutilise.verne"), "Ligne\r\n{nom");
        verifierRefus(application, "VERNE-SYN-001", "inutilise.verne:2:1");
    }

    @Test void conserveLeDiagnosticDeTypageDuModeleSource() throws Exception {
        Path application = preparerApplication();
        Files.writeString(application.resolve("src/main/verne/bonjour.verne"),
                "{@exemple.Personne personne}\n{personne.inconnue}");
        verifierRefus(application, "VERNE-TYP-001", "bonjour.verne:2:1");
    }

    @Test void transmetLeRefusDunProfilNonPrisEnCharge() throws Exception {
        verifierRefus(preparerApplication(), "VERNE-CFG-001", "", "-Dverne.profil.sortie=json");
    }

    @Test void chargeLesDeuxProcesseursParLeProfilMavenClassique() throws Exception {
        verifierRenduConjoint();
    }

    @Test void chargeLesDeuxProcesseursParLeProfilMavenModulaire() throws Exception {
        verifierRenduConjoint("-Pmodules");
    }

    @Test void refuseUnGabaritInutiliseAvecLeProfilMavenModulaire() throws Exception {
        Path application = preparerApplication("application-processeurs");
        Files.writeString(application.resolve("src/main/verne/inutilise.verne"), "Ligne\r\n{nom");
        var construction = construire(application.resolve("pom.xml"), "clean", "package", "-Pmodules");
        assertNotEquals(0, construction.codeSortie(), construction.journal());
        assertTrue(construction.journal().contains("VERNE-SYN-001"), construction.journal());
        assertTrue(construction.journal().contains("inutilise.verne:2:1"), construction.journal());
        assertFalse(Files.exists(application.resolve("target/application-processeurs-1.0.0.jar")));
    }

    @Test void reconstruitLesGabaritsSansNettoyageExplicite() throws Exception {
        verifierReconstructions("application-maven", "application-verne-1.0.0.jar");
    }

    @Test void reconstruitAvecLesDeuxProcesseursClassiques() throws Exception {
        verifierReconstructions("application-processeurs", "application-processeurs-1.0.0.jar");
    }

    @Test void reconstruitAvecLesDeuxProcesseursModulaires() throws Exception {
        verifierReconstructions("application-processeurs", "application-processeurs-1.0.0.jar", "-Pmodules");
    }

    private void verifierReconstructions(String exemple, String nomArchive, String... options) throws Exception {
        Path application = preparerApplication(exemple);
        Path gabarits = application.resolve("src/main/verne");
        Path archive = application.resolve("target").resolve(nomArchive);
        var objectifs = new ArrayList<>(List.of("package"));
        objectifs.addAll(Arrays.asList(options));
        String[] arguments = objectifs.toArray(String[]::new);
        var initiale = construire(application.resolve("pom.xml"), arguments);
        assertEquals(0, initiale.codeSortie(), initiale.journal());
        Path bonjour = gabarits.resolve("bonjour.verne");
        Files.writeString(bonjour, Files.readString(bonjour).replace("Bonjour", "Salut"));
        Files.writeString(gabarits.resolve("ajout.verne"), "Nouveau");
        var modification = construire(application.resolve("pom.xml"), arguments);
        assertEquals(0, modification.codeSortie(), modification.journal());
        var execution = BancCompilation.executer(new BancCompilation.Compilation(true, "", archive,
                application.resolve("target/generated-sources/annotations")));
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        assertEquals("Salut Léa 🚀\r\n", execution.sortie());
        try (var jar = new JarFile(archive.toFile())) {
            assertNotNull(jar.getJarEntry("exemple/gabarits/Ajout.class"));
        }
        Files.delete(gabarits.resolve("ajout.verne"));
        var suppression = construire(application.resolve("pom.xml"), arguments);
        assertEquals(0, suppression.codeSortie(), suppression.journal());
        assertFalse(Files.exists(application.resolve("target/generated-sources/annotations/exemple/gabarits/Ajout.java")));
        try (var jar = new JarFile(archive.toFile())) {
            assertNull(jar.getJarEntry("exemple/gabarits/Ajout.class"));
        }
        Files.writeString(gabarits.resolve("invalide.verne"), "{nom");
        var invalide = construire(application.resolve("pom.xml"), arguments);
        assertNotEquals(0, invalide.codeSortie(), invalide.journal());
        assertTrue(invalide.journal().contains("VERNE-SYN-001"), invalide.journal());
        assertFalse(Files.exists(archive));
        Files.delete(gabarits.resolve("invalide.verne"));
        var reprise = construire(application.resolve("pom.xml"), arguments);
        assertEquals(0, reprise.codeSortie(), reprise.journal());
        Files.delete(bonjour);
        var dependant = construire(application.resolve("pom.xml"), arguments);
        assertNotEquals(0, dependant.codeSortie(), dependant.journal());
        assertTrue(dependant.journal().contains("exemple.gabarits"), dependant.journal());
        assertFalse(Files.exists(application.resolve("target/classes/exemple/gabarits/Bonjour.class")));
        assertFalse(Files.exists(archive));
    }

    private void verifierRenduConjoint(String... options) throws Exception {
        Path application = preparerApplication("application-processeurs");
        var objectifs = new ArrayList<>(List.of("clean", "package"));
        objectifs.addAll(Arrays.asList(options));
        var construction = construire(application.resolve("pom.xml"), objectifs.toArray(String[]::new));
        assertEquals(0, construction.codeSortie(), construction.journal());
        Path archive = application.resolve("target/application-processeurs-1.0.0.jar");
        try (var jar = new JarFile(archive.toFile())) {
            assertNotNull(jar.getJarEntry("module-info.class"));
            assertNotNull(jar.getJarEntry("exemple/PersonneGeneree.class"));
            assertNotNull(jar.getJarEntry("exemple/gabarits/Bonjour.class"));
            assertTrue(jar.stream().noneMatch(entree -> entree.getName().startsWith("exemple/voisin/")
                    || entree.getName().startsWith("io/vidocq/verne/compilation/")
                    || entree.getName().endsWith(".verne") || entree.getName().endsWith(".jar")));
        }
        var execution = BancCompilation.executer(new BancCompilation.Compilation(true, "", archive,
                application.resolve("target/generated-sources/annotations")));
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        assertEquals("Bonjour Léa 🚀\r\n", execution.sortie());
    }

    private void verifierRefus(Path application, String code, String position, String... options) throws Exception {
        var arguments = new ArrayList<>(List.of("clean", "package"));
        arguments.addAll(Arrays.asList(options));
        var construction = construire(application.resolve("pom.xml"), arguments.toArray(String[]::new));
        assertNotEquals(0, construction.codeSortie(), construction.journal());
        assertTrue(construction.journal().contains(code), construction.journal());
        if (!position.isEmpty()) assertTrue(construction.journal().contains(position), construction.journal());
        assertFalse(Files.exists(application.resolve("target/application-verne-1.0.0.jar")));
    }

    private Path preparerApplication() throws IOException {
        return preparerApplication("application-maven");
    }

    private Path preparerApplication(String exemple) throws IOException {
        Path application = repertoire.resolve("application Maven é & espaces");
        copier(projet.resolve("exemples").resolve(exemple), application);
        return application;
    }

    /** Réutilise le lanceur Maven fixé par le projet, avec un dépôt et des journaux propres à cette suite. */
    private Construction construire(Path pom, String... objectifs) throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        var commande = new ArrayList<String>();
        if (windows) commande.addAll(List.of("cmd.exe", "/d", "/c"));
        commande.add(projet.resolve(windows ? "mvnw.cmd" : "mvnw").toString());
        commande.addAll(List.of("-B", "-ntp", "-s", reglages.toString(), "-f", pom.toString()));
        commande.addAll(Arrays.asList(objectifs));
        Path journal = Files.createTempFile(preparation, "maven-", ".log");
        var lancement = new ProcessBuilder(commande).directory(projet.toFile())
                .redirectErrorStream(true).redirectOutput(journal.toFile());
        lancement.environment().put("JAVA_HOME", System.getProperty("java.home"));
        lancement.environment().put("MAVEN_OPTS",
                "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8");
        Process processus = lancement.start();
        try {
            if (!processus.waitFor(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("La construction Maven n'a pas terminé en 60 secondes : " + journal);
            }
            return new Construction(processus.exitValue(), Files.readString(journal));
        } finally {
            if (processus.isAlive()) {
                processus.descendants().forEach(ProcessHandle::destroyForcibly);
                processus.destroyForcibly();
                processus.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /** Copie seulement les fichiers demandés ; les sorties target et le journal local sont exclus. */
    private static void copier(Path source, Path destination) throws IOException {
        if (Files.isRegularFile(source)) {
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination);
            return;
        }
        try (var parcours = Files.walk(source)) {
            for (Path fichier : parcours.filter(Files::isRegularFile).toList()) {
                Path relatif = source.relativize(fichier);
                if (fichier.getFileName().toString().equals("PROMPTS.md")) continue;
                boolean sortie = false;
                for (Path segment : relatif) if (segment.toString().equals("target")) sortie = true;
                if (!sortie) copier(fichier, destination.resolve(relatif));
            }
        }
    }

    private static String echapperXml(String texte) {
        return texte.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
