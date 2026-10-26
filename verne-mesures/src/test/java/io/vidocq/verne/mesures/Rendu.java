package io.vidocq.verne.mesures;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.openjdk.jmh.annotations.*;

/** Rendu préparé du corpus par le témoin et par Verne ; l'oracle est vérifié avant chaque essai. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
@State(Scope.Thread)
public class Rendu {
    @Param({"fiche", "dense100", "grand", "facture", "ficheJson"}) public String cas;
    @Param({"0", "10", "50"}) public int densite;
    @Param({"temoin", "verne"}) public String implementation;
    @Param({"true", "false"}) public boolean echappement;
    private Function<Object, String> rendu;
    private Object[] donnees;
    private int index;

    @Setup(Level.Trial) public void preparer() {
        rendu = Corpus.preparer(cas, densite, echappement, implementation, Corpus.verne(cas, echappement));
        donnees = Corpus.donnees(cas, densite);
    }

    @Benchmark public String rendre() {
        Object jeu = donnees[index];
        index = (index + 1) % Corpus.JEUX;
        return rendu.apply(jeu);
    }
}
