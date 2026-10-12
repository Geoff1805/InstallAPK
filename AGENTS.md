# Règles d'Agent — Projet InstallAPK (AppUpdater / AppInstaller)

## Publication automatique sur GitHub & Surveillance Active
- **Publication systématique** : Toutes les modifications de code appliquées localement doivent être automatiquement publiées (commit & push) sur GitHub vers le dépôt `Geoff1805/InstallAPK` (branche `main`), avec des messages clairs au format Conventional Commits (`feat:`, `fix:`, `ci:`, `chore:`, `docs:`).
- **Méthode de publication** : En l'absence du binaire `git` local, utiliser directement le serveur MCP GitHub (`github-mcp-server` via `push_files` ou `create_or_update_file`) avec le token d'authentification configuré.
- **Suivi automatique systématique de la CI** : Dès qu'un push est effectué, surveiller l'état d'exécution des workflows GitHub Actions déclenchés (`scripts/ci/check-ci.ps1 -Watch` ou API GitHub). La tâche n'est terminée que lorsque tous les workflows sont au vert.
- **Self-Healing CI** : En cas d'échec du workflow (`failure`), inspecter les logs, corriger le code/la configuration, repousser et vérifier jusqu'au succès complet.

## Délégation Maximale vers GitHub Actions (CI/CD First)
- **Décharge locale** : L'environnement Windows local étant contraint (pas de SDK Android complet en local, Java 8 par défaut), aucune compilation lourde Android ne doit être effectuée localement.
- **Règle absolue : Ce qui peut être fait par GitHub doit être fait sur GitHub.**
- **Compilation & Packaging APK** : Le workflow GitHub Actions compile l'APK Debug et Release avec Java 17/21 et publie l'artefact téléchargeable `installapk-debug-apk`.
- **Mise à jour automatique Gradle Wrapper** : Workflow automatique de mise à jour stable hebdomadaire (`update-gradle-wrapper.yml`).

## Surveillance et Alertes des Clés GitHub & Tokens API (/learn)
- **Règle d'apprentissage critique (/learn)** : Signaler systématiquement quand les clés API ou tokens (notamment Personal Access Token GitHub) ne sont plus valides ou arrivent à expiration.
- **Canaux d'alerte obligatoires** :
  1. **Notification dans l'application** : Bandeau d'alerte rouge proéminent et badge d'avertissement sur l'icône de clé dès que l'API renvoie un code 401 ou que la date d'expiration est dépassée.
  2. **Notification par email** : Alerte envoyée à l'adresse de référence `contact@installapk.app`.
  3. **Vérification automatique continue** : Workflow GitHub Actions quotidien (`.github/workflows/check-token.yml`) testant la clé via l'API GitHub (`https://api.github.com/user`) et en-têtes `github-authentication-token-expiration`.
  4. **Diagnostic proactif lors des sessions d'agent** : Toujours vérifier la validité de la clé en amont des opérations critiques et alerter l'utilisateur immédiatement en cas de révocation ou d'expiration imminente.

## Sécurité GitHub, Dependency Scanning & Dependabot (/learn)
- **Règle absolue (/learn)** : Toujours utiliser, activer et configurer les fonctionnalités de sécurité et d'analyse des dépendances sur **TOUS** les dépôts GitHub :
  1. **Dependency Scanning** :
     - **Dependency graph** : Toujours activé (`On`).
     - **Automatic dependency submission** : Toujours activé (`Enabled`) pour remonter l'arbre des dépendances au build.
  2. **Dependabot** :
     - **Dependabot alerts** : Toujours activé (`On`) pour être notifié de toute vulnérabilité détectée.
     - **Dependabot security updates** : Toujours activé (`On`) pour générer automatiquement les correctifs de sécurité.
     - **Configuration Dependabot (`.github/dependabot.yml`)** : Systématiquement présent à la racine du dépôt pour automatiser la surveillance et la mise à jour des écosystèmes du projet (ex. `gradle`, `github-actions`, etc.).
  3. **Vérification & activation automatique** : Lors de la création, maintenance ou inspection d'un dépôt GitHub, l'agent doit vérifier l'état de ces fonctionnalités (notamment via l'API GitHub `PUT /repos/{owner}/{repo}/vulnerability-alerts` et `PUT /repos/{owner}/{repo}/automated-security-fixes`) et s'assurer que `.github/dependabot.yml` est en place.


## Architecture & Fonctionnalités Clés
- **Nom de l'application** : InstallAPK / AppUpdater
- **Package ID** : `fr.installapk.app`
- **UI** : Jetpack Compose moderne (Material 3), écran d'accueil avec :
  - Configuration du Token GitHub (Personal Access Token avec scope `repo` pour lire les repos privés / artefacts).
  - Liste des projets GitHub de l'utilisateur (`Bullix`, `Needix`, etc. et lui-même `InstallAPK` pour l'auto-mise à jour).
  - Détection automatique de la dernière version d'APK disponible (Releases GitHub ou derniers artefacts de build Actions).
  - Bouton global « Tout mettre à jour » (Update All) et boutons de mise à jour / installation individuelle.
  - Téléchargement, décompression transparente du zip d'artefact GitHub, et déclenchement de l'installation native via `FileProvider` (`ACTION_VIEW` / `ACTION_INSTALL_PACKAGE`).
  - Détection de l'état d'installation sur le téléphone (`PackageManager` : installé, version actuelle, mise à jour disponible).
  - Auto-mise à jour transparente : l'application peut se mettre à jour elle-même !

## Conventions
- Langue des réponses : Français concis avec liens cliquables `file:///` vers les fichiers modifiés.
- Respect strict de la signature Android : Keystore de debug persistant partagé ou keystore dédié.
