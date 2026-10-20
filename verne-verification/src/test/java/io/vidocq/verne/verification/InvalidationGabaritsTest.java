package io.vidocq.verne.verification;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Consomme l'API depuis un vrai module Java, sans ouverture réflexive. */
@Timeout(30)
final class InvalidationGabaritsTest {
    @TempDir Path repertoire;

    @Test void premiereConstructionTrieTousLesGabarits() throws Exception {
        verifier("""
                System.out.print(comparer(null, new Etat(a, Map.of("z.verne", g, "a.verne", g), Map.of())));
                """, "Plan[recompiler=[a.verne, z.verne], supprimer=[], conserver=[]]");
    }

    @Test void distingueAjoutModificationSuppressionEtConservation() throws Exception {
        verifier("""
                var avant = new Etat(a, Map.of("ancien.verne", g, "modifie.verne", g, "stable.verne", g), Map.of());
                var apres = new Etat(a, Map.of("ajout.verne", g, "modifie.verne", new Gabarit(b, Set.of()), "stable.verne", g), Map.of());
                System.out.print(comparer(avant, apres));
                """, "Plan[recompiler=[ajout.verne, modifie.verne], supprimer=[ancien.verne], conserver=[stable.verne]]");
    }

    @Test void invalideSeulementLesDependantsDuTypeModifie() throws Exception {
        verifier("""
                var gabarits = Map.of("lie.verne", new Gabarit(a, Set.of("exemple.Personne", "exemple.Adresse")), "libre.verne", g);
                var avant = new Etat(a, gabarits, Map.of("exemple.Personne", a, "exemple.Adresse", a, "exemple.Autre", a));
                var apres = new Etat(a, gabarits, Map.of("exemple.Personne", a, "exemple.Adresse", b, "exemple.Autre", b));
                System.out.print(comparer(avant, apres));
                """, "Plan[recompiler=[lie.verne], supprimer=[], conserver=[libre.verne]]");
    }

    @Test void neReutiliseJamaisUneDependanceAbsente() throws Exception {
        verifier("""
                var gabarits = Map.of("lie.verne", new Gabarit(a, Set.of("exemple.Personne")));
                var avant = new Etat(a, gabarits, Map.of("exemple.Personne", a));
                var apres = new Etat(a, gabarits, Map.of());
                System.out.print(comparer(avant, apres).recompiler());
                System.out.print(comparer(apres, apres).recompiler());
                """, "[lie.verne][lie.verne]");
    }

    @Test void changementDeContexteRecompileToutEtGardeLesSuppressions() throws Exception {
        verifier("""
                System.out.print(comparer(new Etat(a, Map.of("avant.verne", g, "stable.verne", g), Map.of()),
                        new Etat(b, Map.of("stable.verne", g), Map.of())));
                """, "Plan[recompiler=[stable.verne], supprimer=[avant.verne], conserver=[]]");
    }

    @Test void changementDesDependancesInvalideMemeAvecLeMemeContenu() throws Exception {
        verifier("""
                var types = Map.of("exemple.Personne", a);
                System.out.print(comparer(new Etat(a, Map.of("a.verne", g), types),
                        new Etat(a, Map.of("a.verne", new Gabarit(a, Set.of("exemple.Personne"))), types)));
                """, "Plan[recompiler=[a.verne], supprimer=[], conserver=[]]");
    }

    @Test void copieLesEntreesEtProtegeLesResultats() throws Exception {
        verifier("""
                var noms = new HashSet<String>(); noms.add("exemple.Personne");
                var modele = new Gabarit(a, noms); noms.clear();
                var fichiers = new HashMap<String, Gabarit>(); fichiers.put("a.verne", modele);
                var types = new HashMap<String, String>(); types.put("exemple.Personne", a);
                var etat = new Etat(a, fichiers, types); fichiers.clear(); types.clear();
                var plan = comparer(etat, etat);
                int refus = 0;
                try { plan.conserver().clear(); } catch (UnsupportedOperationException attendu) { refus++; }
                try { etat.gabarits().clear(); } catch (UnsupportedOperationException attendu) { refus++; }
                try { modele.typesJava().clear(); } catch (UnsupportedOperationException attendu) { refus++; }
                System.out.print(plan.conserver() + ":" + refus);
                """, "[a.verne]:3");
    }

    @Test void refuseLesEntreesMalFormeesEtNulles() throws Exception {
        verifier("""
                int refus = 0;
                for (String nom : List.of("../a.verne", "/a.verne", "a//b.verne", "a/./b.verne", "C:a.verne", "a\\\\b.verne", "a.java", "a\\n.verne")) {
                    try { new Etat(a, Map.of(nom, g), Map.of()); }
                    catch (IllegalArgumentException attendu) { refus++; }
                }
                try { new Gabarit("incorrect", Set.of()); } catch (IllegalArgumentException attendu) { refus++; }
                try { new Gabarit(a, Set.of("exemple..Type")); } catch (IllegalArgumentException attendu) { refus++; }
                try { new Etat(a, Map.of(), Map.of("exemple.Type", "incorrect")); } catch (IllegalArgumentException attendu) { refus++; }
                try { comparer(null, null); } catch (NullPointerException attendu) { refus++; }
                try { new Gabarit(a, null); } catch (NullPointerException attendu) { refus++; }
                System.out.print(refus);
                """, "13");
    }

    @Test void resteDeterministeEnConcurrenceEtSansChangement() throws Exception {
        verifier("""
                var etat = new Etat(a, Map.of("z.verne", g, "a.verne", g), Map.of("exemple.Inutilise", a));
                var autre = new Etat(a, Map.of("a.verne", g, "z.verne", g), Map.of("exemple.Inutilise", b));
                try (var execution = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                    var un = execution.submit(() -> comparer(etat, autre));
                    var deux = execution.submit(() -> comparer(autre, etat));
                    System.out.print(un.get().equals(deux.get()) + ":" + un.get());
                }
                """, "true:Plan[recompiler=[], supprimer=[], conserver=[a.verne, z.verne]]");
    }

    @Test void traiteLeRenommageUnicodeEtLaSuppressionDuDernierGabarit() throws Exception {
        verifier("""
                var avant = new Etat(a, Map.of("dossier/été.verne", g), Map.of());
                var apres = new Etat(a, Map.of("dossier/hiver.verne", g), Map.of());
                System.out.print(comparer(avant, apres));
                System.out.print(comparer(apres, new Etat(a, Map.of(), Map.of())));
                """, "Plan[recompiler=[dossier/hiver.verne], supprimer=[dossier/été.verne], conserver=[]]"
                        + "Plan[recompiler=[], supprimer=[dossier/hiver.verne], conserver=[]]");
    }

    @Test void selectionneUnSeulGabaritParmiMilleVingtQuatre() throws Exception {
        verifier("""
                var fichiers = new HashMap<String, Gabarit>();
                for (int i = 0; i < 1024; i++) fichiers.put("g" + i + ".verne", g);
                var avant = new Etat(a, fichiers, Map.of());
                fichiers.put("g512.verne", new Gabarit(b, Set.of()));
                var plan = comparer(avant, new Etat(a, fichiers, Map.of()));
                System.out.print(plan.recompiler() + ":" + plan.conserver().size() + ":" + plan.supprimer().size());
                """, "[g512.verne]:1023:0");
    }

    private void verifier(String corps, String attendu) throws Exception {
        String application = """
                package exemple;
                import java.util.*;
                import io.vidocq.verne.invalidation.InvalidationGabarits.*;
                import static io.vidocq.verne.invalidation.InvalidationGabarits.comparer;
                public final class Application {
                    public static void main(String[] arguments) throws Exception {
                        String a = "a".repeat(64), b = "b".repeat(64);
                        var g = new Gabarit(a, Set.of());
                        %s
                    }
                }
                """.formatted(corps);
        Path projet = Path.of(System.getProperty("verne.racine.projet"));
        String chemin = System.getProperty("verne.chemin.processeur", projet.resolve("verne-compilation/target/classes").toString());
        var compilation = BancCompilation.compiler(repertoire, Map.of(), Map.of(
                "module-info.java", "module application.verne { requires io.vidocq.verne.compilation; }",
                "exemple/Application.java", application), false, repertoire.resolve("gabarits"),
                List.of("-proc:none", "--module-path", chemin));
        assertTrue(compilation.reussie(), compilation.diagnostics());
        // Le client est un outil de construction : son lancement inclut le module de compilation.
        var executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path sortie = repertoire.resolve("sortie.txt");
        var processus = new ProcessBuilder(executable.toString(), "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                "--module-path", compilation.classes() + java.io.File.pathSeparator + chemin,
                "--module", "application.verne/exemple.Application").redirectErrorStream(true).redirectOutput(sortie.toFile()).start();
        try {
            assertTrue(processus.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
            String texte = java.nio.file.Files.readString(sortie);
            assertEquals(0, processus.exitValue(), texte);
            assertEquals(attendu, texte);
        } finally { if (processus.isAlive()) processus.destroyForcibly(); }
    }
}
