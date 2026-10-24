# VERNE-004 — Erreurs `.verne` affichées sur leur fichier, ligne et colonne pendant la construction

## Pilotage
- Nature : capacité
- État : À préparer — bloqué par VERNE-002 et D-002
- Priorité et raison : haute ; suite directe de VERNE-002
- Source faisant autorité : selon D0
- Responsable : `agent-b` (Codex)
- Relecteur : `agent-a` (Claude)
- Mise à jour : 24 octobre 2026, Europe/Paris ; `agent-a`
- Dépôt et cible d'intégration : `verne-extension-vidocq` ; `main`
- Base de travail : à fixer après intégration de VERNE-002
- Branche du ticket : `fonctionnalite/diagnostics-positionnes`
- Bloqué par : VERNE-002 ; D-002
- Débloque : rattachement des erreurs du Java généré (T-DIA-02, ticket à créer)
- Travaux simultanés compatibles : VERNE-003 (zones distinctes de `CompilateurGabarit.java`)
- Zones ou contrats partagés : `ProcesseurVerne.java` (`erreur`, `signalerErreur`) ; émission des erreurs dans `CompilateurGabarit.java` ; tous les tests qui lisent le texte des diagnostics (`diagnostics().contains("VERNE-… : bonjour.verne:1:…")`)
- Autorisations : travail local autorisé ; publication et fusion selon D0

## Besoin et contexte
- Objectif : implémenter le contrat proposé par VERNE-002 et validé par D-002.
- Comportement actuel observé : voir VERNE-002.
- Comportement attendu : défini par la section « Diagnostics rattachés aux fichiers de gabarit » de `compilateur-minimal.adoc` issue de VERNE-002.
- Reproduction ou exemple minimal : celle de VERNE-002.
- Dans le périmètre : tous les diagnostics `VERNE-*` portant une position ; extrait de ligne et repère si retenus ; mise à jour des tests existants qui lisent le format.
- Hors périmètre : erreurs du Java généré (T-DIA-02).
- Hypothèses à vérifier : selon VERNE-002.
- Questions bloquantes : D-002.

## Références et contrat
- Instructions applicables : `AGENTS.md` ; `docs/fr/protocole-tickets-agents.md`
- Contrat de référence : section issue de VERNE-002 ; `cas-de-test.adoc` T-DIA-01, T-DIA-03 ; `matrice-fonctionnelle.adoc` F-14
- Parcours du code : voir VERNE-002
- Appelants, tests et ressources concernés : `BancCompilation` (lecture des diagnostics) ; tests de diagnostics ; `ApplicationMavenTest` ; `exemples/application-maven`
- Données et nullité : à préciser par le contrat
- Erreurs : codes inchangés ; seul le rattachement et la forme changent
- Effets de bord et ressources : lecture éventuelle de la ligne fautive, déjà en mémoire, sans relecture du fichier
- Concurrence : sans objet — le processeur s'exécute dans un fil javac unique
- Compatibilité et sécurité : saturation à 64 erreurs conservée ; aucune donnée hors du gabarit ; forme des chemins conforme à D-002
- Performance : sans objet — chemin d'erreur uniquement

## Critères de réussite
| Identifiant | Situation / entrée | Résultat observable | Vérification prévue |
|---|---|---|---|
| C1 | erreur de syntaxe dans un gabarit d'un sous-répertoire | diagnostic au format du contrat, fichier, ligne et colonne exacts | test |
| C2 | erreur après emoji, caractère combiné, commentaire multiligne et CRLF (T-DIA-01) | colonne conforme à la règle du contrat | test |
| C3 | 65 erreurs | saturation `VERNE-LIM-007` inchangée | test existant |
| C4 | construction Maven de `exemples/application-maven` avec un gabarit invalide | sortie Maven au format attendu par le contrat | `ApplicationMavenTest` |
| C5 | IDE retenus par VERNE-002 | comportement constaté consigné | contrôle manuel consigné |

## Réalisation prévue
1. Reporter le contrat validé (statut « livré »).
2. Écrire C1 et C2 ; constater l'échec pour le format attendu.
3. Implémenter dans `ProcesseurVerne` en réutilisant les positions existantes.
4. Adapter les tests dépendants du format sans affaiblir leurs assertions.
- Fichiers susceptibles d'être modifiés : `ProcesseurVerne.java` ; `CompilateurGabarit.java` (émission seulement) ; tests ; `compilateur-minimal.adoc` ; `RESTE-A-FAIRE.md` §3 ; mentions du nombre de tests
- Risques de régression : nombreux tests vérifient le texte exact des diagnostics

## Vérifications et preuves
- Environnement : à relever
- Référence initiale : à consigner
- Phase rouge : à consigner
- Cause attendue confirmée : à consigner
- Phase verte : à consigner
- Suite complète : à consigner
- Contrôles supplémentaires : construction Maven réelle ; IDE
- Preuves consultables : extraits de sortie dans ce ticket
- Non exécuté : à consigner

## Décisions
- D-002 — voir VERNE-002.

## Livraison et passage de relais
- Révision livrée : —
- Accès : —
- Résumé du changement : —
- Critères satisfaits : —
- Travail restant : tout
- Blocages et limites : VERNE-002 ; D-002
- Prochaine action : après D-002, reporter le contrat validé
- Reprise attribuée à : —
- Revue demandée : exactitude des colonnes ; tests adaptés sans perte de précision

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
- Suivis distincts : T-DIA-02
