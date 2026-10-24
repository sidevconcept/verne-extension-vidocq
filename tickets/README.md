# Verne — Tickets

Tickets préparés selon le [protocole de travail entre agents](../docs/fr/protocole-tickets-agents.md), à partir de [RESTE-A-FAIRE.md](../RESTE-A-FAIRE.md). Base commune : `6c6825883176d237e4ece2f8115e91506af357b1` (`main`, après fusion des PR nº 24 et nº 25).

## Agents

| Identifiant | Modèle | Rôle |
|---|---|---|
| `agent-a` | Claude (Opus) | Responsable des tickets VERNE-001, 003 et 006 ; relecteur des tickets d'`agent-b` |
| `agent-b` | Codex | Responsable des tickets VERNE-002, 004 et 005 ; relecteur des tickets d'`agent-a` |

Chaque agent travaille dans son propre clone ou worktree, sur une branche par ticket, et relit l'autre dans un espace séparé de son travail en cours. Les commits utilisent l'identité Git configurée, le DCO et la signature GPG du dépôt, sans co-auteur d'assistant.

## Répartition

| Ticket | Résultat attendu | Nature | Responsable | Relecteur | Vague | Bloqué par | État |
|---|---|---|---|---|---|---|---|
| [VERNE-001](VERNE-001-banc-micro-mesures.md) | Banc de micro-mesures versionné et reproductible | capacité (outillage) | `agent-a` | `agent-b` | 1 | D0 | À préparer |
| [VERNE-002](VERNE-002-diagnostics-positionnes-etude.md) | Contrat des diagnostics de gabarit rattachés au fichier et à la position | investigation | `agent-b` | `agent-a` | 1 | D0 | À préparer |
| [VERNE-003](VERNE-003-echappeurs-par-table.md) | Échappeurs HTML, XML et JSON par table constante, sans régression | capacité (performance) | `agent-a` | `agent-b` | 2 | VERNE-001 | À préparer |
| [VERNE-004](VERNE-004-diagnostics-positionnes.md) | Erreurs `.verne` affichées sur leur fichier, ligne et colonne pendant la construction | capacité | `agent-b` | `agent-a` | 2 | VERNE-002, D-002 | À préparer |
| [VERNE-005](VERNE-005-qualification-linux-windows.md) | Suite complète exécutée sur Linux et Windows | investigation et outillage | `agent-b` | `agent-a` | 3 | D0, D-005 | À préparer |
| [VERNE-006](VERNE-006-contrat-expressions.md) | Contrat de syntaxe et de typage des expressions | documentation | `agent-a` | `agent-b` | 3 | décisions de syntaxe | À préparer |

Une vague réunit deux tickets indépendants, un par agent. VERNE-001 et VERNE-002 ne partagent aucun fichier de code. VERNE-003 et VERNE-004 touchent tous deux `CompilateurGabarit.java`, dans des zones distinctes (constantes d'échappement d'un côté, émission des erreurs de l'autre) : le second intégré se rebase et rejoue la suite complète.

## Zones partagées et conventions

- **Nombre de tests.** `AGENTS.md`, `README.adoc`, `RESTE-A-FAIRE.md`, `docs/fr/protocole-tickets-agents.md`, `matrice-fonctionnelle.adoc` et la section « Vérification finale observée » de `compilateur-minimal.adoc` citent le nombre de tests. Chaque PR met à jour ces mentions ; le second ticket intégré les recalcule après rebase, sans reprendre le chiffre de l'autre ticket.
- **`RESTE-A-FAIRE.md`.** Chaque PR coche uniquement ses propres cases.
- **`pom.xml` racine.** Seul VERNE-001 y ajoute un module dans la vague 1.
- **Contrats.** `compilateur-minimal.adoc` reçoit une section par ticket ; ne pas réécrire la section d'un autre ticket.
- **Numérotation des mesures.** La page externe du plan de mesures emploie ses propres identifiants (M-02 échappement HTML, M-03 échappement XML, M-09 échappement JSON) ; ils ne correspondent pas au corpus M-01 à M-09 de `mesures-performance.adoc`. Les tickets citent toujours la source de l'identifiant.
- **Moteurs externes.** Aucune comparaison avec un moteur externe n'entre dans le dépôt. Les comparaisons restent dans le banc indépendant, hors dépôt.

## Décisions attendues

- **D0 — Source faisant autorité et autorisations.** À décider par l'utilisateur : tickets de la forge GitHub (états et revues visibles immédiatement par les deux agents) ou fichiers de ce répertoire sur `main` (chaque changement d'état passe par une PR). À préciser aussi : publication et fusion autorisées par ticket, ou sur demande explicite comme aujourd'hui. Tant que D0 n'est pas tranchée, aucun ticket n'est prêt.
- **D-002, D-005 et décisions de VERNE-006** : décrites dans leurs tickets.
