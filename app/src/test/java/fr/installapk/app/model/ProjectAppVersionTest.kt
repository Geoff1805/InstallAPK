package fr.installapk.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectAppVersionTest {

    private fun createApp(
        installedVersionCode: Long? = null,
        installedVersionName: String? = null,
        latestVersionCode: Long? = null,
        latestVersionName: String? = null,
        installedUpdateTime: Long? = null,
        artifactTimestamp: Long? = null,
        downloadUrl: String? = "https://example.com/app.apk",
        latestArtifactId: Long? = null,
        lastInstalledArtifactId: Long? = null
    ): ProjectApp {
        return ProjectApp(
            id = "TestApp",
            repoOwner = "Geoff1805",
            repoName = "TestApp",
            packageName = "fr.test.app",
            displayName = "Test App",
            description = "App de test",
            iconEmoji = "🧪",
            installedVersionCode = installedVersionCode,
            installedVersionName = installedVersionName,
            latestVersionCode = latestVersionCode,
            latestVersionName = latestVersionName,
            installedUpdateTime = installedUpdateTime,
            artifactTimestamp = artifactTimestamp,
            downloadUrl = downloadUrl,
            latestArtifactId = latestArtifactId,
            lastInstalledArtifactId = lastInstalledArtifactId
        )
    }

    @Test
    fun testVersionCodeTakesAbsolutePrecedence() {
        // installed code 1 < latest code 2 -> Mise à jour disponible même si le versionName n'a pas bougé
        val appWithSameName = createApp(
            installedVersionCode = 1L,
            installedVersionName = "1.0.0",
            latestVersionCode = 2L,
            latestVersionName = "1.0.0"
        )
        assertFalse("Quand latestVersionCode > installedVersionCode, l'app ne doit pas être à jour", appWithSameName.isUpToDate)
        assertTrue("Une mise à jour doit être disponible", appWithSameName.hasUpdateAvailable)

        // installed code 2 > latest code 1 -> À jour (version dev plus avancée)
        val appWithHigherCode = createApp(
            installedVersionCode = 2L,
            installedVersionName = "1.0.0",
            latestVersionCode = 1L,
            latestVersionName = "1.0.0"
        )
        assertTrue("Quand installedVersionCode > latestVersionCode, l'app doit être à jour", appWithHigherCode.isUpToDate)
        assertFalse(appWithHigherCode.hasUpdateAvailable)
    }

    @Test
    fun testSemanticVersionComparison() {
        // Sans versionCode ou codes identiques : 1.0.9 vs 1.0.12
        val app1 = createApp(
            installedVersionName = "v1.0.9",
            latestVersionName = "1.0.12"
        )
        assertFalse("1.0.9 < 1.0.12 donc pas à jour", app1.isUpToDate)
        assertTrue("Mise à jour disponible", app1.hasUpdateAvailable)

        // Versions identiques
        val app2 = createApp(
            installedVersionName = "1.0.12",
            latestVersionName = "v1.0.12"
        )
        assertTrue("1.0.12 == v1.0.12 donc à jour", app2.isUpToDate)
        assertFalse(app2.hasUpdateAvailable)

        // Version locale plus récente
        val app3 = createApp(
            installedVersionName = "1.2.0",
            latestVersionName = "1.0.12"
        )
        assertTrue("1.2.0 > 1.0.12 donc à jour", app3.isUpToDate)
    }

    @Test
    fun testTimestampComparisonForSameVersionBuilds() {
        val installTime = 1_000_000L
        val newerBuildTime = 2_000_000L // Build CI généré après l'installation
        val olderBuildTime = 500_000L

        // Même version code et name, mais build plus récent sur GitHub Actions
        val appNewBuild = createApp(
            installedVersionCode = 10L,
            installedVersionName = "1.0.0",
            latestVersionCode = 10L,
            latestVersionName = "1.0.0",
            installedUpdateTime = installTime,
            artifactTimestamp = newerBuildTime
        )
        assertFalse("Un artefact CI plus récent doit déclencher la mise à jour disponible", appNewBuild.isUpToDate)
        assertTrue(appNewBuild.hasUpdateAvailable)

        // Même version et build plus ancien que l'installation locale
        val appOldBuild = createApp(
            installedVersionCode = 10L,
            installedVersionName = "1.0.0",
            latestVersionCode = 10L,
            latestVersionName = "1.0.0",
            installedUpdateTime = installTime,
            artifactTimestamp = olderBuildTime
        )
        assertTrue("Build antérieur à l'installation locale donc à jour", appOldBuild.isUpToDate)
    }

    @Test
    fun testRunNumberDoesNotCorruptSemanticComparison() {
        // Si latestVersionName est "Run #45" et installedVersionName "1.0.0"
        val cleanRun = ProjectApp.cleanVersion("Run #45")
        assertEquals("Run #45", cleanRun)
        assertFalse("Run #45 ne doit pas être considéré comme une version sémantique valide", ProjectApp.isSemanticVersion(cleanRun))
    }

    @Test
    fun testSemanticVersionTakesPrecedenceOverBogusVersionCode() {
        // Scénario réel Bullix : le code installé est 1118 (généré par la CI 1000 + run),
        // mais le repo git a un versionCode statique de 1.
        // La nouvelle version disponible est 1.0.119 (supérieure à 1.0.118).
        val app = createApp(
            installedVersionCode = 1118L,
            installedVersionName = "1.0.118",
            latestVersionCode = 1L, // Bogus/statique depuis version.properties
            latestVersionName = "1.0.119" // Tag officiel de release
        )
        assertFalse("1.0.118 < 1.0.119 donc mise à jour REQUISE malgré le code statique", app.isUpToDate)
        assertTrue("Une mise à jour doit être détectée", app.hasUpdateAvailable)
    }

    @Test
    fun testCleanVersionStripsAppTitlePrefix() {
        assertEquals("1.0.119", ProjectApp.cleanVersion("Bullix v1.0.119"))
        assertEquals("1.0.14", ProjectApp.cleanVersion("InstallAPK v1.0.14"))
        assertEquals("0.6.4", ProjectApp.cleanVersion("Needix 0.6.4"))
        assertEquals("1.2.3", ProjectApp.cleanVersion("v1.2.3"))
        assertEquals("1.2.3", ProjectApp.cleanVersion("release-1.2.3"))
    }

    @Test
    fun testSameArtifactIdConfirmedIsUpToDate() {
        val app = createApp(
            installedVersionCode = 10L,
            installedVersionName = "0.6.4",
            latestVersionCode = 10L,
            latestVersionName = "0.6.4",
            latestArtifactId = 9999L,
            lastInstalledArtifactId = 9999L
        )
        assertTrue("Même ID d'artefact déjà installé -> À jour", app.isUpToDate)
        assertFalse(app.hasUpdateAvailable)
    }

    @Test
    fun testNotInstalledApp() {
        val uninstalledApp = createApp(
            installedVersionName = null,
            installedVersionCode = null,
            latestVersionName = "1.0.0",
            latestVersionCode = 1L
        )
        assertFalse("Non installée", uninstalledApp.isInstalled)
        assertFalse("Non installée donc pas à jour", uninstalledApp.isUpToDate)
        assertTrue("Prête à être installée", uninstalledApp.needsInstallationOrUpdate)
    }
}
