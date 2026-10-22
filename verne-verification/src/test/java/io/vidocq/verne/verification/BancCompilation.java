package io.vidocq.verne.verification;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/**
 * Compile de vrais projets Java modulaires dans des répertoires temporaires.
 * Aucun faux compilateur de gabarits ne remplace le processeur attendu.
 */
final class BancCompilation {
    private BancCompilation() {}

    /** Paquet applicatif des classes générées ; un test peut fournir sa propre option -Averne.paquet. */
    static final String PAQUET = "exemple.gabarits";

    /** Résultat immuable : statut du compilateur, diagnostics français et répertoires produits. */
    record Compilation(boolean reussie, String diagnostics, Path classes, Path sourcesGenerees) {}

    /** Résultat d'un processus Java isolé ; les deux sorties sont décodées en UTF-8. */
    record Execution(int codeSortie, String sortie, String erreurs) {}

    /**
     * Prépare les sources et compile pour Java 25. Le paramètre avecVerne décide
     * uniquement de l'activation du processeur réel ; les témoins utilisent -proc:none.
     * Les exceptions d'outillage restent des erreurs de test, jamais des succès métier.
     */
    static Compilation compiler(Path racine, Map<String, String> gabarits,
                                Map<String, String> sources, boolean avecVerne) throws IOException {
        return compiler(racine, gabarits, sources, avecVerne, racine.resolve("gabarits"));
    }

    /** Options du processeur réel chargé explicitement, suivies des options javac supplémentaires. */
    static List<String> chargementVerne(String... supplements) {
        Path projet = Path.of(System.getProperty("verne.racine.projet"));
        String cheminProcesseur = System.getProperty("verne.chemin.processeur",
                projet.resolve("verne-compilation/target/classes").toString());
        var options = new ArrayList<>(List.of("-proc:full", "-processorpath", cheminProcesseur,
                "-processor", "io.vidocq.verne.compilation.ProcesseurVerne"));
        options.addAll(List.of(supplements));
        return options;
    }

    /** La racine déclarée, non nulle, peut différer des fixtures pour vérifier les refus et les alias. */
    static Compilation compiler(Path racine, Map<String, String> gabarits,
                                Map<String, String> sources, boolean avecVerne,
                                Path racineDeclaree) throws IOException {
        List<String> chargementProcesseur;
        if (avecVerne) {
            chargementProcesseur = chargementVerne();
        } else {
            chargementProcesseur = List.of("-proc:none");
        }
        return compiler(racine, gabarits, sources, avecVerne, racineDeclaree, chargementProcesseur);
    }

    /** Les options non nulles choisissent le chargement javac ; avecVerne ajoute la configuration métier. */
    static Compilation compiler(Path racine, Map<String, String> gabarits,
                                Map<String, String> sources, boolean avecVerne,
                                Path racineDeclaree, List<String> chargementProcesseur) throws IOException {
        return compiler(racine, gabarits, sources, avecVerne, racineDeclaree, chargementProcesseur, null);
    }

    /** Un processeur instrumenté peut observer les appels réels ; null conserve le chargement javac. */
    static Compilation compiler(Path racine, Map<String, String> gabarits,
                                Map<String, String> sources, boolean avecVerne,
                                Path racineDeclaree, List<String> chargementProcesseur,
                                Iterable<? extends javax.annotation.processing.Processor> processeurs) throws IOException {
        Path repertoireJava = Files.createDirectories(racine.resolve("java"));
        Path repertoireGabarits = Files.createDirectories(racine.resolve("gabarits"));
        Path classes = Files.createDirectories(racine.resolve("classes"));
        Path sourcesGenerees = Files.createDirectories(racine.resolve("sources-generees"));
        for (var entree : gabarits.entrySet()) {
            ecrire(repertoireGabarits.resolve(entree.getKey()), entree.getValue());
        }
        ecrire(repertoireJava.resolve("module-info.java"), "module application.verne {}\n");
        for (var entree : sources.entrySet()) {
            ecrire(repertoireJava.resolve(entree.getKey()), entree.getValue());
        }
        var compilateur = ToolProvider.getSystemJavaCompiler();
        if (compilateur == null) {
            throw new IllegalStateException("Un JDK 25 complet est nécessaire, pas une simple JVM.");
        }
        List<Path> chemins;
        try (var fichiers = Files.walk(repertoireJava)) {
            chemins = fichiers.filter(chemin -> chemin.toString().endsWith(".java")).sorted().toList();
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var journal = new StringWriter();
        var options = new ArrayList<>(List.of("--release", "25", "-encoding", "UTF-8",
                "-d", classes.toString(), "-s", sourcesGenerees.toString()));
        options.addAll(chargementProcesseur);
        if (avecVerne) {
            options.addAll(List.of("-Averne.racine.gabarits=" + racineDeclaree,
                    "-Averne.profil.sortie=texte"));
            if (chargementProcesseur.stream().noneMatch(option -> option.startsWith("-Averne.paquet"))) {
                options.add("-Averne.paquet=" + PAQUET);
            }
        }
        try (var gestionnaire = compilateur.getStandardFileManager(
                diagnostics, Locale.FRENCH, StandardCharsets.UTF_8)) {
            var unites = gestionnaire.getJavaFileObjectsFromPaths(chemins);
            var tache = compilateur.getTask(journal, gestionnaire, diagnostics, options, null, unites);
            if (processeurs != null) tache.setProcessors(processeurs);
            tache.setLocale(Locale.FRENCH);
            boolean reussie = tache.call();
            for (var diagnostic : diagnostics.getDiagnostics()) {
                journal.append(diagnostic.getCode()).append(" : ")
                        .append(diagnostic.getMessage(Locale.FRENCH)).append('\n');
            }
            return new Compilation(reussie, journal.toString(), classes, sourcesGenerees);
        }
    }

    /**
     * Lance la classe d'application sur le chemin des modules, sans ouverture de module.
     * Les modules supplémentaires, déjà compilés, complètent ce chemin sans le remplacer.
     * Le délai de garde de 15 secondes protège le banc ; ce n'est pas une mesure de performance.
     */
    static Execution executer(Compilation compilation, Path... modulesSupplementaires)
            throws IOException, InterruptedException {
        Path racine = compilation.classes().getParent();
        var modules = new ArrayList<String>(List.of(compilation.classes().toString()));
        for (Path module : modulesSupplementaires) modules.add(module.toString());
        String cheminModules = String.join(java.io.File.pathSeparator, modules);
        Path sortie = racine.resolve("sortie.txt");
        Path erreurs = racine.resolve("erreurs.txt");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Process processus = new ProcessBuilder(java.toString(), "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "--module-path", cheminModules,
                "--module", "application.verne/exemple.Application")
                .redirectOutput(sortie.toFile()).redirectError(erreurs.toFile()).start();
        try {
            if (!processus.waitFor(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Le processus de vérification n'a pas terminé en 15 secondes.");
            }
            return new Execution(processus.exitValue(), Files.readString(sortie, StandardCharsets.UTF_8),
                    Files.readString(erreurs, StandardCharsets.UTF_8));
        } finally {
            if (processus.isAlive()) {
                processus.destroyForcibly();
                processus.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /** Produit l'application témoin qui imprime une expression Java sans ajouter de fin de ligne. */
    static Map<String, String> application(String expression) {
        return Map.of("exemple/Application.java", """
                package exemple;
                public final class Application {
                    public static void main(String[] arguments) {
                        System.out.print(%s);
                    }
                }
                """.formatted(expression));
    }

    /** Écrit une fixture UTF-8 ; les répertoires appartiennent exclusivement au test. */
    private static void ecrire(Path chemin, String contenu) throws IOException {
        Files.createDirectories(chemin.getParent());
        Files.writeString(chemin, contenu, StandardCharsets.UTF_8);
    }
}
