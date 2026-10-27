package fr.installapk.app.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import com.chuckerteam.chucker.api.ChuckerInterceptor
import fr.installapk.app.model.ProjectApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

enum class ArchiveType {
    DIRECT_APK,
    ZIP_CONTAINING_APK,
    INVALID
}

class GitHubAppsRepository(private val context: Context) {

    private val tag = "GitHubAppsRepository"
    private val prefs = context.getSharedPreferences("installapk_prefs", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(ChuckerInterceptor.Builder(context).build())
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            val host = req.url.host.lowercase()
            if (host.contains("s3.amazonaws.com") || host.contains("github-production-release-asset") || host.contains("blob.core.windows.net")) {
                val stripped = req.newBuilder()
                    .removeHeader("Authorization")
                    .build()
                chain.proceed(stripped)
            } else {
                chain.proceed(req)
            }
        }
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
            artifactPattern = "Bullix.apk"
        ),
        ProjectApp(
            id = "Needix",
            repoOwner = "Geoff1805",
            repoName = "Needix",
            packageName = "com.aistudio.inventory.hmtst",
            displayName = "Needix",
            description = "Gestionnaire intelligent d'inventaire et courses du foyer",
            iconEmoji = "📦",
            artifactPattern = "Needix.apk"
        ),
        ProjectApp(
            id = "AppliHome",
            repoOwner = "Geoff1805",
            repoName = "AppliHome",
            packageName = "com.geoff.applihome",
            displayName = "AppliHome",
            description = "Dashboard domotique résidentiel Matter & Google Home Offline-First",
            iconEmoji = "🏠",
            artifactPattern = "AppliHome.apk"
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
            artifactPattern = "InstallAPK.apk"
        ),
        ProjectApp(
            id = "antigravity-controller",
            repoOwner = "Geoff1805",
            repoName = "antigravity-controller",
            packageName = "fr.antigravity.controller",
            displayName = "Antigravity Controller",
            description = "Contrôleur mobile et dashboard Flutter pour l'agent Antigravity",
            iconEmoji = "🎮",
            artifactPattern = "AntigravityController.apk"
        )
    )

    fun getInitialProjects(): List<ProjectApp> {
        val merged = mutableListOf<ProjectApp>()
        merged.addAll(defaultProjects)

        // Charger les projets découverts depuis le cache SharedPreferences
        val acknowledgedSet = getAcknowledgedRepoIds()
        val cachedDiscoveredJson = prefs.getString("cached_discovered_apps", null)
        if (!cachedDiscoveredJson.isNullOrBlank()) {
            try {
                val jsonArray = JSONArray(cachedDiscoveredJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.getString("id")
                    if (merged.none { it.id.equals(id, ignoreCase = true) }) {
                        val isSelf = obj.optBoolean("isSelf", false)
                        val isNew = !isSelf && !acknowledgedSet.contains(id.lowercase())
                        merged.add(
                            ProjectApp(
                                id = id,
                                repoOwner = obj.getString("repoOwner"),
                                repoName = obj.getString("repoName"),
                                packageName = obj.getString("packageName"),
                                displayName = obj.optString("displayName", id),
                                description = obj.optString("description", "Application Android GitHub"),
                                iconEmoji = obj.optString("iconEmoji", "📱"),
                                isSelf = isSelf,
                                isNew = isNew,
                                artifactPattern = obj.optString("artifactPattern", "apk")
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Erreur lecture cache projets découverts: ${e.message}")
            }
        }

        return merged.map { refreshSingleAppInstalledStatus(it) }
    }

    fun refreshSingleAppInstalledStatus(app: ProjectApp): ProjectApp {
        val defaultApp = defaultProjects.firstOrNull { it.id.equals(app.id, ignoreCase = true) }
        var effectivePkg = defaultApp?.packageName ?: prefs.getString("pkg_${app.id}", null) ?: app.packageName
        var installed = getInstalledPackageInfo(effectivePkg)

        // Si non trouvé par le package name théorique, tenter une recherche de secours
        if (installed == null) {
            val foundPkg = findInstalledPackageForApp(app)
            if (foundPkg != null) {
                effectivePkg = foundPkg.packageName
                prefs.edit().putString("pkg_${app.id}", effectivePkg).apply()
                installed = foundPkg
            }
        }

        if (installed != null) {
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                installed.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                installed.versionCode.toLong()
            }

            // Confirmer l'installation en attente si le système Android a bien enregistré le nouveau package
            val pendingArtId = prefs.getLong("pending_art_id_${app.id}", -1L).takeIf { it != -1L }
            val pendingVCode = prefs.getLong("pending_vcode_${app.id}", -1L).takeIf { it != -1L }
            val pendingVName = prefs.getString("pending_vname_${app.id}", null)
            val pendingTime = prefs.getLong("pending_install_time_${app.id}", -1L).takeIf { it != -1L }

            if (pendingArtId != null) {
                val isConfirmed = when {
                    pendingVCode != null && vCode >= pendingVCode -> true
                    !pendingVName.isNullOrBlank() && installed.versionName != null &&
                        ProjectApp.cleanVersion(installed.versionName).equals(ProjectApp.cleanVersion(pendingVName), ignoreCase = true) -> true
                    pendingTime != null && installed.lastUpdateTime >= (pendingTime - 30_000L) -> true
                    else -> false
                }
                if (isConfirmed) {
                    prefs.edit().apply {
                        putLong("installed_artifact_id_${app.id}", pendingArtId)
                        putLong("installed_timestamp_${app.id}", installed.lastUpdateTime)
                        remove("pending_art_id_${app.id}")
                        remove("pending_vcode_${app.id}")
                        remove("pending_vname_${app.id}")
                        remove("pending_install_time_${app.id}")
                    }.apply()
                }
            }

            val lastInstalledArtId = prefs.getLong("installed_artifact_id_${app.id}", -1L).takeIf { it != -1L }
            val effectiveTime = installed.lastUpdateTime

            return app.copy(
                packageName = effectivePkg,
                installedVersionCode = vCode,
                installedVersionName = installed.versionName,
                installedUpdateTime = effectiveTime,
                lastInstalledArtifactId = lastInstalledArtId ?: app.lastInstalledArtifactId
            )
        } else {
            // L'application n'est plus présente sur l'appareil
            prefs.edit().apply {
                remove("installed_artifact_id_${app.id}")
                remove("installed_timestamp_${app.id}")
                remove("pending_art_id_${app.id}")
                remove("pending_vcode_${app.id}")
                remove("pending_vname_${app.id}")
                remove("pending_install_time_${app.id}")
            }.apply()
            return app.copy(
                packageName = effectivePkg,
                installedVersionCode = null,
                installedVersionName = null,
                installedUpdateTime = null,
                lastInstalledArtifactId = null
            )
        }
    }

    private fun findInstalledPackageForApp(app: ProjectApp): PackageInfo? {
        return try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            val searchKey = app.repoName.lowercase().replace("-", "").replace("_", "")
            val appIdKey = app.id.lowercase().replace("-", "").replace("_", "")

            // 1. Recherche par nom de paquet contenant les mots-clés de l'app
            for (pkg in packages) {
                val pkgName = pkg.packageName.lowercase()
                if (pkgName.contains(searchKey) || pkgName.contains(appIdKey)) {
                    return pkg
                }
            }

            // 2. Recherche par label de l'application
            for (pkg in packages) {
                try {
                    val appInfo = pkg.applicationInfo ?: continue
                    val appLabel = pm.getApplicationLabel(appInfo).toString().lowercase()
                    if (appLabel.equals(app.displayName.lowercase(), ignoreCase = true) ||
                        appLabel.contains(app.displayName.lowercase())
                    ) {
                        return pkg
                    }
                } catch (_: Exception) {}
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun getInstalledPackageInfo(packageName: String): PackageInfo? {
        if (packageName.isBlank()) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
        } catch (_: Exception) {
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
                if (!username.isNullOrBlank()) {
                    prefs.edit().putString("github_username", username).apply()
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

    fun getAcknowledgedRepoIds(): Set<String> {
        val saved = prefs.getStringSet("acknowledged_android_repos", null)
        if (saved != null) {
            return saved
        }
        val initialSet = mutableSetOf<String>()
        initialSet.addAll(defaultProjects.map { it.id.lowercase() })
        val cached = prefs.getString("cached_discovered_apps", null)
        if (!cached.isNullOrBlank()) {
            try {
                val arr = JSONArray(cached)
                for (i in 0 until arr.length()) {
                    initialSet.add(arr.getJSONObject(i).getString("id").lowercase())
                }
            } catch (_: Exception) {}
        }
        prefs.edit().putStringSet("acknowledged_android_repos", initialSet).apply()
        return initialSet
    }

    fun markRepoAcknowledged(appId: String) {
        val current = prefs.getStringSet("acknowledged_android_repos", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(appId.lowercase())
        prefs.edit().putStringSet("acknowledged_android_repos", current).apply()
    }

    suspend fun discoverAndRefreshApps(token: String): List<ProjectApp> = withContext(Dispatchers.IO) {
        val discoveredProjects = discoverAndroidProjects(token)

        // Fusion des projets par défaut et découverts
        val allProjects = mutableListOf<ProjectApp>()
        allProjects.addAll(defaultProjects)

        for (discovered in discoveredProjects) {
            val index = allProjects.indexOfFirst { it.id.equals(discovered.id, ignoreCase = true) }
            if (index >= 0) {
                // Mettre à jour avec le package name résolu si besoin et conserver isNew
                val existing = allProjects[index]
                allProjects[index] = existing.copy(
                    packageName = if (existing.packageName.isBlank()) discovered.packageName else existing.packageName,
                    isNew = discovered.isNew
                )
            } else {
                allProjects.add(discovered)
            }
        }

        // Sauvegarder dans le cache pour les prochains démarrages
        saveDiscoveredProjectsCache(allProjects)

        // Récupérer les informations de version et d'artefact en parallèle contrôlé (Semaphore 5)
        val fetchSemaphore = Semaphore(5)
        coroutineScope {
            allProjects.map { app ->
                async(Dispatchers.IO) {
                    fetchSemaphore.withPermit {
                        fetchLatestAppInfo(app, token)
                    }
                }
            }.awaitAll()
        }
    }

    fun addCustomRepo(repoName: String) {
        val customSet = prefs.getStringSet("user_custom_repos", emptySet())?.toMutableSet() ?: mutableSetOf()
        customSet.add(repoName.trim())
        prefs.edit().putStringSet("user_custom_repos", customSet).apply()
        markRepoAcknowledged(repoName.trim())
    }

    private fun isUserCustomRepo(repoName: String): Boolean {
        val customSet = prefs.getStringSet("user_custom_repos", emptySet()) ?: emptySet()
        return customSet.any { it.equals(repoName.trim(), ignoreCase = true) }
    }

    private suspend fun discoverAndroidProjects(token: String): List<ProjectApp> = withContext(Dispatchers.IO) {
        val projects = mutableListOf<ProjectApp>()
        try {
            val authVal = if (token.isNotBlank()) {
                if (token.startsWith("Bearer ") || token.startsWith("token ")) token else "Bearer $token"
            } else null

            // Récupération des dépôts de l'utilisateur
            val currentUsername = prefs.getString("github_username", "Geoff1805") ?: "Geoff1805"
            val reposUrl = if (authVal != null) {
                "https://api.github.com/user/repos?affiliation=owner&sort=updated&per_page=100"
            } else {
                "https://api.github.com/users/$currentUsername/repos?sort=updated&per_page=100"
            }

            val reqBuilder = Request.Builder()
                .url(reposUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "InstallAPK-Android-Discovery")

            if (authVal != null) {
                reqBuilder.header("Authorization", authVal)
            }

            val response = client.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) {
                Log.w(tag, "Échec découverte dépôts: code ${response.code}")
                return@withContext emptyList()
            }

            val bodyStr = response.body?.string() ?: return@withContext emptyList()
            val reposArray = JSONArray(bodyStr)

            for (i in 0 until reposArray.length()) {
                val repo = reposArray.getJSONObject(i)
                val isArchived = repo.optBoolean("archived", false)
                if (isArchived) continue

                val repoName = repo.getString("name")
                val ownerObj = repo.getJSONObject("owner")
                val repoOwner = ownerObj.getString("login")
                val description = repo.optString("description", "")
                val language = repo.optString("language", "")
                val topicsArray = repo.optJSONArray("topics")

                var hasAndroidTopic = false
                if (topicsArray != null) {
                    for (t in 0 until topicsArray.length()) {
                        val topic = topicsArray.optString(t, "").lowercase()
                        if (topic.contains("android") || topic.contains("apk") || topic.contains("flutter") || topic.contains("mobile")) {
                            hasAndroidTopic = true
                            break
                        }
                    }
                }

                val isKnown = defaultProjects.any { it.repoName.equals(repoName, ignoreCase = true) }
                val isDart = language.equals("Dart", ignoreCase = true)
                val isKotlinOrJava = language.equals("Kotlin", ignoreCase = true) || language.equals("Java", ignoreCase = true)
                val isCustom = isUserCustomRepo(repoName)

                // Vérifier si le projet est Android / Flutter ou s'il a un APK
                var isAndroidProject = isKnown || isCustom || hasAndroidTopic || isKotlinOrJava || isDart
                if (!isAndroidProject) {
                    val nameDesc = "${repoName.lowercase()} ${description.lowercase()}"
                    isAndroidProject = nameDesc.contains("android") || nameDesc.contains("apk") || nameDesc.contains("flutter")
                }
                if (!isAndroidProject) {
                    // Vérifier si des artefacts APK existent
                    isAndroidProject = checkHasApkArtifacts(repoOwner, repoName, token)
                }
                if (!isAndroidProject) {
                    // Vérifier si des releases APK existent
                    isAndroidProject = checkHasApkReleases(repoOwner, repoName, token)
                }
                if (!isAndroidProject) {
                    // Vérifier la présence d'une structure mobile (android, app, pubspec.yaml, etc.)
                    isAndroidProject = checkHasMobileStructure(repoOwner, repoName, token)
                }

                if (isAndroidProject) {
                    val resolvedPackageName = resolvePackageName(repoOwner, repoName, token)
                    val emoji = determineEmoji(repoName, description)
                    val isSelf = repoName.equals("InstallAPK", ignoreCase = true)
                    val isNew = !isSelf && !getAcknowledgedRepoIds().contains(repoName.lowercase())

                    projects.add(
                        ProjectApp(
                            id = repoName,
                            repoOwner = repoOwner,
                            repoName = repoName,
                            packageName = resolvedPackageName,
                            displayName = repoName,
                            description = if (description.isNotBlank()) description else "Application Android GitHub",
                            iconEmoji = emoji,
                            isSelf = isSelf,
                            isNew = isNew,
                            artifactPattern = "apk"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Erreur découverte dépôts GitHub: ${e.message}", e)
        }

        projects
    }

    private fun isExcludedArtifact(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".json") ||
            lower.endsWith(".txt") ||
            lower.endsWith(".xml") ||
            lower.endsWith(".html") ||
            lower.contains("dependency-graph") ||
            lower.contains("test-report") ||
            lower.contains("unit-test") ||
            lower.contains("lint-report")
    }

    private fun checkHasApkArtifacts(owner: String, repo: String, token: String): Boolean {
        return try {
            val url = "https://api.github.com/repos/$owner/$repo/actions/artifacts?per_page=100"
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "InstallAPK-Android")

            if (token.isNotBlank()) {
                val authVal = if (token.startsWith("Bearer ") || token.startsWith("token ")) token else "Bearer $token"
                reqBuilder.header("Authorization", authVal)
            }

            val response = client.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) return false
            val body = response.body?.string() ?: return false
            val json = JSONObject(body)
            val artifacts = json.optJSONArray("artifacts") ?: return false
            for (i in 0 until artifacts.length()) {
                val art = artifacts.getJSONObject(i)
                val name = art.optString("name", "")
                val size = art.optLong("size_in_bytes", 0L)
                val isExpired = art.optBoolean("expired", false)
                if (!isExpired && !isExcludedArtifact(name) && name.contains("apk", ignoreCase = true) && size > 200_000L) {
                    return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun checkHasApkReleases(owner: String, repo: String, token: String): Boolean {
        return try {
            val url = "https://api.github.com/repos/$owner/$repo/releases?per_page=5"
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "InstallAPK-Android")

            if (token.isNotBlank()) {
                val authVal = if (token.startsWith("Bearer ") || token.startsWith("token ")) token else "Bearer $token"
                reqBuilder.header("Authorization", authVal)
            }

            val response = client.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) return false
            val body = response.body?.string() ?: return false
            val releases = JSONArray(body)
            for (i in 0 until releases.length()) {
                val rel = releases.getJSONObject(i)
                val assets = rel.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val assetName = asset.optString("name", "")
                    if (assetName.endsWith(".apk", ignoreCase = true)) return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun checkHasMobileStructure(owner: String, repo: String, token: String): Boolean {
        return try {
            val url = "https://api.github.com/repos/$owner/$repo/contents"
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "InstallAPK-Android")

            if (token.isNotBlank()) {
                val authVal = if (token.startsWith("Bearer ") || token.startsWith("token ")) token else "Bearer $token"
                reqBuilder.header("Authorization", authVal)
            }

            val response = client.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) return false
            val body = response.body?.string() ?: return false
            val contents = JSONArray(body)
            for (i in 0 until contents.length()) {
                val item = contents.getJSONObject(i)
                val name = item.optString("name", "").lowercase()
                if (name == "android" || name == "app" || name == "pubspec.yaml" ||
                    name == "build.gradle.kts" || name == "build.gradle" || name == "settings.gradle.kts") {
                    return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun resolvePackageName(owner: String, repo: String, token: String): String {
        // 1. Vérifier dans le cache SharedPreferences
        val cached = prefs.getString("pkg_$repo", null)
        if (!cached.isNullOrBlank()) return cached

        // 2. Vérifier les projets connus par défaut
        val known = defaultProjects.firstOrNull { it.repoName.equals(repo, ignoreCase = true) }
        if (known != null && known.packageName.isNotBlank()) {
            prefs.edit().putString("pkg_$repo", known.packageName).apply()
            return known.packageName
        }

        // 2.5 Tenter de lire composeApp/build.gradle.kts (Kotlin Multiplatform / Compose)
        val extractedFromKmpKts = fetchAndParsePackageFromBuildGradle(owner, repo, "composeApp/build.gradle.kts", token)
        if (!extractedFromKmpKts.isNullOrBlank()) {
            prefs.edit().putString("pkg_$repo", extractedFromKmpKts).apply()
            return extractedFromKmpKts
        }

        // 3. Tenter de lire app/build.gradle.kts depuis l'API GitHub
        val extractedFromKts = fetchAndParsePackageFromBuildGradle(owner, repo, "app/build.gradle.kts", token)
        if (!extractedFromKts.isNullOrBlank()) {
            prefs.edit().putString("pkg_$repo", extractedFromKts).apply()
            return extractedFromKts
        }

        // 4. Tenter de lire app/build.gradle (Groovy)
        val extractedFromGroovy = fetchAndParsePackageFromBuildGradle(owner, repo, "app/build.gradle", token)
        if (!extractedFromGroovy.isNullOrBlank()) {
            prefs.edit().putString("pkg_$repo", extractedFromGroovy).apply()
            return extractedFromGroovy
        }

        // 5. Tenter de lire android/app/build.gradle (Flutter / React Native)
        val extractedFromFlutterGroovy = fetchAndParsePackageFromBuildGradle(owner, repo, "android/app/build.gradle", token)
        if (!extractedFromFlutterGroovy.isNullOrBlank()) {
            prefs.edit().putString("pkg_$repo", extractedFromFlutterGroovy).apply()
            return extractedFromFlutterGroovy
        }

        // 6. Tenter de lire android/app/build.gradle.kts (Flutter KTS)
        val extractedFromFlutterKts = fetchAndParsePackageFromBuildGradle(owner, repo, "android/app/build.gradle.kts", token)
        if (!extractedFromFlutterKts.isNullOrBlank()) {
            prefs.edit().putString("pkg_$repo", extractedFromFlutterKts).apply()
            return extractedFromFlutterKts
        }

        // 7. Fallback conventionnel
        val sanitized = repo.lowercase().replace("-", "").replace("_", "")
        val fallback = "fr.$sanitized.app"
        return fallback
    }

    private fun fetchAndParsePackageFromBuildGradle(owner: String, repo: String, path: String, token: String): String? {
        return try {
            val url = "https://api.github.com/repos/$owner/$repo/contents/$path"
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "InstallAPK-Android")

            if (token.isNotBlank()) {
                val authVal = if (token.startsWith("Bearer ") || token.startsWith("token ")) token else "Bearer $token"
                reqBuilder.header("Authorization", authVal)
            }

            val response = client.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val contentBase64 = json.optString("content", "")
            if (contentBase64.isBlank()) return null

            val decodedBytes = Base64.decode(contentBase64, Base64.DEFAULT)
            val text = String(decodedBytes, Charsets.UTF_8)

            // Regex pour applicationId ou namespace
            val appIdRegex = Regex("""(?:applicationId|namespace)\s*(?:=)?\s*["']([^"']+)["']""")
            val matchAppId = appIdRegex.find(text)
            if (matchAppId != null) {
                return matchAppId.groupValues[1]
            }

            val namespaceRegex = Regex("""namespace\s*(?:=)?\s*["']([^"']+)["']""")
            val matchNamespace = namespaceRegex.find(text)
            matchNamespace?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }

    private fun determineEmoji(repoName: String, description: String): String {
        val text = "${repoName.lowercase()} ${description.lowercase()}"
        return when {
            text.contains("bd") || text.contains("comic") || text.contains("manga") || text.contains("book") -> "📚"
            text.contains("need") || text.contains("invent") || text.contains("course") || text.contains("stock") -> "📦"
            text.contains("install") || text.contains("apk") || text.contains("updater") -> "📲"
            text.contains("gmail") || text.contains("mail") || text.contains("courrier") -> "✉️"
            text.contains("clean") || text.contains("nettoy") -> "🧹"
            text.contains("camera") || text.contains("photo") -> "📷"
            text.contains("audio") || text.contains("music") || text.contains("son") -> "🎵"
            text.contains("game") || text.contains("jeu") -> "🎮"
            text.contains("consult") || text.contains("sqe") || text.contains("audit") -> "💼"
            text.contains("tool") || text.contains("util") -> "🛠️"
            else -> "📱"
        }
    }

    private fun saveDiscoveredProjectsCache(projects: List<ProjectApp>) {
        try {
            val jsonArray = JSONArray()
            for (p in projects) {
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("repoOwner", p.repoOwner)
                    put("repoName", p.repoName)
                    put("packageName", p.packageName)
                    put("displayName", p.displayName)
                    put("description", p.description)
                    put("iconEmoji", p.iconEmoji)
                    put("isSelf", p.isSelf)
                    put("artifactPattern", p.artifactPattern)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString("cached_discovered_apps", jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.w(tag, "Erreur sauvegarde cache: ${e.message}")
        }
    }

    suspend fun fetchLatestAppInfo(app: ProjectApp, token: String): ProjectApp = withContext(Dispatchers.IO) {
        try {
            val refreshedBase = refreshSingleAppInstalledStatus(app)
            val defaultApp = defaultProjects.firstOrNull { it.id.equals(refreshedBase.id, ignoreCase = true) }
            var effectivePkg = defaultApp?.packageName ?: prefs.getString("pkg_${refreshedBase.id}", null) ?: refreshedBase.packageName
            var installed = getInstalledPackageInfo(effectivePkg)
            if (installed == null) {
                val foundPkg = findInstalledPackageForApp(refreshedBase)
                if (foundPkg != null) {
                    effectivePkg = foundPkg.packageName
                    prefs.edit().putString("pkg_${refreshedBase.id}", effectivePkg).apply()
                    installed = foundPkg
                }
            }

            val vCode = if (installed != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    installed.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    installed.versionCode.toLong()
                }
            } else null
            val vName = installed?.versionName
            val effectiveInstalledTime = installed?.lastUpdateTime
            var lastInstalledArtId = prefs.getLong("installed_artifact_id_${app.id}", -1L).takeIf { it != -1L }

            // 1. Consulter les Releases GitHub (APK directs prêts à installer et tags officiels)
            val releaseApp = fetchLatestReleaseAppInfo(refreshedBase, token, vCode, vName, effectiveInstalledTime)

            // 2. Consulter les Artefacts GitHub Actions
            val url = "https://api.github.com/repos/${app.repoOwner}/${app.repoName}/actions/artifacts?per_page=100"
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "InstallAPK-Android")

            if (token.isNotBlank()) {
                val authVal = if (token.startsWith("Bearer ") || token.startsWith("token ")) token else "Bearer $token"
                reqBuilder.header("Authorization", authVal)
            }

            val response = client.newCall(reqBuilder.build()).execute()
            var matchedArtifact: JSONObject? = null

            if (response.isSuccessful) {
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val artifacts = json.optJSONArray("artifacts") ?: JSONArray()
                val candidateArtifacts = mutableListOf<JSONObject>()
                val appIdNorm = app.id.lowercase().replace("-", "").replace("_", "")
                val repoNorm = app.repoName.lowercase().replace("-", "").replace("_", "")
                val patNorm = app.artifactPattern.lowercase().replace("-", "").replace("_", "").replace(".apk", "")

                for (i in 0 until artifacts.length()) {
                    val art = artifacts.getJSONObject(i)
                    val name = art.optString("name", "")
                    val isExpired = art.optBoolean("expired", false)
                    val size = art.optLong("size_in_bytes", 0L)
                    if (isExpired || isExcludedArtifact(name) || size < 200_000L) continue

                    val normalizedName = name.lowercase().replace("-", "").replace("_", "").replace(".apk", "")
                    val repoPrefix = app.repoName.split("-", "_").firstOrNull { it.length >= 4 }?.lowercase() ?: ""
                    val matchesApp = normalizedName.contains(appIdNorm) ||
                        normalizedName.contains(repoNorm) ||
                        (repoPrefix.isNotBlank() && normalizedName.contains(repoPrefix)) ||
                        (patNorm.isNotBlank() && normalizedName.contains(patNorm))
                    val hasApk = name.contains("apk", ignoreCase = true) || matchesApp

                    if (matchesApp && hasApk) {
                        candidateArtifacts.add(art)
                    }
                }

                matchedArtifact = candidateArtifacts
                    .sortedWith(
                        compareByDescending<JSONObject> {
                            val branch = it.optJSONObject("workflow_run")?.optString("head_branch", "") ?: ""
                            branch.equals("main", ignoreCase = true) || branch.equals("master", ignoreCase = true)
                        }
                        .thenByDescending {
                            it.optString("created_at", "")
                        }
                        .thenByDescending {
                            scoreReleaseAsset(it.optString("name", ""), app)
                        }
                        .thenByDescending {
                            val name = it.optString("name", "")
                            name.contains("release", ignoreCase = true)
                        }
                    )
                    .firstOrNull()
            }

            val remoteVersion = fetchRemoteVersionInfo(app.repoOwner, app.repoName, token)

            // 3. Synthèse et arbitrage entre Release et Artefact Actions
            val releaseTimestamp = releaseApp?.artifactTimestamp ?: 0L
            var artifactTimestamp: Long? = null
            var runNumber: Long? = null
            var artId: Long? = null
            var artSize: Long? = null
            var artDate: String? = null
            var archiveUrl: String? = null

            if (matchedArtifact != null) {
                artId = matchedArtifact.getLong("id")
                artSize = matchedArtifact.getLong("size_in_bytes")
                artDate = matchedArtifact.optString("created_at", "")
                archiveUrl = matchedArtifact.getString("archive_download_url")
                artifactTimestamp = parseIsoDateToMillis(artDate)

                val workflowRun = matchedArtifact.optJSONObject("workflow_run")
                val runId = workflowRun?.optLong("id")
                if (runId != null) {
                    runNumber = fetchRunNumber(app.repoOwner, app.repoName, runId, token)
                }
            }

            val artTs = artifactTimestamp ?: 0L

            if (releaseApp != null && (matchedArtifact == null || releaseTimestamp >= (artTs - 300_000L))) {
                // Priorité à la Release : APK direct sans décompression ZIP
                val effectiveArtId = releaseApp.latestArtifactId ?: artId
                if (effectiveArtId != null) {
                    prefs.edit().putLong("latest_known_art_id_${app.id}", effectiveArtId).apply()
                }
                val cachedApkCodeForArt = effectiveArtId?.let {
                    prefs.getLong("apk_vcode_${app.id}_$it", -1L).takeIf { c -> c > 0L }
                }
                val cachedApkNameForArt = effectiveArtId?.let {
                    prefs.getString("apk_vname_${app.id}_$it", null)
                }

                // Si le téléphone a été mis à jour après la publication de la release ou possède déjà le code APK extrait de cet asset
                if (installed != null && effectiveArtId != null) {
                    val matchesInspectedApk = cachedApkCodeForArt != null && vCode == cachedApkCodeForArt &&
                        (cachedApkNameForArt == null || vName == cachedApkNameForArt)
                    val installedAfterBuild = effectiveInstalledTime != null && releaseTimestamp > 0L &&
                        effectiveInstalledTime >= (releaseTimestamp - 30_000L)
                    if (matchesInspectedApk || installedAfterBuild) {
                        lastInstalledArtId = effectiveArtId
                        prefs.edit().putLong("installed_artifact_id_${app.id}", effectiveArtId).apply()
                    }
                }

                val resolvedLatestCode = cachedApkCodeForArt ?: remoteVersion.first

                releaseApp.copy(
                    packageName = effectivePkg,
                    installedVersionCode = vCode,
                    installedVersionName = vName,
                    installedUpdateTime = effectiveInstalledTime,
                    latestVersionCode = resolvedLatestCode,
                    latestArtifactId = effectiveArtId,
                    lastInstalledArtifactId = lastInstalledArtId ?: refreshedBase.lastInstalledArtifactId,
                    isDirectApk = true,
                    statusText = "Prêt"
                )
            } else if (matchedArtifact != null && archiveUrl != null) {
                // Priorité à l'artefact GitHub Actions
                if (artId != null) {
                    prefs.edit().putLong("latest_known_art_id_${app.id}", artId).apply()
                }
                val cachedApkCodeForArt = artId?.let {
                    prefs.getLong("apk_vcode_${app.id}_$it", -1L).takeIf { c -> c > 0L }
                }
                val cachedApkNameForArt = artId?.let {
                    prefs.getString("apk_vname_${app.id}_$it", null)
                }

                if (installed != null && artId != null) {
                    val matchesInspectedApk = cachedApkCodeForArt != null && vCode == cachedApkCodeForArt &&
                        (cachedApkNameForArt == null || vName == cachedApkNameForArt)
                    val installedAfterBuild = effectiveInstalledTime != null && artTs > 0L &&
                        effectiveInstalledTime >= (artTs - 30_000L)
                    if (matchesInspectedApk || installedAfterBuild) {
                        lastInstalledArtId = artId
                        prefs.edit().putLong("installed_artifact_id_${app.id}", artId).apply()
                    }
                }

                // Ne jamais utiliser runNumber comme versionCode Android !
                val resolvedLatestCode = cachedApkCodeForArt ?: remoteVersion.first
                val resolvedLatestName = when {
                    cachedApkNameForArt != null -> ProjectApp.cleanVersion(cachedApkNameForArt)
                    remoteVersion.second != null && !remoteVersion.second.equals("1.0.0", ignoreCase = true) -> ProjectApp.cleanVersion(remoteVersion.second)
                    runNumber != null -> "Run #$runNumber"
                    remoteVersion.second != null -> ProjectApp.cleanVersion(remoteVersion.second)
                    else -> "Run #${artId ?: 0}"
                }

                refreshedBase.copy(
                    packageName = effectivePkg,
                    installedVersionCode = vCode,
                    installedVersionName = vName,
                    installedUpdateTime = effectiveInstalledTime,
                    latestArtifactId = artId,
                    lastInstalledArtifactId = lastInstalledArtId ?: refreshedBase.lastInstalledArtifactId,
                    latestArtifactSize = artSize,
                    latestArtifactDate = artDate,
                    artifactTimestamp = artifactTimestamp,
                    downloadUrl = archiveUrl,
                    isDirectApk = false,
                    latestVersionCode = resolvedLatestCode,
                    latestVersionName = resolvedLatestName,
                    statusText = "Prêt"
                )
            } else if (releaseApp != null) {
                releaseApp
            } else {
                refreshedBase.copy(
                    packageName = effectivePkg,
                    installedVersionCode = vCode,
                    installedVersionName = vName,
                    installedUpdateTime = effectiveInstalledTime,
                    statusText = "Aucun APK pour l'instant (En attente du build CI)"
                )
            }
        } catch (e: Exception) {
            app.copy(statusText = "Erreur: ${e.message}")
        }
    }

    fun recordAppInstallationInitiated(appId: String, artifactId: Long?, apkFile: File? = null) {
        val editor = prefs.edit()
        val effectiveArtId = artifactId ?: prefs.getLong("latest_known_art_id_$appId", -1L).takeIf { it != -1L }
        if (effectiveArtId != null) {
            editor.putLong("pending_art_id_$appId", effectiveArtId)
        }
        editor.putLong("pending_install_time_$appId", System.currentTimeMillis())

        if (apkFile != null && apkFile.exists()) {
            try {
                val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                if (archiveInfo != null) {
                    val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        archiveInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        archiveInfo.versionCode.toLong()
                    }
                    if (!archiveInfo.packageName.isNullOrBlank()) {
                        editor.putString("pkg_$appId", archiveInfo.packageName)
                    }
                    editor.putLong("pending_vcode_$appId", vCode)
                    if (!archiveInfo.versionName.isNullOrBlank()) {
                        editor.putString("pending_vname_$appId", archiveInfo.versionName)
                    }
                    if (effectiveArtId != null) {
                        editor.putLong("apk_vcode_${appId}_$effectiveArtId", vCode)
                        if (!archiveInfo.versionName.isNullOrBlank()) {
                            editor.putString("apk_vname_${appId}_$effectiveArtId", archiveInfo.versionName)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        editor.apply()
    }

    /**
     * Appelé immédiatement lorsqu'Android diffuse ACTION_PACKAGE_ADDED ou ACTION_PACKAGE_REPLACED.
     * Confirme l'installation de l'artefact en attente et retourne l'identifiant de l'application correspondante.
     */
    fun confirmPackageInstalledByBroadcast(installedPackageName: String, currentApps: List<ProjectApp>): String? {
        if (installedPackageName.isBlank()) return null
        val matchedApp = currentApps.firstOrNull { app ->
            val savedPkg = prefs.getString("pkg_${app.id}", null)
            app.packageName.equals(installedPackageName, ignoreCase = true) ||
                (savedPkg != null && savedPkg.equals(installedPackageName, ignoreCase = true))
        } ?: defaultProjects.firstOrNull { it.packageName.equals(installedPackageName, ignoreCase = true) }

        val appId = matchedApp?.id ?: return null
        val pendingArtId = prefs.getLong("pending_art_id_$appId", -1L).takeIf { it != -1L }
            ?: matchedApp.latestArtifactId
            ?: prefs.getLong("latest_known_art_id_$appId", -1L).takeIf { it != -1L }

        prefs.edit().apply {
            putString("pkg_$appId", installedPackageName)
            if (pendingArtId != null) {
                putLong("installed_artifact_id_$appId", pendingArtId)
            }
            putLong("installed_timestamp_$appId", System.currentTimeMillis())
            remove("pending_art_id_$appId")
            remove("pending_vcode_$appId")
            remove("pending_vname_$appId")
            remove("pending_install_time_$appId")
        }.apply()

        return appId
    }

    private fun parseIsoDateToMillis(dateStr: String): Long? {
        if (dateStr.isBlank()) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                java.time.Instant.parse(dateStr).toEpochMilli()
            } else {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                sdf.parse(dateStr)?.time
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchRemoteVersionInfo(owner: String, repo: String, token: String): Pair<Long?, String?> = withContext(Dispatchers.IO) {
        // 1. Tenter version.properties
        try {
            val url = "https://api.github.com/repos/$owner/$repo/contents/version.properties"
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "InstallAPK-Android")
            if (token.isNotBlank()) {
                val authVal = if (token.startsWith("Bearer ") || token.startsWith("token ")) token else "Bearer $token"
                reqBuilder.header("Authorization", authVal)
            }
            val res = client.newCall(reqBuilder.build()).execute()
            if (res.isSuccessful) {
                val bodyStr = res.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val base64Content = json.optString("content", "").replace("\n", "").replace("\r", "")
                if (base64Content.isNotBlank()) {
                    val decoded = String(android.util.Base64.decode(base64Content, android.util.Base64.DEFAULT), Charsets.UTF_8)
                    val codeRegex = Regex("""(?i)version[._-]?code\s*[:=]\s*(\d+)""")
                    val nameRegex = Regex("""(?i)version[._-]?name\s*[:=]\s*([^\r\n]+)""")
                    val code = codeRegex.find(decoded)?.groupValues?.get(1)?.toLongOrNull()
                    val rawName = nameRegex.find(decoded)?.groupValues?.get(1)?.trim()
                    val name = rawName?.removeSurrounding("\"")?.removeSurrounding("'")?.trim()
                    if (code != null || !name.isNullOrBlank()) {
                        return@withContext Pair(code, name)
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Tenter pubspec.yaml (pour Flutter)
        try {
            val url = "https://api.github.com/repos/$owner/$repo/contents/pubspec.yaml"
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "InstallAPK-Android")
            if (token.isNotBlank()) {
                val authVal = if (token.startsWith("Bearer ") || token.startsWith("token ")) token else "Bearer $token"
                reqBuilder.header("Authorization", authVal)
            }
            val res = client.newCall(reqBuilder.build()).execute()
            if (res.isSuccessful) {
                val bodyStr = res.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val base64Content = json.optString("content", "").replace("\n", "").replace("\r", "")
                if (base64Content.isNotBlank()) {
                    val decoded = String(android.util.Base64.decode(base64Content, android.util.Base64.DEFAULT), Charsets.UTF_8)
                    val versionRegex = Regex("""version:\s*([0-9\.]+)(?:\+(\d+))?""")
                    val match = versionRegex.find(decoded)
                    if (match != null) {
                        val name = match.groupValues.getOrNull(1)
                        val code = match.groupValues.getOrNull(2)?.toLongOrNull() ?: 1L
                        return@withContext Pair(code, name)
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Tenter app/build.gradle.kts, composeApp/build.gradle.kts ou app/build.gradle
        for (gradlePath in listOf("app/build.gradle.kts", "composeApp/build.gradle.kts", "app/build.gradle")) {
            try {
                val url = "https://api.github.com/repos/$owner/$repo/contents/$gradlePath"
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "InstallAPK-Android")
                if (token.isNotBlank()) {
                    val authVal = if (token.startsWith("Bearer ") || token.startsWith("token ")) token else "Bearer $token"
                    reqBuilder.header("Authorization", authVal)
                }
                val res = client.newCall(reqBuilder.build()).execute()
                if (res.isSuccessful) {
                    val bodyStr = res.body?.string() ?: ""
                    val json = JSONObject(bodyStr)
                    val base64Content = json.optString("content", "").replace("\n", "").replace("\r", "")
                    if (base64Content.isNotBlank()) {
                        val decoded = String(android.util.Base64.decode(base64Content, android.util.Base64.DEFAULT), Charsets.UTF_8)
                        val codeRegex = Regex("""(?i)versionCode\s*(?:=)?\s*\(?(\d+)\)?""")
                        val nameRegex = Regex("""(?i)versionName\s*(?:=)?\s*["']([^"']+)["']""")
                        val code = codeRegex.find(decoded)?.groupValues?.get(1)?.toLongOrNull()
                        val name = nameRegex.find(decoded)?.groupValues?.get(1)?.trim()
                        if (code != null || !name.isNullOrBlank()) {
                            return@withContext Pair(code, name)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        Pair(null, null)
    }

    private fun fetchLatestReleaseAppInfo(
        app: ProjectApp,
        token: String,
        installedCode: Long?,
        installedName: String?,
        installedTime: Long?
    ): ProjectApp? {
        return try {
            val url = "https://api.github.com/repos/${app.repoOwner}/${app.repoName}/releases?per_page=5"
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "InstallAPK-Android")

            if (token.isNotBlank()) {
                val authVal = if (token.startsWith("Bearer ") || token.startsWith("token ")) token else "Bearer $token"
                reqBuilder.header("Authorization", authVal)
            }

            val response = client.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val releases = JSONArray(body)
            if (releases.length() == 0) return null

            for (i in 0 until releases.length()) {
                val rel = releases.getJSONObject(i)
                val assets = rel.optJSONArray("assets") ?: continue
                var bestAsset: JSONObject? = null
                var bestScore = -1
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val assetName = asset.optString("name", "")
                    val score = scoreReleaseAsset(assetName, app)
                    if (score > bestScore) {
                        bestScore = score
                        bestAsset = asset
                    }
                }

                if (bestAsset != null) {
                    val assetId = bestAsset.optLong("id", -1L).takeIf { it > 0L }
                    val assetUrl = bestAsset.optString("url", "").takeIf { it.isNotBlank() }
                        ?: bestAsset.getString("browser_download_url")
                    val tagName = rel.optString("tag_name", "Release")
                    val size = bestAsset.optLong("size", 0L)
                    val date = bestAsset.optString("updated_at", "").ifBlank { rel.optString("published_at", "") }
                    val relTimestamp = parseIsoDateToMillis(date)
                    val releaseBody = rel.optString("body", "").trim().takeIf { it.isNotBlank() }
                    return app.copy(
                        installedVersionCode = installedCode,
                        installedVersionName = installedName,
                        installedUpdateTime = installedTime,
                        latestArtifactId = assetId,
                        latestArtifactSize = size,
                        latestArtifactDate = date,
                        artifactTimestamp = relTimestamp,
                        downloadUrl = assetUrl,
                        isDirectApk = true,
                        latestVersionName = tagName,
                        releaseNotes = releaseBody,
                        statusText = "Release disponible"
                    )
                }
            }
            null
        } catch (_: Exception) {
            null
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
        } catch (_: Exception) {
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
            val isReleaseAssetApi = downloadUrl.contains("/releases/assets/")
            val reqBuilder = Request.Builder()
                .url(downloadUrl)
                .header("Accept", if (isReleaseAssetApi) "application/octet-stream" else "application/vnd.github+json")
                .header("User-Agent", "InstallAPK-Android")

            if (token.isNotBlank()) {
                val authVal = if (token.startsWith("Bearer ") || token.startsWith("token ")) token else "Bearer $token"
                reqBuilder.header("Authorization", authVal)
            }

            val response = client.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) {
                Log.e(tag, "Échec téléchargement pour ${app.id}: HTTP ${response.code} ${response.message}")
                return@withContext null
            }

            val responseBody = response.body
            if (responseBody == null) {
                Log.e(tag, "Corps de réponse vide pour ${app.id}")
                return@withContext null
            }
            val contentLength = responseBody.contentLength()
            val inputStream = responseBody.byteStream()

            val tempDownloadFile = File(context.cacheDir, "${app.id}_temp_download")
            if (tempDownloadFile.exists()) tempDownloadFile.delete()

            val fos = FileOutputStream(tempDownloadFile)
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

            if (tempDownloadFile.length() < 50_000L) {
                Log.w(tag, "Fichier téléchargé trop petit (${tempDownloadFile.length()} octets) pour ${app.id}")
            }

            val updatesDir = File(context.filesDir, "updates")
            if (!updatesDir.exists()) updatesDir.mkdirs()

            val cleanVer = ProjectApp.cleanVersion(app.latestVersionName)
                .replace("Run #", "run-")
                .replace("#", "")
                .replace(Regex("[^a-zA-Z0-9.-]"), "-")
                .trim('-')
                .ifBlank { "latest" }
            val sanitizedId = app.id.replace(Regex("[^a-zA-Z0-9.-]"), "-").trim('-')
            val destinationApk = File(updatesDir, "$sanitizedId-v$cleanVer.apk")
            if (destinationApk.exists()) destinationApk.delete()

            // Nettoyage proactif des anciennes versions APK de cette application pour préserver l'espace disque
            updatesDir.listFiles()?.forEach { f ->
                if (f.name.startsWith(sanitizedId, ignoreCase = true) &&
                    f.name.endsWith(".apk", ignoreCase = true) &&
                    f.name != destinationApk.name) {
                    f.delete()
                }
            }

            // Déterminer si le fichier téléchargé est directement un APK Android ou un conteneur ZIP d'artefact Actions
            val archiveType = classifyArchiveFile(tempDownloadFile)
            when (archiveType) {
                ArchiveType.DIRECT_APK -> {
                    tempDownloadFile.copyTo(destinationApk, overwrite = true)
                    tempDownloadFile.delete()
                    Log.i(tag, "APK direct prêt à installer pour ${app.id} (${destinationApk.length()} octets)")
                }
                ArchiveType.ZIP_CONTAINING_APK -> {
                    val extracted = extractApkFromZipContainer(tempDownloadFile, destinationApk)
                    tempDownloadFile.delete()
                    if (!extracted || !destinationApk.exists() || destinationApk.length() == 0L) {
                        Log.e(tag, "Échec d'extraction du .apk depuis le conteneur ZIP pour ${app.id}")
                        return@withContext null
                    }
                    Log.i(tag, "APK extrait du conteneur ZIP avec succès pour ${app.id} (${destinationApk.length()} octets)")
                }
                ArchiveType.INVALID -> {
                    Log.e(tag, "Le fichier téléchargé pour ${app.id} n'est ni un APK ni un ZIP contenant un APK")
                    tempDownloadFile.delete()
                    return@withContext null
                }
            }

            // Inspecter l'APK pour découvrir / confirmer le vrai packageName, versionCode et versionName
            try {
                val archiveInfo = context.packageManager.getPackageArchiveInfo(destinationApk.absolutePath, 0)
                if (archiveInfo != null) {
                    val realPkg = archiveInfo.packageName
                    val apkCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        archiveInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        archiveInfo.versionCode.toLong()
                    }
                    val apkName = archiveInfo.versionName
                    prefs.edit().apply {
                        if (!realPkg.isNullOrBlank()) {
                            putString("pkg_${app.id}", realPkg)
                            putString("pkg_${app.repoName}", realPkg)
                        }
                        putLong("apk_vcode_${app.id}", apkCode)
                        if (!apkName.isNullOrBlank()) putString("apk_vname_${app.id}", apkName)
                        if (app.latestArtifactId != null) {
                            putLong("apk_vcode_${app.id}_${app.latestArtifactId}", apkCode)
                            if (!apkName.isNullOrBlank()) {
                                putString("apk_vname_${app.id}_${app.latestArtifactId}", apkName)
                            }
                        }
                    }.apply()
                    Log.i(tag, "APK inspecté pour ${app.id}: pkg=$realPkg, code=$apkCode, name=$apkName")
                }
            } catch (e: Exception) {
                Log.w(tag, "Impossible d'inspecter l'archive APK: ${e.message}")
            }

            destinationApk
        } catch (e: Exception) {
            Log.e(tag, "Erreur téléchargement/extraction APK: ${e.message}", e)
            null
        }
    }

    private fun classifyArchiveFile(file: File): ArchiveType {
        // 1. Vérification structurelle pure ZIP/APK (AndroidManifest.xml vs *.apk imbriqué)
        val structuralType = inspectDownloadedArchive(file)
        if (structuralType != ArchiveType.INVALID) {
            return structuralType
        }
        // 2. Fallback via PackageManager Android
        return try {
            val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            if (info != null) ArchiveType.DIRECT_APK else ArchiveType.INVALID
        } catch (_: Exception) {
            ArchiveType.INVALID
        }
    }

    fun updateAppPackageInfoAfterInstall(app: ProjectApp, apkFile: File): ProjectApp {
        return try {
            val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (archiveInfo != null) {
                val realPkg = archiveInfo.packageName ?: app.packageName
                val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    archiveInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    archiveInfo.versionCode.toLong()
                }
                val vName = archiveInfo.versionName
                app.copy(
                    packageName = realPkg,
                    latestVersionCode = vCode,
                    latestVersionName = vName
                )
            } else {
                app
            }
        } catch (_: Exception) {
            app
        }
    }

    companion object {
        /**
         * Évalue la pertinence d'un asset ou artefact pour une application donnée :
         * 4: Nom exact normalisé avec version (<AppName>-v*.apk ou <AppName>-*.apk)
         * 3: Nom exact sans version (<AppName>.apk ou artifactPattern)
         * 2: Nom contenant l'id ou repo de l'app
         * 1: Tout autre APK (fallback)
         * -1: Non APK ou exclu
         */
        fun scoreReleaseAsset(assetName: String, app: ProjectApp): Int {
            if (!assetName.endsWith(".apk", ignoreCase = true)) return -1
            val lower = assetName.lowercase()
            val appIdLower = app.id.lowercase()
            val repoLower = app.repoName.lowercase()
            val patternLower = app.artifactPattern.lowercase().removeSuffix(".apk").trim()
            val hasValidPattern = patternLower.isNotBlank() && patternLower != "apk"

            val normAsset = lower.replace("-", "").replace("_", "")
            val normAppId = appIdLower.replace("-", "").replace("_", "")
            val normRepo = repoLower.replace("-", "").replace("_", "")
            val normPattern = patternLower.replace("-", "").replace("_", "")

            if ((lower.startsWith("$appIdLower-") || lower.startsWith("${appIdLower}_") ||
                 lower.startsWith("$repoLower-") || lower.startsWith("${repoLower}_") ||
                 normAsset.startsWith(normAppId) || normAsset.startsWith(normRepo)) &&
                lower.any { it.isDigit() }) {
                return 4
            }
            if (lower == "$appIdLower.apk" || lower == "$repoLower.apk" ||
                normAsset == "${normAppId}.apk" || normAsset == "${normRepo}.apk" ||
                (hasValidPattern && (lower == app.artifactPattern.lowercase() || normAsset == "${normPattern}.apk"))) {
                return 3
            }
            if (lower.contains(appIdLower) || lower.contains(repoLower) ||
                normAsset.contains(normAppId) || normAsset.contains(normRepo) ||
                (hasValidPattern && (lower.contains(patternLower) || normAsset.contains(normPattern)))) {
                return 2
            }
            return 1
        }

        /**
         * Inspecte la structure interne d'un fichier téléchargé pour distinguer :
         * - Un fichier .apk direct (archive ZIP contenant AndroidManifest.xml ou classes.dex à la racine)
         * - Un conteneur .zip GitHub Actions (archive ZIP contenant un fichier *.apk)
         * - Un fichier invalide ou corrompu
         */
        fun inspectDownloadedArchive(file: File): ArchiveType {
            if (!file.exists() || file.length() < 4) return ArchiveType.INVALID
            return try {
                val fis = FileInputStream(file)
                val header = ByteArray(4)
                val read = fis.read(header)
                fis.close()
                val isZipMagic = read == 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
                if (!isZipMagic) return ArchiveType.INVALID

                var hasManifestOrDex = false
                var hasNestedApk = false

                ZipInputStream(FileInputStream(file)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!entry.isDirectory) {
                            if (name == "AndroidManifest.xml" || name == "classes.dex" || name == "resources.arsc") {
                                hasManifestOrDex = true
                                break
                            }
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                hasNestedApk = true
                            }
                        }
                        entry = zis.nextEntry
                    }
                }

                when {
                    hasManifestOrDex -> ArchiveType.DIRECT_APK
                    hasNestedApk -> ArchiveType.ZIP_CONTAINING_APK
                    else -> ArchiveType.INVALID
                }
            } catch (_: Exception) {
                ArchiveType.INVALID
            }
        }

        fun extractApkFromZipContainer(zipFile: File, destinationApk: File): Boolean {
            return try {
                var foundApk = false
                ZipInputStream(FileInputStream(zipFile)).use { zis ->
                    var zipEntry = zis.nextEntry
                    while (zipEntry != null) {
                        if (!zipEntry.isDirectory && zipEntry.name.endsWith(".apk", ignoreCase = true)) {
                            FileOutputStream(destinationApk).use { apkFos ->
                                val apkBuffer = ByteArray(8192)
                                var count: Int
                                while (zis.read(apkBuffer).also { count = it } != -1) {
                                    apkFos.write(apkBuffer, 0, count)
                                }
                                apkFos.flush()
                            }
                            foundApk = true
                            break
                        }
                        zipEntry = zis.nextEntry
                    }
                }
                foundApk && destinationApk.exists() && destinationApk.length() > 0L
            } catch (_: Exception) {
                false
            }
        }
    }
}
