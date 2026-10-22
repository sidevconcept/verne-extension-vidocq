package io.vidocq.verne.compilation;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;

/**
 * Indique si un type est accessible depuis le paquet généré, d'après le seul modèle symbolique de javac.
 * L'instance appartient à une compilation ; elle ne charge aucune classe et ne conserve aucun état partagé.
 */
final class VisibiliteModules {
    private final Elements elements;
    private final ModuleElement courant;
    private final Set<ModuleElement> lus = new HashSet<>();

    /** Un module courant nul ou sans nom n'est soumis qu'aux exports sans restriction des modules nommés. */
    VisibiliteModules(Elements elements, ModuleElement courant) {
        this.elements = elements;
        this.courant = courant;
        if (courant != null && !courant.isUnnamed()) {
            var aParcourir = new ArrayDeque<ModuleElement>();
            lus.add(courant);
            ModuleElement base = elements.getModuleElement("java.base");
            if (base != null) lus.add(base);
            for (var requis : ElementFilter.requiresIn(courant.getDirectives())) {
                if (lus.add(requis.getDependency())) aParcourir.add(requis.getDependency());
            }
            while (!aParcourir.isEmpty()) {
                for (var requis : ElementFilter.requiresIn(aParcourir.remove().getDirectives())) {
                    if (requis.isTransitive() && lus.add(requis.getDependency())) aParcourir.add(requis.getDependency());
                }
            }
        }
    }

    /** Le type et ses englobants sont publics, et son paquet est exporté vers un module lu. */
    boolean accessible(TypeElement type) {
        for (Element element = type; element instanceof TypeElement; element = element.getEnclosingElement()) {
            if (!element.getModifiers().contains(Modifier.PUBLIC)) return false;
        }
        ModuleElement module = elements.getModuleOf(type);
        if (module == null || module.isUnnamed() || module.equals(courant)) return true;
        boolean courantNomme = courant != null && !courant.isUnnamed();
        if (courantNomme && !lus.contains(module)) return false;
        var paquet = elements.getPackageOf(type);
        return ElementFilter.exportsIn(module.getDirectives()).stream()
                .anyMatch(export -> export.getPackage().equals(paquet) && (export.getTargetModules() == null
                        || courantNomme && export.getTargetModules().contains(courant)));
    }
}
