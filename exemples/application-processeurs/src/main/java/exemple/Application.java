package exemple;

import io.vidocq.verne.genere.Bonjour;

/** Utilise le modèle et le gabarit produits par les deux processeurs. */
@Marque
public final class Application {
    private Application() {}

    /** Lance le rendu avec le seul module applicatif. */
    public static void main(String[] arguments) {
        System.out.print(Bonjour.rendre(new PersonneGeneree("Léa")));
    }
}
