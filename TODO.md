# TODO & Feuilles de Route — InstallAPK

> **Note d'Architecture (Règle 16)** : Soldé et validé lors de la mise à jour v1.0.26 (16 Octobre 2026).

## ✅ Améliorations de Stack & Qualité (Intégrées en v1.0.26)
- [x] **Analyse Statique Kotlin** : Plugin `org.jlleitschuh.gradle.ktlint` intégré dans `build.gradle.kts` (`./gradlew ktlintCheck`).
- [x] **Observabilité Production** : SDK Sentry Android (`io.sentry:sentry-android:7.20.0`) intégré dans `InstallApkApp.kt` avec capture automatique des exceptions.
- [x] **Diagnostic Debug** : Chucker (`com.github.chuckerteam.chucker:library:4.1.0`) intégré en `debugImplementation` (`library-no-op` en `releaseImplementation`) sur `OkHttpClient`.
- [x] **Compatibilité OS & Conflits de Signature** : `FileProvider` compatible Android 15/16 (API 36), `BroadcastReceiver` temps réel (`ACTION_PACKAGE_ADDED` / `ACTION_PACKAGE_REPLACED`), et détection proactive des conflits de certificats SHA-256 (`hasSignatureConflict`).
- [x] **Vérification R8 Full Mode** : `android.enableR8.fullMode=true`, `isMinifyEnabled = true` et `isShrinkResources = true` actifs avec `proguard-rules.pro` optimisé.
- [x] **Réutilisation `NotificationHelper`** : Canaux hiérarchisés (`CRITICAL_ALERTS`, `ROUTINES_SCENARIOS`, `INFO`) conformes à `STACK_LIBRARY.md`.
- [x] **Mise à jour automatique au lancement** : Déclenchement automatique de la file d'attente dès la détection de mises à jour au démarrage avec pré-téléchargement parallèle.
