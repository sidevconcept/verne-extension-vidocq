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
                "io/vidocq/verne/verne-compilation/0.1.0-SNAPSHOT/verne-compilation-0.1.0-SNAPSHOT.jar")));
    }

    @Test void compileEmpaquetteEtExecuteSansProcesseurAuLancement() throws Exception {
        Path application = preparerApplication();
        var construction = construire(application.resolve("pom.xml"), "clean", "package");
        assertEquals(0, construction.codeSortie(), construction.journal());
        Path archive = application.resolve("target/application-verne-1.0.0.jar");
        try (var jar = new JarFile(archive.toFile())) {
            assertNotNull(jar.getJarEntry("module-info.class"));
            assertNotNull(jar.getJarEntry("exemple/Personne.class"));
            assertNotNull(jar.getJarEntry("io/vidocq/verne/genere/Bonjour.class"));
            assertTrue(jar.stream().noneMatch(entree -> entree.getName().startsWith("io/vidocq/verne/compilation/")
                    || entree.getName().endsWith(".verne") || entree.getName().endsWith(".jar")));
        }
        var execution = BancCompilation.executer(new BancCompilation.Compilation(true, "", archive,
                application.resolve("target/generated-sources/annotations")));
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        assertEquals("Bonjour Léa 🚀\r\n", execution.sortie());
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
        verifierRefus(preparerApplication(), "VERNE-CFG-001", "", "-Dverne.profil.sortie=html");
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
        Path application = repertoire.resolve("application Maven é & espaces");
        copier(projet.resolve("exemples/application-maven"), application);
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
