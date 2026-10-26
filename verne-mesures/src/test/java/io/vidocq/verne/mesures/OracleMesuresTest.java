package io.vidocq.verne.mesures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Oracle du banc : chaque rendu mesuré produit exactement la sortie du témoin, hors chronométrage. */
class OracleMesuresTest {
    /** Répertoire de travail des tests : la racine du module. */
    private static final Path GABARITS = Path.of("src/test/verne");
    /** Interpolation : accolade non échappée qui n'ouvre ni déclaration ni commentaire. */
    private static final Pattern INTERPOLATION = Pattern.compile("(?<!\\\\)\\{(?![@!])[^}]*}");

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

    @ParameterizedTest(name = "mesure de {0}")
    @ValueSource(strings = {"temoin", "verne"})
    void refuseDePreparerUneMesureSiVerneSEcarteDuTemoin(String implementation) {
        var temoin = Corpus.temoin("fiche", true);
        var ecart = assertThrows(IllegalStateException.class,
                () -> Corpus.preparer("fiche", 10, true, implementation, donnees -> temoin.apply(donnees) + " "));
        assertTrue(ecart.getMessage().startsWith("Sortie différente : fiche, densité 10 %, échappement true"), ecart.getMessage());
    }

    @Test void prepareLImplementationChoisie() {
        Object jeu = Corpus.donnees("fiche", 10)[0];
        String attendu = Corpus.temoin("fiche", true).apply(jeu);
        assertEquals(attendu, Corpus.preparer("fiche", 10, true, "temoin", Corpus.verne("fiche", true)).apply(jeu));
        assertEquals(attendu, Corpus.preparer("fiche", 10, true, "verne", Corpus.verne("fiche", true)).apply(jeu));
    }

    @Test void compteCentInterpolationsDansLeCasDense100() throws IOException {
        for (String fichier : List.of("dense100Html.html.verne", "dense100Texte.texte.verne")) {
            String source = Files.readString(GABARITS.resolve(fichier));
            assertEquals(100, INTERPOLATION.matcher(source).results().count(), fichier);
        }
    }

    @Test void partageUneMemeSourceEntreLesDeuxVariantesDUnCas() throws IOException {
        for (var variantes : List.of(List.of("ficheHtml.html", "ficheTexte.texte"), List.of("dense100Html.html", "dense100Texte.texte"),
                List.of("grandHtml.html", "grandTexte.texte"), List.of("factureXml.xml", "factureTexte.texte"),
                List.of("ficheJson.json", "ficheJsonTexte.texte"))) {
            assertEquals(Files.readString(GABARITS.resolve(variantes.get(0) + ".verne")),
                    Files.readString(GABARITS.resolve(variantes.get(1) + ".verne")), variantes.toString());
        }
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
