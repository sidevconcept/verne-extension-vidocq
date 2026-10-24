package io.vidocq.verne.verification;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Profil JSON (T-SEC-06) : littéraux de chaîne suivis à la compilation, sortie relue par un lecteur indépendant. */
@Timeout(30)
class ProfilJsonTest {
    @TempDir Path repertoire;
    private static final String DECLARATION = "{@java.lang.String v}";
    /** Littéral Java de la valeur piège : guillemet, antislash, contrôles, séparateurs de ligne Unicode, emoji, barre. */
    private static final String PIEGE_JAVA = "a\\\"b\\\\c\\nd\\te\\u0001f\\u2028g\\u2029h</i>🚀";
    private static final String PIEGE = "a\"b\\c\nd\te\u0001f\u2028g\u2029h</i>🚀";

    @Test void rendUnDocumentRelisibleAvecLesValeursEtTypesOriginaux() throws Exception {
        var sources = new HashMap<>(BancCompilation.application(
                "exemple.gabarits.Bonjour.rendre(new Mesure(\"" + PIEGE_JAVA + "\", 42, -7L, 1.5, true, '\"'))"));
        sources.put("exemple/Mesure.java",
                "package exemple; public record Mesure(String nom, int n, long l, double d, boolean b, char c) {}");
        String gabarit = "{@exemple.Mesure m}\\{\"nom\": \"{m.nom}\", \"n\": {m.n}, \"l\": {m.l}, \"d\": {m.d},"
                + " \"b\": {m.b}, \"c\": \"{m.c}\", \"texte\": \"id-{m.n}-{m.b}\"}";
        String document = rendre("json", gabarit, sources);
        JsonObject objet = lire(document);
        assertAll(() -> assertEquals(PIEGE, objet.getString("nom")),
                () -> assertEquals(42, objet.getInt("n")),
                () -> assertEquals(-7L, objet.getJsonNumber("l").longValue()),
                () -> assertEquals(1.5, objet.getJsonNumber("d").doubleValue()),
                () -> assertTrue(objet.getBoolean("b")),
                () -> assertEquals("\"", objet.getString("c")),
                () -> assertEquals("id-42-true", objet.getString("texte")),
                () -> assertTrue(document.contains("\\u2028") && document.contains("\\u0001"), document),
                () -> assertTrue(document.contains("</i>🚀"), "La barre et les emoji restent tels quels : " + document));
    }

    @Test void echappeUnSubstitutIsoleEnSequenceUnicode() throws Exception {
        String document = rendre("json", DECLARATION + "\\{\"v\": \"{v}\"}",
                BancCompilation.application("exemple.gabarits.Bonjour.rendre(\"x\\uD800y\")"));
        assertEquals("{\"v\": \"x\\ud800y\"}", document);
    }

    @Test void echappeAussiLesGrandsDocuments() throws Exception {
        String bloc = "a".repeat(65_536);
        String document = rendre("json", DECLARATION + "\\{\"bloc\": \"" + bloc + "\", \"v\": \"{v}\"}",
                BancCompilation.application("exemple.gabarits.Bonjour.rendre(\"" + PIEGE_JAVA + "\")"));
        assertEquals(PIEGE, lire(document).getString("v"));
    }

    @Test void choisitLeProfilJsonParSuffixe() throws Exception {
        var compilation = BancCompilation.compiler(repertoire, Map.of("donnees.json.verne", DECLARATION + "[\"{v}\"]"),
                BancCompilation.application("exemple.gabarits.Donnees.rendre(\"" + PIEGE_JAVA + "\")"), true,
                repertoire.resolve("gabarits"), BancCompilation.chargementVerne("-Averne.profil.sortie=texte"));
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        assertEquals(PIEGE, Json.createReader(new StringReader(execution.sortie())).readArray().getString(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\{\"a\": {v}}", "[{v}]", "\\{\"a\": \"\\\\{v}\"}", "{v}"})
    void refuseUneChaineHorsLitteralOuDansUneSequenceDEchappement(String gabarit) throws Exception {
        var compilation = compiler(DECLARATION + gabarit, BancCompilation.application("\"témoin\""));
        assertAll(() -> assertFalse(compilation.reussie()),
                () -> assertTrue(compilation.diagnostics().contains("VERNE-SEC-002 : bonjour.verne:1:"
                        + (DECLARATION.length() + gabarit.indexOf("{v}") + 1)), compilation.diagnostics()),
                () -> assertFalse(Files.exists(compilation.sourcesGenerees().resolve("exemple/gabarits/Bonjour.java"))));
    }

    @Test void refuseUnCaractereHorsLitteral() throws Exception {
        var sources = new HashMap<>(BancCompilation.application("\"témoin\""));
        sources.put("exemple/Lettre.java", "package exemple; public record Lettre(char c) {}");
        var compilation = compiler("{@exemple.Lettre l}[{l.c}]", sources);
        assertFalse(compilation.reussie());
        assertTrue(compilation.diagnostics().contains("VERNE-SEC-002 : bonjour.verne:1:21"), compilation.diagnostics());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Double.NaN", "Double.POSITIVE_INFINITY", "Double.NEGATIVE_INFINITY"})
    void refuseAuRenduUnNombreNonFiniHorsLitteral(String valeur) throws Exception {
        var sources = new HashMap<String, String>();
        sources.put("exemple/Mesure.java", "package exemple; public record Mesure(double d, float f) {}");
        sources.put("exemple/Application.java", """
                package exemple;
                public final class Application {
                    public static void main(String[] arguments) {
                        try { exemple.gabarits.Bonjour.rendre(new Mesure(%s, 1f)); System.out.print("accepté"); }
                        catch (IllegalArgumentException exception) { System.out.print(exception.getMessage()); }
                    }
                }
                """.formatted(valeur));
        String sortie = rendre("json", "{@exemple.Mesure m}[{m.d}, {m.f}]", sources);
        assertTrue(sortie.startsWith("VERNE-REN-003"), sortie);
    }

    @Test void refuseAuRenduUnFloatNonFiniEtGardeSaRepresentation() throws Exception {
        var sources = new HashMap<String, String>();
        sources.put("exemple/Mesure.java", "package exemple; public record Mesure(float f) {}");
        sources.put("exemple/Application.java", """
                package exemple;
                public final class Application {
                    public static void main(String[] arguments) {
                        String fini = exemple.gabarits.Bonjour.rendre(new Mesure(1.1f));
                        String refus;
                        try { exemple.gabarits.Bonjour.rendre(new Mesure(Float.NaN)); refus = "accepté"; }
                        catch (IllegalArgumentException exception) { refus = exception.getMessage(); }
                        System.out.print(fini + "|" + refus);
                    }
                }
                """);
        String sortie = rendre("json", "{@exemple.Mesure m}[{m.f}]", sources);
        assertTrue(sortie.startsWith("[1.1]|VERNE-REN-003"), sortie);
    }

    @Test void accepteUnNombreNonFiniDansUnLitteral() throws Exception {
        var sources = new HashMap<>(BancCompilation.application("exemple.gabarits.Bonjour.rendre(new Mesure(Double.NaN))"));
        sources.put("exemple/Mesure.java", "package exemple; public record Mesure(double d) {}");
        assertEquals("[\"NaN\"]", rendre("json", "{@exemple.Mesure m}[\"{m.d}\"]", sources));
    }

    @Test void isoleLesRendusJsonConcurrents() throws Exception {
        var sources = Map.of("exemple/Application.java", """
                package exemple;
                public final class Application {
                    public static void main(String[] arguments) throws Exception {
                        try (var fils = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                            var taches = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                            for (int index = 0; index < 32; index++) {
                                String valeur = "\\"" + index + "\\\\";
                                taches.add(fils.submit(() -> {
                                    String attendu = "[\\"\\\\\\"" + valeur.substring(1, valeur.length() - 1) + "\\\\\\\\\\"]";
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
        assertEquals("concurrence vérifiée", rendre("json", DECLARATION + "[\"{v}\"]", sources));
    }

    private static JsonObject lire(String document) {
        try (var lecteur = Json.createReader(new StringReader(document))) {
            return lecteur.readObject();
        }
    }

    private BancCompilation.Compilation compiler(String gabarit, Map<String, String> sources) throws Exception {
        return BancCompilation.compiler(repertoire, Map.of("bonjour.verne", gabarit), sources, true,
                repertoire.resolve("gabarits"), BancCompilation.chargementVerne("-Averne.profil.sortie=json"));
    }

    private String rendre(String profil, String gabarit, Map<String, String> sources) throws Exception {
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne", gabarit), sources, true,
                repertoire.resolve("gabarits"), BancCompilation.chargementVerne("-Averne.profil.sortie=" + profil));
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        return execution.sortie();
    }
}
