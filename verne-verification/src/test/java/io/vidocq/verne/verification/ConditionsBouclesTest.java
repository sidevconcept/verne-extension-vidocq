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
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Conditions et boucles (VERNE-008 ; T-CTL-01 à 04, 06 et 07 partiels) : structure, typage, portée, rendu et profils. */
@Timeout(60)
class ConditionsBouclesTest {
    @TempDir Path repertoire;
    private static final String DECLARATION = "{@exemple.Commande c}";
    /** Modèle commun ; les méthodes statiques et d'instance ne sont pas des composants, donc invisibles des gabarits. */
    private static final Map<String, String> MODELE = Map.of(
            "exemple/Article.java", "package exemple; public record Article(String nom, boolean actif) {}",
            "exemple/Commande.java", """
                    package exemple;
                    import java.util.*;
                    public record Commande(boolean payee, Boolean livree, String client, int nombre, Article article,
                            List<Article> articles, Article[] tableau, int[] quantites, boolean[] drapeaux, Set<String> etiquettes,
                            Iterable<Article> flux, List<? extends Article> rayon, List<?> divers) {
                        public static Commande exemple(boolean payee) {
                            return new Commande(payee, Boolean.TRUE, "Zoé", 3, null,
                                    List.of(new Article("x", true), new Article("y", false), new Article("z", true)),
                                    new Article[] {new Article("t", true)}, new int[] {1, 2}, new boolean[] {true, false},
                                    new LinkedHashSet<>(List.of("b", "a", "c")), List.of(), List.of(new Article("r", true)), List.of(1));
                        }
                        public Commande avecArticles(List<Article> nouveaux) {
                            return new Commande(payee, livree, client, nombre, article, nouveaux, tableau, quantites, drapeaux,
                                    etiquettes, flux, rayon, divers);
                        }
                        public Commande avecLivree(Boolean valeur) {
                            return new Commande(payee, valeur, client, nombre, article, articles, tableau, quantites, drapeaux,
                                    etiquettes, flux, rayon, divers);
                        }
                        public Commande avecFlux(Iterable<Article> valeur) {
                            return new Commande(payee, livree, client, nombre, article, articles, tableau, quantites, drapeaux,
                                    etiquettes, valeur, rayon, divers);
                        }
                    }
                    """);

    /** Record générique navigué à travers des jokers bornés supérieurement ou inférieurement. */
    private static final Map<String, String> MODELE_JOKERS = Map.of(
            "exemple/Boite.java", "package exemple; public record Boite<T>(T valeur) {}",
            "exemple/Enveloppe.java", "package exemple; public record Enveloppe(Boite<? extends java.util.List<String>> liste,"
                    + " Boite<? extends Boolean> drapeau, Boite<? extends String> texte, Boite<? super Boolean> large) {}");

    @Test void rendSeulementLaBrancheChoisie() throws Exception {
        String gabarit = DECLARATION + "{#if c.payee}payée{#else}attente{/if}|{#if c.payee}seule{/if}|"
                + "{#if c.livree}livrée{/if}";
        assertEquals("payée|seule|livrée", rendre("texte", gabarit, "exemple.gabarits.Bonjour.rendre(exemple.Commande.exemple(true))"));
        assertEquals("attente||livrée", rendre("texte", gabarit, "exemple.gabarits.Bonjour.rendre(exemple.Commande.exemple(false))"));
    }

    @Test void nEvaluePasLaBrancheNonChoisie() throws Exception {
        // article est nul : sa navigation lèverait VERNE-REN-001 si la branche était évaluée.
        String gabarit = DECLARATION + "{#if c.payee}{c.article.nom}{#else}rien{/if}";
        assertEquals("rien", rendre("texte", gabarit, "exemple.gabarits.Bonjour.rendre(exemple.Commande.exemple(false))"));
    }

    @Test void accepteUnParametreBooleanEtImbriqueLesConditions() throws Exception {
        String gabarit = "{@java.lang.Boolean b}{@exemple.Commande c}{#if b}{#if c.payee}11{#else}10{/if}{#else}0{/if}";
        assertEquals("10", rendre("texte", gabarit,
                "exemple.gabarits.Bonjour.rendre(Boolean.TRUE, exemple.Commande.exemple(false))"));
    }

    @Test void refuseAuRenduUneConditionBooleanNulle() throws Exception {
        var execution = executer(DECLARATION + "{#if c.livree}oui{/if}",
                "exemple.gabarits.Bonjour.rendre(exemple.Commande.exemple(true).avecLivree(null))");
        assertAll(() -> assertNotEquals(0, execution.codeSortie()),
                () -> assertTrue(execution.erreurs().contains("VERNE-REN-001"), execution.erreurs()),
                () -> assertEquals("", execution.sortie()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"c.client", "c.nombre", "c.article", "c.articles"})
    void refuseUneConditionNonBooleenne(String chemin) throws Exception {
        verifierRefus("texte", "§{#if " + chemin + "}x{/if}", "VERNE-TYP-006");
    }

    @Test void parcourtTableauxCollectionsEtEnsemblesDansLOrdre() throws Exception {
        String gabarit = DECLARATION + "{#for a in c.articles}[{a.nom}]{/for}|{#for q in c.quantites}{q},{/for}|"
                + "{#for e in c.etiquettes}{e};{/for}|{#for a in c.tableau}{a.nom}{/for}|"
                + "{#for b in c.drapeaux}{#if b}V{#else}F{/if}{/for}|{#for a in c.rayon}{a.nom}{/for}|{#for a in c.flux}?{/for}";
        assertEquals("[x][y][z]|1,2,|b;a;c;|t|VF|r|", rendre("texte", gabarit,
                "exemple.gabarits.Bonjour.rendre(exemple.Commande.exemple(true))"));
    }

    @Test void repeteZeroUneOuTroisFois() throws Exception {
        String gabarit = DECLARATION + "{#for a in c.articles}-{a.nom}{/for}.";
        String base = "exemple.gabarits.Bonjour.rendre(exemple.Commande.exemple(true).avecArticles(java.util.List.of(%s)))";
        assertEquals(".", rendre("texte", gabarit, base.formatted("")));
        assertEquals("-u.", rendre("texte", gabarit, base.formatted("new exemple.Article(\"u\", true)")));
        assertEquals("-u-v-w.", rendre("texte", gabarit, base.formatted(
                "new exemple.Article(\"u\", true), new exemple.Article(\"v\", true), new exemple.Article(\"w\", true)")));
    }

    @Test void isoleLesPorteesDesBouclesImbriquees() throws Exception {
        String gabarit = DECLARATION + "{#for a in c.articles}{#for b in c.articles}{a.nom}{b.nom} {/for}{/for}|"
                + "{#for a in c.articles}{#if a.actif}{a.nom}{/if}{/for}|{#for a in c.tableau}{a.nom}{/for}";
        assertEquals("xx xy xz yx yy yz zx zy zz |xz|t", rendre("texte", gabarit,
                "exemple.gabarits.Bonjour.rendre(exemple.Commande.exemple(true))"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"§{#for c in c.articles}{/for}",
            "{#for a in c.articles}§{#for a in c.tableau}{/for}{/for}"})
    void refuseUnNomDeVariableDejaUtilise(String gabarit) throws Exception {
        verifierRefus("texte", gabarit, "VERNE-TYP-002");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{#for a in c.articles}{/for}§{a.nom}", "{#if c.payee}{#for a in c.articles}{/for}§{a}{/if}",
            "§{#for a in a.articles}{/for}"})
    void refuseUneVariableHorsDeSaPortee(String gabarit) throws Exception {
        verifierRefus("texte", gabarit, "VERNE-TYP-001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"c.client", "c.article", "c.nombre", "c.payee"})
    void refuseUneBoucleSurUneValeurNonIterable(String chemin) throws Exception {
        verifierRefus("texte", "§{#for x in " + chemin + "}{/for}", "VERNE-TYP-007");
    }

    @Test void typeUnElementSansBorneCommeObjet() throws Exception {
        verifierRefus("texte", "{#for x in c.divers}§{x}{/for}", "VERNE-TYP-004");
    }

    /** R1 de la revue : la borne supérieure d'un joker obtenu par navigation est celle du type utilisé. */
    @Test void utiliseLaBorneDUnJokerObtenuParNavigation() throws Exception {
        var sources = new HashMap<>(MODELE_JOKERS);
        sources.putAll(BancCompilation.application("exemple.gabarits.Bonjour.rendre(new exemple.Enveloppe("
                + "new exemple.Boite<>(java.util.List.of(\"a\", \"b\")), new exemple.Boite<>(true), new exemple.Boite<>(\"<t>\"),"
                + " new exemple.Boite<>(true)))"));
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne", "{@exemple.Enveloppe e}"
                + "{#for v in e.liste.valeur}{v}{/for}{#if e.drapeau.valeur}oui{/if}{e.texte.valeur}"), sources, true,
                repertoire.resolve("gabarits"), BancCompilation.chargementVerne("-Averne.profil.sortie=html"));
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals("aboui&lt;t&gt;", execution.sortie()));
    }

    /** Un joker inférieur n'a pas de borne booléenne : la condition reste refusée. */
    @Test void refuseUneConditionDerriereUnJokerInferieur() throws Exception {
        var sources = new HashMap<>(MODELE_JOKERS);
        sources.putAll(BancCompilation.application("\"témoin\""));
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne",
                "{@exemple.Enveloppe e}{#if e.large.valeur}oui{/if}"), sources, true, repertoire.resolve("gabarits"),
                BancCompilation.chargementVerne("-Averne.profil.sortie=texte"));
        assertAll(() -> assertFalse(compilation.reussie()),
                () -> assertTrue(compilation.diagnostics().contains("VERNE-TYP-006 : bonjour.verne:1:23"), compilation.diagnostics()));
    }

    @Test void refuseAuRenduUneCollectionNulle() throws Exception {
        var execution = executer(DECLARATION + "{#for a in c.articles}-{/for}",
                "exemple.gabarits.Bonjour.rendre(exemple.Commande.exemple(true).avecArticles(null))");
        assertAll(() -> assertNotEquals(0, execution.codeSortie()),
                () -> assertTrue(execution.erreurs().contains("VERNE-REN-001"), execution.erreurs()));
    }

    @Test void neControleUnElementNulQueSIlEstUtilise() throws Exception {
        String donnees = "exemple.Commande.exemple(true).avecArticles(java.util.Arrays.asList(null, new exemple.Article(\"u\", true)))";
        assertEquals("--", rendre("texte", DECLARATION + "{#for a in c.articles}-{/for}",
                "exemple.gabarits.Bonjour.rendre(" + donnees + ")"));
        var execution = executer(DECLARATION + "{#for a in c.articles}{a.nom}{/for}", "exemple.gabarits.Bonjour.rendre(" + donnees + ")");
        assertAll(() -> assertNotEquals(0, execution.codeSortie()),
                () -> assertTrue(execution.erreurs().contains("VERNE-REN-001"), execution.erreurs()));
    }

    @Test void appelleUneSeuleFoisLIterateurEtPropageSesErreurs() throws Exception {
        // Un second appel à iterator() échouerait : la boucle ne doit ni copier ni reparcourir la collection.
        String unique = "exemple.gabarits.Bonjour.rendre(exemple.Commande.exemple(true).avecFlux(new Iterable<exemple.Article>() {"
                + " boolean consomme; public java.util.Iterator<exemple.Article> iterator() {"
                + " if (consomme) throw new IllegalStateException(\"second parcours\"); consomme = true;"
                + " return java.util.List.of(new exemple.Article(\"u\", true), new exemple.Article(\"v\", true)).iterator(); } }))";
        assertEquals("uv", rendre("texte", DECLARATION + "{#for a in c.flux}{a.nom}{/for}", unique));
        String defaillant = "exemple.gabarits.Bonjour.rendre(exemple.Commande.exemple(true).avecFlux(() -> new java.util.Iterator<exemple.Article>() {"
                + " int n; public boolean hasNext() { return true; }"
                + " public exemple.Article next() { if (n++ == 1) throw new IllegalStateException(\"panne simulée\");"
                + " return new exemple.Article(\"u\", true); } }))";
        var execution = executer(DECLARATION + "{#for a in c.flux}{a.nom}{/for}", defaillant);
        assertAll(() -> assertNotEquals(0, execution.codeSortie()),
                () -> assertTrue(execution.erreurs().contains("panne simulée"), execution.erreurs()),
                () -> assertEquals("", execution.sortie()));
    }

    static Stream<Arguments> structuresInvalides() {
        return Stream.of(
                Arguments.of("§{#else}", "VERNE-SYN-005"),
                Arguments.of("{#if c.payee}{#else}§{#else}{/if}", "VERNE-SYN-005"),
                Arguments.of("{#for a in c.articles}§{#else}{/for}", "VERNE-SYN-005"),
                Arguments.of("§{/if}", "VERNE-SYN-005"),
                Arguments.of("§{/for}", "VERNE-SYN-005"),
                Arguments.of("{#if c.payee}§{/for}", "VERNE-SYN-005"),
                Arguments.of("{#for a in c.articles}{#if c.payee}§{/for}{/if}", "VERNE-SYN-005"),
                Arguments.of("§{#if c.payee}x", "VERNE-SYN-005"),
                Arguments.of("{#if c.payee}{/if}§{#for a in c.articles}{#if c.payee}{/if}", "VERNE-SYN-005"),
                // La structure est vérifiée avant le typage.
                Arguments.of("{#if c.inconnu}§{/for}", "VERNE-SYN-005"),
                Arguments.of("§{#while c.payee}{/while}", "VERNE-SYN-002"),
                Arguments.of("§{#if}{/if}", "VERNE-SYN-002"),
                Arguments.of("§{#If c.payee}{/if}", "VERNE-SYN-002"),
                Arguments.of("§{#if c.payee c.payee}{/if}", "VERNE-SYN-002"),
                Arguments.of("{#if c.payee}§{#else c.payee}{/if}", "VERNE-SYN-002"),
                Arguments.of("{#if c.payee}§{/if c.payee}", "VERNE-SYN-002"),
                Arguments.of("§{#for a c.articles}{/for}", "VERNE-SYN-002"),
                Arguments.of("§{#for a in}{/for}", "VERNE-SYN-002"),
                Arguments.of("§{#for 1a in c.articles}{/for}", "VERNE-SYN-002"),
                Arguments.of("§{#for class in c.articles}{/for}", "VERNE-SYN-002"));
    }

    @ParameterizedTest
    @MethodSource("structuresInvalides")
    void refuseUneStructureInvalide(String gabarit, String code) throws Exception {
        verifierRefus("texte", gabarit, code);
    }

    @Test void echappeLesValeursDesBlocsEnHtmlEtXml() throws Exception {
        String donnees = "exemple.gabarits.Bonjour.rendre(exemple.Commande.exemple(true).avecArticles(java.util.List.of("
                + "new exemple.Article(\"<b>&\", true), new exemple.Article(\"'\\\"\", false))))";
        String gabarit = DECLARATION + "<ul>{#for a in c.articles}<li title=\"{a.nom}\">{#if a.actif}{a.nom}{/if}</li>{/for}</ul>";
        assertEquals("<ul><li title=\"&lt;b&gt;&amp;\">&lt;b&gt;&amp;</li><li title=\"&#39;&quot;\"></li></ul>",
                rendre("html", gabarit, donnees));
        String xml = rendre("xml", DECLARATION + "<l>{#for a in c.articles}<a n=\"{a.nom}\">{a.nom}</a>{/for}</l>", donnees);
        var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new StringReader(xml)));
        var elements = document.getElementsByTagName("a");
        assertAll(() -> assertEquals(2, elements.getLength()),
                () -> assertEquals("<b>&", elements.item(0).getTextContent()),
                () -> assertEquals("'\"", ((org.w3c.dom.Element) elements.item(1)).getAttribute("n")));
    }

    @Test void produitUnDocumentJsonRelisible() throws Exception {
        String gabarit = DECLARATION + "\\{\"payee\": {#if c.payee}true{#else}false{/if}, \"client\": \"{c.client}\","
                + " \"quantites\": [{#for q in c.quantites}{q}, {/for}0]}";
        var objet = Json.createReader(new StringReader(rendre("json", gabarit,
                "exemple.gabarits.Bonjour.rendre(exemple.Commande.exemple(false))"))).readObject();
        assertAll(() -> assertFalse(objet.getBoolean("payee")),
                () -> assertEquals("Zoé", objet.getString("client")),
                () -> assertEquals(3, objet.getJsonArray("quantites").size()));
    }

    static Stream<Arguments> directivesHorsContexteNeutre() {
        return Stream.of(
                Arguments.of("html", "<a §{#if c.payee}href{/if}>"),
                Arguments.of("html", "<a title=\"§{#if c.payee}x{/if}\">"),
                Arguments.of("html", "{#if c.payee}<a title=\"§{/if}\">"),
                Arguments.of("html", "<script>§{#if c.payee}{/if}</script>"),
                Arguments.of("html", "<textarea>§{#for a in c.articles}{/for}</textarea>"),
                Arguments.of("html", "<!-- §{#if c.payee}{/if} -->"),
                Arguments.of("html", "<§{#if c.payee}a{/if}>"),
                Arguments.of("xml", "<![CDATA[§{#if c.payee}{/if}]]>"),
                Arguments.of("xml", "<?p §{#if c.payee}{/if}?>"),
                Arguments.of("json", "\"a§{#if c.payee}b{/if}\""));
    }

    @ParameterizedTest
    @MethodSource("directivesHorsContexteNeutre")
    void refuseUneDirectiveHorsDuContenu(String profil, String gabarit) throws Exception {
        verifierRefus(profil, gabarit, "VERNE-SEC-003");
    }

    @Test void admetLesDirectivesPartoutEnProfilTexte() throws Exception {
        assertEquals("<a href>", rendre("texte", DECLARATION + "<a {#if c.payee}href{/if}>",
                "exemple.gabarits.Bonjour.rendre(exemple.Commande.exemple(true))"));
    }

    @Test void rendUnGrandDocumentAvecBlocs() throws Exception {
        // Au-delà de 4 000 unités de texte statique, un gabarit à blocs garde le tampon au fil du rendu.
        String bloc = "é".repeat(70_000);
        String sortie = rendre("html", DECLARATION + bloc + "{#for a in c.articles}<i>{a.nom}</i>{/for}" + bloc,
                "exemple.gabarits.Bonjour.rendre(exemple.Commande.exemple(true))");
        assertEquals(bloc + "<i>x</i><i>y</i><i>z</i>" + bloc, sortie);
    }

    @Test void borneLImbricationATrenteDeuxBlocs() throws Exception {
        // Refus d'abord : verifierRefus contrôle l'absence de source générée dans le même répertoire.
        String ouverture = "{#if c.payee}";
        verifierRefus("texte", ouverture.repeat(32) + "§" + ouverture + "{/if}".repeat(33), "VERNE-LIM-009");
        assertEquals("x", rendre("texte", DECLARATION + ouverture.repeat(32) + "x" + "{/if}".repeat(32),
                "exemple.gabarits.Bonjour.rendre(exemple.Commande.exemple(true))"));
    }

    @Test void compteLesBlocsDansLeBudgetDeGeneration() throws Exception {
        // 128 + 12 (paramètre) + 12 (fragment) + 7 × 64 (interpolations) + 825 × 72 (conditions) = 60 000 unités ;
        // un {#else} de plus (8) dépasse le budget.
        String debut = DECLARATION + "x" + "{c.client}".repeat(7);
        var compilation = compiler("texte", debut + "{#if c.payee}{/if}".repeat(825));
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var depasse = compiler("texte", debut + "{#if c.payee}{#else}{/if}" + "{#if c.payee}{/if}".repeat(824));
        assertAll(() -> assertFalse(depasse.reussie()),
                () -> assertTrue(depasse.diagnostics().contains("VERNE-LIM-008"), depasse.diagnostics()));
    }

    @Test void resteSousLaLimiteDeBytecodeAvecDesBouclesAuBudgetMaximal() throws Exception {
        // 600 (comme ci-dessus) + 436 × 136 (boucle 64 + 8, interpolation navigante 64) + 72 (condition Boolean) = 59 968.
        String gabarit = DECLARATION + "x" + "{c.client}".repeat(7) + "{#for a in c.articles}{a.nom}{/for}".repeat(436)
                + "{#if c.livree}{/if}";
        var compilation = compiler("html", gabarit);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var modele = ClassFile.of().parse(compilation.classes().resolve("exemple/gabarits/Bonjour.class"));
        var methode = modele.methods().stream().filter(candidat -> candidat.methodName().equalsString("rendre"))
                .findFirst().orElseThrow();
        var code = (CodeAttribute) methode.code().orElseThrow();
        assertTrue(code.codeLength() <= 60_000, "Taille observée de la méthode : " + code.codeLength());
        System.out.println("Taille observée de la méthode de rendu avec blocs : " + code.codeLength() + " octets.");
    }

    @Test void isoleLesRendusConcurrentsDUneBoucle() throws Exception {
        var sources = new HashMap<>(MODELE);
        sources.put("exemple/Application.java", """
                package exemple;
                public final class Application {
                    public static void main(String[] arguments) throws Exception {
                        try (var fils = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                            var taches = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                            for (int index = 0; index < 32; index++) {
                                var articles = new java.util.ArrayList<Article>();
                                var attendu = new StringBuilder("<ul>");
                                for (int numero = 0; numero <= index; numero++) {
                                    articles.add(new Article(index + "<" + numero, numero % 2 == 0));
                                    if (numero % 2 == 0) attendu.append("<li>").append(index).append("&lt;").append(numero).append("</li>");
                                }
                                var commande = Commande.exemple(true).avecArticles(articles);
                                String texte = attendu.append("</ul>").toString();
                                taches.add(fils.submit(() -> {
                                    for (int tour = 0; tour < 64; tour++) {
                                        if (!texte.equals(exemple.gabarits.Bonjour.rendre(commande))) throw new AssertionError("Rendus mélangés.");
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
                DECLARATION + "<ul>{#for a in c.articles}{#if a.actif}<li>{a.nom}</li>{/if}{/for}</ul>"), sources, true,
                repertoire.resolve("gabarits"), BancCompilation.chargementVerne("-Averne.profil.sortie=html"));
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

    private BancCompilation.Execution executer(String gabarit, String expression) throws Exception {
        var sources = new HashMap<>(MODELE);
        sources.putAll(BancCompilation.application(expression));
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne", gabarit), sources, true,
                repertoire.resolve("gabarits"), BancCompilation.chargementVerne("-Averne.profil.sortie=texte"));
        assertTrue(compilation.reussie(), compilation.diagnostics());
        return BancCompilation.executer(compilation);
    }

    private String rendre(String profil, String gabarit, String expression) throws Exception {
        var sources = new HashMap<>(MODELE);
        sources.putAll(BancCompilation.application(expression));
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne", gabarit), sources, true,
                repertoire.resolve("gabarits"), BancCompilation.chargementVerne("-Averne.profil.sortie=" + profil));
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        return execution.sortie();
    }
}
