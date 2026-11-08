package io.vidocq.verne.verification;

import jakarta.json.Json;
import java.io.StringReader;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.CodeAttribute;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** Expressions (VERNE-009) : cas T-EXP-01 et T-EXP-03 du contrat expressions.adoc, limites, profils et budget. */
@Timeout(60)
class ExpressionsTest {
    @TempDir Path repertoire;
    private static final String DECLARATION = "{@exemple.Ligne l}";
    /** Modèle des cas du contrat ; compteur() compte ses appels pour vérifier les courts-circuits. */
    private static final Map<String, String> MODELE = Map.of("exemple/Ligne.java", """
            package exemple;
            public record Ligne(int quantite, long stock, double prix, Integer remise, boolean active, Boolean livree,
                    String statut, String autre, char code, int zero, int minimum, int compteur, java.util.List<String> liste) {
                public static final java.util.concurrent.atomic.AtomicInteger APPELS = new java.util.concurrent.atomic.AtomicInteger();
                public int compteur() { APPELS.incrementAndGet(); return 1; }
                public static Ligne exemple() {
                    return new Ligne(3, 10L, 1.5, 4, true, Boolean.TRUE, new String("payé"), "x", 'c', 0, Integer.MIN_VALUE, 1,
                            java.util.List.of("a"));
                }
                public Ligne quantite(int v) { return new Ligne(v, stock, prix, remise, active, livree, statut, autre, code, zero, minimum, compteur, liste); }
                public Ligne stock(long v) { return new Ligne(quantite, v, prix, remise, active, livree, statut, autre, code, zero, minimum, compteur, liste); }
                public Ligne prix(double v) { return new Ligne(quantite, stock, v, remise, active, livree, statut, autre, code, zero, minimum, compteur, liste); }
                public Ligne remise(Integer v) { return new Ligne(quantite, stock, prix, v, active, livree, statut, autre, code, zero, minimum, compteur, liste); }
                public Ligne active(boolean v) { return new Ligne(quantite, stock, prix, remise, v, livree, statut, autre, code, zero, minimum, compteur, liste); }
                public Ligne livree(Boolean v) { return new Ligne(quantite, stock, prix, remise, active, v, statut, autre, code, zero, minimum, compteur, liste); }
                public Ligne statut(String v) { return new Ligne(quantite, stock, prix, remise, active, livree, v, autre, code, zero, minimum, compteur, liste); }
                public Ligne autre(String v) { return new Ligne(quantite, stock, prix, remise, active, livree, statut, v, code, zero, minimum, compteur, liste); }
            }
            """);

    static Stream<Arguments> rendus() {
        return Stream.of(
                // T-EXP-01 : précédence, associativité, promotion, négation, courts-circuits, égalité de contenu.
                Arguments.of("{1 + 2 * 3}", "", "7|0"),
                Arguments.of("{(1 + 2) * 3}", "", "9|0"),
                Arguments.of("{10 - 4 - 3}", "", "3|0"),
                Arguments.of("{-l.quantite * 2}", "", "-6|0"),
                Arguments.of("{7 / 2} {7 % 3} {-7 / 2} {-7 % 3}", "", "3 1 -3 -1|0"),
                Arguments.of("{l.quantite * l.prix}", "", "4.5|0"),
                Arguments.of("{l.quantite < l.stock}", "", "true|0"),
                Arguments.of("{#if !l.active}A{#else}B{/if}", "", "B|0"),
                Arguments.of("{#if l.active || l.compteur > 0}R{/if}", "", "R|0"),
                Arguments.of("{#if !l.active && l.compteur > 0}R{/if}", "", "|0"),
                Arguments.of("{#if l.active && l.compteur > 0}R{/if}", "", "R|1"),
                Arguments.of("{#if l.statut == \"payé\"}R{/if}", "", "R|0"),
                Arguments.of("{(1 < 2) == true}", "", "true|0"),
                Arguments.of("{#if l.statut == \"}\"}x{/if}", ".statut(\"}\")", "x|0"),
                Arguments.of("{#if l.active && l.livree}x{/if}", ".active(false).livree(null)", "|0"),
                Arguments.of("{#if l.active && l.statut == \"x\"}x{/if}", ".active(false).statut(null)", "|0"),
                Arguments.of("{#if !!l.active}x{/if}{- -l.quantite}", "", "x3|0"),
                Arguments.of("{l.code == l.code} {l.active != l.active} {l.autre != \"y\"}", "", "true false true|0"),
                // T-EXP-03 : politique numérique.
                Arguments.of("{0} {-0} {0.0} {-0.0}", "", "0 0 0.0 -0.0|0"),
                Arguments.of("{2147483647}", "", "2147483647|0"),
                Arguments.of("{2147483648L} {9223372036854775807L}", "", "2147483648 9223372036854775807|0"),
                Arguments.of("{-2147483648L / -1}", "", "2147483648|0"),
                Arguments.of("{0.1 + 0.2}", "", "0.30000000000000004|0"),
                Arguments.of("{l.remise + 1}", "", "5|0"),
                Arguments.of("{l.quantite == l.prix}", ".prix(3.0)", "true|0"),
                Arguments.of("{1.5}", "", "1.5|0"),
                Arguments.of("{17976931348623157" + "0".repeat(292) + ".0}", "", "1.7976931348623157E308|0"),
                Arguments.of("{0." + "0".repeat(319) + "1}", "", "1.0E-320|0"),
                Arguments.of("{0.000}", "", "0.0|0"),
                Arguments.of("{1.0 / 0.0}", "", "Infinity|0"),
                // D-006d : enveloppes interpolées.
                Arguments.of("{l.remise} {l.livree}", "", "4 true|0"),
                Arguments.of("{\"a\\\"b\\\\c\\td\"}", "", "a\"b\\c\td|0"));
    }

    @ParameterizedTest
    @MethodSource("rendus")
    void rendLesCasDuContrat(String gabarit, String donnees, String attendu) throws Exception {
        assertEquals(attendu, rendre("texte", gabarit, donnees));
    }

    static Stream<Arguments> echecsAuRendu() {
        return Stream.of(
                Arguments.of("{2147483647 + 1}", "", "VERNE-REN-004"),
                Arguments.of("{l.quantite * l.quantite}", ".quantite(50000)", "VERNE-REN-004"),
                Arguments.of("{l.stock * l.stock}", ".stock(4000000000L)", "VERNE-REN-004"),
                Arguments.of("{l.quantite / l.zero}", "", "VERNE-REN-004"),
                Arguments.of("{l.quantite % l.zero}", "", "VERNE-REN-004"),
                Arguments.of("{-l.minimum}", "", "VERNE-REN-004"),
                Arguments.of("{l.minimum / -1}", "", "VERNE-REN-004"),
                Arguments.of("{l.minimum - 1}", "", "VERNE-REN-004"),
                Arguments.of("{#if l.livree && l.active}x{/if}", ".livree(null)", "VERNE-REN-001"),
                Arguments.of("{#if l.statut == \"payé\"}x{/if}", ".statut(null)", "VERNE-REN-001"),
                Arguments.of("{#if \"payé\" != l.statut}x{/if}", ".statut(null)", "VERNE-REN-001"),
                Arguments.of("{#if l.statut == l.autre}x{/if}", ".statut(null).autre(null)", "VERNE-REN-001"),
                Arguments.of("{l.statut}", ".statut(null)", "VERNE-REN-001"),
                Arguments.of("{l.remise + 1}", ".remise(null)", "VERNE-REN-001"),
                Arguments.of("{l.remise}", ".remise(null)", "VERNE-REN-001"));
    }

    @ParameterizedTest
    @MethodSource("echecsAuRendu")
    void echoueAuRenduSansSortie(String gabarit, String donnees, String code) throws Exception {
        var execution = executer("texte", gabarit, donnees);
        assertAll(() -> assertNotEquals(0, execution.codeSortie()),
                () -> assertTrue(execution.erreurs().contains(code), execution.erreurs()),
                () -> assertEquals("", execution.sortie()));
    }

    @Test void refuseAuRenduUnFlottantNonFiniHorsLitteralJson() throws Exception {
        var execution = executer("json", "[{1.0 / 0.0}]", "");
        assertAll(() -> assertNotEquals(0, execution.codeSortie()),
                () -> assertTrue(execution.erreurs().contains("VERNE-REN-003"), execution.erreurs()));
    }

    static Stream<Arguments> refus() {
        return Stream.of(
                Arguments.of("§{1 < 2 < 3}", "VERNE-SYN-002"),
                Arguments.of("§{true == true == true}", "VERNE-SYN-002"),
                Arguments.of("§{#if l.quantite}x{/if}", "VERNE-TYP-006"),
                Arguments.of("§{#if l.quantite + 1}x{/if}", "VERNE-TYP-006"),
                Arguments.of("§{l.statut + \"x\"}", "VERNE-TYP-008"),
                Arguments.of("§{l.code + 1}", "VERNE-TYP-008"),
                Arguments.of("§{l.statut < \"m\"}", "VERNE-TYP-008"),
                Arguments.of("§{l.active < l.active}", "VERNE-TYP-008"),
                Arguments.of("§{l.quantite == l.statut}", "VERNE-TYP-008"),
                Arguments.of("§{(!l.quantite)}", "VERNE-TYP-008"),
                Arguments.of("§{-l.active}", "VERNE-TYP-008"),
                Arguments.of("§{l.active && 1}", "VERNE-TYP-008"),
                Arguments.of("§{l.liste == l.liste}", "VERNE-TYP-008"),
                Arguments.of("§{l.liste}", "VERNE-TYP-004"),
                Arguments.of("§{#for a in l.liste && true}{/for}", "VERNE-SYN-002"),
                Arguments.of("§{2147483648}", "VERNE-SYN-002"),
                Arguments.of("§{9223372036854775808L}", "VERNE-SYN-002"),
                Arguments.of("§{.5}", "VERNE-SYN-002"),
                Arguments.of("§{5.}", "VERNE-SYN-002"),
                Arguments.of("§{007}", "VERNE-SYN-002"),
                Arguments.of("§{1_000}", "VERNE-SYN-002"),
                Arguments.of("§{0x10}", "VERNE-SYN-002"),
                Arguments.of("§{1e3}", "VERNE-SYN-002"),
                Arguments.of("§{1.5L}", "VERNE-SYN-002"),
                Arguments.of("§{" + "9".repeat(400) + ".0}", "VERNE-SYN-002"),
                Arguments.of("§{0." + "0".repeat(400) + "1}", "VERNE-SYN-002"),
                Arguments.of("§{-" + "9".repeat(400) + ".0}", "VERNE-SYN-002"),
                Arguments.of("§{\"a\\qb\"}", "VERNE-SYN-002"),
                Arguments.of("§{l.active & l.active}", "VERNE-SYN-002"),
                Arguments.of("§{l.quantite = 1}", "VERNE-SYN-002"),
                Arguments.of("§{(1 + 2}", "VERNE-SYN-002"),
                Arguments.of("§{1 + }", "VERNE-SYN-002"),
                Arguments.of("§{l.inconnu + 1}", "VERNE-TYP-001"),
                Arguments.of("§{\"abc}", "VERNE-SYN-001"));
    }

    @ParameterizedTest
    @MethodSource("refus")
    void refuseALaCompilation(String gabarit, String code) throws Exception {
        verifierRefus("texte", gabarit, code);
    }

    /** {! ouvre toujours un commentaire : une négation en tête d'interpolation s'écrit entre parenthèses. */
    @Test void gardeLaSyntaxeDesCommentairesDevantUneNegation() throws Exception {
        // Refus d'abord : verifierRefus contrôle l'absence de source générée dans le même répertoire.
        verifierRefus("texte", "§{!l.active}", "VERNE-SYN-001");
        assertEquals("false|0", rendre("texte", "{(!l.active)}", ""));
        assertEquals("|0", rendre("texte", "{! commentaire !}", ""));
    }

    @Test void recommandeLaJuxtapositionPourLaConcatenation() throws Exception {
        var compilation = compiler("texte", DECLARATION + "{l.statut + \"x\"}");
        assertTrue(compilation.diagnostics().contains("juxtaposer"), compilation.diagnostics());
    }

    @Test void borneLesOperateursEtLesParentheses() throws Exception {
        verifierRefus("texte", "§{1" + " + 1".repeat(65) + "}", "VERNE-LIM-010");
        assertEquals("65|0", rendre("texte", "{1" + " + 1".repeat(64) + "}", ""));
    }

    @Test void borneLesNiveauxDeParentheses() throws Exception {
        verifierRefus("texte", "§{" + "(".repeat(33) + "1" + ")".repeat(33) + "}", "VERNE-LIM-010");
        assertEquals("1|0", rendre("texte", "{" + "(".repeat(32) + "1" + ")".repeat(32) + "}", ""));
    }

    /** R1 de la revue : un littéral de chaîne reste sous la limite d'une constante Java, contrôlé par Verne. */
    @Test void borneLaLongueurDesLitterauxDeChaine() throws Exception {
        verifierRefus("texte", "§{\"" + "a".repeat(4_001) + "\"}", "VERNE-LIM-011");
        verifierRefus("texte", "§{\"" + "a".repeat(65_536) + "\"}", "VERNE-LIM-011");
        verifierRefus("texte", "§{\"" + "漢".repeat(4_001) + "\" == l.statut}", "VERNE-LIM-011");
        assertEquals("a".repeat(4_000) + "|0", rendre("texte", "{\"" + "a".repeat(4_000) + "\"}", ""));
        assertEquals("漢".repeat(4_000) + "|0", rendre("texte", "{\"" + "漢".repeat(4_000) + "\"}", ""));
        assertEquals("🚀".repeat(2_000) + "|0", rendre("texte", "{\"" + "🚀".repeat(2_000) + "\"}", ""));
    }

    @Test void echappeLesChainesEtPasLesNombresEnHtmlEtXml() throws Exception {
        assertEquals("<p title=\"true\">&lt;b&gt; 6</p><i>x</i>|0", rendre("html",
                "<p title=\"{l.statut == \"payé\"}\">{\"<b>\"} {l.quantite * 2}</p>{#if l.quantite < 5 && l.active}<i>x</i>{/if}", ""));
        assertEquals("<a n=\"&amp;\">true</a>|0", rendre("xml", "<a n=\"{\"&\"}\">{l.quantite > 1}</a>", ""));
    }

    @Test void produitUnDocumentJsonRelisible() throws Exception {
        verifierRefus("json", "[§{\"x\"}]", "VERNE-SEC-002");
        String sortie = rendre("json", "\\{\"total\": {l.quantite * l.prix}, \"nom\": \"{\"é\\\"\"}\", \"ok\": {(!l.active)}}", "");
        var objet = Json.createReader(new StringReader(sortie.substring(0, sortie.lastIndexOf('|')))).readObject();
        assertAll(() -> assertEquals(4.5, objet.getJsonNumber("total").doubleValue()),
                () -> assertEquals("é\"", objet.getString("nom")),
                () -> assertFalse(objet.getBoolean("ok")));
    }

    @Test void compteLesExpressionsDansLeBudgetDeGeneration() throws Exception {
        // 128 + 12 (paramètre) + 12 (fragment) + 393 × 152 + 2 × 56 = 60 000 unités. Une interpolation
        // {l.quantite * l.quantite + 1} vaut 48 + 2 × 16 (accès) + 16 (second chemin) + 2 × 24 (opérateurs) + 8 (littéral).
        String debut = DECLARATION + "x" + "{l.quantite * l.quantite + 1}".repeat(393);
        var compilation = compiler("html", debut + "{1}{1}");
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var modele = ClassFile.of().parse(compilation.classes().resolve("exemple/gabarits/Bonjour.class"));
        var methode = modele.methods().stream().filter(candidat -> candidat.methodName().equalsString("rendre"))
                .findFirst().orElseThrow();
        var code = (CodeAttribute) methode.code().orElseThrow();
        assertTrue(code.codeLength() <= 60_000, "Taille observée de la méthode : " + code.codeLength());
        System.out.println("Taille observée de la méthode de rendu avec expressions : " + code.codeLength() + " octets.");
        var depasse = compiler("html", debut + "{1}{1 + 1}");
        assertAll(() -> assertFalse(depasse.reussie()),
                () -> assertTrue(depasse.diagnostics().contains("VERNE-LIM-008"), depasse.diagnostics()));
    }

    @Test void isoleLesRendusConcurrents() throws Exception {
        var sources = new HashMap<>(MODELE);
        sources.put("exemple/Application.java", """
                package exemple;
                public final class Application {
                    public static void main(String[] arguments) throws Exception {
                        try (var fils = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                            var taches = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                            for (int index = 0; index < 32; index++) {
                                var ligne = Ligne.exemple().quantite(index);
                                String attendu = (index * 2 + 1) + " " + (index < 16);
                                taches.add(fils.submit(() -> {
                                    for (int tour = 0; tour < 64; tour++) {
                                        if (!attendu.equals(exemple.gabarits.Bonjour.rendre(ligne))) throw new AssertionError("Rendus mélangés.");
                                    }
                                }));
                            }
                            for (var tache : taches) tache.get();
                        }
                        System.out.print("concurrence vérifiée");
                    }
                }
                """);
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne",
                DECLARATION + "{l.quantite * 2 + 1} {l.quantite < 16}"), sources, true,
                repertoire.resolve("gabarits"), BancCompilation.chargementVerne("-Averne.profil.sortie=texte"));
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals("concurrence vérifiée", execution.sortie()));
    }

    /** Le repère § marque la directive attendue dans le diagnostic ; il est retiré du gabarit compilé. */
    private void verifierRefus(String profil, String gabaritMarque, String code) throws Exception {
        int colonne = DECLARATION.length() + gabaritMarque.indexOf('§') + 1;
        var compilation = compiler(profil, DECLARATION + gabaritMarque.replace("§", ""));
        assertAll(() -> assertFalse(compilation.reussie()),
                () -> assertTrue(compilation.diagnostics().contains(code + " : bonjour.verne:1:" + colonne), compilation.diagnostics()),
                () -> assertFalse(Files.exists(compilation.sourcesGenerees().resolve("exemple/gabarits/Bonjour.java"))));
    }

    private BancCompilation.Compilation compiler(String profil, String gabarit) throws Exception {
        var sources = new HashMap<>(MODELE);
        sources.putAll(BancCompilation.application("\"témoin\""));
        return BancCompilation.compiler(repertoire, Map.of("bonjour.verne", gabarit), sources, true,
                repertoire.resolve("gabarits"), BancCompilation.chargementVerne("-Averne.profil.sortie=" + profil));
    }

    /** Rendu suivi du nombre d'appels à compteur(), séparés par une barre verticale. */
    private BancCompilation.Execution executer(String profil, String gabarit, String donnees) throws Exception {
        var sources = new HashMap<>(MODELE);
        sources.putAll(BancCompilation.application("exemple.gabarits.Bonjour.rendre(exemple.Ligne.exemple()" + donnees
                + ") + \"|\" + exemple.Ligne.APPELS.get()"));
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne", DECLARATION + gabarit), sources, true,
                repertoire.resolve("gabarits"), BancCompilation.chargementVerne("-Averne.profil.sortie=" + profil));
        assertTrue(compilation.reussie(), compilation.diagnostics());
        return BancCompilation.executer(compilation);
    }

    private String rendre(String profil, String gabarit, String donnees) throws Exception {
        var execution = executer(profil, gabarit, donnees);
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        return execution.sortie();
    }
}
