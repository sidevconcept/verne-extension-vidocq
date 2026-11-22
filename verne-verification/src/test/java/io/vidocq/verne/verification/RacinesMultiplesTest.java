package io.vidocq.verne.verification;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Racines multiples (VERNE-010, D-010a) : ordre déclaré, identifiants relatifs à leur racine,
 * collisions entre racines, configurations refusées et budgets partagés.
 */
@Timeout(60)
class RacinesMultiplesTest {
    @TempDir Path repertoire;

    @Test void compileEtRendLesGabaritsDeDeuxRacines() throws Exception {
        var compilation = compiler(repertoire, Map.of("premiere/entete.verne", "En-tête", "seconde/sous/pied.verne", "pied"),
                "exemple.gabarits.Entete.rendre() + \" / \" + exemple.gabarits.Pied.rendre()", "premiere", "seconde");
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals("En-tête / pied", execution.sortie()));
    }

    @Test void refuseUnMemeIdentifiantDansDeuxRacines() throws Exception {
        var compilation = compiler(repertoire, Map.of("premiere/sous/bonjour.verne", "un", "seconde/sous/bonjour.verne", "deux"),
                "\"témoin\"", "premiere", "seconde");
        refuser(compilation, "VERNE-LIA-001 : sous/bonjour.verne:1:1 : identifiant déjà fourni par une racine précédente.");
        assertEquals(1, compilation.diagnostics().lines().filter(ligne -> ligne.contains("VERNE-")).count(),
                compilation.diagnostics());
    }

    @Test void refuseLaCollisionDeClasseSurLaRacineDeclareePlusTard() throws Exception {
        var gabarits = Map.of("premiere/bonjour.verne", "un", "seconde/BONJOUR.verne", "deux");
        refuser(compiler(repertoire.resolve("direct"), gabarits, "\"témoin\"", "premiere", "seconde"),
                "VERNE-LIA-001 : BONJOUR.verne:1:1 : plusieurs ressources produisent des noms de classe équivalents.");
        refuser(compiler(repertoire.resolve("inverse"), gabarits, "\"témoin\"", "seconde", "premiere"),
                "VERNE-LIA-001 : bonjour.verne:1:1 : plusieurs ressources produisent des noms de classe équivalents.");
    }

    @Test void refuseUnSegmentVideSansLireLesGabarits() throws Exception {
        String s = File.pathSeparator;
        refuserConfiguration("vide", "");
        refuserConfiguration("initial", s + racine("initial", "premiere"));
        refuserConfiguration("final", racine("final", "premiere") + s);
        refuserConfiguration("double", racine("double", "premiere") + s + s + racine("double", "seconde"));
    }

    @Test void refuseUneRacineEnDoubleOuImbriquee() throws Exception {
        String s = File.pathSeparator;
        refuserConfiguration("double", racine("double", "premiere") + s + racine("double", "premiere"));
        refuserConfiguration("imbriquee", racine("imbriquee", "premiere") + s + racine("imbriquee", "premiere/sous"));
        refuserConfiguration("englobante", racine("englobante", "premiere/sous") + s + racine("englobante", "premiere"));
    }

    @Test void refuseUnLienVersUneAutreRacine() throws Exception {
        Path seconde = Files.createDirectories(repertoire.resolve("gabarits/seconde"));
        Path cible = repertoire.resolve("gabarits/premiere/cible.verne");
        Files.createDirectories(cible.getParent());
        Files.writeString(cible, "cible");
        Files.createSymbolicLink(seconde.resolve("lien.verne"), cible);
        var compilation = compiler(repertoire, Map.of(), "\"témoin\"", "premiere", "seconde");
        refuser(compilation, "VERNE-SEC-001 : lien.verne:1:1 : ressource hors de la racine autorisée.");
    }

    @Test void partageLaBorneDuNombreDeRessourcesEntreRacines() throws Exception {
        var gabarits = new HashMap<String, String>();
        for (int index = 0; index < 1_025; index++) {
            String racine = index < 512 ? "premiere" : "seconde";
            gabarits.put(String.format(Locale.ROOT, "%s/g%04d.verne", racine, index), "x");
        }
        var compilation = compiler(repertoire, gabarits, "\"témoin\"", "premiere", "seconde");
        refuser(compilation, "VERNE-LIM-002");
    }

    @Test void partageLeBudgetCumuleDeLectureEntreRacines() throws Exception {
        String mebioctet = "a".repeat(1_048_576);
        var gabarits = new HashMap<String, String>();
        for (int index = 0; index < 4; index++) {
            gabarits.put("premiere/p" + index + ".verne", mebioctet);
            gabarits.put("seconde/s" + index + ".verne", mebioctet);
        }
        gabarits.put("seconde/z.verne", "a");
        var compilation = compiler(repertoire, gabarits, "\"témoin\"", "premiere", "seconde");
        refuser(compilation, "VERNE-LIM-006 : z.verne:1:1 : ");
    }

    private BancCompilation.Compilation compiler(Path base, Map<String, String> gabarits, String expression,
                                                 String... racines) throws Exception {
        var declarees = new StringBuilder();
        for (String racine : racines) {
            if (!declarees.isEmpty()) declarees.append(File.pathSeparator);
            declarees.append(racine(base, racine));
        }
        return BancCompilation.compilerAvecRacines(base, gabarits, BancCompilation.application(expression),
                declarees.toString());
    }

    /** Prépare les racines premiere et seconde, puis vérifie le refus de configuration sans lecture. */
    private void refuserConfiguration(String nom, String declaration) throws Exception {
        Path base = repertoire.resolve("configuration-" + nom);
        var compilation = BancCompilation.compilerAvecRacines(base,
                Map.of("premiere/sous/invalide.verne", "{inconnu}", "seconde/invalide.verne", "{inconnu}"),
                BancCompilation.application("\"témoin\""), declaration);
        refuser(compilation, "VERNE-CFG-001");
        assertFalse(compilation.diagnostics().contains("VERNE-TYP-001"), compilation.diagnostics());
    }

    private String racine(String nom, String sousRepertoire) {
        return racine(repertoire.resolve("configuration-" + nom), sousRepertoire);
    }

    private static String racine(Path base, String sousRepertoire) {
        return base.resolve("gabarits").resolve(sousRepertoire).toString();
    }

    private void refuser(BancCompilation.Compilation compilation, String diagnostic) {
        assertAll(() -> assertFalse(compilation.reussie(), compilation.diagnostics()),
                () -> assertTrue(compilation.diagnostics().contains(diagnostic), compilation.diagnostics()),
                () -> assertFalse(compilation.diagnostics().contains(repertoire.toString()), compilation.diagnostics()));
    }
}
