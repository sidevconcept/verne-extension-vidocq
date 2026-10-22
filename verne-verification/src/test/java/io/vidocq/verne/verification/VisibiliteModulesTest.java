package io.vidocq.verne.verification;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Les types d'autres modules doivent être lus et exportés avant toute génération Java. */
@Timeout(30)
class VisibiliteModulesTest {
    @TempDir Path repertoire;
    private Path alpha;
    private Path gamma;

    /** alpha expose une API, garde un paquet interne et réserve un paquet à un autre module. */
    @BeforeEach void preparerModules() throws Exception {
        var compilationAlpha = BancCompilation.compiler(repertoire.resolve("alpha"), Map.of(), Map.of(
                "module-info.java", "module alpha { exports alpha.api; exports alpha.restreint to autre.module; }",
                "alpha/api/Personne.java", "package alpha.api; public record Personne(String nom, alpha.interne.Adresse adresse) {}",
                "alpha/interne/Adresse.java", "package alpha.interne; public record Adresse(String ville) {}",
                "alpha/restreint/Secret.java", "package alpha.restreint; public record Secret(String code) {}"), false);
        assertTrue(compilationAlpha.reussie(), compilationAlpha.diagnostics());
        alpha = compilationAlpha.classes();
        var compilationGamma = BancCompilation.compiler(repertoire.resolve("gamma"), Map.of(), Map.of(
                "module-info.java", "module gamma { requires transitive alpha; }"), false,
                repertoire.resolve("gamma/gabarits"), java.util.List.of("-proc:none", "--module-path", alpha.toString()));
        assertTrue(compilationGamma.reussie(), compilationGamma.diagnostics());
        gamma = compilationGamma.classes();
    }

    @Test void accepteUnTypeExporteParUnModuleLu() throws Exception {
        rendre("module application.verne { requires alpha; }", "Léa", alpha);
    }

    @Test void accepteUnModuleLuParRequiresTransitive() throws Exception {
        rendre("module application.verne { requires gamma; }", "Léa", alpha, gamma);
    }

    @Test void refuseUnTypeDUnPaquetNonExporte() throws Exception {
        refuser("module application.verne { requires alpha; }", "{@alpha.interne.Adresse adresse}{adresse.ville}",
                "VERNE-TYP-003", "vue.verne:1:1");
    }

    @Test void refuseUnExportQualifieVersUnAutreModule() throws Exception {
        refuser("module application.verne { requires alpha; }", "{@alpha.restreint.Secret secret}{secret.code}",
                "VERNE-TYP-003", "vue.verne:1:1");
    }

    @Test void refuseUnTypeDUnModuleNonLu() throws Exception {
        refuser("module application.verne {}", "{@alpha.api.Personne personne}{personne.nom}",
                "VERNE-TYP-003", "vue.verne:1:1", "--add-modules", "alpha");
    }

    @Test void refuseUneNavigationVersUnRecordNonExporte() throws Exception {
        refuser("module application.verne { requires alpha; }", "{@alpha.api.Personne personne}\n{personne.adresse.ville}",
                "VERNE-TYP-001", "vue.verne:2:1");
    }

    private void rendre(String descripteur, String attendu, Path... modules) throws Exception {
        var compilation = compiler(descripteur, "{@alpha.api.Personne personne}{personne.nom}",
                "exemple.gabarits.Vue.rendre(new alpha.api.Personne(\"Léa\", null))", cheminModules(modules));
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation, modules);
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        assertEquals(attendu, execution.sortie());
    }

    private void refuser(String descripteur, String gabarit, String code, String position, String... options)
            throws Exception {
        var supplements = new java.util.ArrayList<>(java.util.List.of(cheminModules(alpha)));
        supplements.addAll(java.util.List.of(options));
        var compilation = compiler(descripteur, gabarit, "\"témoin\"", supplements.toArray(String[]::new));
        assertFalse(compilation.reussie(), compilation.diagnostics());
        assertTrue(compilation.diagnostics().contains(code), compilation.diagnostics());
        assertTrue(compilation.diagnostics().contains(position), compilation.diagnostics());
        assertFalse(Files.exists(compilation.sourcesGenerees().resolve("exemple/gabarits/Vue.java")),
                "Aucune source Java ne doit être générée pour un type inaccessible.");
    }

    private BancCompilation.Compilation compiler(String descripteur, String gabarit, String expression,
            String... supplements) throws Exception {
        var sources = new java.util.HashMap<>(BancCompilation.application(expression));
        sources.put("module-info.java", descripteur);
        return BancCompilation.compiler(repertoire.resolve("application"), Map.of("vue.verne", gabarit), sources,
                true, repertoire.resolve("application/gabarits"), BancCompilation.chargementVerne(supplements));
    }

    private static String[] cheminModules(Path... modules) {
        var chemins = new String[modules.length];
        for (int index = 0; index < modules.length; index++) chemins[index] = modules[index].toString();
        return new String[] {"--module-path", String.join(File.pathSeparator, chemins)};
    }
}
