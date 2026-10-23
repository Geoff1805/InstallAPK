# Règles d'Agent — Projet InstallAPK (AppUpdater / AppInstaller)

> **Héritage global** : Ce projet applique l'ensemble des règles transversales consolidées dans [`~/.gemini/config/AGENTS.md`](file:///C:/Users/geoff/.gemini/config/AGENTS.md) (Sécurité Dependabot, Zéro warning CI, Normalisation de l'APK `<AppName>-v<versionName>.apk` sans ZIP, éradication de Foojay, économie de jetons).

## Architecture & Fonctionnalités Clés
- **Détection automatique des versions d'APKs** : Surveillance des Releases GitHub et derniers artefacts de build Actions des dépôts de l'écosystème.
- **Mise à jour globale et unitaire** : Bouton global « Tout mettre à jour » et boutons individuels d'installation/mise à jour.
- **Téléchargement & Installation native** : Téléchargement optimisé via `/releases/assets/{id}` (`Accept: application/octet-stream`), décompression transparente des artefacts zip si nécessaire, et déclenchement de l'installation native via `FileProvider` (`ACTION_VIEW` / `ACTION_INSTALL_PACKAGE`).
- **Détection d'état sur l'appareil** : `PackageManager` (installé, version actuelle, mise à jour disponible).
- **Auto-mise à jour transparente** : L'application peut télécharger et installer sa propre mise à jour.

## Détection Proactive des Nouveaux Dépôts GitHub Android (/learn)
1. **Détection automatique de tout nouveau repo Android** :
   - Dès qu'un nouveau dépôt GitHub de l'utilisateur est détecté (non encore acquitté), le système analyse s'il s'agit d'une application Android (structure `app`, `android`, manifests, `build.gradle.kts`, topics `android`/`apk`, releases `.apk`).
2. **Proposition d'installation proactive** :
   - Si le projet est une application Android, `InstallAPK` affiche immédiatement une boîte de dialogue proposant à l'utilisateur d'installer l'APK en un clic.
   - La carte de l'application affiche un badge proéminent « ✨ NOUVEAU » et se positionne en tête de liste.
3. **Prise en charge transverse par l'agent** :
   - Dès l'initialisation d'un nouveau dépôt Android, l'agent configure le workflow GitHub Actions pour produire l'artefact `<AppName>-v<versionName>.apk` directement reconnu par `InstallAPK`.

## Surveillance et Alertes des Clés GitHub & Tokens API (/learn)
- Notification proactive à l'utilisateur en cas de token GitHub expiré, invalide (401 Bad Credentials) ou avec des permissions insuffisantes.

## Conventions Techniques Spécifiques
- **Socle UI & Imagerie** : **Coil** (`io.coil-kt:coil-compose`) avec mise en cache automatique et composant `AppIcon` avec fallback local.
- **Réseau** : OkHttp standardisé avec timeouts et gestion proactive des erreurs.
- **Signature Android** : Keystore de debug persistant partagé pour les mises à jour in-place.
- **Langue & Liens** : Réponses concises en français avec liens cliquables `file:///` vers les fichiers et symboles modifiés.
