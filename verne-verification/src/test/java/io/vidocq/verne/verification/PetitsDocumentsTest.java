package io.vidocq.verne.verification;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Tampon dimensionné et échappement direct des petits documents ; allocations mesurées sans seuil de durée. */
@Timeout(30)
class PetitsDocumentsTest {
    @TempDir Path repertoire;
    private static final String FICHE = "{@exemple.Fiche f}<article class=\"fiche\" data-ref=\"{f.reference}\">\n<h1>{f.titre}</h1>\n"
            + "<p class=\"auteur\" title=\"{f.auteur}\">Par {f.auteur}</p>\n<p>{f.resume}</p>\n"
            + "<ul><li>{f.motCle1}</li><li>{f.motCle2}</li><li>{f.motCle3}</li></ul>\n</article>\n";

    /** Un seul tampon à la bonne taille : pas d'agrandissements successifs pour une fiche Latin-1. */
    @Test void dimensionneLeTamponDUnePetiteFicheTexte() throws Exception {
        verifierBudget("texte", false, 25);
    }

    /** Les valeurs échappées sont écrites dans le tampon du document, sans chaîne intermédiaire. */
    @Test void echappeDirectementDansLeTamponDUnePetiteFicheHtml() throws Exception {
        verifierBudget("html", true, 45);
    }

    @Test void evalueChaqueValeurUneSeuleFoisDansLOrdreDuGabarit() throws Exception {
        var sources = Map.of("exemple/Application.java", """
                package exemple;
                public final class Application {
                    static final StringBuilder trace = new StringBuilder();
                    public static void main(String[] arguments) {
                        String document = exemple.gabarits.Bonjour.rendre(new Valeur("A"), new Valeur("<B>"));
                        System.out.print(document + "#" + trace);
                    }
                }
                """, "exemple/Valeur.java", """
                package exemple;
                public record Valeur(String valeur) {
                    public String valeur() { Application.trace.append(valeur); return valeur; }
                }
                """);
        String gabarit = "{@exemple.Valeur a}{@exemple.Valeur b}{a.valeur}|{b.valeur}|{a.valeur}";
        assertEquals("A|<B>|A#A<B>A", rendre("texte", gabarit, sources));
        assertEquals("A|&lt;B&gt;|A#A<B>A", rendre("html", gabarit, sources));
    }

    private void verifierBudget(String profil, boolean speciaux, int dixiemesParCaractere) throws Exception {
        var sources = new HashMap<String, String>();
        sources.put("module-info.java", "module application.verne { requires java.management; requires jdk.management; }");
        sources.put("exemple/Fiche.java", "package exemple; public record Fiche(String reference, String titre, String auteur,"
                + " String resume, String motCle1, String motCle2, String motCle3) {}");
        sources.put("exemple/Application.java", """
                package exemple;
                public final class Application {
                    private static volatile String temoin;
                    private static String valeur(int longueur, int graine) {
                        var resultat = new StringBuilder();
                        String speciaux = "&<>'" + '"';
                        for (int i = 0; i < longueur; i++) {
                            resultat.append(%1$s && i %% 10 == 9 ? speciaux.charAt((i / 10 + graine) %% 5) : (char) ('a' + (i + graine) %% 26));
                        }
                        return resultat.toString();
                    }
                    public static void main(String[] arguments) {
                        var fiches = new Fiche[8];
                        for (int g = 0; g < 8; g++) {
                            fiches[g] = new Fiche(valeur(10, g), valeur(40, g), valeur(20, g), valeur(400, g),
                                    valeur(12, g), valeur(12, g), valeur(12, g));
                        }
                        var compteur = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
                        if (compteur == null || !compteur.isThreadAllocatedMemorySupported()) {
                            throw new IllegalStateException("Comptage des allocations indisponible dans ce JDK de vérification.");
                        }
                        compteur.setThreadAllocatedMemoryEnabled(true);
                        for (int index = 0; index < 20_000; index++) temoin = exemple.gabarits.Bonjour.rendre(fiches[index & 7]);
                        long longueur = 0;
                        for (var fiche : fiches) longueur += exemple.gabarits.Bonjour.rendre(fiche).length();
                        long fil = Thread.currentThread().threadId();
                        long debut = compteur.getThreadAllocatedBytes(fil);
                        for (int index = 0; index < 800; index++) temoin = exemple.gabarits.Bonjour.rendre(fiches[index & 7]);
                        long moyenne = (compteur.getThreadAllocatedBytes(fil) - debut) / 800;
                        long limite = %2$dL * longueur / 8 / 10;
                        if (moyenne >= limite) throw new AssertionError("Budget d'allocations dépassé : " + moyenne + " >= " + limite);
                        System.out.print("budget respecté");
                    }
                }
                """.formatted(speciaux, dixiemesParCaractere));
        assertEquals("budget respecté", rendre(profil, FICHE, sources));
    }

    private String rendre(String profil, String gabarit, Map<String, String> sources) throws Exception {
        var compilation = BancCompilation.compiler(repertoire.resolve(profil), Map.of("bonjour.verne", gabarit), sources,
                true, repertoire.resolve(profil + "/gabarits"), BancCompilation.chargementVerne("-Averne.profil.sortie=" + profil));
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals("", execution.erreurs()));
        return execution.sortie();
    }
}
