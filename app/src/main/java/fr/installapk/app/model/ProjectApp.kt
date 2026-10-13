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

            // 2. Si le versionCode réel est disponible
            if (latestVersionCode != null && installedVersionCode != null && latestVersionCode > 0) {
                if (installedVersionCode >= latestVersionCode) return true
                if (installedVersionCode < latestVersionCode) return false
            }

            // 3. Si le versionName réel est disponible et concorde
            if (latestVersionName != null && installedVersionName != null && installedVersionName == latestVersionName) {
                return true
            }

            // 4. Par horodatage : si l'application sur le téléphone a été installée après la création de l'artefact (marge 60s)
            if (installedUpdateTime != null && artifactTimestamp != null) {
                return installedUpdateTime >= (artifactTimestamp - 60_000L)
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
}
