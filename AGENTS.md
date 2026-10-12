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
