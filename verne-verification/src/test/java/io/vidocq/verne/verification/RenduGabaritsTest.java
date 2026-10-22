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
        verifier("", "exemple.gabarits.Bonjour.rendre()", "");
    }

    @Test
    void preserveLeTexteUnicode() throws Exception {
        verifier("été, cœur, e\u0301 et 🚀", "exemple.gabarits.Bonjour.rendre()",
                "été, cœur, e\u0301 et 🚀");
    }

    @Test
    void preserveEspacesTabulationsEtFinsDeLigne() throws Exception {
        verifier(" \tbonjour\r\n\nfin \t", "exemple.gabarits.Bonjour.rendre()",
                " \tbonjour\r\n\nfin \t");
    }

    @Test
    void interpoleUnParametreDeclareSansProduireLaDeclaration() throws Exception {
        verifier("{@java.lang.String nom}Bonjour {nom}",
                "exemple.gabarits.Bonjour.rendre(\"Léa\")", "Bonjour Léa");
    }

    @Test
    void conserveUneChaineVideCommeValeurPresente() throws Exception {
        verifier("{@java.lang.String nom}avant{nom}après",
                "exemple.gabarits.Bonjour.rendre(\"\")", "avantaprès");
    }

    @Test
    void neReinterpretePasUneDonneeCommeUnGabarit() throws Exception {
        verifier("{@java.lang.String nom}{nom}",
                "exemple.gabarits.Bonjour.rendre(\"{! contenu utilisateur !}\")",
                "{! contenu utilisateur !}");
    }

    @Test
    void conserveLesCaracteresSpeciauxDansLeProfilTexte() throws Exception {
        verifier("{@java.lang.String nom}{nom}",
                "exemple.gabarits.Bonjour.rendre(\"&<>\\\"'\")", "&<>\"'");
    }

    @Test
    void retireUnCommentaireSansRetirerSaFinDeLigne() throws Exception {
        verifier("avant{! commentaire !}après\n{! ligne !}\nfin",
                "exemple.gabarits.Bonjour.rendre()", "avantaprès\n\nfin");
    }

    @Test
    void rendUnDelimiteurEchappeCommeTexte() throws Exception {
        verifier("\\{nom}", "exemple.gabarits.Bonjour.rendre()", "{nom}");
    }

    @Test
    void rendUnTexteDepassantLaTailleDuneConstanteDeClasse() throws Exception {
        String texte = "é🚀".repeat(24_000);
        verifier(texte, "exemple.gabarits.Bonjour.rendre()", texte);
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
