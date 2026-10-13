package fr.installapk.app.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import fr.installapk.app.model.ProjectApp
import kotlinx.coroutines.Dispatchers
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

class GitHubAppsRepository(private val context: Context) {

    private val tag = "GitHubAppsRepository"
    private val prefs = context.getSharedPreferences("installapk_prefs", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
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
            artifactPattern = "installapk-release-apk"
        ),
        ProjectApp(
            id = "antigravity-controller",
            repoOwner = "Geoff1805",
            repoName = "antigravity-controller",
            packageName = "com.antigravity.antigravity_controller",
            displayName = "Antigravity Mobile",
            description = "Contrôleur mobile et dashboard Flutter pour l'agent Antigravity",
            iconEmoji = "🎮",
            artifactPattern = "apk"
        )
    )

    fun getInitialProjects(): List<ProjectApp> {
        val merged = mutableListOf<ProjectApp>()
        merged.addAll(defaultProjects)

        // Charger les projets découverts depuis le cache SharedPreferences
        val cachedDiscoveredJson = prefs.getString("cached_discovered_apps", null)
        if (!cachedDiscoveredJson.isNullOrBlank()) {
            try {
                val jsonArray = JSONArray(cachedDiscoveredJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.getString("id")
                    if (merged.none { it.id.equals(id, ignoreCase = true) }) {
                        merged.add(
                            ProjectApp(
                                id = id,
                                repoOwner = obj.getString("repoOwner"),
                                repoName = obj.getString("repoName"),
                                packageName = obj.getString("packageName"),
                                displayName = obj.optString("displayName", id),
                                description = obj.optString("description", "Application Android GitHub"),
                                iconEmoji = obj.optString("iconEmoji", "📱"),
                                isSelf = obj.optBoolean("isSelf", false),
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
        var effectivePkg = prefs.getString("pkg_${app.id}", null) ?: app.packageName
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

        val lastInstalledArtId = prefs.getLong("installed_artifact_id_${app.id}", -1L).takeIf { it != -1L }
        val lastInstalledTime = prefs.getLong("installed_timestamp_${app.id}", -1L).takeIf { it != -1L }

        return if (installed != null) {
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                installed.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                installed.versionCode.toLong()
            }
            val effectiveTime = maxOf(installed.lastUpdateTime, lastInstalledTime ?: 0L).takeIf { it > 0L }
            app.copy(
                packageName = effectivePkg,
                installedVersionCode = vCode,
                installedVersionName = installed.versionName,
                installedUpdateTime = effectiveTime,
                lastInstalledArtifactId = lastInstalledArtId ?: app.lastInstalledArtifactId
            )
        } else {
            app.copy(
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

    suspend fun discoverAndRefreshApps(token: String): List<ProjectApp> = withContext(Dispatchers.IO) {
        val discoveredProjects = discoverAndroidProjects(token)

        // Fusion des projets par défaut et découverts
        val allProjects = mutableListOf<ProjectApp>()
        allProjects.addAll(defaultProjects)

        for (discovered in discoveredProjects) {
            val index = allProjects.indexOfFirst { it.id.equals(discovered.id, ignoreCase = true) }
            if (index >= 0) {
                // Mettre à jour avec le package name résolu si besoin
                val existing = allProjects[index]
                if (existing.packageName.isBlank() && discovered.packageName.isNotBlank()) {
                    allProjects[index] = existing.copy(packageName = discovered.packageName)
                }
            } else {
                allProjects.add(discovered)
            }
        }

        // Sauvegarder dans le cache pour les prochains démarrages
        saveDiscoveredProjectsCache(allProjects)

        // Récupérer les informations de version et d'artefact pour chaque application
        allProjects.map { app ->
            fetchLatestAppInfo(app, token)
        }
    }

    fun addCustomRepo(repoName: String) {
        val customSet = prefs.getStringSet("user_custom_repos", emptySet())?.toMutableSet() ?: mutableSetOf()
        customSet.add(repoName.trim())
        prefs.edit().putStringSet("user_custom_repos", customSet).apply()
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
            val appIdRegex = Regex("""applicationId\s*(?:=)?\s*["']([^"']+)["']""")
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
            if (!response.isSuccessful) {
                return@withContext app.copy(
                    statusText = if (response.code == 401) "Clé invalide (401)" else "Erreur API (${response.code})"
                )
            }

            val bodyStr = response.body?.string() ?: return@withContext app
            val json = JSONObject(bodyStr)
            val artifacts = json.optJSONArray("artifacts") ?: JSONArray()

            val candidateArtifacts = mutableListOf<JSONObject>()
            for (i in 0 until artifacts.length()) {
                val art = artifacts.getJSONObject(i)
                val name = art.optString("name", "")
                val isExpired = art.optBoolean("expired", false)
                val size = art.optLong("size_in_bytes", 0L)
                if (isExpired || isExcludedArtifact(name) || size < 200_000L) continue

                val hasPattern = app.artifactPattern.isNotBlank() && name.contains(app.artifactPattern, ignoreCase = true)
                val hasApk = name.contains("apk", ignoreCase = true)
                if (hasPattern || hasApk) {
                    candidateArtifacts.add(art)
                }
            }

            // Tri intelligent :
            // 1. Branche 'main' ou 'master' en priorité
            // 2. Artefact Release en priorité
            // 3. Date de création la plus récente
            var matchedArtifact: JSONObject? = candidateArtifacts
                .sortedWith(
                    compareByDescending<JSONObject> {
                        val branch = it.optJSONObject("workflow_run")?.optString("head_branch", "") ?: ""
                        branch.equals("main", ignoreCase = true) || branch.equals("master", ignoreCase = true)
                    }
                    .thenByDescending {
                        val name = it.optString("name", "")
                        name.contains("release", ignoreCase = true)
                    }
                    .thenByDescending {
                        val name = it.optString("name", "")
                        app.artifactPattern.isNotBlank() && name.contains(app.artifactPattern, ignoreCase = true)
                    }
                    .thenByDescending {
                        it.optString("created_at", "")
                    }
                )
                .firstOrNull()

            var effectivePkg = prefs.getString("pkg_${app.id}", null) ?: app.packageName
            var installed = getInstalledPackageInfo(effectivePkg)
            if (installed == null) {
                val foundPkg = findInstalledPackageForApp(app)
                if (foundPkg != null) {
                    effectivePkg = foundPkg.packageName
                    prefs.edit().putString("pkg_${app.id}", effectivePkg).apply()
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
            val lastInstalledArtId = prefs.getLong("installed_artifact_id_${app.id}", -1L).takeIf { it != -1L }
            val lastInstalledTime = prefs.getLong("installed_timestamp_${app.id}", -1L).takeIf { it != -1L }
            val effectiveInstalledTime = maxOf(installed?.lastUpdateTime ?: 0L, lastInstalledTime ?: 0L).takeIf { it > 0L }

            if (matchedArtifact != null) {
                val artId = matchedArtifact.getLong("id")
                val artSize = matchedArtifact.getLong("size_in_bytes")
                val artDate = matchedArtifact.optString("created_at", "")
                val archiveUrl = matchedArtifact.getString("archive_download_url")
                val workflowRun = matchedArtifact.optJSONObject("workflow_run")
                val runId = workflowRun?.optLong("id")

                var runNumber: Long? = null
                if (runId != null) {
                    runNumber = fetchRunNumber(app.repoOwner, app.repoName, runId, token)
                }

                val remoteVersion = fetchRemoteVersionInfo(app.repoOwner, app.repoName, token)
                val cachedApkCode = prefs.getLong("apk_vcode_${app.id}", -1L).takeIf { it != -1L }
                val cachedApkName = prefs.getString("apk_vname_${app.id}", null)

                val resolvedLatestCode = remoteVersion.first ?: cachedApkCode
                val resolvedLatestName = remoteVersion.second ?: cachedApkName ?: runNumber?.let { "Run #$it" }
                val artTimestamp = parseIsoDateToMillis(artDate)

                app.copy(
                    packageName = effectivePkg,
                    installedVersionCode = vCode,
                    installedVersionName = vName,
                    installedUpdateTime = effectiveInstalledTime,
                    latestArtifactId = artId,
                    lastInstalledArtifactId = lastInstalledArtId,
                    latestArtifactSize = artSize,
                    latestArtifactDate = artDate,
                    artifactTimestamp = artTimestamp,
                    downloadUrl = archiveUrl,
                    latestVersionCode = resolvedLatestCode,
                    latestVersionName = resolvedLatestName,
                    statusText = "Prêt"
                )
            } else {
                // Tenter de chercher dans les Releases GitHub
                val releaseApp = fetchLatestReleaseAppInfo(app, token, vCode, vName, effectiveInstalledTime)
                releaseApp ?: app.copy(
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

    fun recordAppInstallationInitiated(appId: String, artifactId: Long?) {
        prefs.edit().apply {
            if (artifactId != null) {
                putLong("installed_artifact_id_$appId", artifactId)
            }
            putLong("installed_timestamp_$appId", System.currentTimeMillis())
        }.apply()
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
                    val codeRegex = Regex("""versionCode\s*=\s*(\d+)""")
                    val nameRegex = Regex("""versionName\s*=\s*([^\r\n]+)""")
                    val code = codeRegex.find(decoded)?.groupValues?.get(1)?.toLongOrNull()
                    val name = nameRegex.find(decoded)?.groupValues?.get(1)?.trim()
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

        // 3. Tenter app/build.gradle.kts ou app/build.gradle
        for (gradlePath in listOf("app/build.gradle.kts", "app/build.gradle")) {
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
                        val codeRegex = Regex("""versionCode\s*(?:=)?\s*(\d+)""")
                        val nameRegex = Regex("""versionName\s*(?:=)?\s*["']([^"']+)["']""")
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
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val assetName = asset.optString("name", "")
                    if (assetName.endsWith(".apk", ignoreCase = true)) {
                        val downloadUrl = asset.getString("browser_download_url")
                        val tagName = rel.optString("tag_name", "Release")
                        val size = asset.optLong("size", 0L)
                        val date = rel.optString("published_at", "")
                        val relTimestamp = parseIsoDateToMillis(date)
                        return app.copy(
                            installedVersionCode = installedCode,
                            installedVersionName = installedName,
                            installedUpdateTime = installedTime,
                            latestArtifactSize = size,
                            latestArtifactDate = date,
                            artifactTimestamp = relTimestamp,
                            downloadUrl = downloadUrl,
                            latestVersionName = tagName,
                            statusText = "Release disponible"
                        )
                    }
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
            val reqBuilder = Request.Builder()
                .url(downloadUrl)
                .header("Accept", "application/vnd.github+json")
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

            if (tempDownloadFile.length() < 100_000L) {
                Log.w(tag, "Fichier téléchargé suspect car trop petit (${tempDownloadFile.length()} octets) pour ${app.id}")
            }

            val updatesDir = File(context.filesDir, "updates")
            if (!updatesDir.exists()) updatesDir.mkdirs()

            val destinationApk = File(updatesDir, "${app.id}.apk")
            if (destinationApk.exists()) destinationApk.delete()

            // Vérifier si le fichier téléchargé est un ZIP ou directement un APK
            val isZip = isZipFile(tempDownloadFile)
            if (isZip) {
                // Dézipper et extraire le fichier .apk contenu
                val zis = ZipInputStream(tempDownloadFile.inputStream())
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
                        Log.i(tag, "APK extrait du zip avec succès: ${zipEntry.name} (${destinationApk.length()} octets)")
                        break
                    }
                    zipEntry = zis.nextEntry
                }
                zis.close()
                tempDownloadFile.delete()

                if (!foundApk || !destinationApk.exists() || destinationApk.length() == 0L) {
                    Log.e(tag, "Aucun fichier .apk trouvé dans l'archive zip téléchargée pour ${app.id}")
                    return@withContext null
                }
            } else {
                // Le fichier téléchargé est déjà directement un APK
                tempDownloadFile.copyTo(destinationApk, overwrite = true)
                tempDownloadFile.delete()
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

    private fun isZipFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            val fis = FileInputStream(file)
            val header = ByteArray(4)
            val read = fis.read(header)
            fis.close()
            read == 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        } catch (_: Exception) {
            false
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
}
