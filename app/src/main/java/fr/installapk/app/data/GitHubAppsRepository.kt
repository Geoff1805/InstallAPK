package fr.installapk.app.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import fr.installapk.app.model.ProjectApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class GitHubAppsRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val defaultProjects = listOf(
        ProjectApp(
            id = "Bullix",
            repoOwner = "Geoff1805",
            repoName = "Bullix",
            packageName = "fr.bullix.app",
            displayName = "Bullix",
            description = "Assistant personnel offline-first pour collectionneurs de BD & scans",
            iconEmoji = "📚",
            artifactPattern = "bullix-debug-apk"
        ),
        ProjectApp(
            id = "Needix",
            repoOwner = "Geoff1805",
            repoName = "Needix",
            packageName = "com.aistudio.inventory.hmtst",
            displayName = "Needix",
            description = "Gestionnaire intelligent d'inventaire et courses du foyer",
            iconEmoji = "📦",
            artifactPattern = "Needix-Debug-APK"
        ),
        ProjectApp(
            id = "InstallAPK",
            repoOwner = "Geoff1805",
            repoName = "InstallAPK",
            packageName = "fr.installapk.app",
            displayName = "InstallAPK (Cette application)",
            description = "Mise à jour et installation directe depuis GitHub Actions",
            iconEmoji = "📲",
            isSelf = true,
            artifactPattern = "installapk-debug-apk"
        )
    )

    fun getInitialProjects(): List<ProjectApp> {
        return defaultProjects.map { app ->
            val installed = getInstalledPackageInfo(app.packageName)
            if (installed != null) {
                val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    installed.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    installed.versionCode.toLong()
                }
                app.copy(
                    installedVersionCode = vCode,
                    installedVersionName = installed.versionName
                )
            } else {
                app
            }
        }
    }

    private fun getInstalledPackageInfo(packageName: String): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun validateToken(token: String): TokenValidationResult = withContext(Dispatchers.IO) {
        if (token.isBlank()) {
            return@withContext TokenValidationResult(
                isValid = false,
                errorMessage = "Aucun jeton configuré"
            )
        }

        try {
            val authVal = if (token.startsWith("Bearer ") || token.startsWith("token ")) token else "Bearer $token"
            val request = Request.Builder()
                .url("https://api.github.com/user")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "InstallAPK-Token-Validator")
                .header("Authorization", authVal)
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            val expirationHeader = response.header("github-authentication-token-expiration")
            val scopesHeader = response.header("x-oauth-scopes")

            if (response.isSuccessful) {
                val bodyStr = response.body?.string() ?: ""
                val username = try {
                    JSONObject(bodyStr).optString("login", "")
                } catch (e: Exception) {
                    null
                }
                TokenValidationResult(
                    isValid = true,
                    statusCode = code,
                    username = username,
                    expirationDate = expirationHeader,
                    scopes = scopesHeader
                )
            } else {
                TokenValidationResult(
                    isValid = false,
                    statusCode = code,
                    errorMessage = when (code) {
                        401 -> "Jeton GitHub invalide ou révoqué (Bad credentials)"
                        403 -> "Accès refusé ou quota API atteint"
                        404 -> "Utilisateur GitHub introuvable"
                        else -> "Erreur HTTP $code"
                    }
                )
            }
        } catch (e: Exception) {
            TokenValidationResult(
                isValid = false,
                errorMessage = "Erreur de connexion : ${e.message}"
            )
        }
    }

    suspend fun fetchLatestAppInfo(app: ProjectApp, token: String): ProjectApp = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/${app.repoOwner}/${app.repoName}/actions/artifacts?per_page=15"
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "InstallAPK-Android")

            if (token.isNotBlank()) {
                val authVal = if (token.startsWith("Bearer ") || token.startsWith("token ")) token else "Bearer $token"
                reqBuilder.header("Authorization", authVal)
            }

            val response = client.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) {
                return@withContext app.copy(
                    statusText = if (response.code == 401) "Clé invalide (401)" else "Erreur API (${response.code})"
                )
            }

            val bodyStr = response.body?.string() ?: return@withContext app
            val json = JSONObject(bodyStr)
            val artifacts = json.optJSONArray("artifacts") ?: return@withContext app

            var matchedArtifact: JSONObject? = null
            for (i in 0 until artifacts.length()) {
                val art = artifacts.getJSONObject(i)
                val name = art.optString("name", "")
                val isExpired = art.optBoolean("expired", false)
                if (isExpired) continue

                if (name.contains(app.artifactPattern, ignoreCase = true) || 
                    (app.artifactPattern == "apk" && name.contains("apk", ignoreCase = true))) {
                    matchedArtifact = art
                    break
                }
            }

            if (matchedArtifact != null) {
                val artId = matchedArtifact.getLong("id")
                val artSize = matchedArtifact.getLong("size_in_bytes")
                val artDate = matchedArtifact.optString("created_at", "")
                val archiveUrl = matchedArtifact.getString("archive_download_url")
                val workflowRun = matchedArtifact.optJSONObject("workflow_run")
                val runId = workflowRun?.optLong("id")

                // Récupération optionnelle du run_number pour le numéro de build
                var runNumber: Long? = null
                if (runId != null) {
                    runNumber = fetchRunNumber(app.repoOwner, app.repoName, runId, token)
                }

                val installed = getInstalledPackageInfo(app.packageName)
                val vCode = if (installed != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        installed.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        installed.versionCode.toLong()
                    }
                } else null

                val vName = installed?.versionName

                app.copy(
                    installedVersionCode = vCode,
                    installedVersionName = vName,
                    latestArtifactId = artId,
                    latestArtifactSize = artSize,
                    latestArtifactDate = artDate,
                    downloadUrl = archiveUrl,
                    latestVersionCode = runNumber?.let { 1000L + it },
                    latestVersionName = runNumber?.let { "1.0.$it" },
                    statusText = "Prêt"
                )
            } else {
                app.copy(statusText = "Aucun artefact APK trouvé")
            }
        } catch (e: Exception) {
            app.copy(statusText = "Erreur: ${e.message}")
        }
    }

    private fun fetchRunNumber(owner: String, repo: String, runId: Long, token: String): Long? {
        return try {
            val runUrl = "https://api.github.com/repos/$owner/$repo/actions/runs/$runId"
            val reqBuilder = Request.Builder()
                .url(runUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "InstallAPK-Android")

            if (token.isNotBlank()) {
                val authVal = if (token.startsWith("Bearer ") || token.startsWith("token ")) token else "Bearer $token"
                reqBuilder.header("Authorization", authVal)
            }

            val response = client.newCall(reqBuilder.build()).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string() ?: return null
                val runJson = JSONObject(bodyStr)
                runJson.optLong("run_number")
            } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun downloadAndExtractApk(
        app: ProjectApp,
        token: String,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val downloadUrl = app.downloadUrl ?: return@withContext null
        try {
            val reqBuilder = Request.Builder()
                .url(downloadUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "InstallAPK-Android")

            if (token.isNotBlank()) {
                val authVal = if (token.startsWith("Bearer ") || token.startsWith("token ")) token else "Bearer $token"
                reqBuilder.header("Authorization", authVal)
            }

            val response = client.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) return@withContext null

            val responseBody = response.body ?: return@withContext null
            val contentLength = responseBody.contentLength()
            val inputStream = responseBody.byteStream()

            // Sauvegarder le zip temporaire
            val tempZipFile = File(context.cacheDir, "${app.id}_temp.zip")
            if (tempZipFile.exists()) tempZipFile.delete()

            val fos = FileOutputStream(tempZipFile)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                fos.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (contentLength > 0) {
                    onProgress(totalRead.toFloat() / contentLength.toFloat())
                }
            }
            fos.flush()
            fos.close()
            inputStream.close()

            // Dézipper et extraire le fichier .apk
            val updatesDir = File(context.filesDir, "updates")
            if (!updatesDir.exists()) updatesDir.mkdirs()

            val destinationApk = File(updatesDir, "${app.id}.apk")
            if (destinationApk.exists()) destinationApk.delete()

            val zis = ZipInputStream(tempZipFile.inputStream())
            var zipEntry = zis.nextEntry
            var foundApk = false

            while (zipEntry != null) {
                if (!zipEntry.isDirectory && zipEntry.name.endsWith(".apk", ignoreCase = true)) {
                    val apkFos = FileOutputStream(destinationApk)
                    val apkBuffer = ByteArray(8192)
                    var count: Int
                    while (zis.read(apkBuffer).also { count = it } != -1) {
                        apkFos.write(apkBuffer, 0, count)
                    }
                    apkFos.flush()
                    apkFos.close()
                    foundApk = true
                    break
                }
                zipEntry = zis.nextEntry
            }
            zis.close()
            tempZipFile.delete()

            if (foundApk && destinationApk.exists() && destinationApk.length() > 0) {
                destinationApk
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
