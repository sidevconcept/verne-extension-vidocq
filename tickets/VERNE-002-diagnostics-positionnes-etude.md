# VERNE-002 — Contrat des diagnostics de gabarit rattachés au fichier et à la position

## Pilotage
- Nature : investigation
- État : À préparer — en attente de D0 (`tickets/README.md`)
- Priorité et raison : haute ; demandée par l'utilisateur le 23 octobre 2026 (`RESTE-A-FAIRE.md` §3). Une erreur de gabarit est aujourd'hui difficile à localiser pendant la construction.
- Source faisant autorité : selon D0
- Responsable : `agent-b` (Codex)
- Relecteur : `agent-a` (Claude)
- Mise à jour : 24 octobre 2026, Europe/Paris ; `agent-a`
- Dépôt et cible d'intégration : `verne-extension-vidocq` ; `main`
- Base de travail : `6c6825883176d237e4ece2f8115e91506af357b1`
- Branche du ticket : `etude/diagnostics-positionnes`
- Bloqué par : D0
- Débloque : VERNE-004
- Travaux simultanés compatibles : VERNE-001 (aucun fichier commun)
- Zones ou contrats partagés : `compilateur-minimal.adoc` (nouvelle section seulement) ; un prototype éventuel de `ProcesseurVerne` reste sur la branche d'étude et n'est pas intégré
- Autorisations : travail local autorisé ; publication et fusion selon D0

## Besoin et contexte
- Objectif : établir comment afficher chaque erreur `.verne` sur son fichier réel, avec ligne et colonne exploitables par Maven et les IDE, et proposer le contrat correspondant.
- Comportement actuel observé : `ProcesseurVerne.signalerErreur` appelle `Messager.printMessage(Diagnostic.Kind.ERROR, message)` sans élément source. La position figure seulement dans le texte (`VERNE-SYN-001 : bonjour.verne:2:1 …`), relative à la racine des gabarits. Maven l'affiche sans lien vers le fichier.
- Comportement attendu : un rapport d'étude et une proposition de contrat qui permettent à VERNE-004 de s'implémenter sans deviner.
- Reproduction ou exemple minimal : dans `exemples/application-maven`, rendre `src/main/verne/*.verne` invalide (accolade non fermée), lancer `../../mvnw -q compile`, observer la sortie ; ouvrir le même projet dans un IDE et observer le panneau des problèmes.
- Dans le périmètre : javac en ligne de commande ; Maven 3.9.16 avec maven-compiler-plugin 3.14.1 ; IntelliJ IDEA (construction Maven déléguée et construction native) ; Eclipse JDT ; VS Code Java ; format d'extrait de ligne avec repère sous la colonne ; règle de colonne (unités de code, points de code ou graphèmes) au regard de T-DIA-01.
- Hors périmètre : rattachement des erreurs du Java généré (T-DIA-02) ; implémentation livrée (VERNE-004).
- Hypothèses à vérifier : H1 — l'API `Messager` ne rattache un diagnostic qu'à un élément Java, jamais à une ressource quelconque. H2 — les IDE transforment en lien une ligne de console `chemin:ligne:colonne` lorsque le chemin est résoluble depuis le projet. H3 — Eclipse JDT exécute les processeurs avec son propre compilateur et peut se comporter différemment.
- Questions bloquantes : aucune pour l'étude ; la décision D-002 sera demandée à l'utilisateur à la livraison.

## Références et contrat
- Instructions applicables : `AGENTS.md` ; `docs/fr/protocole-tickets-agents.md`
- Contrat de référence : `docs/fr/modules/ROOT/pages/compilateur-minimal.adoc`, ligne 64 (« Les diagnostics n'exposent ni chemin absolu de la racine ou de la cible… »), sections « Diagnostics livrés » et « Nombre de diagnostics de compilation » ; `cas-de-test.adoc` T-DIA-01 à T-DIA-04 ; `matrice-fonctionnelle.adoc` F-14
- Parcours du code : `CompilateurGabarit` (positions ligne:colonne) → rappel d'erreur → `ProcesseurVerne.erreur` / `signalerErreur` → `Messager` → javac → plexus-compiler → console Maven ou IDE
- Appelants, tests et ressources concernés : `NombreDiagnosticsTest`, `CasLimitesCompilationTest`, `ApplicationMavenTest` ; `exemples/application-maven`
- Données et nullité : sans objet — étude
- Erreurs : tous les codes `VERNE-*` positionnés
- Effets de bord et ressources : sans objet — étude
- Concurrence : sans objet — étude
- Compatibilité et sécurité : la proposition doit dire si elle conserve l'interdiction du chemin absolu ou demande de la lever, et pourquoi ; aucun contenu métier dans l'extrait hors ligne du gabarit lui-même
- Performance : sans objet — affichage d'erreurs uniquement

## Critères de réussite
| Identifiant | Situation / entrée | Résultat observable | Vérification prévue |
|---|---|---|---|
| C1 | chaque outil du périmètre | tableau : affichage obtenu, lien cliquable ou non, soulignement ou non, avec extrait de sortie ou description reproductible | consigné dans ce ticket |
| C2 | H1, H2, H3 | chacune confirmée ou infirmée avec preuve | consigné dans ce ticket |
| C3 | options d'affichage (au moins : préfixe relatif, préfixe absolu, rattachement à un élément Java d'origine, extrait avec repère) | chaque option évaluée contre la ligne 64, la saturation à 64 erreurs et le français | tableau comparatif |
| C4 | proposition | section « Diagnostics rattachés aux fichiers de gabarit (proposé) » dans `compilateur-minimal.adoc`, avec format exact, règle de colonne et exemples | relecture |
| C5 | décision | D-002 formulée avec recommandation et options, prête à soumettre à l'utilisateur | relecture |

## Réalisation prévue
1. Reproduire la situation actuelle dans `exemples/application-maven`.
2. Vérifier H1 à H3 ; prototyper si utile sur la branche d'étude.
3. Rédiger le comparatif, la proposition de contrat et D-002.
- Fichiers susceptibles d'être modifiés : `compilateur-minimal.adoc` ; ce ticket
- Risques de régression : sans objet — documentation seule ; contrôles documentaires uniquement

## Vérifications et preuves
- Environnement : à relever (système, Java 25, Maven 3.9.16, versions des IDE)
- Référence initiale : à consigner
- Phase rouge : sans objet — documentation seule (protocole, section 5)
- Cause attendue confirmée : sans objet
- Phase verte : sans objet
- Suite complète : `./mvnw test` inchangé, à consigner si un fichier hors documentation est touché
- Contrôles supplémentaires : relecture du contrat proposé contre les sections citées
- Preuves consultables : extraits de console dans ce ticket
- Non exécuté : IDE indisponibles dans l'environnement, à consigner le cas échéant

## Décisions
- D-002 — à formuler à la livraison : format retenu et sort de l'interdiction du chemin absolu. Décision de l'utilisateur.

## Livraison et passage de relais
- Révision livrée : —
- Accès : —
- Résumé du changement : —
- Critères satisfaits : —
- Travail restant : tout
- Blocages et limites : D0
- Prochaine action : après D0, reproduire l'affichage actuel dans `exemples/application-maven`
- Reprise attribuée à : —
- Revue demandée : la proposition est-elle implémentable par `Messager` seul ? respecte-t-elle la saturation et l'absence de données sensibles ?

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
- Suivis distincts : VERNE-004
