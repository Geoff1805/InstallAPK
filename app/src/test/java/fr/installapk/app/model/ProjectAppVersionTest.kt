package fr.installapk.app.model

import fr.installapk.app.data.ArchiveType
import fr.installapk.app.data.GitHubAppsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        val appWithSameName = createApp(
            installedVersionCode = 1L,
            installedVersionName = "1.0.0",
            latestVersionCode = 2L,
            latestVersionName = "1.0.0"
        )
        assertFalse("Quand latestVersionCode > installedVersionCode, l'app ne doit pas être à jour", appWithSameName.isUpToDate)
        assertTrue("Une mise à jour doit être disponible", appWithSameName.hasUpdateAvailable)

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
        val app1 = createApp(
            installedVersionName = "v1.0.9",
            latestVersionName = "1.0.12"
        )
        assertFalse("1.0.9 < 1.0.12 donc pas à jour", app1.isUpToDate)
        assertTrue("Mise à jour disponible", app1.hasUpdateAvailable)

        val app2 = createApp(
            installedVersionName = "1.0.12",
            latestVersionName = "v1.0.12"
        )
        assertTrue("1.0.12 == v1.0.12 donc à jour", app2.isUpToDate)
        assertFalse(app2.hasUpdateAvailable)

        val app3 = createApp(
            installedVersionName = "1.2.0",
            latestVersionName = "1.0.12"
        )
        assertTrue("1.2.0 > 1.0.12 donc à jour", app3.isUpToDate)
    }

    @Test
    fun testTimestampComparisonForSameVersionBuilds() {
        val installTime = 1_000_000L
        val newerBuildTime = 2_000_000L
        val olderBuildTime = 500_000L

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
        val cleanRun = ProjectApp.cleanVersion("Run #45")
        assertEquals("Run #45", cleanRun)
        assertFalse("Run #45 ne doit pas être considéré comme une version sémantique valide", ProjectApp.isSemanticVersion(cleanRun))
    }

    @Test
    fun testSemanticVersionTakesPrecedenceOverBogusVersionCode() {
        val app = createApp(
            installedVersionCode = 1118L,
            installedVersionName = "1.0.118",
            latestVersionCode = 1L,
            latestVersionName = "1.0.119"
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
    fun testSameArtifactIdConfirmedIsUpToDateEvenWhenTagDiffersFromApkManifest() {
        // Cas critique corrigé : le tag GitHub Release est "v1.0.45", mais l'APK compilé avait versionName="1.0.0".
        // Puisque latestArtifactId == lastInstalledArtifactId (cet APK précis a déjà été installé),
        // l'application ne doit PLUS reproposer la même mise à jour en boucle !
        val app = createApp(
            installedVersionCode = 1L,
            installedVersionName = "1.0.0",
            latestVersionCode = 1L,
            latestVersionName = "v1.0.45",
            latestArtifactId = 9999L,
            lastInstalledArtifactId = 9999L
        )
        assertTrue("Même ID d'artefact déjà installé -> Doit être considéré À jour malgré la différence de tag", app.isUpToDate)
        assertFalse("Ne doit pas reproposer la même mise à jour", app.hasUpdateAvailable)
    }

    @Test
    fun testInstalledAfterBuildTimestampIsUpToDateEvenWhenTagDiffers() {
        // L'APK a été installé sur le téléphone à t=2_000_000 alors que la Release GitHub a été publiée à t=1_000_000
        val app = createApp(
            installedVersionCode = 1L,
            installedVersionName = "1.0.0",
            latestVersionCode = 1L,
            latestVersionName = "v1.0.45",
            installedUpdateTime = 2_000_000L,
            artifactTimestamp = 1_000_000L
        )
        assertTrue("Installé après la date du build GitHub -> À jour", app.isUpToDate)
        assertFalse(app.hasUpdateAvailable)
    }

    @Test
    fun testInspectDownloadedArchiveDistinguishesDirectApkFromActionZip() {
        val tempDirectApk = File.createTempFile("test_direct", ".apk")
        val tempZipContainer = File.createTempFile("test_container", ".zip")
        val tempExtractedApk = File.createTempFile("test_extracted", ".apk")

        try {
            // 1. Créer un vrai fichier structurellement APK (ZIP contenant AndroidManifest.xml et classes.dex)
            ZipOutputStream(FileOutputStream(tempDirectApk)).use { zos ->
                zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zos.write("manifest-binary-data".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("classes.dex"))
                zos.write("dex-binary-data".toByteArray())
                zos.closeEntry()
            }

            assertEquals(
                "Un fichier .apk contenant AndroidManifest.xml doit être reconnu comme DIRECT_APK et non décompressé",
                ArchiveType.DIRECT_APK,
                GitHubAppsRepository.inspectDownloadedArchive(tempDirectApk)
            )

            // 2. Créer un conteneur ZIP GitHub Actions contenant un fichier Bullix.apk
            val nestedApkBytes = ByteArrayOutputStream().also { baos ->
                ZipOutputStream(baos).use { zos ->
                    zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                    zos.write("inner-manifest".toByteArray())
                    zos.closeEntry()
                }
            }.toByteArray()

            ZipOutputStream(FileOutputStream(tempZipContainer)).use { zos ->
                zos.putNextEntry(ZipEntry("Bullix.apk"))
                zos.write(nestedApkBytes)
                zos.closeEntry()
            }

            assertEquals(
                "Une archive ZIP contenant Bullix.apk doit être reconnue comme ZIP_CONTAINING_APK",
                ArchiveType.ZIP_CONTAINING_APK,
                GitHubAppsRepository.inspectDownloadedArchive(tempZipContainer)
            )

            val extractedOk = GitHubAppsRepository.extractApkFromZipContainer(tempZipContainer, tempExtractedApk)
            assertTrue("L'extraction du .apk depuis le conteneur ZIP doit réussir", extractedOk)
            assertEquals(
                "Le fichier extrait doit lui-même être reconnu comme DIRECT_APK",
                ArchiveType.DIRECT_APK,
                GitHubAppsRepository.inspectDownloadedArchive(tempExtractedApk)
            )
        } finally {
            tempDirectApk.delete()
            tempZipContainer.delete()
            tempExtractedApk.delete()
        }
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

    @Test
    fun testScoreReleaseAssetPrioritizesVersionedApk() {
        val app = createApp() // id = "TestApp", repoName = "TestApp"
        val scoreVersioned = GitHubAppsRepository.scoreReleaseAsset("TestApp-v1.0.52.apk", app)
        val scoreVersionedWithoutV = GitHubAppsRepository.scoreReleaseAsset("TestApp-1.0.52.apk", app)
        val scoreExactLegacy = GitHubAppsRepository.scoreReleaseAsset("TestApp.apk", app)
        val scoreContainsApp = GitHubAppsRepository.scoreReleaseAsset("TestApp-snapshot.apk", app)
        val scoreGenericApk = GitHubAppsRepository.scoreReleaseAsset("other.apk", app)
        val scoreNonApk = GitHubAppsRepository.scoreReleaseAsset("TestApp-v1.0.52.zip", app)

        assertEquals("APK normalisé avec numéro de version doit avoir la priorité maximale (4)", 4, scoreVersioned)
        assertEquals("APK avec numéro de version sans préfixe 'v' doit avoir la priorité maximale (4)", 4, scoreVersionedWithoutV)
        assertEquals("APK exact sans version (legacy) doit avoir la priorité 3", 3, scoreExactLegacy)
        assertEquals("APK contenant le nom sans version doit avoir la priorité 2", 2, scoreContainsApp)
        assertEquals("APK générique externe doit avoir la priorité 1", 1, scoreGenericApk)
        assertEquals("Fichier non APK doit être rejeté (-1)", -1, scoreNonApk)
    }
}
