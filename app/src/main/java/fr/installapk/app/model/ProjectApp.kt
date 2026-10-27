package fr.installapk.app.model

data class ProjectApp(
    val id: String, // ex: "Bullix"
    val repoOwner: String, // "Geoff1805"
    val repoName: String, // "Bullix"
    val packageName: String, // "fr.bullix.app"
    val displayName: String, // "Bullix"
    val description: String,
    val iconEmoji: String, // "📚", "📦", "📲"
    val iconUrl: String? = null, // URL d'avatar/icône distante (ex: GitHub repo/owner avatar)
    val isSelf: Boolean = false, // true pour InstallAPK lui-même
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
    val isDirectApk: Boolean = false,
    val isNew: Boolean = false,
    val isUpdating: Boolean = false,
    val isQueued: Boolean = false,
    val hasSignatureConflict: Boolean = false,
    val downloadProgress: Float? = null, // 0.0 to 1.0
    val releaseNotes: String? = null,
    val statusText: String? = null
) {
    val effectiveIconUrl: String
        get() = iconUrl ?: "https://github.com/$repoOwner.png"

    val isInstalled: Boolean
        get() = !installedVersionName.isNullOrBlank() || (installedVersionCode != null && installedVersionCode > 0L)

    val isUpToDate: Boolean
        get() {
            if (!isInstalled || downloadUrl == null) return false

            // 1. IDENTIFIANT D'ARTEFACT / ASSET DÉJÀ INSTALLÉ (Priorité absolue)
            // Si ce binaire précis (Release asset ID ou Actions artifact ID) a déjà été installé
            // sur l'appareil par InstallAPK, l'application est formellement à jour.
            if (latestArtifactId != null && lastInstalledArtifactId != null && latestArtifactId == lastInstalledArtifactId) {
                return true
            }

            val cleanInstalled = cleanVersion(installedVersionName)
            val cleanLatest = cleanVersion(latestVersionName)
            val isInstalledSem = isSemanticVersion(cleanInstalled)
            val isLatestSem = isSemanticVersion(cleanLatest)
            val semCmp = runCatching {
                if (isInstalledSem && isLatestSem) compareSemanticVersions(cleanInstalled, cleanLatest) else null
            }.getOrNull()

            // 2. COMPARAISON SÉMANTIQUE DE VERSION (Priorité logique)
            if (semCmp != null) {
                if (semCmp < 0) {
                    return false // Version installée formellement inférieure -> Mise à jour REQUISE
                }
                if (semCmp > 0) {
                    return true // Version installée supérieure (ex: dev local) -> À jour
                }
                // Si semCmp == 0, les versions sont identiques -> vérifier versionCode ou build CI ci-dessous
            }

            // 3. CRITÈRE OFFICIEL ANDROID : versionCode
            val hasInstalledCode = installedVersionCode != null && installedVersionCode > 0
            val hasLatestCode = latestVersionCode != null && latestVersionCode > 0
            if (hasInstalledCode && hasLatestCode) {
                if (installedVersionCode!! < latestVersionCode!!) {
                    return false
                }
                if (installedVersionCode > latestVersionCode) {
                    return true
                }
            }

            // 4. PAR DÉFAUT SI IDENTIQUES : Si les versions nettoyées sont identiques et non vides
            if (cleanInstalled.isNotBlank() && cleanLatest.isNotBlank() && cleanInstalled.equals(cleanLatest, ignoreCase = true)) {
                // Même version : vérifier si un build CI plus récent a été produit après l'installation
                if (installedUpdateTime != null && artifactTimestamp != null && artifactTimestamp > 0L) {
                    val safeUpper = if (installedUpdateTime < Long.MAX_VALUE - 60_000L) installedUpdateTime + 60_000L else Long.MAX_VALUE
                    if (artifactTimestamp > safeUpper) {
                        return false // Nouveau build CI disponible pour la même version
                    }
                }
                return true
            }

            // 5. Cas où les versions ne sont pas sémantiques (ex: CI run numbers "Run #45")
            if (installedUpdateTime != null && artifactTimestamp != null && artifactTimestamp > 0L) {
                val safeUpper = if (installedUpdateTime < Long.MAX_VALUE - 60_000L) installedUpdateTime + 60_000L else Long.MAX_VALUE
                if (artifactTimestamp > safeUpper) {
                    return false
                }
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
            var str = v.trim()
            if (str.startsWith("Run #", ignoreCase = true) || str.startsWith("#")) {
                return str
            }
            val lastSpace = str.lastIndexOf(' ')
            if (lastSpace >= 0 && lastSpace < str.length - 1) {
                val candidate = str.substring(lastSpace + 1).trim()
                if (!candidate.startsWith("#") && candidate.any { it.isDigit() }) {
                    str = candidate
                }
            }
            return str.trim()
                .removePrefix("v")
                .removePrefix("V")
                .removePrefix("release-")
                .removePrefix("Release-")
                .removePrefix("version-")
                .removePrefix("Version-")
                .trim()
        }

        fun isSemanticVersion(v: String): Boolean {
            if (v.isBlank() || v.startsWith("Run #", ignoreCase = true) || v.startsWith("#")) return false
            val firstChar = v.firstOrNull() ?: return false
            if (!firstChar.isDigit()) return false
            return v.any { it.isDigit() } && !v.contains(" ")
        }

        fun compareSemanticVersions(v1: String, v2: String): Int? {
            val parts1 = v1.split(".", "-", "_").mapNotNull { it.trim().takeWhile { c -> c.isDigit() }.toIntOrNull() }
            val parts2 = v2.split(".", "-", "_").mapNotNull { it.trim().takeWhile { c -> c.isDigit() }.toIntOrNull() }
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
