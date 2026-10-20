package io.vidocq.verne.invalidation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import javax.lang.model.SourceVersion;

/** Calcule les sorties affectées par les entrées de construction, sans accès au système de fichiers. */
public final class InvalidationGabarits {
    private InvalidationGabarits() {}

    /** Empreinte de contenu et dépendances Java ; copies immuables, valeurs non nulles. */
    public record Gabarit(String empreinte, Set<String> typesJava) {
        public Gabarit {
            verifierEmpreinte(empreinte);
            Objects.requireNonNull(typesJava, "Les dépendances Java sont obligatoires.");
            typesJava.forEach(InvalidationGabarits::verifierType);
            typesJava = Set.copyOf(typesJava);
        }
    }

    /** Instantané fourni par l'intégration ; une dépendance absente interdit la réutilisation. */
    public record Etat(String contexte, Map<String, Gabarit> gabarits, Map<String, String> typesJava) {
        public Etat {
            verifierEmpreinte(contexte);
            Objects.requireNonNull(gabarits, "Les gabarits sont obligatoires.");
            Objects.requireNonNull(typesJava, "Les empreintes Java sont obligatoires.");
            gabarits.forEach((identifiant, gabarit) -> {
                verifierIdentifiant(identifiant);
                Objects.requireNonNull(gabarit, "L'empreinte du gabarit est obligatoire.");
            });
            typesJava.forEach((type, empreinte) -> {
                verifierType(type);
                verifierEmpreinte(empreinte);
            });
            gabarits = Map.copyOf(gabarits);
            typesJava = Map.copyOf(typesJava);
        }
    }

    /** Décisions immuables ; comparer produit des listes triées et disjointes. */
    public record Plan(List<String> recompiler, List<String> supprimer, List<String> conserver) {
        public Plan {
            recompiler = List.copyOf(Objects.requireNonNull(recompiler, "La liste à recompiler est obligatoire."));
            supprimer = List.copyOf(Objects.requireNonNull(supprimer, "La liste à supprimer est obligatoire."));
            conserver = List.copyOf(Objects.requireNonNull(conserver, "La liste à conserver est obligatoire."));
        }
    }

    /** Un précédent nul désigne la première construction ; le courant est obligatoire. Aucun effet de bord. */
    public static Plan comparer(Etat precedent, Etat courant) {
        Objects.requireNonNull(courant, "L'état courant est obligatoire.");
        var recompiler = new ArrayList<String>();
        var conserver = new ArrayList<String>();
        var supprimer = new TreeSet<String>();
        if (precedent != null) {
            supprimer.addAll(precedent.gabarits().keySet());
            supprimer.removeAll(courant.gabarits().keySet());
        }
        for (String identifiant : new TreeSet<>(courant.gabarits().keySet())) {
            Gabarit gabarit = courant.gabarits().get(identifiant);
            boolean change = precedent == null || !precedent.contexte().equals(courant.contexte())
                    || !gabarit.equals(precedent.gabarits().get(identifiant));
            if (!change) {
                for (String type : gabarit.typesJava()) {
                    String empreinte = courant.typesJava().get(type);
                    if (empreinte == null || !empreinte.equals(precedent.typesJava().get(type))) {
                        change = true;
                        break;
                    }
                }
            }
            (change ? recompiler : conserver).add(identifiant);
        }
        return new Plan(recompiler, List.copyOf(supprimer), conserver);
    }

    private static void verifierEmpreinte(String empreinte) {
        Objects.requireNonNull(empreinte, "L'empreinte est obligatoire.");
        if (!empreinte.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Empreinte SHA-256 attendue en hexadécimal minuscule.");
        }
    }

    private static void verifierType(String type) {
        Objects.requireNonNull(type, "Le nom du type est obligatoire.");
        if (!SourceVersion.isName(type, SourceVersion.RELEASE_25)
                || type.codePoints().anyMatch(Character::isIdentifierIgnorable)) {
            throw new IllegalArgumentException("Nom Java qualifié attendu pour une dépendance.");
        }
    }

    private static void verifierIdentifiant(String identifiant) {
        Objects.requireNonNull(identifiant, "L'identifiant du gabarit est obligatoire.");
        if (!identifiant.endsWith(".verne") || identifiant.indexOf('\\') >= 0
                || identifiant.indexOf(':') >= 0 || identifiant.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Identifiant relatif de gabarit .verne attendu.");
        }
        for (String segment : identifiant.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Segment de gabarit vide ou relatif interdit.");
            }
        }
    }
}
