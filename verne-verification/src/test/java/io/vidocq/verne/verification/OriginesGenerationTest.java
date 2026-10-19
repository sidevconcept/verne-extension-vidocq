package io.vidocq.verne.verification;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.Executors;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Observe les origines transmises au vrai Filer, sans remplacer le compilateur Verne. */
@Timeout(30)
final class OriginesGenerationTest {
    @TempDir Path repertoire;

    @Test void suitLesTypesTraversesEtLesParentsSansDoublons() throws Exception {
        var origines = compiler(repertoire, Map.of("bonjour.verne",
                "{@exemple.Modele.Personne p}{@exemple.Inutilise autre}{p.adresse.ville}{p.adresse.ville}"), Map.of(
                "exemple/Modele.java", "package exemple; public class Modele { public record Personne(Adresse adresse) {} }",
                "exemple/Adresse.java", "package exemple; public record Adresse(String ville) {}",
                "exemple/Inutilise.java", "package exemple; public record Inutilise(int nombre) {}",
                "exemple/Etranger.java", "package exemple; public record Etranger(String nom) {}"), true, false);
        assertEquals(List.of("exemple.Modele.Personne", "exemple.Modele", "exemple.Inutilise",
                "exemple.Adresse", "java.lang.String"), origines.get("io.vidocq.verne.genere.Bonjour"));
    }

    @Test void isoleLesOriginesDeChaqueGabarit() throws Exception {
        var origines = compiler(repertoire, Map.of("bonjour.verne", "{@java.lang.String nom}{nom}",
                "statique.verne", "Texte"), Map.of(), true, false);
        assertEquals(List.of("java.lang.String"), origines.get("io.vidocq.verne.genere.Bonjour"));
        assertEquals(List.of(), origines.get("io.vidocq.verne.genere.Statique"));
    }

    @Test void nePubliePasLesOriginesDunGabaritRefuse() throws Exception {
        var origines = compiler(repertoire, Map.of("bonjour.verne", "{@java.lang.String nom}{absent}"),
                Map.of(), false, false);
        assertTrue(origines.isEmpty());
    }

    @Test void reprendLesOriginesDuModeleGenereAuTourSuivant() throws Exception {
        var origines = compiler(repertoire, Map.of("bonjour.verne", "{@exemple.Personne p}{p.nom}"),
                Map.of(), true, true);
        assertEquals(List.of("exemple.Personne", "java.lang.String"), origines.get("io.vidocq.verne.genere.Bonjour"));
    }

    @Test void neDeclarePasDeTypePourUnScalairePrimitif() throws Exception {
        var origines = compiler(repertoire, Map.of("bonjour.verne", "{@exemple.Nombre n}{n.valeur}"),
                Map.of("exemple/Nombre.java", "package exemple; public record Nombre(int valeur) {}"), true, false);
        assertEquals(List.of("exemple.Nombre"), origines.get("io.vidocq.verne.genere.Bonjour"));
    }

    @Test void conserveDesCollectesIndependantesEnConcurrence() throws Exception {
        try (var execution = Executors.newFixedThreadPool(2)) {
            var premiere = execution.submit(() -> compiler(repertoire.resolve("un"),
                    Map.of("bonjour.verne", "{@java.lang.String nom}{nom}"), Map.of(), true, false));
            var seconde = execution.submit(() -> compiler(repertoire.resolve("deux"),
                    Map.of("bonjour.verne", "Texte"), Map.of(), true, false));
            assertEquals(List.of("java.lang.String"), premiere.get().get("io.vidocq.verne.genere.Bonjour"));
            assertEquals(List.of(), seconde.get().get("io.vidocq.verne.genere.Bonjour"));
        }
    }

    private Map<String, List<String>> compiler(Path racine, Map<String, String> gabarits,
            Map<String, String> sources, boolean succes, boolean avecVoisin) throws Exception {
        Path projet = Path.of(System.getProperty("verne.racine.projet"));
        Path chemin = Path.of(System.getProperty("verne.chemin.processeur",
                projet.resolve("verne-compilation/target/classes").toString()));
        try (var chargement = new URLClassLoader(new java.net.URL[]{chemin.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Processor verne = ServiceLoader.load(Processor.class, chargement).stream()
                    .filter(fournisseur -> fournisseur.type().getName().equals("io.vidocq.verne.compilation.ProcesseurVerne"))
                    .findFirst().orElseThrow().get();
            var observation = new Observation(verne);
            var processeurs = new ArrayList<Processor>(List.of(observation));
            if (avecVoisin) processeurs.add(new Voisin());
            var resultat = BancCompilation.compiler(racine, gabarits, sources, true,
                    racine.resolve("gabarits"), List.of("-proc:full"), processeurs);
            assertEquals(succes, resultat.reussie(), resultat.diagnostics());
            if (!succes) assertTrue(resultat.diagnostics().contains("VERNE-TYP-001"), resultat.diagnostics());
            return observation.origines;
        }
    }

    private static final class Observation extends AbstractProcessor {
        private final Processor verne;
        private final Map<String, List<String>> origines = new LinkedHashMap<>();
        Observation(Processor verne) { this.verne = verne; }
        @Override public Set<String> getSupportedAnnotationTypes() { return verne.getSupportedAnnotationTypes(); }
        @Override public Set<String> getSupportedOptions() { return verne.getSupportedOptions(); }
        @Override public SourceVersion getSupportedSourceVersion() { return verne.getSupportedSourceVersion(); }
        @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment tour) {
            return verne.process(annotations, tour);
        }
        @Override public void init(ProcessingEnvironment environnement) {
            super.init(environnement);
            Filer delegue = environnement.getFiler();
            Filer observation = new Filer() {
                @Override public JavaFileObject createSourceFile(CharSequence nom, Element... elements) throws IOException {
                    origines.put(nom.toString(), java.util.Arrays.stream(elements)
                            .map(element -> ((TypeElement) element).getQualifiedName().toString()).toList());
                    return delegue.createSourceFile(nom, elements);
                }
                @Override public JavaFileObject createClassFile(CharSequence nom, Element... elements) throws IOException {
                    return delegue.createClassFile(nom, elements);
                }
                @Override public FileObject createResource(JavaFileManager.Location lieu, CharSequence paquet,
                        CharSequence nom, Element... elements) throws IOException {
                    return delegue.createResource(lieu, paquet, nom, elements);
                }
                @Override public FileObject getResource(JavaFileManager.Location lieu, CharSequence paquet,
                        CharSequence nom) throws IOException { return delegue.getResource(lieu, paquet, nom); }
            };
            verne.init(new ProcessingEnvironment() {
                @Override public Map<String, String> getOptions() { return environnement.getOptions(); }
                @Override public Messager getMessager() { return environnement.getMessager(); }
                @Override public Filer getFiler() { return observation; }
                @Override public Elements getElementUtils() { return environnement.getElementUtils(); }
                @Override public Types getTypeUtils() { return environnement.getTypeUtils(); }
                @Override public SourceVersion getSourceVersion() { return environnement.getSourceVersion(); }
                @Override public java.util.Locale getLocale() { return environnement.getLocale(); }
                @Override public boolean isPreviewEnabled() { return environnement.isPreviewEnabled(); }
            });
        }
    }

    private static final class Voisin extends AbstractProcessor {
        private boolean genere;
        @Override public Set<String> getSupportedAnnotationTypes() { return Set.of("*"); }
        @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.RELEASE_25; }
        @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment tour) {
            if (!genere && !tour.processingOver()) {
                genere = true;
                try (var sortie = processingEnv.getFiler().createSourceFile("exemple.Personne").openWriter()) {
                    sortie.write("package exemple; public record Personne(String nom) {}");
                } catch (IOException erreur) { throw new java.io.UncheckedIOException(erreur); }
            }
            return false;
        }
    }
}
