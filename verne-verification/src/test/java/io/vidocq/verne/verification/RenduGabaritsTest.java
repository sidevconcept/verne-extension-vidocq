package io.vidocq.verne.verification;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Rendus exécutés dans une JVM modulaire neuve, après compilation des gabarits. */
@Timeout(30)
class RenduGabaritsTest {
    @TempDir Path repertoire;

    @Test
    void rendUnDocumentVide() throws Exception {
        verifier("", "io.vidocq.verne.genere.Bonjour.rendre()", "");
    }

    @Test
    void preserveLeTexteUnicode() throws Exception {
        verifier("été, cœur, e\u0301 et 🚀", "io.vidocq.verne.genere.Bonjour.rendre()",
                "été, cœur, e\u0301 et 🚀");
    }

    @Test
    void preserveEspacesTabulationsEtFinsDeLigne() throws Exception {
        verifier(" \tbonjour\r\n\nfin \t", "io.vidocq.verne.genere.Bonjour.rendre()",
                " \tbonjour\r\n\nfin \t");
    }

    @Test
    void interpoleUnParametreDeclareSansProduireLaDeclaration() throws Exception {
        verifier("{@java.lang.String nom}Bonjour {nom}",
                "io.vidocq.verne.genere.Bonjour.rendre(\"Léa\")", "Bonjour Léa");
    }

    @Test
    void conserveUneChaineVideCommeValeurPresente() throws Exception {
        verifier("{@java.lang.String nom}avant{nom}après",
                "io.vidocq.verne.genere.Bonjour.rendre(\"\")", "avantaprès");
    }

    @Test
    void neReinterpretePasUneDonneeCommeUnGabarit() throws Exception {
        verifier("{@java.lang.String nom}{nom}",
                "io.vidocq.verne.genere.Bonjour.rendre(\"{! contenu utilisateur !}\")",
                "{! contenu utilisateur !}");
    }

    @Test
    void conserveLesCaracteresSpeciauxDansLeProfilTexte() throws Exception {
        verifier("{@java.lang.String nom}{nom}",
                "io.vidocq.verne.genere.Bonjour.rendre(\"&<>\\\"'\")", "&<>\"'");
    }

    @Test
    void retireUnCommentaireSansRetirerSaFinDeLigne() throws Exception {
        verifier("avant{! commentaire !}après\n{! ligne !}\nfin",
                "io.vidocq.verne.genere.Bonjour.rendre()", "avantaprès\n\nfin");
    }

    @Test
    void rendUnDelimiteurEchappeCommeTexte() throws Exception {
        verifier("\\{nom}", "io.vidocq.verne.genere.Bonjour.rendre()", "{nom}");
    }

    @Test
    void rendUnTexteDepassantLaTailleDuneConstanteDeClasse() throws Exception {
        String texte = "é🚀".repeat(24_000);
        verifier(texte, "io.vidocq.verne.genere.Bonjour.rendre()", texte);
    }

    /** Compare une sortie indépendante, sans utiliser le moteur pour construire l'oracle. */
    private void verifier(String gabarit, String expression, String attendu) throws Exception {
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne", gabarit),
                BancCompilation.application(expression), true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals("", execution.erreurs()),
                () -> assertEquals(attendu, execution.sortie()));
    }
}
