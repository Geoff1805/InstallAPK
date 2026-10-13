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

            // 1. Si cet artefact précis a déjà été installé avec succès
            if (latestArtifactId != null && lastInstalledArtifactId != null && latestArtifactId == lastInstalledArtifactId) {
                return true
            }

            // 2. Normalisation et comparaison robuste des noms de version (ex: "v1.0.9" vs "1.0.9")
            val cleanInstalled = cleanVersion(installedVersionName)
            val cleanLatest = cleanVersion(latestVersionName)
            if (cleanInstalled.isNotBlank() && cleanLatest.isNotBlank()) {
                if (cleanInstalled.equals(cleanLatest, ignoreCase = true)) {
                    return true
                }
                val semCmp = compareSemanticVersions(cleanInstalled, cleanLatest)
                if (semCmp != null) {
                    if (semCmp >= 0) return true
                    if (semCmp < 0) return false
                }
            }

            // 3. Si le versionCode réel est disponible sur les deux
            if (latestVersionCode != null && installedVersionCode != null && latestVersionCode > 0 && installedVersionCode > 0) {
                if (installedVersionCode >= latestVersionCode) return true
                if (installedVersionCode < latestVersionCode) return false
            }

            // 4. Par horodatage : si l'application sur le téléphone a été installée après la création de l'artefact (marge 120s)
            if (installedUpdateTime != null && artifactTimestamp != null) {
                return installedUpdateTime >= (artifactTimestamp - 120_000L)
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
                .removePrefix("Run #")
                .trim()
        }

        fun compareSemanticVersions(v1: String, v2: String): Int? {
            val parts1 = v1.split(".").mapNotNull { it.trim().takeWhile { c -> c.isDigit() }.toIntOrNull() }
            val parts2 = v2.split(".").mapNotNull { it.trim().takeWhile { c -> c.isDigit() }.toIntOrNull() }
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
