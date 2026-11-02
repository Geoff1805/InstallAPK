# InstallAPK — Règles Projet
> Hérite de `~/.gemini/config/AGENTS.md` et `PROJECTS_CACHE.md`.
- **Flux APK** : Surveillance Releases/Actions (`<AppName>-v*.apk`), download `/releases/assets/{id}` (`Accept: application/octet-stream`), tri APK direct vs ZIP (`inspectDownloadedArchive`), installation `FileProvider` (`ACTION_VIEW`/`ACTION_INSTALL_PACKAGE`) + `BroadcastReceiver` (`PACKAGE_ADDED`/`REPLACED`/`REMOVED`).
- **Màj & Conflits** : `autoUpdateOnLaunch`, bouton « Tout mettre à jour », détection SHA-256 (`hasSignatureConflict`) avec désinstallation/réinstallation auto, auto-update d'`InstallAPK`.
- **Nouveaux dépôts Android** : Détection proactive -> badge « ✨ NOUVEAU » en tête + dialogue d'installation 1-clic.
- **Alertes Token** : Notification proactive si PAT GitHub expiré, 401 ou scopes insuffisants.
- **Socle** : Coil (`AppIcon` + cache/fallback), OkHttp (timeouts), Keystore persistant partagé.
