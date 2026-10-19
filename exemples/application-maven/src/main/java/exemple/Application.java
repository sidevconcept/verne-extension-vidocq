package exemple;

import io.vidocq.verne.genere.Bonjour;

/** Lance le rendu sans processeur ni moteur chargé à l'exécution. */
public final class Application {
    private Application() {}

    /** Imprime le document sans ajouter ni modifier sa fin de ligne. */
    public static void main(String[] arguments) {
        System.out.print(Bonjour.rendre(new Personne("Léa")));
    }
}
