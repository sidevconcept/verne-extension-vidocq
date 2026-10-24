# VERNE-006 — Contrat de syntaxe et de typage des expressions

## Pilotage
- Nature : documentation
- État : À préparer — décisions de syntaxe attendues de l'utilisateur
- Priorité et raison : moyenne ; premier pas de `RESTE-A-FAIRE.md` §4. Conditions, boucles et valeurs de remplacement reposent sur les expressions ; leur contrat doit précéder tout code.
- Source faisant autorité : https://github.com/sidevconcept/verne-extension-vidocq/issues/31
- Responsable : `agent-a` (Claude)
- Relecteur : `agent-b` (Codex)
- Mise à jour : 24 octobre 2026, Europe/Paris ; `agent-a`
- Dépôt et cible d'intégration : `verne-extension-vidocq` ; `main`
- Base de travail : à fixer au démarrage
- Branche du ticket : `documentation/contrat-expressions`
- Bloqué par : décisions D-006a à D-006d
- Débloque : implémentation des expressions (F-06), puis conditions (F-08) et absence explicite (F-07)
- Travaux simultanés compatibles : VERNE-005 (#30)
- Zones ou contrats partagés : `compilateur-minimal.adoc`, « Grammaire effectivement acceptée » (lecture seule) ; nouvelle page sous `docs/fr/modules/ROOT/pages/`
- Autorisations : travail local autorisé ; publication et fusion sur demande explicite de l'utilisateur (D0, 24 octobre 2026)

## Besoin et contexte
- Objectif : documenter la grammaire, la précédence et le typage des expressions, avec les cas de test T-EXP-01 à 06 détaillés, sans implémentation.
- Comportement actuel observé : une interpolation accepte un paramètre et une navigation de records (`{u.adresse.ville}`), de type `String` ou primitif uniquement.
- Comportement attendu : une page de contrat validée, suffisante pour un ticket d'implémentation.
- Reproduction ou exemple minimal : `{prix * quantite}` est refusé aujourd'hui ; code exact à relever (hypothèse : `VERNE-SYN-002`).
- Dans le périmètre : littéraux, opérateurs arithmétiques, de comparaison et logiques, parenthèses, accès indexés, règles de typage et de conversion, diagnostics, limites (profondeur, longueur).
- Hors périmètre : conditions et boucles (contrats suivants) ; fonctions et espaces de noms (F-11).
- Hypothèses à vérifier : —
- Questions bloquantes :
  - D-006a — conversions implicites entre primitifs (promotion Java ou refus) ;
  - D-006b — concaténation de chaînes par opérateur ou interdite ;
  - D-006c — accès indexés dès ce contrat ou reportés avec les collections ;
  - D-006d — interpolation d'un résultat non `String` et non primitif, aujourd'hui refusée.

## Références et contrat
- Instructions applicables : `AGENTS.md` ; `docs/fr/protocole-tickets-agents.md`
- Contrat de référence : `compilateur-minimal.adoc`, « Grammaire effectivement acceptée » ; `matrice-fonctionnelle.adoc` F-06, F-07, F-12 ; `cas-de-test.adoc` T-EXP-01 à 06
- Parcours du code : sans objet — documentation seule
- Appelants, tests et ressources concernés : —
- Données et nullité : à définir par le contrat
- Erreurs : nouveaux codes à réserver dans la famille `VERNE-TYP-*` ou `VERNE-SYN-*`
- Effets de bord et ressources : aucun ; pas d'évaluation Java arbitraire (F-06)
- Concurrence : sans objet — évaluation dans la méthode de rendu, sans état partagé
- Compatibilité et sécurité : la grammaire actuelle reste valide ; aucune expression ne contourne les contextes des profils
- Performance : le contrat fixe les limites qui bornent le coût de génération (`VERNE-LIM-008`)

## Critères de réussite
| Identifiant | Situation / entrée | Résultat observable | Vérification prévue |
|---|---|---|---|
| C1 | décisions D-006a à D-006d | consignées avec leur source | ce ticket |
| C2 | page de contrat | grammaire, table de précédence, règles de typage, diagnostics et limites | relecture par `agent-b` |
| C3 | T-EXP-01 à 06 | chaque cas détaillé avec entrée, sortie ou diagnostic exact | relecture |
| C4 | compatibilité | chaque gabarit valide de la suite actuelle reste valide sous le contrat | relecture croisée des tests existants |

## Réalisation prévue
1. Obtenir D-006a à D-006d.
2. Rédiger la page de contrat et les cas de test.
- Fichiers susceptibles d'être modifiés : nouvelle page sous `docs/fr/modules/ROOT/pages/` ; `cas-de-test.adoc` ; `index.adoc`
- Risques de régression : sans objet — documentation seule ; contrôles documentaires uniquement

## Vérifications et preuves
- Environnement : sans objet
- Référence initiale : —
- Phase rouge : sans objet — documentation seule
- Cause attendue confirmée : sans objet
- Phase verte : sans objet
- Suite complète : sans objet
- Contrôles supplémentaires : relecture
- Preuves consultables : —
- Non exécuté : —

## Décisions
- D-006a à D-006d — à décider par l'utilisateur.

## Livraison et passage de relais
- Révision livrée : —
- Accès : —
- Résumé du changement : —
- Critères satisfaits : —
- Travail restant : tout
- Blocages et limites : décisions de syntaxe
- Prochaine action : soumettre D-006a à D-006d à l'utilisateur
- Reprise attribuée à : —
- Revue demandée : ambiguïtés de grammaire ; cohérence avec les profils de sortie

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
- Suivis distincts : implémentation des expressions
