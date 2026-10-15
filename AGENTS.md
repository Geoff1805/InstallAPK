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
  2. **Notification par email** : Alerte envoyée à l'adresse de référence `token.github@alsteens.net`.
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
  4. **Éradication des 51 alertes Dependabot dues à Foojay** : Dans tous les projets Android/Gradle, ne jamais inclure `org.gradle.toolchains.foojay-resolver-convention` dans `settings.gradle.kts` (et le supprimer immédiatement s'il est présent). Ce plugin superflu charge des dizaines de dépendances transitives obsolètes (Netty, Bouncy Castle, Apache HttpClient, JDOM2, Jose4j, etc.) responsables de 51 alertes de sécurité Dependabot dans les dépôts GitHub. Le JDK est déjà garanti par le runner CI (`actions/setup-java`) et l'environnement local.


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

## Identité Visuelle & Icônes Modernes (/learn)
- **Règle absolue (/learn)** : Toujours concevoir et intégrer une icône moderne pour toute application et tout site web :
  - Applications Android : Adaptive icons (API 26+) complets (foreground + background), mipmaps toutes densités (`mdpi` à `xxxhdpi`), icône ronde (`ic_launcher_round`), et haute résolution (512x512).
  - Éléments in-app : Intégrer l'icône dans l'UI (ex. barre d'action / en-tête) pour renforcer l'identité de marque.
  - Proscription formelle des icônes par défaut ou génériques.

## Qualité CI/CD, Actions GitHub & Zéro Avertissement / Zéro Dépréciation (/learn)
- **Règle absolue (/learn)** : Tous les workflows GitHub Actions créés ou maintenus doivent s'exécuter avec **zéro avertissement (0 warning), zéro notice de dépréciation et zéro paramètre inattendu** :
  1. **Validation stricte des paramètres (Inputs)** :
     - Toujours vérifier la signature exacte (`action.yml`) des actions tierces utilisées. Ne jamais inventer ou supposer les clés d'entrée (`with:`) pour éviter les alertes `Unexpected input(s)`.
  2. **Interdiction des actions sur runtimes Node obsolètes / dépréciés** :
     - Ne jamais utiliser d'actions tierces obsolètes ou abandonnées ciblant des versions de Node.js dépréciées (ex. Node 16, Node 20 sur runners Node 24).
     - Toujours utiliser les versions majeures à jour des actions officielles maintenues (ex: `actions/checkout`, `actions/setup-java`, etc.).
     - Pour la mise à jour du Gradle Wrapper : **ne pas utiliser d'actions Node tierces non maintenues** (comme `gradle-update/update-gradle-wrapper-action@v2`). Utiliser systématiquement un step natif autonome (`./gradlew wrapper` avec interrogation de l'API Gradle `https://services.gradle.org/versions/current` et `gh pr create` via la CLI GitHub native du runner).
  3. **Stabilité des images Runners** :
     - Éviter les notices informatives de transition de labels (ex. avertissement de migration de `ubuntu-latest` vers Ubuntu 26) en privilégiant des runners LTS explicitement versionnés (ex. `ubuntu-24.04`) ou s'assurer de leur conformité anticipée.

## Normalisation des Noms d'APK et Zéro ZIP Superflu (/learn)
- **Règle absolue (/learn)** : Pour **TOUTES les applications existantes et TOUTES les nouvelles applications futures sans exception** (Android natif, Kotlin Multiplatform, Flutter, React Native, etc., incluant `InstallAPK`, `Bullix`, `Needix`, `AntigravityController`, etc.) :
  1. **Nommage normalisé strict `<AppName>.apk` dès la création** :
     - Dès l'initialisation, le premier commit ou la mise en place de la CI/CD de **toute nouvelle application**, l'APK produit et distribué doit porter exclusivement et obligatoirement le nom exact de l'application : **`<AppName>.apk`** (ex: `InstallAPK.apk`, `Bullix.apk`, `Needix.apk`, `AntigravityController.apk`).
     - Proscription formelle et absolue des noms par défaut des frameworks ou suffixes hétérogènes (`app-release.apk`, `app-debug.apk`, `-release-apk`, `-debug-apk`, `Needix-Debug-APK`, etc.) dans les artefacts et les releases.
  2. **Zéro ZIP superflu (Binaire APK brut directement installable)** :
     - Les APKs doivent être mis à disposition directement sous leur format `.apk` natif sans aucune compression ou encapsulation zip superflue.
     - Dans les GitHub Releases, le fichier `.apk` doit être immédiatement cliquable et téléchargeable depuis un smartphone pour une installation en un clic via `InstallAPK` ou le navigateur.
     - Dans les workflows CI/CD (`.github/workflows/*.yml`), toujours copier ou renommer l'APK compilé vers `build-artifacts/<AppName>.apk` avant les étapes d'upload d'artefact et de publication de release.
  3. **Détection automatique et intégration dans InstallAPK** :
     - Toute nouvelle application Android/mobile doit être compatible et immédiatement reconnue par `InstallAPK`.
     - `InstallAPK` trie les artefacts GitHub prioritairement par **date de création (`created_at`) décroissante** afin de toujours garantir l'installation du build le plus récent.
     - Les téléchargements depuis les dépôts privés s'effectuent via l'endpoint de l'API GitHub (`/releases/assets/{id}`) avec l'en-tête `Accept: application/octet-stream` pour éviter toute erreur 404.

## Détection Proactive des Nouveaux Dépôts GitHub Android & Proposition d'Installation (/learn)
- **Règle absolue (/learn)** : L'application `InstallAPK` et l'agent doivent surveiller proactivement les dépôts GitHub de l'utilisateur :
  1. **Détection automatique de tout nouveau repo Android** :
     - Dès qu'un nouveau dépôt GitHub de l'utilisateur est détecté (non encore acquitté/connu), le système analyse s'il s'agit d'une application Android (structure `app`, `android`, manifests, `build.gradle.kts` Android, Flutter/Dart mobile, topics `android`/`apk`, artefacts de build ou releases `.apk`).
  2. **Proposition d'installation proactive** :
     - Si le projet est une application Android, l'application `InstallAPK` affiche immédiatement une boîte de dialogue engageante proposant à l'utilisateur d'installer l'APK en un clic (ou de l'ajouter aux suivis si le premier build CI est encore en attente).
     - La carte de l'application affiche un badge proéminent « ✨ NOUVEAU » et se positionne en tête de liste pour une visibilité immédiate.
  3. **Prise en charge transverse par l'agent (/learn)** :
     - Dès que l'agent ou l'utilisateur initialise un nouveau dépôt d'application Android, l'agent doit immédiatement vérifier son packaging APK, configurer le workflow GitHub Actions pour produire l'artefact `<AppName>.apk`, et s'assurer qu'il sera instantanément proposé dans `InstallAPK`.

## Règle Architecturale Fondamentale : Le Reuse d'Abord (/learn)
- **Principe absolu (/learn) : Le reuse (réutilisation) est le premier principe d'architecture pour tous les projets :**
  1. **Socle UI & Imagerie éprouvé** :
     - Pour tout chargement d'image ou avatar distant en Jetpack Compose, réutiliser **Coil** (`io.coil-kt:coil-compose`) avec mise en cache automatique, décodage optimisé et composant générique `AppIcon` avec fallback local gracieux (inspiré des standards *Now in Android*, *Compose Samples* et *Obtainium*).
  2. **CI/CD & Actions GitHub officielles standardisées** :
     - Toujours réutiliser les versions majeures stables des actions officielles : `actions/checkout@v4`, `actions/setup-java@v4`, `gradle/actions/setup-gradle@v4`, `actions/upload-artifact@v4`.
     - Standardisation stricte de la compilation, du cache Gradle et du livrable direct `<AppName>.apk` sans compression zip superflue.
  3. **Patterns Réseau, Données & Cycle de Vie** :
     - Réseau : OkHttp standardisé avec timeouts et gestion proactive des erreurs d'authentification (401 Bad Credentials).
     - Persistance : Patterns Room / SharedPreferences unifiés pour le mode *offline-first*.
     - Systématiser la réutilisation des briques éprouvées d'un projet à l'autre (`InstallAPK`, `Bullix`, `Needix`, `antigravity-controller`).


