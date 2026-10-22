# Consignes de contribution à Verne

- Le composant se nomme Verne, en hommage à Jules Verne.
- Tout texte maîtrisé par le projet est en français : documentation, noms de classes, méthodes, variables, commentaires, diagnostics et messages de contribution. Conserver les mots réservés Java et les signatures imposées par les API tierces.
- Ne jamais nommer, citer ou référencer de moteur externe d'inspiration dans le code, la documentation ou les contributions.
- Respecter Java 25 et les Java Modules explicites. Le moteur n'a aucune dépendance tierce à l'exécution ; les dépendances de vérification restent en portée test.
- Travailler exclusivement sur une branche dédiée. L'auteur Git est l'identité configurée localement pour ce dépôt. Ne pas ajouter de signature ou de co-auteur d'assistant.
- Les commits locaux comportent toujours le DCO (`git commit -s`) et utilisent cette identité. Depuis le 18 octobre 2026, les commits locaux sont signés avec la clé GPG configurée pour ce dépôt. Ne jamais fabriquer une identité ou une signature ; réexaminer les exigences de la forge avant une contribution distante. Ne pas publier de changement sans demande correspondante.
- Après fusion des pull requests, supprimer leurs branches locale et distante une fois leurs commits vérifiés dans la branche cible. Préserver les branches des PR ouvertes, la branche de travail active et la branche par défaut de la forge.
- Consigner chaque prompt utilisateur dans `PROMPTS.md`, strictement local, exclu via `.git/info/exclude`. Ne jamais le versionner, le transmettre ni l'intégrer aux archives.
- Appliquer le développement piloté par les tests : test écrit et observé en échec pour la cause attendue avant le code correspondant, implémentation minimale, réussite vérifiée puis amélioration.
- Tester erreurs, limites, sécurité, concurrence et performances pertinentes. Ne jamais ignorer un test pour masquer l'absence du moteur.
- Documenter chaque contrat avant son implémentation : données, nullité, erreurs, effets de bord, ressources et concurrence. Les documents de référence résident dans `docs/fr`.
- Utiliser Ponytail par défaut : comprendre le flux, réutiliser le code existant et préférer la solution complète la plus simple. Cela ne réduit ni les tests ni la documentation demandés.
- Aucun balayage du chemin des classes ni réflexion métier à l'exécution. Les gabarits sont vérifiés et compilés avant déploiement.
- Le dépôt autonome Verne est hébergé sur le distant `origin` explicitement choisi par l'utilisateur. Cette destination est autorisée pour Verne. Les autres dépôts de l'écosystème Vidocq restent sur CodeFloe ; ne pas leur appliquer cette exception.

## Vérifications

Utiliser `./mvnw test` sous Unix ou `.\mvnw.cmd test` sous Windows avec Java 25. Le lanceur fixe Maven 3.9.16. Les 183 tests actuels doivent réussir. Pour une nouvelle capacité, constater le test en échec avant son implémentation ; ne pas accepter de régression sur les capacités livrées. Lire `docs/fr/modules/ROOT/pages/premiers-tests.adoc` avant de modifier ce contrat.
