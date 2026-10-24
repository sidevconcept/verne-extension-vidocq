# VERNE-001 — Banc de micro-mesures versionné et reproductible

## Pilotage
- Nature : capacité (outillage de vérification)
- État : À préparer — en attente de D0 (`tickets/README.md`)
- Priorité et raison : haute ; VERNE-003 et toute optimisation future exigent une mesure avant/après que le relecteur peut rejouer. Les mesures du 22-23 octobre 2026 ont été faites avec un banc hors dépôt, dans un espace temporaire de session inaccessible à `agent-b`.
- Source faisant autorité : selon D0
- Responsable : `agent-a` (Claude)
- Relecteur : `agent-b` (Codex)
- Mise à jour : 24 octobre 2026, Europe/Paris ; `agent-a`
- Dépôt et cible d'intégration : `verne-extension-vidocq` ; `main`
- Base de travail : `6c6825883176d237e4ece2f8115e91506af357b1`
- Branche du ticket : `fonctionnalite/banc-micro-mesures`
- Bloqué par : D0
- Débloque : VERNE-003
- Travaux simultanés compatibles : VERNE-002 (aucun fichier de code commun)
- Zones ou contrats partagés : `pom.xml` racine (ajout d'un module, seul ticket à le modifier en vague 1) ; `mesures-performance.adoc` ; mentions du nombre de tests (voir `tickets/README.md`)
- Autorisations : travail local autorisé ; publication et fusion selon D0

## Besoin et contexte
- Objectif : mesurer le rendu des classes générées par Verne contre un témoin Java écrit à la main, depuis le dépôt, avec une commande documentée.
- Comportement actuel observé : `mesures-performance.adoc` décrit un protocole et un corpus, mais le dépôt ne contient aucun banc. Les résultats de la page du plan (M-02, M-03, M-04, M-09 et tampon dimensionné) proviennent d'un banc JMH 1.37 local non versionné.
- Comportement attendu : un module de vérification contenant le corpus, le témoin, un oracle d'égalité exécuté par `./mvnw test`, et des mesures JMH lancées uniquement à la demande.
- Reproduction ou exemple minimal : depuis une copie propre de `main`, aucun moyen de rejouer « fiche HTML, densité 50 % ».
- Dans le périmètre : corpus texte, HTML, XML et JSON (fiche de 7 valeurs, 100 interpolations, grand document de 64 Kio, facture XML) ; densités de caractères à échapper 0, 10 et 50 % tirées d'une graine fixe ; témoin Java ; oracle ; commande JMH ; documentation.
- Hors périmètre : comparaison avec un moteur externe (reste dans le banc indépendant hors dépôt) ; mesures d'intégration HTTP ; corpus M-03 à M-09 de `mesures-performance.adoc` qui dépendent de capacités absentes (conditions, boucles, inclusions, flux).
- Hypothèses à vérifier : `annotationProcessorPaths` de maven-compiler-plugin 3.14.1 résout l'artefact `verne-compilation` du réacteur sans installation préalable ; sinon, compiler les gabarits du banc par l'API javax.tools comme `BancCompilation`.
- Questions bloquantes : aucune au-delà de D0.

## Références et contrat
- Instructions applicables : `AGENTS.md` ; `docs/fr/protocole-tickets-agents.md`
- Contrat de référence : `docs/fr/modules/ROOT/pages/mesures-performance.adoc`, sections « Corpus versionné », « Micro-mesures », « Environnement et reproductibilité » ; révision de base
- Parcours du code : gabarit `.verne` → `ProcesseurVerne` → classe générée `rendre(...)` → chaîne comparée au témoin, puis chronométrée par JMH
- Appelants, tests et ressources concernés : `pom.xml` racine (modules) ; nouveau module, par exemple `verne-mesures`
- Données et nullité : valeurs non nulles générées de façon déterministe ; aucune donnée réelle
- Erreurs : un écart entre Verne et le témoin fait échouer l'oracle avant toute mesure
- Effets de bord et ressources : résultats JMH écrits dans `target/`, jamais versionnés automatiquement
- Concurrence : sans objet — JMH mono-fil par défaut ; le protocole ne demande pas de mesure concurrente dans ce ticket
- Compatibilité et sécurité : JMH et son processeur en portée test ; module non déployé ; dépendances de `verne-compilation` inchangées
- Performance : c'est l'objet du ticket ; l'oracle ajouté à `./mvnw test` doit rester court

## Critères de réussite
| Identifiant | Situation / entrée | Résultat observable | Vérification prévue |
|---|---|---|---|
| C1 | `./mvnw test` depuis la racine | le module se construit, l'oracle s'exécute, aucune mesure JMH ne tourne | sortie Maven ; nombre de tests |
| C2 | chaque cas du corpus × densité × profil | sortie Verne identique au témoin, caractère par caractère | test d'oracle |
| C3 | témoin volontairement faussé d'un caractère | l'oracle échoue en nommant le cas | phase rouge consignée |
| C4 | deux générations du corpus | textes identiques (graine fixe) | test |
| C5 | commande JMH documentée | résultats JSON produits avec 2 JVM, 3 échauffements, 5 mesures d'une seconde, profileur GC | exécution consignée dans le ticket |
| C6 | arbre des dépendances | JMH absent des portées compile et runtime de `verne-compilation` ; module non déployé | `./mvnw dependency:tree` ; configuration de déploiement |
| C7 | documentation | `mesures-performance.adoc` décrit le module, les commandes et la correspondance avec les identifiants de la page du plan | relecture |

## Réalisation prévue
1. Documenter dans `mesures-performance.adoc` le banc versionné : corpus, oracle, commande, portée des dépendances.
2. Écrire l'oracle et le constater en échec avec un témoin faussé.
3. Porter le corpus et le témoin du banc local, sans comparaison externe.
4. Ajouter la commande JMH et rejouer une mesure de référence.
- Fichiers susceptibles d'être modifiés : `pom.xml` ; `verne-mesures/**` ; `mesures-performance.adoc` ; mentions du nombre de tests
- Risques de régression : durée de `./mvnw test` ; résolution du processeur dans le réacteur

## Vérifications et preuves
- Environnement : macOS, Java 25, Maven 3.9.16 par `./mvnw`
- Référence initiale : `6c6825883176d237e4ece2f8115e91506af357b1` ; `./mvnw test` ; 259 tests attendus, à relever
- Phase rouge : à consigner
- Cause attendue confirmée : à consigner
- Phase verte : à consigner
- Suite complète : à consigner
- Contrôles supplémentaires : arbre des dépendances
- Preuves consultables : extraits de sortie dans ce ticket
- Non exécuté : à consigner

## Décisions
- D1 — 24 octobre 2026 ; aucune comparaison externe dans le dépôt ; `AGENTS.md`.

## Livraison et passage de relais
- Révision livrée : —
- Accès : —
- Résumé du changement : —
- Critères satisfaits : —
- Travail restant : tout
- Blocages et limites : D0
- Prochaine action : après D0, documenter le banc dans `mesures-performance.adoc`
- Reprise attribuée à : —
- Revue demandée : portée des dépendances ; déterminisme du corpus ; l'oracle détecte-t-il un écart d'échappement réel ?

## Revue
- Relecteur : `agent-b`
- Base et révision examinée : —
- Diff examiné : —
- Vérifications réellement exécutées : —
- Observations : —
- Verdict : —
- Limites de la revue : —

## Intégration et clôture
- Autorisation d'intégration : non reçue
- Révision intégrée et cible : —
- Vérifications après intégration : —
- Critères clôturés : —
- Suivis distincts : —
