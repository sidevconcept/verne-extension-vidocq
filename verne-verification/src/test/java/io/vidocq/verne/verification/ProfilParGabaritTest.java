package io.vidocq.verne.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Le suffixe du nom choisit le profil d'un gabarit ; sans suffixe, le profil par défaut de l'option s'applique. */
@Timeout(30)
class ProfilParGabaritTest {
    @TempDir Path repertoire;
    private static final String DECLARATION = "{@java.lang.String v}";

    @Test void melangeLesProfilsDansUneMemeCompilation() throws Exception {
        var gabarits = Map.of(
                "page.html.verne", DECLARATION + "<p title=\"{v}\">{v}</p>",
                "flux.xml.verne", DECLARATION + "<a b=\"{v}\">{v}</a>",
                "note.texte.verne", DECLARATION + "<b>{v}</b>",
                "defaut.verne", DECLARATION + "<c>{v}</c>");
        var application = BancCompilation.application("exemple.gabarits.Page.rendre(\"'<\") + \"|\""
                + " + exemple.gabarits.Flux.rendre(\"'<\") + \"|\" + exemple.gabarits.Note.rendre(\"'<\") + \"|\""
                + " + exemple.gabarits.Defaut.rendre(\"'<\")");
        assertEquals("<p title=\"&#39;&lt;\">&#39;&lt;</p>|<a b=\"&apos;&lt;\">&apos;&lt;</a>|<b>'<</b>|<c>&apos;&lt;</c>",
                rendre(gabarits, application, "xml"));
    }

    @Test void choisitExplicitementLeTexteMalgreUnProfilParDefautHtml() throws Exception {
        assertEquals("<b>'<</b>", rendre(Map.of("note.texte.verne", DECLARATION + "<b>{v}</b>"),
                BancCompilation.application("exemple.gabarits.Note.rendre(\"'<\")"), "html"));
    }

    @Test void appliqueLesContextesDuProfilDeChaqueGabarit() throws Exception {
        var compilation = compiler(Map.of(
                "page.html.verne", DECLARATION + "<script>{v}</script>",
                "flux.xml.verne", DECLARATION + "<script>{v}</script>"), BancCompilation.application("\"témoin\""), "texte");
        assertFalse(compilation.reussie());
        assertTrue(compilation.diagnostics().contains("VERNE-SEC-002 : page.html.verne:1:30"), compilation.diagnostics());
        assertFalse(compilation.diagnostics().contains("flux.xml.verne"), compilation.diagnostics());
        assertTrue(Files.exists(compilation.sourcesGenerees().resolve("exemple/gabarits/Flux.java")));
    }

    @Test void refuseUnSuffixeDeProfilInconnuSansArreterLesAutres() throws Exception {
        var compilation = compiler(Map.of(
                "donnees.json.verne", "{inconnu",
                "majuscules.HTML.verne", "texte",
                "bonjour.html.verne", "Bonjour"), BancCompilation.application("\"témoin\""), "texte");
        assertFalse(compilation.reussie());
        assertTrue(compilation.diagnostics().contains("VERNE-LIA-001 : donnees.json.verne:1:1 : suffixe de profil inconnu"), compilation.diagnostics());
        assertTrue(compilation.diagnostics().contains("VERNE-LIA-001 : majuscules.HTML.verne:1:1 : suffixe de profil inconnu"), compilation.diagnostics());
        assertFalse(compilation.diagnostics().contains("VERNE-SYN-001"), "Le fichier au suffixe inconnu n'est pas lu.");
        assertTrue(Files.exists(compilation.sourcesGenerees().resolve("exemple/gabarits/Bonjour.java")));
    }

    @Test void refuseDeuxGabaritsNeDifferantQueParLeurProfil() throws Exception {
        var compilation = compiler(Map.of("a/fiche.html.verne", "html", "b/fiche.xml.verne", "xml"),
                BancCompilation.application("\"témoin\""), "texte");
        assertFalse(compilation.reussie());
        assertTrue(compilation.diagnostics().contains("VERNE-LIA-001 : b/fiche.xml.verne:1:1 : plusieurs ressources produisent des noms de classe équivalents"), compilation.diagnostics());
    }

    private BancCompilation.Compilation compiler(Map<String, String> gabarits, Map<String, String> sources,
            String profilParDefaut) throws Exception {
        return BancCompilation.compiler(repertoire, gabarits, sources, true, repertoire.resolve("gabarits"),
                BancCompilation.chargementVerne("-Averne.profil.sortie=" + profilParDefaut));
    }

    private String rendre(Map<String, String> gabarits, Map<String, String> sources, String profilParDefaut)
            throws Exception {
        var compilation = compiler(gabarits, sources, profilParDefaut);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertEquals(0, execution.codeSortie(), execution.erreurs());
        return execution.sortie();
    }
}
