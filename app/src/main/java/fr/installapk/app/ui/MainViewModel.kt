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
    val isLoading: Boolean = false,
    val isUpdatingAll: Boolean = false,
    val updateQueue: List<String> = emptyList(), // IDs des apps dans la file d'attente
    val activeUpdateId: String? = null,          // ID de l'app en cours de téléchargement/installation
    val needsInstallPermission: Boolean = false,
    val pendingInstallFile: File? = null,
    val errorMessage: String? = null
) {
    val selfApp: ProjectApp?
        get() = apps.firstOrNull { it.isSelf }

    val managedApps: List<ProjectApp>
        get() = apps.filter { !it.isSelf }

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
        val activeId = state.activeUpdateId ?: return

        val app = state.apps.firstOrNull { it.id == activeId } ?: return
        val freshApp = repository.refreshSingleAppInstalledStatus(app)

        // Si l'application active a été installée avec succès (mise à jour prise en compte)
        if (freshApp.isUpToDate) {
            _uiState.update { s ->
                s.copy(
                    apps = s.apps.map { if (it.id == activeId) freshApp.copy(statusText = "Installé avec succès !") else it },
                    activeUpdateId = null
                )
            }
            // Enchaîner avec la suite de la file d'attente s'il y a des apps en attente
            if (state.updateQueue.isNotEmpty()) {
                processNextInQueue()
            } else {
                _uiState.update { it.copy(isUpdatingAll = false) }
            }
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

            _uiState.update {
                it.copy(
                    apps = updatedList,
                    isLoading = false
                )
            }
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
                _uiState.update { state ->
                    state.copy(
                        apps = state.apps.map {
                            if (it.id == app.id) it.copy(isUpdating = false, downloadProgress = 1f, statusText = "Prêt à installer") else it
                        }
                    )
                }
                ApkInstaller.installApk(context, apkFile)
            } else {
                _uiState.update { state ->
                    state.copy(
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

    fun cancelQueue() {
        currentDownloadJob?.cancel()
        _uiState.update { state ->
            state.copy(
                isUpdatingAll = false,
                activeUpdateId = null,
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
}
