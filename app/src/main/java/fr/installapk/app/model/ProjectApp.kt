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
    val isDirectApk: Boolean = false,
    val isNew: Boolean = false,
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

            val cleanInstalled = cleanVersion(installedVersionName)
            val cleanLatest = cleanVersion(latestVersionName)
            val isInstalledSem = isSemanticVersion(cleanInstalled)
            val isLatestSem = isSemanticVersion(cleanLatest)
            val semCmp = if (isInstalledSem && isLatestSem) compareSemanticVersions(cleanInstalled, cleanLatest) else null

            // 1. COMPARAISON SÉMANTIQUE DE VERSION (Arbitre prioritaire pour les versions sémantiques)
            // Exemples : "1.0.119" vs "1.0.118" -> MISE À JOUR REQUISE
            // "1.0.14" vs "1.0.13" -> MISE À JOUR REQUISE
            if (semCmp != null) {
                if (semCmp < 0) {
                    // Version installée sémantiquement inférieure -> Mise à jour REQUISE
                    return false
                }
                if (semCmp > 0) {
                    // Version installée sémantiquement supérieure (version dev locale) -> À jour
                    return true
                }
                // Si semCmp == 0 : Les numéros de version sémantiques sont identiques (ex: "1.0.0" vs "1.0.0").
                // On passe aux vérifications plus fines (versionCode officiel, ID d'artefact, horodatage de build).
            }

            // 2. CRITÈRE OFFICIEL ANDROID : versionCode
            if (hasInstalledCode && hasLatestCode) {
                if (installedVersionCode!! < latestVersionCode!!) {
                    // Le code installé est inférieur au code disponible -> Mise à jour REQUISE
                    return false
                }
                if (installedVersionCode!! > latestVersionCode!!) {
                    // Le code installé est supérieur -> À jour
                    return true
                }
                // Si installedVersionCode == latestVersionCode :
                // Les codes sont identiques. On examine si un nouvel artefact/build CI a été produit.
            }

            // 3. IDENTIFIANT D'ARTEFACT INSTALLÉ
            // Si cet artefact précis a déjà été installé avec succès sur le téléphone
            if (latestArtifactId != null && lastInstalledArtifactId != null && latestArtifactId == lastInstalledArtifactId) {
                return true
            }

            // 4. CRITÈRE HORODATAGE (Détection des nouveaux builds CI quand les versions sont identiques)
            if (installedUpdateTime != null && artifactTimestamp != null) {
                if (artifactTimestamp > (installedUpdateTime + 60_000L)) {
                    // Nouvel APK compilé plus récemment sur GitHub Actions après l'installation -> Mise à jour disponible
                    return false
                }
            }

            // 5. PAR DÉFAUT :
            // Si les versions sont identiques et non vides
            if (cleanInstalled.isNotBlank() && cleanLatest.isNotBlank() && cleanInstalled.equals(cleanLatest, ignoreCase = true)) {
                return true
            }

            // Si les versions ne correspondent pas et qu'un APK est disponible -> proposer la mise à jour
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
            val lastSpace = str.lastIndexOf(' ')
            if (lastSpace >= 0 && lastSpace < str.length - 1) {
                val candidate = str.substring(lastSpace + 1).trim()
                if (candidate.any { it.isDigit() }) {
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
            // Extraction des blocs de nombres séparés par . ou - ou _
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
