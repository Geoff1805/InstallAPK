package fr.installapk.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.installapk.app.BuildConfig
import fr.installapk.app.data.GitHubAppsRepository
import fr.installapk.app.data.TokenValidationResult
import fr.installapk.app.model.ProjectApp
import fr.installapk.app.util.ApkInstaller
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class MainUiState(
    val token: String = "",
    val tokenValidation: TokenValidationResult? = null,
    val isTokenValidating: Boolean = false,
    val alertEmail: String = BuildConfig.ALERT_EMAIL,
    val apps: List<ProjectApp> = emptyList(),
    val newlyDetectedApp: ProjectApp? = null,    // Nouveau dépôt Android détecté à proposer
    val isLoading: Boolean = false,
    val isUpdatingAll: Boolean = false,
    val updateQueue: List<String> = emptyList(), // IDs des apps dans la file d'attente
    val activeUpdateId: String? = null,          // ID de l'app en cours de téléchargement/installation
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
                compareByDescending<ProjectApp> { it.isNew } // Priorité visuelle aux nouveaux projets
                    .thenByDescending { it.isUpdating || it.isQueued }
                    .thenByDescending { it.hasUpdateAvailable }
                    .thenByDescending { !it.isInstalled && it.downloadUrl != null }
                    .thenByDescending { it.isUpToDate }
                    .thenBy { it.displayName.lowercase() }
            )

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
            apps = repository.getInitialProjects(),
            needsInstallPermission = !ApkInstaller.canRequestPackageInstalls(application)
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var currentDownloadJob: Job? = null

    init {
        validateCurrentToken()
        refreshApps()
    }

    fun onTokenChanged(newToken: String) {
        val trimmed = newToken.trim()
        _uiState.update { it.copy(token = trimmed) }
        prefs.edit().putString("github_token", trimmed).apply()
        validateCurrentToken()
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
        // Deuxième passe après court délai pour garantir la prise en compte du nouveau package par l'OS
        viewModelScope.launch {
            kotlinx.coroutines.delay(600)
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
                if (freshApp.isUpToDate) {
                    // L'application active a bien été installée et est maintenant confirmée à jour !
                    _uiState.update { s ->
                        s.copy(
                            apps = s.apps.map { if (it.id == activeId) freshApp.copy(statusText = "À jour", isUpdating = false, isQueued = false) else it },
                            activeUpdateId = null,
                            isWaitingForUserInstall = false
                        )
                    }

                    if (state.updateQueue.isNotEmpty()) {
                        processNextInQueue()
                    } else {
                        _uiState.update { it.copy(isUpdatingAll = false) }
                    }
                    return
                } else if (!state.isUpdatingAll) {
                    // L'installation individuelle a été annulée ou n'a pas été confirmée par Android
                    _uiState.update { s ->
                        s.copy(
                            apps = s.apps.map { if (it.id == activeId) freshApp.copy(statusText = "Prêt", isUpdating = false, isQueued = false) else it },
                            activeUpdateId = null,
                            isWaitingForUserInstall = false
                        )
                    }
                }
            }
        }

        // Si la file d'attente globale est active et qu'aucune app n'est en cours, lancer la suivante
        if (state.isUpdatingAll && state.activeUpdateId == null && state.updateQueue.isNotEmpty()) {
            processNextInQueue()
        }
    }

    fun addCustomRepository(repoName: String) {
        val trimmed = repoName.trim()
        if (trimmed.isNotBlank()) {
            repository.addCustomRepo(trimmed)
            refreshApps()
        }
    }

    fun refreshApps() {
        val currentToken = _uiState.value.token
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            validateCurrentToken()

            val updatedList = repository.discoverAndRefreshApps(currentToken)

            // Détecter si un nouveau dépôt Android a été trouvé et n'a pas encore été acquitté
            val firstNewApp = updatedList.firstOrNull { it.isNew && !it.isSelf }

            _uiState.update {
                it.copy(
                    apps = updatedList,
                    newlyDetectedApp = firstNewApp,
                    isLoading = false
                )
            }
        }
    }

    /**
     * L'utilisateur accepte la proposition d'installer le nouveau repo Android détecté.
     */
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

    /**
     * L'utilisateur acquitte la proposition sans installer immédiatement (ex: Ignorer ou Ajouter).
     */
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

    /**
     * L'utilisateur reporte la décision à plus tard pour cette session.
     */
    fun postponeNewApp(app: ProjectApp) {
        _uiState.update { s ->
            s.copy(newlyDetectedApp = null)
        }
    }

    /**
     * Lance ou met en file d'attente une mise à jour individuelle.
     * Si l'app est déjà à jour et forceReinstall est false, l'opération est ignorée.
     */
    fun installOrUpdateApp(app: ProjectApp, forceReinstall: Boolean = false) {
        val context = getApplication<Application>()
        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            _uiState.update { it.copy(needsInstallPermission = true) }
            return
        }

        // Règle absolue : ne pas réinstaller une appli déjà à jour sans demande explicite
        if (!forceReinstall && app.isUpToDate) {
            return
        }

        val state = _uiState.value

        // Si une mise à jour est déjà active
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
            }
            return
        }

        // Aucune mise à jour active : on démarre directement celle-ci
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
     * Les applications déjà à jour sont formellement ignorées (zéro réinstallation intempestive).
     * Les mises à jour sont traitées en file d'attente séquentielle (une par une).
     */
    fun updateAllApps() {
        val context = getApplication<Application>()
        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            _uiState.update { it.copy(needsInstallPermission = true) }
            return
        }

        // Filtre strict : uniquement les applications clientes gérées (excluant InstallAPK qui a son propre cycle)
        val appsToUpdate = _uiState.value.managedApps.filter { it.needsInstallationOrUpdate }
        if (appsToUpdate.isEmpty()) return

        val queueIds = appsToUpdate.map { it.id }
        val firstApp = appsToUpdate.first()
        val remainingQueue = queueIds.drop(1)

        _uiState.update { state ->
            state.copy(
                isUpdatingAll = true,
                activeUpdateId = firstApp.id,
                updateQueue = remainingQueue,
                apps = state.apps.map { item ->
                    when {
                        item.id == firstApp.id -> item.copy(isUpdating = true, isQueued = false, downloadProgress = 0f, statusText = "Téléchargement...")
                        remainingQueue.contains(item.id) -> item.copy(isQueued = true, isUpdating = false, statusText = "En file d'attente...")
                        else -> item
                    }
                }
            )
        }

        startDownloadAndInstall(firstApp)
    }

    /**
     * Auto-mise à jour spécifique pour InstallAPK.
     * Traitée de manière totalement indépendante des applications clientes.
     */
    fun updateSelfApp() {
        val self = _uiState.value.selfApp ?: return
        installOrUpdateApp(self, forceReinstall = false)
    }

    private fun processNextInQueue() {
        val state = _uiState.value
        if (state.updateQueue.isEmpty()) {
            _uiState.update { it.copy(isUpdatingAll = false, activeUpdateId = null) }
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

        _uiState.update { s ->
            s.copy(
                activeUpdateId = nextId,
                updateQueue = remainingQueue,
                apps = s.apps.map {
                    if (it.id == nextId) it.copy(isUpdating = true, isQueued = false, downloadProgress = 0f, statusText = "Téléchargement...") else it
                }
            )
        }

        startDownloadAndInstall(nextApp)
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

            val apkFile = repository.downloadAndExtractApk(app, currentToken) { progress ->
                _uiState.update { state ->
                    state.copy(
                        apps = state.apps.map {
                            if (it.id == app.id) it.copy(downloadProgress = progress) else it
                        }
                    )
                }
            }

            if (apkFile != null) {
                repository.recordAppInstallationInitiated(app.id, app.latestArtifactId, apkFile)
                _uiState.update { state ->
                    state.copy(
                        isWaitingForUserInstall = true,
                        apps = state.apps.map {
                            if (it.id == app.id) it.copy(isUpdating = false, downloadProgress = 1f, statusText = "Confirmation Android...") else it
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
                // Si échec sur une appli de la file d'attente, on passe à la suivante
                if (_uiState.value.updateQueue.isNotEmpty()) {
                    processNextInQueue()
                } else {
                    _uiState.update { it.copy(isUpdatingAll = false) }
                }
            }
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

    /**
     * Tente de lancer l'application installée.
     */
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

    /**
     * Lance la procédure de désinstallation de l'application via le système Android.
     */
    fun uninstallApp(app: ProjectApp): Boolean {
        val context = getApplication<Application>()
        return ApkInstaller.uninstallApp(context, app.packageName)
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
