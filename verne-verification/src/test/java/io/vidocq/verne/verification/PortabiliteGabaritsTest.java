package io.vidocq.verne.verification;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Noms placés dans des répertoires distincts pour exercer aussi les volumes insensibles à la casse. */
@Timeout(30)
class PortabiliteGabaritsTest {
    @TempDir Path repertoire;

    @Test void refuseDeuxNomsNeDifferantQueParLaCasse() throws Exception {
        refuserCollision("bonjour", "BONJOUR");
    }

    @Test void refuseDeuxNomsCanoniquementEquivalents() throws Exception {
        refuserCollision("café", "cafe\u0301");
    }

    @Test void refuseUneCollisionCombinantCasseEtNormalisation() throws Exception {
        refuserCollision("CAFÉ", "cafe\u0301");
    }

    @Test void conserveDeuxNomsDistinctsEtLeurRenduUnicode() throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("premier/café.verne", "café", "second/cafes.verne", "cafe\u0301s"),
                BancCompilation.application("exemple.gabarits.Café.rendre() + \" / \""
                        + " + exemple.gabarits.Cafes.rendre()"), true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals("café / cafe\u0301s", execution.sortie()));
    }

    /** D-010c : Windows ne peut créer ni Con.java ni Con.class ; le refus vaut sur tous les systèmes. */
    @Test void refuseLesNomsReservesDeWindowsSansTenirCompteDeLaCasse() throws Exception {
        var noms = java.util.List.of("con.verne", "PRN.verne", "Aux.html.verne", "nul.json.verne",
                "conin$.verne", "CONOUT$.verne", "com0.verne", "COM9.verne", "lpt1.xml.verne", "Lpt9.verne");
        var gabarits = new java.util.HashMap<String, String>();
        for (String nom : noms) gabarits.put("reserves/" + nom, "{inconnu}");
        var compilation = BancCompilation.compiler(repertoire, gabarits, BancCompilation.application("\"témoin\""), true);
        assertFalse(compilation.reussie(), compilation.diagnostics());
        for (String nom : noms) {
            assertTrue(compilation.diagnostics().contains("VERNE-LIA-001 : reserves/" + nom
                    + ":1:1 : nom réservé par Windows."), compilation.diagnostics());
        }
        assertFalse(compilation.diagnostics().contains("VERNE-TYP-001"), "Un nom réservé ne doit pas être lu");
    }

    @Test void accepteLesNomsVoisinsDesNomsReserves() throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("console.verne", "a", "com10.verne", "b", "nulle.verne", "c", "lpt.verne", "d", "conin.verne", "e"),
                BancCompilation.application("exemple.gabarits.Console.rendre() + exemple.gabarits.Com10.rendre()"
                        + " + exemple.gabarits.Nulle.rendre() + exemple.gabarits.Lpt.rendre()"
                        + " + exemple.gabarits.Conin.rendre()"), true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals("abcde", execution.sortie()));
    }

    private void refuserCollision(String premier, String second) throws Exception {
        var compilation = BancCompilation.compiler(repertoire,
                Map.of("premier/" + premier + ".verne", "un", "second/" + second + ".verne", "deux"),
                BancCompilation.application("\"témoin\""), true);
        assertAll(() -> assertFalse(compilation.reussie(), "La collision doit faire échouer la compilation"),
                () -> assertTrue(compilation.diagnostics().contains("VERNE-LIA-001"), compilation.diagnostics()),
                () -> assertTrue(compilation.diagnostics().contains("second/" + second + ".verne:1:1"),
                        compilation.diagnostics()),
                () -> assertFalse(compilation.diagnostics().contains(repertoire.toString()),
                        "Le diagnostic ne doit pas exposer le chemin du poste"));
    }
}
