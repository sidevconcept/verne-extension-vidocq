# VERNE-003 — Échappeurs HTML, XML et JSON par table constante, sans régression

## Pilotage
- Nature : capacité (performance)
- État : À préparer — bloqué par VERNE-001
- Priorité et raison : moyenne ; à 50 % de caractères à échapper, la fiche JSON est mesurée plus lente qu'une implémentation par table (page du plan, mesure M-09, 23 octobre 2026). L'aiguillage `switch` par caractère des échappeurs générés est la cause supposée.
- Source faisant autorité : https://github.com/sidevconcept/verne-extension-vidocq/issues/28
- Responsable : `agent-a` (Claude)
- Relecteur : `agent-b` (Codex)
- Mise à jour : 24 octobre 2026, Europe/Paris ; `agent-a`
- Dépôt et cible d'intégration : `verne-extension-vidocq` ; `main`
- Base de travail : à fixer après intégration de VERNE-001
- Branche du ticket : `fonctionnalite/echappeurs-par-table`
- Bloqué par : VERNE-001 (#26)
- Débloque : aucun
- Travaux simultanés compatibles : VERNE-004 (#29) (zones distinctes de `CompilateurGabarit.java`)
- Zones ou contrats partagés : `CompilateurGabarit.java`, constantes `ECHAPPEMENT_*` uniquement ; VERNE-004 (#29) modifie l'émission des erreurs dans le même fichier. Ordre indifférent ; le second intégré se rebase et rejoue la suite complète.
- Autorisations : travail local autorisé ; publication et fusion sur demande explicite de l'utilisateur (D0, 24 octobre 2026)

## Besoin et contexte
- Objectif : remplacer l'aiguillage par caractère des échappeurs générés par une table de remplacement constante pour les caractères ASCII, si la mesure le justifie.
- Comportement actuel observé : `CompilateurGabarit` insère dans chaque classe générée les méthodes `ECHAPPEMENT_HTML`, `ECHAPPEMENT_HTML_TAMPON`, `ECHAPPEMENT_XML`, `ECHAPPEMENT_XML_TAMPON`, `ECHAPPEMENT_JSON_TAMPON` et `ECHAPPEMENT_JSON_CHAINE`, fondées sur un `switch`.
- Comportement attendu : sorties strictement identiques ; temps de rendu réduit à forte densité, sans dégradation à densité nulle.
- Reproduction ou exemple minimal : banc de VERNE-001, cas « fiche JSON », densités 0 et 50 %.
- Dans le périmètre : les trois profils ; petits et grands documents ; mesure avant et après.
- Hors périmètre : changement des caractères échappés ou de leur forme ; profil texte.
- Hypothèses à vérifier : le coût mémoire d'une table par classe générée reste négligeable (moins de 1 Kio par profil et par classe) ; JIT : la table n'empêche pas l'inlining.
- Questions bloquantes : aucune.

## Références et contrat
- Instructions applicables : `AGENTS.md` ; `docs/fr/protocole-tickets-agents.md`
- Contrat de référence : `compilateur-minimal.adoc`, sections « Échappement des valeurs », « Profil JSON », « Tampon dimensionné des petits documents » ; `mesures-performance.adoc`, « Lecture et qualification des écarts »
- Parcours du code : `CompilateurGabarit` (choix du profil et des constantes, lignes 160 à 175 de la base) → méthodes privées statiques de la classe générée → `rendre(...)`
- Appelants, tests et ressources concernés : `ProfilsSortieTest`, `ProfilJsonTest`, `PetitsDocumentsTest`, `GrandsDocumentsTest`, banc de VERNE-001
- Données et nullité : inchangées ; une valeur nulle reste refusée par `VERNE-REN-001`
- Erreurs : `VERNE-REN-002` et `VERNE-REN-003` inchangés
- Effets de bord et ressources : une constante statique de plus par classe générée et par profil utilisé
- Concurrence : table immuable, partagée en lecture seule ; `isoleLesRendusJsonConcurrents` et équivalents doivent rester verts
- Compatibilité et sécurité : aucune différence de sortie admise ; substituts isolés et caractères interdits en XML toujours traités
- Performance : objet du ticket ; protocole de VERNE-001

## Critères de réussite
| Identifiant | Situation / entrée | Résultat observable | Vérification prévue |
|---|---|---|---|
| C1 | chaque caractère de U+0000 à U+00FF, plus U+2028, U+2029, substitut isolé et emoji, dans chaque profil et chaque taille de document | sortie identique à celle de la base | nouveau test exhaustif, vert sur la base puis après changement |
| C2 | suite complète | aucune régression | `./mvnw test` |
| C3 | banc VERNE-001, HTML, XML et JSON, densités 0, 10 et 50 % | à 0 % : pas de dégradation au-delà des intervalles de confiance à 99 % ; à 50 % : gain mesuré et chiffré | résultats JMH avant/après, ordre alterné |
| C4 | absence de gain à 50 % | le ticket est clos sans intégrer le changement, mesure consignée | décision documentée |
| C5 | documentation | contrat inchangé ; section de vérification ajoutée avec les chiffres | relecture |

## Réalisation prévue
1. Écrire le test exhaustif C1, vert sur la base (test de non-régression ; la phase rouge est obtenue en faussant volontairement une entrée de la table).
2. Mesurer la base avec le banc de VERNE-001.
3. Remplacer l'aiguillage par la table, profil par profil.
4. Remesurer en alternant l'ordre ; consigner les chiffres ici et sur la page du plan.
- Fichiers susceptibles d'être modifiés : `CompilateurGabarit.java` ; nouveau test ; `compilateur-minimal.adoc` ; mentions du nombre de tests
- Risques de régression : sortie modifiée pour un caractère limite ; budget de génération `VERNE-LIM-008`

## Vérifications et preuves
- Environnement : macOS, Java 25, Maven 3.9.16 ; Mac sur secteur
- Référence initiale : à consigner
- Phase rouge : à consigner
- Cause attendue confirmée : à consigner
- Phase verte : à consigner
- Suite complète : à consigner
- Contrôles supplémentaires : concurrence (tests existants) ; mesure JMH
- Preuves consultables : tableaux de mesure dans ce ticket
- Non exécuté : à consigner

## Décisions
- Aucune.

## Livraison et passage de relais
- Révision livrée : —
- Accès : —
- Résumé du changement : —
- Critères satisfaits : —
- Travail restant : tout
- Blocages et limites : VERNE-001 (#26)
- Prochaine action : après intégration de VERNE-001 (#26), écrire le test exhaustif C1
- Reprise attribuée à : —
- Revue demandée : équivalence exacte des sorties ; lecture statistique des mesures

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
