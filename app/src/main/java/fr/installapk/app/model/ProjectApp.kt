package fr.installapk.app.model

data class ProjectApp(
    val id: String,                 // ex: "Bullix"
    val repoOwner: String,          // "Geoff1805"
    val repoName: String,           // "Bullix"
    val packageName: String,        // "fr.bullix.app"
    val displayName: String,        // "Bullix"
    val description: String,
    val iconEmoji: String,          // "📚", "📦", "📲"
    val isSelf: Boolean = false,    // true pour InstallAPK lui-même
    val artifactPattern: String = "apk",
    val installedVersionCode: Long? = null,
    val installedVersionName: String? = null,
    val latestVersionCode: Long? = null,
    val latestVersionName: String? = null,
    val latestArtifactId: Long? = null,
    val lastInstalledArtifactId: Long? = null,
    val latestArtifactSize: Long? = null,
    val latestArtifactDate: String? = null,
    val artifactTimestamp: Long? = null,
    val installedUpdateTime: Long? = null,
    val downloadUrl: String? = null,
    val isUpdating: Boolean = false,
    val isQueued: Boolean = false,
    val downloadProgress: Float? = null, // 0.0 to 1.0
    val statusText: String? = null
) {
    val isInstalled: Boolean
        get() = !installedVersionName.isNullOrBlank()

    val isUpToDate: Boolean
        get() {
            if (!isInstalled || downloadUrl == null) return false

            val hasInstalledCode = installedVersionCode != null && installedVersionCode > 0
            val hasLatestCode = latestVersionCode != null && latestVersionCode > 0

            // 1. CRITÈRE PRIORITAIRE ANDROID : Le versionCode officiel
            // Si les deux versions ont un versionCode valide, c'est l'arbitre absolu du système Android
            if (hasInstalledCode && hasLatestCode) {
                if (installedVersionCode!! < latestVersionCode!!) {
                    // Le code installé est inférieur au code disponible -> Mise à jour REQUISE
                    return false
                }
                if (installedVersionCode!! > latestVersionCode!!) {
                    // Le code installé est supérieur (version locale / dev plus avancée) -> À jour
                    return true
                }
                // Si installedVersionCode == latestVersionCode :
                // Les codes sont identiques. On examine si un nouvel artefact/build a été produit ou si le nom a changé.
            }

            // 2. CRITÈRE SÉMANTIQUE : versionName
            // Uniquement si les deux versions sont dans un format sémantique (ex: "1.0.12" vs "1.0.10")
            // et ne sont pas des identifiants de builds bruts (ex: "Run #42")
            val cleanInstalled = cleanVersion(installedVersionName)
            val cleanLatest = cleanVersion(latestVersionName)
            val isInstalledSem = isSemanticVersion(cleanInstalled)
            val isLatestSem = isSemanticVersion(cleanLatest)

            if (isInstalledSem && isLatestSem) {
                val semCmp = compareSemanticVersions(cleanInstalled, cleanLatest)
                if (semCmp != null) {
                    if (semCmp < 0) {
                        // Version installée sémantiquement inférieure -> Mise à jour REQUISE
                        return false
                    }
                    if (semCmp > 0) {
                        return true
                    }
                }
            }

            // 3. CRITÈRE PAR IDENTIFIANT D'ARTEFACT INSTALLÉ
            // Si cet artefact précis a déjà été installé avec succès sur le téléphone
            if (latestArtifactId != null && lastInstalledArtifactId != null && latestArtifactId == lastInstalledArtifactId) {
                return true
            }

            // 4. CRITÈRE HORODATAGE (Détection des nouveaux builds CI même sans bump de version)
            // Si l'artefact GitHub a été compilé après la date d'installation sur le téléphone (+ marge 60s)
            if (installedUpdateTime != null && artifactTimestamp != null) {
                if (artifactTimestamp > (installedUpdateTime + 60_000L)) {
                    // Nouvel APK compilé plus récemment sur GitHub -> Mise à jour disponible
                    return false
                } else {
                    return true
                }
            }

            // 5. Par défaut, si les chaînes de version sont identiques et non vides
            if (cleanInstalled.isNotBlank() && cleanLatest.isNotBlank() && cleanInstalled.equals(cleanLatest, ignoreCase = true)) {
                return true
            }

            return false
        }

    val hasUpdateAvailable: Boolean
        get() {
            if (!isInstalled || downloadUrl == null) return false
            return !isUpToDate
        }

    val needsInstallationOrUpdate: Boolean
        get() = downloadUrl != null && (!isInstalled || hasUpdateAvailable)

    companion object {
        fun cleanVersion(v: String?): String {
            if (v == null) return ""
            return v.trim()
                .removePrefix("v")
                .removePrefix("V")
                .removePrefix("release-")
                .removePrefix("Release-")
                .trim()
        }

        fun isSemanticVersion(v: String): Boolean {
            if (v.isBlank() || v.startsWith("Run #", ignoreCase = true)) return false
            // Doit contenir au moins un chiffre et optionnellement des points
            return v.any { it.isDigit() } && !v.contains(" ")
        }

        fun compareSemanticVersions(v1: String, v2: String): Int? {
            // Extraction des blocs de nombres séparés par . ou -
            val parts1 = v1.split(".", "-").mapNotNull { it.trim().takeWhile { c -> c.isDigit() }.toIntOrNull() }
            val parts2 = v2.split(".", "-").mapNotNull { it.trim().takeWhile { c -> c.isDigit() }.toIntOrNull() }
            if (parts1.isEmpty() || parts2.isEmpty()) return null
            val maxLen = maxOf(parts1.size, parts2.size)
            for (i in 0 until maxLen) {
                val p1 = parts1.getOrElse(i) { 0 }
                val p2 = parts2.getOrElse(i) { 0 }
                if (p1 != p2) return p1.compareTo(p2)
            }
            return 0
        }
    }
}
