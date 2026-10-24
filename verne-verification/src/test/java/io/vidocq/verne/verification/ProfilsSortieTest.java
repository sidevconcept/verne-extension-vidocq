package io.vidocq.verne.verification;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.InputSource;
import static org.junit.jupiter.api.Assertions.*;

/** Profils HTML et XML : échappement des valeurs, contextes refusés à la compilation et caractères interdits. */
@Timeout(30)
class ProfilsSortieTest {
    @TempDir Path repertoire;
    private static final String DECLARATION = "{@java.lang.String v}";
    private static final String SPECIAUX = "&<>\\\"'";

    // T-SOR-01 : même valeur, texte brut exact et HTML échappé.
    @Test void rendLaMemeValeurEnTexteEtEnHtml() throws Exception {
        String gabarit = DECLARATION + "<p title=\"{v}\">{v}</p>";
        assertEquals("<p title=\"&<>\"'\">&<>\"'</p>", rendre("texte", gabarit, chaine(SPECIAUX)));
        assertEquals("<p title=\"&amp;&lt;&gt;&quot;&#39;\">&amp;&lt;&gt;&quot;&#39;</p>",
                rendre("html", gabarit, chaine(SPECIAUX)));
    }

    // T-SEC-03 : texte, attributs entre guillemets doubles ou simples, éléments à texte échappé.
    @Test void echappeLesTextesEtLesAttributsAutorises() throws Exception {
        String gabarit = DECLARATION + "<a class='{v}' data-x=\"{v}\" title={! !}\"{v}\">{v}</a>"
                + "<textarea>{v}</textarea><TITLE>{v}</TITLE><meta name=\"description\" content=\"{v}\">";
        String e = "&amp;&lt;&gt;&quot;&#39;";
        assertEquals("<a class='" + e + "' data-x=\"" + e + "\" title=\"" + e + "\">" + e + "</a><textarea>" + e
                + "</textarea><TITLE>" + e + "</TITLE><meta name=\"description\" content=\"" + e + "\">",
                rendre("html", gabarit, chaine(SPECIAUX)));
    }

    @Test void neDoublePasLeTraitementMaisEchappeUneValeurDejaEchappee() throws Exception {
        assertEquals("<p>&amp;amp; sans spécial é🚀</p>",
                rendre("html", DECLARATION + "<p>{v}</p>", chaine("&amp; sans spécial é🚀")));
    }

    @Test void echappeLesCaracteresPrimitifsSansTransformerLesAutresPrimitives() throws Exception {
        var sources = new HashMap<>(BancCompilation.application(
                "exemple.gabarits.Bonjour.rendre(new Mesure(42, true, '<', 1.5, -7L))"));
        sources.put("exemple/Mesure.java",
                "package exemple; public record Mesure(int n, boolean b, char c, double d, long l) {}");
        String gabarit = "{@exemple.Mesure m}<p data-c=\"{m.c}\">{m.n} {m.b} {m.c} {m.d} {m.l}</p>";
        for (String profil : new String[] {"html", "xml"}) {
            assertEquals("<p data-c=\"&lt;\">42 true &lt; 1.5 -7</p>", rendre(profil, gabarit, sources));
        }
    }

    @Test void echappeAussiLesGrandsDocuments() throws Exception {
        String bloc = "a".repeat(65_536);
        assertEquals("<p>" + bloc + "&lt;é🚀&gt;</p>",
                rendre("html", DECLARATION + "<p>" + bloc + "{v}</p>", chaine("<é🚀>")));
    }

    @Test void reconnaitLesChevronsDeTexteEtLaFinDesElementsBruts() throws Exception {
        String gabarit = DECLARATION + "1 < 2 : {v}<script>if (a < b) x = \"</p>\";</SCRIPT ><p>{v}</p>"
                + "<style>p > a \\{ color: red; }</style ><b>{v}</b><!-- -> --><i>{v}</i>";
        assertEquals("1 < 2 : &lt;<script>if (a < b) x = \"</p>\";</SCRIPT ><p>&lt;</p>"
                + "<style>p > a { color: red; }</style ><b>&lt;</b><!-- -> --><i>&lt;</i>",
                rendre("html", gabarit, chaine("<")));
    }

    // T-SEC-04 : contextes HTML interdits, refusés avant génération.
    @ParameterizedTest
    @ValueSource(strings = {
            "<{v}>", "</{v}>", "<p {v}>", "<p title=\"x\" {v}>", "<p title={v}>", "<p title= {v}>",
            "<p onclick=\"{v}\">", "<p ONMOUSEOVER='{v}'>", "<p style=\"{v}\">", "<iframe srcdoc=\"{v}\">",
            "<a href=\"{v}\">", "<img SRC='{v}'>", "<form action=\"{v}\">", "<svg><a xlink:href=\"{v}\">",
            "<script>{v}</script>", "<style>{v}</style>", "<noscript>{v}</noscript>", "<iframe>{v}</iframe>",
            "<!-- {v} -->", "<!DOCTYPE {v}>", "<?x {v}>", "<meta http-equiv=\"{v}\">",
            "<meta content=\"{v}\" http-equiv=\"refresh\">", "<meta http-equiv=\"refresh\" content=\"{v}\">",
            "<textarea></text{v}", "<plaintext><p>{v}"})
    void refuseLesContextesHtmlNonPrisEnCharge(String balisage) throws Exception {
        refuser("html", DECLARATION + balisage, "VERNE-SEC-002", colonne(balisage));
    }

    // T-SEC-07 et profil XML : échappement relu par un analyseur indépendant.
    @Test void produitUnXmlRelisibleAvecLesValeursOriginales() throws Exception {
        String gabarit = DECLARATION + "<facture ref=\"{v}\"><client nom='{v}'>{v}</client><note>{v}</note></facture>";
        String valeur = "a&<>\"'\t\n\r]]>b🚀";
        String document = rendre("xml", gabarit, chaine("a&<>\\\"'\\t\\n\\r]]>b🚀"));
        var fabrique = DocumentBuilderFactory.newDefaultInstance();
        fabrique.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var racine = fabrique.newDocumentBuilder().parse(new InputSource(new StringReader(document))).getDocumentElement();
        var client = (org.w3c.dom.Element) racine.getElementsByTagName("client").item(0);
        assertAll(() -> assertEquals(valeur, racine.getAttribute("ref")),
                () -> assertEquals(valeur, client.getAttribute("nom")),
                () -> assertEquals(valeur, client.getTextContent()),
                () -> assertTrue(document.contains("&apos;"), document),
                () -> assertFalse(document.contains("]]>b"), document));
    }

    @Test void neDonneAucuneSemantiqueHtmlAuProfilXml() throws Exception {
        assertEquals("<lien href=\"&lt;\"/><script>&lt;</script>",
                rendre("xml", DECLARATION + "<lien href=\"{v}\"/><script>{v}</script>", chaine("<")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<![CDATA[{v}]]>", "<!-- {v} -->", "<?pi {v}?>", "<!DOCTYPE a [<!ENTITY e \"{v}\">]><a/>",
            "<{v}/>", "<a {v}=\"1\"/>", "<a b={v}/>", "</{v}>"})
    void refuseLesContextesXmlNonPrisEnCharge(String balisage) throws Exception {
        refuser("xml", DECLARATION + balisage, "VERNE-SEC-002", colonne(balisage));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\u0001", "\\uFFFE", "\\uD800", "a\\u001Fb"})
    void refuseAuRenduUnCaractereInterditEnXml(String valeur) throws Exception {
        var sources = Map.of("exemple/Application.java", """
                package exemple;
                public final class Application {
                    public static void main(String[] arguments) {
                        try { exemple.gabarits.Bonjour.rendre("%s"); System.out.print("accepté"); }
                        catch (IllegalArgumentException exception) { System.out.print(exception.getMessage()); }
                    }
                }
                """.formatted(valeur));
        String sortie = rendre("xml", DECLARATION + "<a>{v}</a>", sources);
        assertTrue(sortie.startsWith("VERNE-REN-002"), sortie);
    }

    @Test void refuseUnCaractereInterditDansLeTexteDUnGabaritXml() throws Exception {
        refuser("xml", DECLARATION + "<a>\u0001</a>{v}", "VERNE-SYN-004", 25);
    }

    @ParameterizedTest
    @ValueSource(strings = {"yaml", "HTML", ""})
    void refuseUnProfilInconnu(String profil) throws Exception {
        var compilation = compiler(profil, DECLARATION + "{v}", BancCompilation.application("\"témoin\""));
        assertFalse(compilation.reussie());
        assertTrue(compilation.diagnostics().contains("VERNE-CFG-001"), compilation.diagnostics());
    }

    @Test void isoleLesRendusHtmlConcurrents() throws Exception {
        var sources = Map.of("exemple/Application.java", """
                package exemple;
                public final class Application {
                    public static void main(String[] arguments) throws Exception {
                        try (var fils = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                            var taches = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                            for (int index = 0; index < 32; index++) {
                                String valeur = "<" + index + "&";
                                taches.add(fils.submit(() -> {
                                    String attendu = "<p>&lt;" + valeur.substring(1, valeur.length() - 1) + "&amp;</p>";
                                    for (int numero = 0; numero < 64; numero++) {
                                        if (!attendu.equals(exemple.gabarits.Bonjour.rendre(valeur))) throw new AssertionError("Rendus mélangés.");
                                    }
                                }));
                            }
                            for (var tache : taches) tache.get();
                        }
                        System.out.print("concurrence vérifiée");
                    }
                }
                """);
        assertEquals("concurrence vérifiée", rendre("html", DECLARATION + "<p>{v}</p>", sources));
    }

    /** Colonne de la première interpolation du balisage, sur une seule ligne. */
    private static int colonne(String balisage) {
        return DECLARATION.length() + balisage.indexOf("{v}") + 1;
    }

    private static Map<String, String> chaine(String litteralJava) {
        return BancCompilation.application("exemple.gabarits.Bonjour.rendre(\"" + litteralJava + "\")");
    }

    private BancCompilation.Compilation compiler(String profil, String gabarit, Map<String, String> sources)
            throws Exception {
        return BancCompilation.compiler(repertoire.resolve(profil.isEmpty() ? "vide" : profil),
                Map.of("bonjour.verne", gabarit), sources, true,
                repertoire.resolve((profil.isEmpty() ? "vide" : profil) + "/gabarits"),
                BancCompilation.chargementVerne("-Averne.profil.sortie=" + profil));
    }

    private String rendre(String profil, String gabarit, Map<String, String> sources) throws Exception {
        var compilation = compiler(profil, gabarit, sources);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        return execution.sortie();
    }

    private void refuser(String profil, String gabarit, String code, int colonne) throws Exception {
        var compilation = compiler(profil, gabarit, BancCompilation.application("\"témoin\""));
        assertAll(() -> assertFalse(compilation.reussie(), "Le contexte doit être refusé."),
                () -> assertTrue(compilation.diagnostics().contains(code + " : bonjour.verne:1:" + colonne),
                        compilation.diagnostics()),
                () -> assertEquals(0, compilation.diagnostics().lines().filter(ligne -> ligne.startsWith("compiler.err")
                        && !ligne.startsWith("compiler.err.proc.messager")).count(), compilation.diagnostics()),
                () -> assertFalse(Files.exists(compilation.sourcesGenerees().resolve("exemple/gabarits/Bonjour.java"))));
    }
}
