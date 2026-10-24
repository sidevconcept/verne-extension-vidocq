package io.vidocq.verne.mesures;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** Oracle du banc : chaque rendu mesuré produit exactement la sortie du témoin, hors chronométrage. */
class OracleMesuresTest {
    static Stream<Arguments> combinaisons() {
        return Corpus.CAS.stream().flatMap(cas -> Corpus.DENSITES.stream()
                .flatMap(densite -> Stream.of(true, false).map(echappe -> Arguments.of(cas, densite, echappe))));
    }

    @ParameterizedTest(name = "{0}, densité {1} %, échappement {2}")
    @MethodSource("combinaisons")
    void verneProduitLaSortieDuTemoin(String cas, int densite, boolean echappe) {
        assertDoesNotThrow(() -> Corpus.verifier(cas, densite, echappe, Corpus.verne(cas, echappe)));
    }

    @Test void signaleUnEcartEnNommantLaCombinaison() {
        var temoin = Corpus.temoin("facture", true);
        var ecart = assertThrows(IllegalStateException.class,
                () -> Corpus.verifier("facture", 10, true, donnees -> temoin.apply(donnees) + " "));
        assertTrue(ecart.getMessage().startsWith("Sortie différente : facture, densité 10 %, échappement true"), ecart.getMessage());
    }

    @Test void genereDeuxFoisLesMemesDonnees() {
        for (String cas : Corpus.CAS) {
            for (int densite : Corpus.DENSITES) {
                assertArrayEquals(Corpus.donnees(cas, densite), Corpus.donnees(cas, densite), cas + " " + densite);
            }
        }
    }

    @Test void exerceReellementLEchappement() {
        for (String cas : Corpus.CAS) {
            Object jeu = Corpus.donnees(cas, 50)[0];
            assertNotEquals(Corpus.temoin(cas, false).apply(jeu), Corpus.temoin(cas, true).apply(jeu), cas);
            Object neutre = Corpus.donnees(cas, 0)[0];
            assertEquals(Corpus.temoin(cas, false).apply(neutre), Corpus.temoin(cas, true).apply(neutre), cas);
        }
    }

    @Test void varieLesDonneesEntreLesJeux() {
        for (String cas : Corpus.CAS) {
            assertEquals(8, Arrays.stream(Corpus.donnees(cas, 10)).distinct().count(), cas);
        }
    }
}
