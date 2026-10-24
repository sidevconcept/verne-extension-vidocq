# VERNE-005 — Suite complète exécutée sur Linux et Windows

## Pilotage
- Nature : investigation et outillage
- État : À préparer — en attente de D-005
- Priorité et raison : moyenne ; seuls les 42 tests initiaux ont été exécutés sur Windows, aucun sur Linux (`RESTE-A-FAIRE.md`, « État acquis » et §10). Les liens symboliques, l'encodage et le nommage dépendent du système.
- Source faisant autorité : https://github.com/sidevconcept/verne-extension-vidocq/issues/30
- Responsable : `agent-b` (Codex)
- Relecteur : `agent-a` (Claude)
- Mise à jour : 24 octobre 2026, Europe/Paris ; `agent-a`
- Dépôt et cible d'intégration : `verne-extension-vidocq` ; `main`
- Base de travail : à fixer au démarrage
- Branche du ticket : `qualification/linux-windows`
- Bloqué par : D-005
- Débloque : tickets de correction éventuels, un par cause
- Travaux simultanés compatibles : VERNE-006 (#31) (documentation seule)
- Zones ou contrats partagés : `.github/workflows/` (nouveau) si D-005 retient l'intégration continue de la forge
- Autorisations : travail local autorisé ; publication et fusion sur demande explicite de l'utilisateur (D0) ; l'exécution sur la forge dépend de D-005

## Besoin et contexte
- Objectif : exécuter `./mvnw test` (Linux) et `.\mvnw.cmd test` (Windows) avec Java 25 et consigner chaque échec avec sa cause.
- Comportement actuel observé : aucune exécution récente hors macOS ; aucune intégration continue dans le dépôt.
- Comportement attendu : résultats par système consignés ; chaque échec rattaché à une cause et à un ticket de correction distinct.
- Reproduction ou exemple minimal : `./mvnw test` sur une machine Linux avec Java 25.
- Dans le périmètre : Linux et Windows ; macOS en témoin ; consignation des résultats ; mise en place de l'exécution automatique si D-005 la retient.
- Hors périmètre : corrections de code (tickets distincts).
- Hypothèses à vérifier : la création de liens symboliques sous Windows exige le mode développeur ou un privilège ; les tests qui en créent doivent alors le signaler sans masquer l'absence de vérification.
- Questions bloquantes : D-005.

## Références et contrat
- Instructions applicables : `AGENTS.md` ; `docs/fr/protocole-tickets-agents.md`
- Contrat de référence : `premiers-tests.adoc` (à lire avant toute modification du contrat de vérification) ; `compilateur-minimal.adoc`, « Racine et liens symboliques », « Encodage et noms »
- Parcours du code : sans objet — exécution de la suite
- Appelants, tests et ressources concernés : `RacinesEtLiensGabaritsTest`, `PortabiliteGabaritsTest`, `ApplicationMavenTest`, `ChargementConjointModulesTest`
- Données et nullité : sans objet
- Erreurs : chaque échec consigné avec commande, système, extrait
- Effets de bord et ressources : exécutions sur machines ou services de la forge
- Concurrence : sans objet
- Compatibilité et sécurité : aucun secret dans la configuration d'intégration continue
- Performance : sans objet

## Critères de réussite
| Identifiant | Situation / entrée | Résultat observable | Vérification prévue |
|---|---|---|---|
| C1 | Linux, Java 25 | nombre de tests exécutés, réussis, en échec, en erreur, ignorés ; code de sortie | consigné ici |
| C2 | Windows, Java 25 | idem | consigné ici |
| C3 | chaque échec | cause identifiée et ticket de correction créé | liste des tickets |
| C4 | si D-005 retient l'intégration continue | exécution sur chaque PR vers `main` pour les trois systèmes | exécution observée |
| C5 | aucun test désactivé ou ignoré pour obtenir un résultat vert | vérifié | relecture du diff |

## Réalisation prévue
1. Obtenir D-005.
2. Exécuter la suite sur chaque système et consigner.
3. Ouvrir un ticket par cause d'échec.
- Fichiers susceptibles d'être modifiés : `.github/workflows/*.yml` selon D-005 ; `RESTE-A-FAIRE.md`
- Risques de régression : sans objet — aucun changement de code

## Vérifications et preuves
- Environnement : à relever par système
- Référence initiale : à consigner
- Phase rouge : sans objet — qualification
- Cause attendue confirmée : sans objet
- Phase verte : sans objet
- Suite complète : par système, à consigner
- Contrôles supplémentaires : —
- Preuves consultables : extraits dans ce ticket
- Non exécuté : à consigner

## Décisions
- D-005 — à décider par l'utilisateur : exécution par l'intégration continue de la forge GitHub (macOS, Linux, Windows), ou exécutions manuelles sur des machines fournies.

## Livraison et passage de relais
- Révision livrée : —
- Accès : —
- Résumé du changement : —
- Critères satisfaits : —
- Travail restant : tout
- Blocages et limites : D-005
- Prochaine action : obtenir D-005
- Reprise attribuée à : —
- Revue demandée : aucune vérification masquée ; causes correctement attribuées

## Revue
- Relecteur : `agent-a`
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
