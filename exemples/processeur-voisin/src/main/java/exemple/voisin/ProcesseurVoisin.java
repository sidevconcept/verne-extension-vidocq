package exemple.voisin;

import java.io.IOException;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/** Produit un modèle au deuxième tour et vérifie le mode réel de chargement du voisin. */
public final class ProcesseurVoisin extends AbstractProcessor {
    private int nombreTours;

    /** Constructeur public du fournisseur de service. */
    public ProcesseurVoisin() {}

    @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.RELEASE_25; }
    @Override public Set<String> getSupportedAnnotationTypes() { return Set.of("exemple.Marque"); }
    @Override public Set<String> getSupportedOptions() { return Set.of("voisin.chargement"); }

    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment tour) {
        if (tour.processingOver()) return false;
        if (nombreTours == 0) {
            String mode = processingEnv.getOptions().get("voisin.chargement");
            if ((!"classique".equals(mode) && !"modules".equals(mode))
                    || ("modules".equals(mode) != getClass().getModule().isNamed())) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "VOISIN-CFG-001 : mode de chargement inattendu.");
                return true;
            }
            if (annotations.stream().noneMatch(annotation ->
                    annotation.getQualifiedName().contentEquals("exemple.Marque"))) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "VOISIN-CFG-002 : annotation du voisin absente.");
                return true;
            }
        }
        try {
            switch (++nombreTours) {
                case 1 -> produire("Etape", "public record Etape() {}");
                case 2 -> produire("PersonneGeneree", "public record PersonneGeneree(String nom) {}");
                default -> {}
            }
        } catch (IOException exception) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "VOISIN-IO-001 : impossible de produire le modèle voisin.");
        }
        return true;
    }

    private void produire(String nom, String declaration) throws IOException {
        try (var sortie = processingEnv.getFiler().createSourceFile("exemple." + nom).openWriter()) {
            sortie.write("package exemple; " + declaration);
        }
    }
}
