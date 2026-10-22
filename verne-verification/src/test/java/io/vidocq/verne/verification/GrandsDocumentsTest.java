package io.vidocq.verne.verification;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Allocation mesurée sans seuil de durée ; rendus réels sur le chemin des modules. */
@Timeout(30)
class GrandsDocumentsTest {
    @TempDir Path repertoire;
    private static final String PREFIXE = "a".repeat(65_536);

    @Test void respecteLeBudgetDAllocationsDuDocumentUnicodeDeSoixanteQuatreKio() throws Exception {
        verifierBudget("Léa🚀", 70);
    }

    /** Assemblage unique : le texte Latin-1 n'est copié qu'une fois, sans tampon intermédiaire. */
    @Test void copieUneSeuleFoisUnGrandDocumentLatin1() throws Exception {
        verifierBudget("Léa", 15);
    }

    /** Une valeur hors Latin-1 en fin de document ne provoque ni élargissement ni seconde copie du texte. */
    @Test void copieUneSeuleFoisUnGrandDocumentSuiviDUneValeurHorsLatin1() throws Exception {
        verifierBudget("Léa🚀", 25);
    }

    /** Budget moyen, en dixièmes d'octet par caractère statique, après échauffement et sur 100 rendus. */
    private void verifierBudget(String valeur, int dixiemesParCaractere) throws Exception {
        var sources = Map.of("module-info.java", "module application.verne { requires java.management; requires jdk.management; }",
                "exemple/Application.java", """
                package exemple;
                public final class Application {
                    private static volatile String temoin;
                    public static void main(String[] arguments) {
                        var compteur = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
                        if (compteur == null || !compteur.isThreadAllocatedMemorySupported()) {
                            throw new IllegalStateException("Comptage des allocations indisponible dans ce JDK de vérification.");
                        }
                        compteur.setThreadAllocatedMemoryEnabled(true);
                        for (int index = 0; index < 2_000; index++) temoin = exemple.gabarits.Bonjour.rendre("%1$s");
                        long fil = Thread.currentThread().threadId();
                        long debut = compteur.getThreadAllocatedBytes(fil);
                        for (int index = 0; index < 100; index++) temoin = exemple.gabarits.Bonjour.rendre("%1$s");
                        long moyenne = (compteur.getThreadAllocatedBytes(fil) - debut) / 100;
                        if (moyenne >= %2$dL * 65_536 / 10) throw new AssertionError("Budget d'allocations dépassé : " + moyenne);
                        if (!temoin.equals("a".repeat(65_536) + "%1$s")) throw new AssertionError("Rendu incorrect.");
                        System.out.print("budget respecté");
                    }
                }
                """.formatted(valeur, dixiemesParCaractere));
        verifier("{@java.lang.String nom}" + PREFIXE + "{nom}", sources, "budget respecté");
    }

    @Test void evalueChaqueOccurrenceUneSeuleFoisSansCollisionDeNom() throws Exception {
        String gabarit = "{@exemple.Valeur java}{@exemple.Valeur $verneValeur0}{@java.lang.String $verneResultat}"
                + PREFIXE + "{java.valeur}|{$verneValeur0.valeur}|{java.valeur}|{$verneResultat}";
        var sources = Map.of("exemple/Application.java", """
                package exemple;
                public final class Application {
                    static final StringBuilder trace = new StringBuilder();
                    public static void main(String[] arguments) {
                        String document = exemple.gabarits.Bonjour.rendre(new Valeur("A"), new Valeur("B"), "C");
                        System.out.print(document + "#" + trace);
                    }
                }
                """, "exemple/Valeur.java", """
                package exemple;
                public record Valeur(String valeur) {
                    public String valeur() { Application.trace.append(valeur); return valeur; }
                }
                """);
        verifier(gabarit, sources, PREFIXE + "A|B|A|C#ABA");
    }

    @Test void conserveLesPrimitivesEtUneValeurDepassantLeTexteStatique() throws Exception {
        String gabarit = "{@exemple.Mesure mesure}{@java.lang.String texte}" + PREFIXE
                + "{mesure.entier}|{mesure.total}|{mesure.decimal}|{mesure.actif}|{mesure.lettre}|{texte}";
        var sources = new java.util.HashMap<>(BancCompilation.application(
                "exemple.gabarits.Bonjour.rendre(new Mesure(Integer.MIN_VALUE, Long.MIN_VALUE, -0.0, true, 'é'), \"é🚀\".repeat(30_000))"));
        sources.put("exemple/Mesure.java", "package exemple; public record Mesure(int entier, long total, double decimal, boolean actif, char lettre) {}");
        verifier(gabarit, sources, PREFIXE + "-2147483648|-9223372036854775808|-0.0|true|é|" + "é🚀".repeat(30_000));
    }

    @Test void refuseUneValeurNulleSansLaConvertirEnTexte() throws Exception {
        var sources = Map.of("exemple/Application.java", """
                package exemple;
                public final class Application {
                    public static void main(String[] arguments) {
                        try { exemple.gabarits.Bonjour.rendre(new Valeur(null)); throw new AssertionError("Nullité ignorée."); }
                        catch (NullPointerException exception) {
                            if (!exception.getMessage().contains("VERNE-REN-001")) throw exception;
                            System.out.print("nullité contrôlée");
                        }
                    }
                }
                """, "exemple/Valeur.java", "package exemple; public record Valeur(String valeur) {}");
        verifier("{@exemple.Valeur valeur}" + PREFIXE + "{valeur.valeur}", sources, "nullité contrôlée");
    }

    @Test void isoleLesGrandsRendusConcurrents() throws Exception {
        var sources = Map.of("exemple/Application.java", """
                package exemple;
                public final class Application {
                    public static void main(String[] arguments) throws Exception {
                        try (var fils = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                            var taches = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                            for (int index = 0; index < 32; index++) {
                                String valeur = "é🚀" + index;
                                taches.add(fils.submit(() -> {
                                    String attendu = "a".repeat(65_536) + valeur;
                                    for (int numero = 0; numero < 16; numero++) {
                                        if (!attendu.equals(exemple.gabarits.Bonjour.rendre(valeur))) throw new AssertionError("Rendus concurrents mélangés.");
                                    }
                                }));
                            }
                            for (var tache : taches) tache.get();
                        }
                        System.out.print("concurrence vérifiée");
                    }
                }
                """);
        verifier("{@java.lang.String nom}" + PREFIXE + "{nom}", sources, "concurrence vérifiée");
    }

    @Test void compileUnGrandDocumentAtteignantLaLimiteDInterpolations() throws Exception {
        verifier("{@java.lang.String nom}" + PREFIXE + "{nom}".repeat(1_024),
                BancCompilation.application("exemple.gabarits.Bonjour.rendre(\"é🚀\")"),
                PREFIXE + "é🚀".repeat(1_024));
    }

    private void verifier(String gabarit, Map<String, String> sources, String attendu) throws Exception {
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne", gabarit), sources, true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals("", execution.erreurs()), () -> assertEquals(attendu, execution.sortie()));
    }
}
