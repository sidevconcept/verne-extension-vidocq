package io.vidocq.verne.verification;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Qualification des positions des diagnostics (VERNE-004, T-DIA-01) : identifiant relatif, ligne et colonne
 * en points de code, tabulation comptée pour un, CRLF pour une seule fin de ligne, délimiteur ouvrant pointé.
 */
@Timeout(30)
class PositionsDiagnosticsTest {
    @TempDir Path repertoire;

    @Test void positionneUneErreurDeSyntaxeDansUnSousRepertoire() throws Exception {
        var compilation = compiler(Map.of("sous/dossier/page.verne", "première ligne\r\n\té🚀 {nom"));
        attendre(compilation, "VERNE-SYN-001 : sous/dossier/page.verne:2:5 : ");
        assertFalse(compilation.diagnostics().contains(repertoire.toString()), compilation.diagnostics());
    }

    @Test void compteUnCaractereCombineCommeDeuxPointsDeCode() throws Exception {
        var compilation = compiler(Map.of(
                "combine.verne", "é{inconnu}",
                "compose.verne", "é{inconnu}"));
        attendre(compilation, "VERNE-TYP-001 : combine.verne:1:3 : ");
        attendre(compilation, "VERNE-TYP-001 : compose.verne:1:2 : ");
    }

    @Test void compteLesLignesDUnCommentaireMultiligne() throws Exception {
        var compilation = compiler(Map.of("bonjour.verne", "{! un\r\ndeux\ntrois !}\t{inconnu}"));
        attendre(compilation, "VERNE-TYP-001 : bonjour.verne:3:10 : ");
    }

    @Test void compteUneTabulationPourUneColonne() throws Exception {
        var compilation = compiler(Map.of("bonjour.verne", "\t\t{inconnu}"));
        attendre(compilation, "VERNE-TYP-001 : bonjour.verne:1:3 : ");
    }

    @Test void compteLesFinsDeLigneLfEtCrlfMelangees() throws Exception {
        var compilation = compiler(Map.of("bonjour.verne", "a\nb\r\nc\n{inconnu}"));
        attendre(compilation, "VERNE-TYP-001 : bonjour.verne:4:1 : ");
    }

    @Test void cumuleEmojiCaractereCombineEtTabulationSurUneLigne() throws Exception {
        var compilation = compiler(Map.of("bonjour.verne", "ligne\r\n🚀é\t{inconnu}"));
        attendre(compilation, "VERNE-TYP-001 : bonjour.verne:2:5 : ");
    }

    private BancCompilation.Compilation compiler(Map<String, String> gabarits) throws Exception {
        return BancCompilation.compiler(repertoire, gabarits, BancCompilation.application("\"témoin\""), true);
    }

    private static void attendre(BancCompilation.Compilation compilation, String diagnostic) {
        assertFalse(compilation.reussie());
        assertTrue(compilation.diagnostics().contains(diagnostic), compilation.diagnostics());
    }
}
