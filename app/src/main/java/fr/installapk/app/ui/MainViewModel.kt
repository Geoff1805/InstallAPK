package fr.installapk.app.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.installapk.app.BuildConfig
import fr.installapk.app.data.GitHubAppsRepository
import fr.installapk.app.data.TokenValidationResult
import fr.installapk.app.model.ProjectApp
import fr.installapk.app.util.ApkInstaller
import fr.installapk.app.util.NotificationHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class AppFilterMode(val label: String) {
    ALL("Toutes"),
    UPDATES("Mises à jour"),
    INSTALLED("Installées")
}

data class MainUiState(
    val token: String = "",
    val tokenValidation: TokenValidationResult? = null,
    val isTokenValidating: Boolean = false,
    val alertEmail: String = BuildConfig.ALERT_EMAIL,
    val autoUpdateOnLaunch: Boolean = true,
    val apps: List<ProjectApp> = emptyList(),
    val newlyDetectedApp: ProjectApp? = null, // Nouveau dépôt Android détecté à proposer
    val signatureConflictApp: ProjectApp? = null, // Application bloquée par un conflit de signature Android
    val searchQuery: String = "",
    val filterMode: AppFilterMode = AppFilterMode.ALL,
    val isLoading: Boolean = false,
    val isUpdatingAll: Boolean = false,
    val updateQueue: List<String> = emptyList(), // IDs des apps dans la file d'attente
    val activeUpdateId: String? = null, // ID de l'app en cours de téléchargement/installation
    val isWaitingForUserInstall: Boolean = false, // Vrai quand l'installateur système Android a été ouvert
    val needsInstallPermission: Boolean = false,
    val pendingInstallFile: File? = null,
    val errorMessage: String? = null
) {
    val selfApp: ProjectApp?
        get() = apps.firstOrNull { it.isSelf }

    val managedApps: List<ProjectApp>
        get() = apps
            .filter { !it.isSelf }
            .sortedWith(
                compareByDescending<ProjectApp> { it.isNew }
                    .thenByDescending { it.isUpdating || it.isQueued }
                    .thenByDescending { it.hasUpdateAvailable }
                    .thenByDescending { !it.isInstalled && it.downloadUrl != null }
                    .thenByDescending { it.isUpToDate }
                    .thenBy { it.displayName.lowercase() }
            )

    val filteredManagedApps: List<ProjectApp>
        get() = managedApps.filter { app ->
            val matchesFilter = when (filterMode) {
                AppFilterMode.ALL -> true
                AppFilterMode.UPDATES -> app.hasUpdateAvailable || (!app.isInstalled && app.downloadUrl != null)
                AppFilterMode.INSTALLED -> app.isInstalled
            }
            val matchesQuery = searchQuery.isBlank() ||
                app.displayName.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true) ||
                app.id.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesQuery
        }

    val nextQueueApp: ProjectApp?
        get() {
            val nextId = updateQueue.firstOrNull() ?: return null
            return apps.firstOrNull { it.id == nextId }
        }

    val activeApp: ProjectApp?
        get() = activeUpdateId?.let { id -> apps.firstOrNull { it.id == id } }

    val pendingManagedUpdatesCount: Int
        get() = managedApps.count { it.needsInstallationOrUpdate }

    val isAllManagedUpToDate: Boolean
        get() = managedApps.isNotEmpty() && pendingManagedUpdatesCount == 0

    val pendingUpdatesCount: Int
        get() = apps.count { it.needsInstallationOrUpdate }

    val isAllUpToDate: Boolean
        get() = apps.isNotEmpty() && pendingUpdatesCount == 0
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = GitHubAppsRepository(application)
    private val prefs = application.getSharedPreferences("installapk_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        MainUiState(
            token = prefs.getString("github_token", BuildConfig.DEFAULT_GITHUB_TOKEN) ?: BuildConfig.DEFAULT_GITHUB_TOKEN,
            autoUpdateOnLaunch = prefs.getBoolean("auto_update_on_launch", true),
            apps = repository.getInitialProjects(),
            needsInstallPermission = !ApkInstaller.canRequestPackageInstalls(application)
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var currentDownloadJob: Job? = null
    private var preDownloadJob: Job? = null
    private val preDownloadedFiles = ConcurrentHashMap<String, File>()
    private var hasAutoTriggeredOnLaunch = false
    private var installLaunchedAtMillis = 0L
    private var pendingReinstallAfterUninstallAppId: String? = null
    private var pendingReinstallApkFile: File? = null

    private val packageBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val pkgName = intent.data?.schemeSpecificPart ?: return
            val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

            when (action) {
                Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED -> {
                    onSystemPackageInstalled(pkgName)
                }
                Intent.ACTION_PACKAGE_REMOVED -> {
                    if (!isReplacing) {
                        onSystemPackageRemoved(pkgName)
                    }
                }
            }
        }
    }

    init {
        registerPackageReceiver()
        validateCurrentToken()
        refreshApps(isInitialLaunch = true)
    }

    private fun registerPackageReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            val app = getApplication<Application>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(packageBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(packageBroadcastReceiver, filter)
            }
        } catch (_: Exception) {
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(packageBroadcastReceiver)
        } catch (_: Exception) {
        }
    }

    private fun onSystemPackageInstalled(pkgName: String) {
        val currentApps = _uiState.value.apps
        val confirmedAppId = repository.confirmPackageInstalledByBroadcast(pkgName, currentApps)
        refreshInstalledPackageStatuses()

        if (confirmedAppId != null) {
            preDownloadedFiles.remove(confirmedAppId)
            val state = _uiState.value
            val updatedApp = state.apps.firstOrNull { it.id == confirmedAppId }
            if (updatedApp != null) {
                NotificationHelper.notifyUpdateProgress(
                    getApplication(),
                    confirmedAppId.hashCode(),
                    "${updatedApp.displayName} est à jour",
                    "Version ${updatedApp.installedVersionName ?: ""} installée avec succès."
                )
            }

            if (state.activeUpdateId == confirmedAppId) {
                _uiState.update { s ->
                    s.copy(
                        apps = s.apps.map {
                            if (it.id == confirmedAppId) {
                                repository.refreshSingleAppInstalledStatus(it).copy(
                                    statusText = "À jour",
                                    isUpdating = false,
                                    isQueued = false,
                                    hasSignatureConflict = false
                                )
                            } else {
                                it
                            }
                        },
                        activeUpdateId = null,
                        isWaitingForUserInstall = false,
                        signatureConflictApp = if (s.signatureConflictApp?.id == confirmedAppId) null else s.signatureConflictApp
                    )
                }

                if (_uiState.value.updateQueue.isNotEmpty()) {
                    viewModelScope.launch {
                        delay(350)
                        processNextInQueue()
                    }
                } else {
                    _uiState.update { it.copy(isUpdatingAll = false) }
                }
            }
        }
    }

    private fun onSystemPackageRemoved(pkgName: String) {
        refreshInstalledPackageStatuses()
        val targetAppId = pendingReinstallAfterUninstallAppId ?: return
        val apkFile = pendingReinstallApkFile ?: return
        val targetApp = _uiState.value.apps.firstOrNull { it.id == targetAppId } ?: return

        if (targetApp.packageName.equals(pkgName, ignoreCase = true)) {
            pendingReinstallAfterUninstallAppId = null
            pendingReinstallApkFile = null
            viewModelScope.launch {
                delay(400)
                val context = getApplication<Application>()
                repository.recordAppInstallationInitiated(targetApp.id, targetApp.latestArtifactId, apkFile)
                installLaunchedAtMillis = System.currentTimeMillis()
                _uiState.update { state ->
                    state.copy(
                        activeUpdateId = targetApp.id,
                        isWaitingForUserInstall = true,
                        signatureConflictApp = null,
                        apps = state.apps.map {
                            if (it.id == targetApp.id) {
                                it.copy(
                                    isUpdating = false,
                                    hasSignatureConflict = false,
                                    downloadProgress = 1f,
                                    statusText = "Installation nouvelle version..."
                                )
                            } else {
                                it
                            }
                        }
                    )
                }
                ApkInstaller.installApk(context, apkFile)
            }
        }
    }

    fun setAutoUpdateOnLaunch(enabled: Boolean) {
        prefs.edit().putBoolean("auto_update_on_launch", enabled).apply()
        _uiState.update { it.copy(autoUpdateOnLaunch = enabled) }
    }

    fun onTokenChanged(newToken: String) {
        val trimmed = newToken.trim()
        _uiState.update { it.copy(token = trimmed) }
        prefs.edit().putString("github_token", trimmed).apply()
        validateCurrentToken()
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setFilterMode(mode: AppFilterMode) {
        _uiState.update { it.copy(filterMode = mode) }
    }

    fun validateCurrentToken() {
        val currentToken = _uiState.value.token
        viewModelScope.launch {
            _uiState.update { it.copy(isTokenValidating = true) }
            val result = repository.validateToken(currentToken)
            _uiState.update {
                it.copy(
                    tokenValidation = result,
                    isTokenValidating = false
                )
            }
            if (!result.isValid) {
                NotificationHelper.notifyCritical(
                    getApplication(),
                    9001,
                    "Clé GitHub Invalide ou Expirée",
                    result.errorMessage ?: "Veuillez renouveler votre Personal Access Token GitHub."
                )
            }
        }
    }

    fun checkPermissions() {
        val hasPermission = ApkInstaller.canRequestPackageInstalls(getApplication())
        _uiState.update { it.copy(needsInstallPermission = !hasPermission) }
    }

    fun onAppResumed() {
        checkPermissions()
        refreshInstalledPackageStatuses()
        checkQueueProgressOnResume()
        viewModelScope.launch {
            delay(700)
            refreshInstalledPackageStatuses()
            checkQueueProgressOnResume()
        }
    }

    fun refreshInstalledPackageStatuses() {
        _uiState.update { state ->
            state.copy(
                apps = state.apps.map { repository.refreshSingleAppInstalledStatus(it) }
            )
        }
    }

    private fun checkQueueProgressOnResume() {
        val state = _uiState.value
        val activeId = state.activeUpdateId

        if (activeId != null) {
            val app = state.apps.firstOrNull { it.id == activeId }
            if (app != null) {
                val freshApp = repository.refreshSingleAppInstalledStatus(app)
                val wasRecentlyUpdated = freshApp.installedUpdateTime != null &&
                    installLaunchedAtMillis > 0L &&
                    freshApp.installedUpdateTime >= (installLaunchedAtMillis - 15_000L)

                if (freshApp.isUpToDate || wasRecentlyUpdated) {
                    if (freshApp.latestArtifactId != null) {
                        prefs.edit().putLong("installed_artifact_id_${freshApp.id}", freshApp.latestArtifactId).apply()
                    }
                    val confirmedApp = repository.refreshSingleAppInstalledStatus(freshApp)
                    _uiState.update { s ->
                        s.copy(
                            apps = s.apps.map {
                                if (it.id == activeId) {
                                    confirmedApp.copy(
                                        statusText = "À jour",
                                        isUpdating = false,
                                        isQueued = false,
                                        hasSignatureConflict = false
                                    )
                                } else {
                                    it
                                }
                            },
                            activeUpdateId = null,
                            isWaitingForUserInstall = false
                        )
                    }

                    if (_uiState.value.updateQueue.isNotEmpty()) {
                        processNextInQueue()
                    } else {
                        _uiState.update { it.copy(isUpdatingAll = false) }
                    }
                    return
                }
            }
        }

        if (state.isUpdatingAll && state.activeUpdateId == null && state.updateQueue.isNotEmpty()) {
            processNextInQueue()
        }
    }

    fun addCustomRepository(repoName: String) {
        val trimmed = repoName.trim()
        if (trimmed.isNotBlank()) {
            repository.addCustomRepo(trimmed)
            refreshApps(isInitialLaunch = false)
        }
    }

    fun refreshApps(isInitialLaunch: Boolean = false) {
        val currentToken = _uiState.value.token
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            validateCurrentToken()

            val updatedList = repository.discoverAndRefreshApps(currentToken)
            val firstNewApp = updatedList.firstOrNull { it.isNew && !it.isSelf }

            _uiState.update {
                it.copy(
                    apps = updatedList,
                    newlyDetectedApp = firstNewApp,
                    isLoading = false
                )
            }

            // Déclenchement automatique des mises à jour au lancement de l'application
            if ((isInitialLaunch || !hasAutoTriggeredOnLaunch) && _uiState.value.autoUpdateOnLaunch) {
                hasAutoTriggeredOnLaunch = true
                val hasUpdates = updatedList.any { it.hasUpdateAvailable }
                if (hasUpdates && ApkInstaller.canRequestPackageInstalls(getApplication())) {
                    delay(300)
                    updateAllApps(onlyInstalledUpdates = true)
                }
            }
        }
    }

    fun acceptNewAppInstall(app: ProjectApp) {
        repository.markRepoAcknowledged(app.id)
        _uiState.update { s ->
            val remainingNew = s.apps.firstOrNull { it.isNew && it.id != app.id && !it.isSelf }
            s.copy(
                newlyDetectedApp = remainingNew,
                apps = s.apps.map { if (it.id == app.id) it.copy(isNew = false) else it }
            )
        }
        if (app.downloadUrl != null) {
            installOrUpdateApp(app, forceReinstall = false)
        }
    }

    fun dismissNewApp(app: ProjectApp) {
        repository.markRepoAcknowledged(app.id)
        _uiState.update { s ->
            val remainingNew = s.apps.firstOrNull { it.isNew && it.id != app.id && !it.isSelf }
            s.copy(
                newlyDetectedApp = remainingNew,
                apps = s.apps.map { if (it.id == app.id) it.copy(isNew = false) else it }
            )
        }
    }

    fun postponeNewApp(app: ProjectApp) {
        _uiState.update { s ->
            s.copy(newlyDetectedApp = null)
        }
    }

    fun installOrUpdateApp(app: ProjectApp, forceReinstall: Boolean = false) {
        val context = getApplication<Application>()
        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            _uiState.update { it.copy(needsInstallPermission = true) }
            return
        }

        if (!forceReinstall && app.isUpToDate) {
            return
        }

        val state = _uiState.value

        if (state.activeUpdateId != null) {
            if (state.activeUpdateId != app.id && !state.updateQueue.contains(app.id)) {
                val newQueue = state.updateQueue + app.id
                _uiState.update { s ->
                    s.copy(
                        updateQueue = newQueue,
                        apps = s.apps.map {
                            if (it.id == app.id) it.copy(isQueued = true, statusText = "En file d'attente...") else it
                        }
                    )
                }
                triggerPreDownloadForQueue()
            }
            return
        }

        _uiState.update { s ->
            s.copy(
                activeUpdateId = app.id,
                apps = s.apps.map {
                    if (it.id == app.id) it.copy(isUpdating = true, isQueued = false, downloadProgress = 0f, statusText = "Téléchargement...") else it
                }
            )
        }

        startDownloadAndInstall(app)
    }

    /**
     * Met à jour toutes les applications nécessitant une mise à jour.
     * Place systématiquement InstallAPK (isSelf) en toute fin de file d'attente
     * pour ne jamais interrompre la mise à jour des autres applications.
     */
    fun updateAllApps(onlyInstalledUpdates: Boolean = false) {
        val context = getApplication<Application>()
        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            _uiState.update { it.copy(needsInstallPermission = true) }
            return
        }

        val state = _uiState.value
        val managedCandidates = if (onlyInstalledUpdates) {
            state.managedApps.filter { it.hasUpdateAvailable }
        } else {
            state.managedApps.filter { it.needsInstallationOrUpdate }
        }

        val selfCandidate = state.selfApp?.takeIf { it.hasUpdateAvailable }
        val appsToUpdate = buildList {
            addAll(managedCandidates)
            if (selfCandidate != null) {
                add(selfCandidate)
            }
        }

        if (appsToUpdate.isEmpty()) return

        NotificationHelper.notifyUpdateProgress(
            context,
            8000,
            "Mise à jour automatique (${appsToUpdate.size})",
            "Traitement de : ${appsToUpdate.joinToString(", ") { it.displayName }}"
        )

        val queueIds = appsToUpdate.map { it.id }
        val firstApp = appsToUpdate.first()
        val remainingQueue = queueIds.drop(1)

        _uiState.update { s ->
            s.copy(
                isUpdatingAll = true,
                activeUpdateId = firstApp.id,
                updateQueue = remainingQueue,
                apps = s.apps.map { item ->
                    when {
                        item.id == firstApp.id -> item.copy(isUpdating = true, isQueued = false, downloadProgress = 0f, statusText = "Téléchargement...")
                        remainingQueue.contains(item.id) -> item.copy(isQueued = true, isUpdating = false, statusText = "En file d'attente...")
                        else -> item
                    }
                }
            )
        }

        startDownloadAndInstall(firstApp)
        triggerPreDownloadForQueue()
    }

    fun updateSelfApp() {
        val self = _uiState.value.selfApp ?: return
        installOrUpdateApp(self, forceReinstall = false)
    }

    private fun triggerPreDownloadForQueue() {
        val nextId = _uiState.value.updateQueue.firstOrNull() ?: return
        if (preDownloadedFiles.containsKey(nextId)) return
        val nextApp = _uiState.value.apps.firstOrNull { it.id == nextId } ?: return
        val currentToken = _uiState.value.token

        if (preDownloadJob?.isActive == true) return
        preDownloadJob = viewModelScope.launch {
            val file = repository.downloadAndExtractApk(nextApp, currentToken) { _ -> }
            if (file != null && file.exists()) {
                preDownloadedFiles[nextId] = file
                _uiState.update { s ->
                    s.copy(
                        apps = s.apps.map {
                            if (it.id == nextId && it.isQueued) {
                                it.copy(statusText = "Prêt à installer (pré-téléchargé)")
                            } else {
                                it
                            }
                        }
                    )
                }
            }
        }
    }

    private fun processNextInQueue() {
        val state = _uiState.value
        if (state.updateQueue.isEmpty()) {
            _uiState.update { it.copy(isUpdatingAll = false, activeUpdateId = null, isWaitingForUserInstall = false) }
            return
        }

        val nextId = state.updateQueue.first()
        val remainingQueue = state.updateQueue.drop(1)
        val nextApp = state.apps.firstOrNull { it.id == nextId }

        if (nextApp == null) {
            _uiState.update { it.copy(updateQueue = remainingQueue) }
            processNextInQueue()
            return
        }

        val freshNext = repository.refreshSingleAppInstalledStatus(nextApp)
        if (freshNext.isUpToDate) {
            _uiState.update { s ->
                s.copy(
                    updateQueue = remainingQueue,
                    apps = s.apps.map {
                        if (it.id == nextId) freshNext.copy(isUpdating = false, isQueued = false, statusText = "À jour") else it
                    }
                )
            }
            processNextInQueue()
            return
        }

        _uiState.update { s ->
            s.copy(
                activeUpdateId = nextId,
                updateQueue = remainingQueue,
                apps = s.apps.map {
                    if (it.id == nextId) it.copy(isUpdating = true, isQueued = false, downloadProgress = 0f, statusText = "Téléchargement...") else it
                }
            )
        }

        startDownloadAndInstall(freshNext)
        triggerPreDownloadForQueue()
    }

    private fun startDownloadAndInstall(app: ProjectApp) {
        val currentToken = _uiState.value.token
        val context = getApplication<Application>()

        currentDownloadJob?.cancel()
        currentDownloadJob = viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    activeUpdateId = app.id,
                    isWaitingForUserInstall = false,
                    apps = state.apps.map {
                        if (it.id == app.id) it.copy(isUpdating = true, isQueued = false, downloadProgress = 0f, statusText = "Téléchargement...") else it
                    }
                )
            }

            val preDownloaded = preDownloadedFiles.remove(app.id)?.takeIf { it.exists() && it.length() > 0L }
            val apkFile = preDownloaded ?: repository.downloadAndExtractApk(app, currentToken) { progress ->
                _uiState.update { state ->
                    state.copy(
                        apps = state.apps.map {
                            if (it.id == app.id) it.copy(downloadProgress = progress) else it
                        }
                    )
                }
            }

            if (apkFile != null) {
                val updatedAppInfo = repository.updateAppPackageInfoAfterInstall(app, apkFile)
                val sigConflict = ApkInstaller.hasSignatureConflict(context, updatedAppInfo.packageName, apkFile)

                if (sigConflict) {
                    pendingReinstallApkFile = apkFile
                    _uiState.update { state ->
                        state.copy(
                            isWaitingForUserInstall = false,
                            signatureConflictApp = updatedAppInfo,
                            pendingInstallFile = apkFile,
                            apps = state.apps.map {
                                if (it.id == app.id) {
                                    it.copy(
                                        packageName = updatedAppInfo.packageName,
                                        isUpdating = false,
                                        hasSignatureConflict = true,
                                        statusText = "Conflit de signature (Désinstallation requise)"
                                    )
                                } else {
                                    it
                                }
                            }
                        )
                    }
                    return@launch
                }

                repository.recordAppInstallationInitiated(app.id, app.latestArtifactId, apkFile)
                installLaunchedAtMillis = System.currentTimeMillis()
                _uiState.update { state ->
                    state.copy(
                        isWaitingForUserInstall = true,
                        pendingInstallFile = apkFile,
                        apps = state.apps.map {
                            if (it.id == app.id) {
                                it.copy(
                                    packageName = updatedAppInfo.packageName,
                                    isUpdating = false,
                                    downloadProgress = 1f,
                                    statusText = "Confirmation Android..."
                                )
                            } else {
                                it
                            }
                        }
                    )
                }
                ApkInstaller.installApk(context, apkFile)
            } else {
                _uiState.update { state ->
                    state.copy(
                        isWaitingForUserInstall = false,
                        apps = state.apps.map {
                            if (it.id == app.id) it.copy(isUpdating = false, statusText = "Échec du téléchargement") else it
                        },
                        activeUpdateId = null
                    )
                }
                if (_uiState.value.updateQueue.isNotEmpty()) {
                    processNextInQueue()
                } else {
                    _uiState.update { it.copy(isUpdatingAll = false) }
                }
            }
        }
    }

    /**
     * Résout un conflit de signature Android (INSTALL_FAILED_UPDATE_INCOMPATIBLE) :
     * lance la désinstallation de l'ancienne version puis installe automatiquement le nouvel APK
     * dès la réception du broadcast ACTION_PACKAGE_REMOVED.
     */
    fun resolveSignatureConflictByUninstalling(app: ProjectApp) {
        val updatesDir = File(getApplication<Application>().filesDir, "updates")
        val apkFile = _uiState.value.pendingInstallFile ?: File(updatesDir, "${app.id}.apk")
        pendingReinstallAfterUninstallAppId = app.id
        pendingReinstallApkFile = apkFile
        _uiState.update { it.copy(signatureConflictApp = null) }
        ApkInstaller.uninstallApp(getApplication(), app.packageName)
    }

    fun dismissSignatureConflict() {
        val conflictApp = _uiState.value.signatureConflictApp
        _uiState.update { s ->
            s.copy(
                signatureConflictApp = null,
                activeUpdateId = if (s.activeUpdateId == conflictApp?.id) null else s.activeUpdateId
            )
        }
        if (_uiState.value.updateQueue.isNotEmpty()) {
            processNextInQueue()
        } else {
            _uiState.update { it.copy(isUpdatingAll = false) }
        }
    }

    fun skipToNextInQueue() {
        val state = _uiState.value
        val activeId = state.activeUpdateId
        _uiState.update { s ->
            s.copy(
                apps = s.apps.map {
                    if (it.id == activeId) it.copy(isUpdating = false, isQueued = false, statusText = "Prêt") else it
                },
                activeUpdateId = null,
                isWaitingForUserInstall = false
            )
        }
        processNextInQueue()
    }

    fun retryCurrentInQueue() {
        val state = _uiState.value
        val activeId = state.activeUpdateId ?: return
        val app = state.apps.firstOrNull { it.id == activeId } ?: return
        startDownloadAndInstall(app)
    }

    fun cancelQueue() {
        currentDownloadJob?.cancel()
        preDownloadJob?.cancel()
        preDownloadedFiles.clear()
        _uiState.update { state ->
            state.copy(
                isUpdatingAll = false,
                activeUpdateId = null,
                isWaitingForUserInstall = false,
                updateQueue = emptyList(),
                apps = state.apps.map {
                    if (it.isQueued || it.isUpdating) it.copy(isQueued = false, isUpdating = false, statusText = "Prêt") else it
                }
            )
        }
    }

    fun continueQueueToNext() {
        processNextInQueue()
    }

    fun openApp(app: ProjectApp): Boolean {
        val context = getApplication<Application>()
        val success = ApkInstaller.openApp(context, app.packageName)
        if (!success) {
            _uiState.update {
                it.copy(errorMessage = "Impossible de lancer ${app.displayName}. L'application est-elle bien installée ?")
            }
        }
        return success
    }

    fun uninstallApp(app: ProjectApp): Boolean {
        val context = getApplication<Application>()
        return ApkInstaller.uninstallApp(context, app.packageName)
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
