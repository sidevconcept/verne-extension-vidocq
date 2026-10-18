# Verne — Reste à faire

État au 18 octobre 2026. L'ordre ci-dessous est conseillé ; chaque capacité suit le cycle contrat documenté, test observé en échec, implémentation minimale et vérification des régressions.

Ce document sert au suivi. Les contrats de référence restent dans [docs/fr](docs/fr/modules/ROOT/pages/index.adoc), notamment la [matrice fonctionnelle](docs/fr/modules/ROOT/pages/matrice-fonctionnelle.adoc) et le [contrat du compilateur](docs/fr/modules/ROOT/pages/compilateur-minimal.adoc).

## État acquis

- Le sous-ensemble texte est implémenté : paramètres typés, interpolations, navigation dans les records publics, commentaires et échappement des délimiteurs.
- Les contrôles de ressources, les budgets du compilateur et l'optimisation des grands documents sont intégrés.
- Les PR nº 10 et nº 11 sont fusionnées dans `main` ; sa dernière vérification sur Mac compte **106 tests réussis**, sans échec, erreur ni test ignoré.
- La découverte automatique du processeur est prête localement sur `codex/decouverte-processeur`. Avec cette tranche, **115 tests réussissent sur Mac**, sans échec, erreur ni test ignoré.
- Les 42 tests initiaux ont également été exécutés sur Windows. Les 73 tests supplémentaires restent à qualifier sur Windows et Linux.
- Le banc indépendant dispose de mesures de performances ; elles ne qualifient pas les fonctionnalités restant à développer.

## 1. Publier la découverte automatique du processeur

- [ ] Créer le commit signé GPG avec DCO pour la tranche locale.
- [ ] Pousser la branche et créer la pull request vers `main`.
- [ ] Fusionner la pull request après vérification et autorisation correspondante.

## 2. Finaliser l'intégration à la construction Java

- [ ] Vérifier une véritable application Maven consommatrice, distincte du banc interne.
- [ ] Qualifier la configuration et le chargement du processeur depuis son artefact publié ou installé.
- [ ] Tester la coexistence avec d'autres processeurs et les types générés pendant les tours suivants.

## 3. Fiabiliser les reconstructions et les diagnostics

- [ ] Couvrir l'ajout, la modification et la suppression de gabarits, ainsi que leurs dépendants, sans classes périmées.
- [ ] Rattacher les erreurs du Java généré aux identifiants et positions des gabarits.
- [ ] Compléter la qualification du budget de génération et des limites restantes de javac.
- [ ] Compléter les règles de nommage portable, les racines multiples et les collisions entre fournisseurs.

## 4. Compléter le langage

- [ ] Ajouter les expressions, opérateurs, littéraux, parenthèses et accès indexés avec un typage vérifié.
- [ ] Définir la navigation optionnelle et les valeurs de remplacement, en distinguant absence, nullité et chaîne vide.
- [ ] Ajouter les conditions, branches alternatives et choix multiples.
- [ ] Ajouter les boucles, variables locales, portées et métadonnées d'itération.
- [ ] Prendre en charge les tableaux, collections et tables typées nécessaires à ces capacités.
- [ ] Ajouter les fonctions et espaces de noms explicitement déclarés, ainsi que les blocs littéraux.

## 5. Ajouter la composition de documents

- [ ] Ajouter les inclusions avec chemins logiques et paramètres vérifiés.
- [ ] Ajouter les gabarits parents, emplacements nommés et substitutions.
- [ ] Ajouter les fragments et composants réutilisables avec leurs portées explicites.
- [ ] Vérifier les cycles, profondeurs et dépendances de composition.

## 6. Étendre les sorties et leur sécurité

- [ ] Définir les profils de sortie, types de média et encodages au-delà du profil texte.
- [ ] Ajouter l'échappement contextuel et refuser les contextes non pris en charge.
- [ ] Définir les types de contenus déclarés fiables et les encodeurs personnalisés.
- [ ] Ajouter les sections personnalisées avec validation à la construction.

## 7. Ajouter le rendu en flux et l'asynchronisme

- [ ] Permettre le rendu vers une sortie fournie par l'appelant avec un contrat de propriété et de fermeture des ressources.
- [ ] Définir les limites de sortie et de mémoire hors matérialisation explicite.
- [ ] Ajouter les données différées et le rendu asynchrone en conservant l'ordre textuel.
- [ ] Propager les erreurs d'entrée-sortie, échecs partiels et annulations.

## 8. Ajouter l'internationalisation

- [ ] Ajouter les catalogues typés de messages, langues, replis, pluriels et paramètres.
- [ ] Formater les nombres, dates et heures avec une langue et un fuseau explicites.
- [ ] Sélectionner les variantes de documents de façon déterministe et refuser les ambiguïtés.

## 9. Créer l'extension Vidocq sur CodeFloe

- [ ] Développer l'extension dans le dépôt Vidocq, sur sa propre branche.
- [ ] Intégrer CDI et les réponses REST avec leurs contrats de portée, de média et d'erreur.
- [ ] Intégrer configuration, enregistrement et arrêt dans le cycle de vie Vidocq.
- [ ] Ajouter le rechargement en développement avec remplacement atomique et diagnostics visibles.
- [ ] Conserver la séparation entre le moteur autonome et l'extension.

## 10. Qualifier la livraison

- [ ] Exécuter la suite complète sur Windows et Linux, notamment les cas de liens symboliques, d'encodage et de nommage.
- [ ] Compléter les vérifications de concurrence, d'isolation, de résilience et de ressources.
- [ ] Étendre les campagnes de performances aux nouvelles capacités, avec sorties équivalentes et résultats bruts conservés.
- [ ] Vérifier les archives, leur reproductibilité et l'absence des outils de compilation, de test et de mesure dans le livrable d'exécution.
- [ ] Compléter les exemples, contrats, diagnostics, limites et instructions de migration.
- [ ] Vérifier la couverture de la matrice fonctionnelle ; le nombre de tests actuel ne prouve pas à lui seul la réalisation de tous ses scénarios.

## Prochaine priorité

Publier la tranche de découverte automatique, puis qualifier une application Maven consommatrice. Cette étape doit vérifier l'utilisation de Verne hors de son propre banc avant le raccordement à Vidocq.
