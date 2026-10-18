package io.vidocq.verne.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Bornes, refus explicites et protection de la génération avant implémentation. */
@Timeout(30)
class CasLimitesCompilationTest {
    @TempDir Path repertoire;

    @Test void refuseUnParametreInconnu() throws Exception {
        refuser("{inconnu}", "VERNE-TYP-001");
    }

    @Test void refuseUnTypeInconnu() throws Exception {
        refuser("{@exemple.Inconnu personne}{personne}", "VERNE-TYP-003");
    }

    @Test void refuseUnNomReserveJava() throws Exception {
        refuser("{@java.lang.String class}", "VERNE-SYN-002");
    }

    @Test void refuseUneExpressionQuiInjecteraitDuJava() throws Exception {
        refuser("{@java.lang.String nom}{nom);System.exit(99)}", "VERNE-SYN-002");
    }

    @Test void refuseUnCommentaireNonFerme() throws Exception {
        refuser("avant{! commentaire", "VERNE-SYN-001");
    }

    @Test void refuseUneSectionPasEncorePriseEnCharge() throws Exception {
        refuser("{#si actif}oui{/si}", "VERNE-SYN-002");
    }

    @Test void refuseUnEchappementInconnuOuIncomplet() throws Exception {
        refuser("texte\\", "VERNE-SYN-002");
        refuser("texte\\q", "VERNE-SYN-002", repertoire.resolve("inconnu"));
    }

    @Test void conserveUnAntislashEchappe() throws Exception {
        rendre("avant\\\\après", "avant\\après");
    }

    @Test void protegeLesLitterauxJavaGeneres() throws Exception {
        String texte = "\"; System.exit(99); //\r\n\t\0fin";
        rendre(texte, texte);
    }

    @Test void compteLesColonnesEnPointsDeCodeUnicode() throws Exception {
        var compilation = compiler("🚀{inconnu}");
        assertFalse(compilation.reussie());
        assertTrue(compilation.diagnostics().contains("bonjour.verne:1:2"), compilation.diagnostics());
    }

    @Test void compteUnRetourChariotEtUnSautDeLigneCommeUneLigne() throws Exception {
        var compilation = compiler("première\r\n{inconnu}");
        assertFalse(compilation.reussie());
        assertTrue(compilation.diagnostics().contains("bonjour.verne:2:1"), compilation.diagnostics());
    }

    @Test void refuseDeuxRessourcesProduisantLaMemeClasse() throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("bonjour.verne", "un", "sous/bonjour.verne", "deux"),
                BancCompilation.application("\"témoin\""), true);
        assertFalse(compilation.reussie());
        assertTrue(compilation.diagnostics().contains("VERNE-LIA-001"), compilation.diagnostics());
    }

    @Test void refuseUneSourceAuDelaDeLaLimiteUnMebioctet() throws Exception {
        refuser("a".repeat(1_048_577), "VERNE-LIM-001");
    }

    @Test void refuseUnEncodageUtf8Malforme() throws Exception {
        Path gabarits = Files.createDirectories(repertoire.resolve("gabarits"));
        Files.write(gabarits.resolve("bonjour.verne"), new byte[] {(byte) 0xc3, 0x28});
        var compilation = BancCompilation.compiler(repertoire, Map.of(),
                BancCompilation.application("\"témoin\""), true);
        assertFalse(compilation.reussie());
        assertTrue(compilation.diagnostics().contains("VERNE-SYN-003"), compilation.diagnostics());
    }

    @Test void refuseUneValeurNullePourUnParametreObligatoire() throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("bonjour.verne", "{@java.lang.String nom}{nom}"),
                BancCompilation.application("io.vidocq.verne.genere.Bonjour.rendre((String) null)"), true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertNotEquals(0, execution.codeSortie()),
                () -> assertTrue(execution.erreurs().contains("VERNE-REN-001"), execution.erreurs()));
    }

    @Test void refuseUnAccesIntrospectifMemeSurUnRecord() throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("bonjour.verne", "{@exemple.Personne personne}{personne.getClass}"),
                Map.of("exemple/Personne.java", "package exemple; public record Personne(String nom) {}"), true);
        assertFalse(compilation.reussie());
        assertTrue(compilation.diagnostics().contains("VERNE-TYP-001"), compilation.diagnostics());
    }

    @Test void accepteUnParametreNommeResultat() throws Exception {
        rendreParametre("resultat");
    }

    @Test void accepteUnParametreNommeJava() throws Exception {
        rendreParametre("java");
    }

    @Test void eviteUneCollisionAvecLeNomInterneDuResultat() throws Exception {
        rendreParametre("$verneResultat");
    }

    @Test void resoutDeuxAccesseursDeRecordsSansReflexion() throws Exception {
        var sources = new java.util.HashMap<>(BancCompilation.application(
                "io.vidocq.verne.genere.Bonjour.rendre(new Personne(new Adresse(\"Paris\")))"));
        sources.put("exemple/Personne.java", "package exemple; public record Personne(Adresse adresse) {}");
        sources.put("exemple/Adresse.java", "package exemple; public record Adresse(String ville) {}");
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("bonjour.verne", "{@exemple.Personne personne}{personne.adresse.ville}"), sources, true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals("Paris", execution.sortie()));
    }

    @Test void accepteUneSourceALaLimiteUnMebioctet() throws Exception {
        var compilation = compiler("a".repeat(1_048_576));
        assertTrue(compilation.reussie(), compilation.diagnostics());
    }

    private void rendreParametre(String nom) throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("bonjour.verne", "{@java.lang.String " + nom + "}{" + nom + "}"),
                BancCompilation.application("io.vidocq.verne.genere.Bonjour.rendre(\"valeur\")"), true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals("valeur", execution.sortie()));
    }

    /** Vérifie le code métier ; un simple échec de javac ne suffit jamais. */
    private void refuser(String source, String code) throws Exception {
        refuser(source, code, repertoire);
    }

    private void refuser(String source, String code, Path racine) throws Exception {
        var compilation = BancCompilation.compiler(racine, Map.of("bonjour.verne", source),
                BancCompilation.application("\"témoin\""), true);
        assertFalse(compilation.reussie());
        assertTrue(compilation.diagnostics().contains(code), compilation.diagnostics());
    }

    private BancCompilation.Compilation compiler(String source) throws Exception {
        return BancCompilation.compiler(repertoire, Map.of("bonjour.verne", source),
                BancCompilation.application("\"témoin\""), true);
    }

    private void rendre(String source, String attendu) throws Exception {
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne", source),
                BancCompilation.application("io.vidocq.verne.genere.Bonjour.rendre()"), true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals(attendu, execution.sortie()));
    }
}
