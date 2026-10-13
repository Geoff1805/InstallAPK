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
    val latestArtifactSize: Long? = null,
    val latestArtifactDate: String? = null,
    val downloadUrl: String? = null,
    val isUpdating: Boolean = false,
    val isQueued: Boolean = false,
    val downloadProgress: Float? = null, // 0.0 to 1.0
    val statusText: String? = null
) {
    val isInstalled: Boolean
        get() = !installedVersionName.isNullOrBlank()

    val hasUpdateAvailable: Boolean
        get() {
            if (!isInstalled || downloadUrl == null) return false
            if (latestVersionCode != null && installedVersionCode != null) {
                return latestVersionCode > installedVersionCode
            }
            if (latestVersionName != null && installedVersionName != null) {
                return latestVersionName != installedVersionName
            }
            return false
        }

    val isUpToDate: Boolean
        get() {
            if (!isInstalled || downloadUrl == null) return false
            if (latestVersionCode != null && installedVersionCode != null) {
                return installedVersionCode >= latestVersionCode
            }
            if (latestVersionName != null && installedVersionName != null) {
                return installedVersionName == latestVersionName
            }
            return false
        }

    val needsInstallationOrUpdate: Boolean
        get() = downloadUrl != null && (!isInstalled || hasUpdateAvailable)
}
