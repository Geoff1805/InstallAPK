package fr.installapk.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.installapk.app.data.GitHubAppsRepository
import fr.installapk.app.model.ProjectApp
import fr.installapk.app.util.ApkInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class MainUiState(
    val token: String = "",
    val apps: List<ProjectApp> = emptyList(),
    val isLoading: Boolean = false,
    val isUpdatingAll: Boolean = false,
    val needsInstallPermission: Boolean = false,
    val pendingInstallFile: File? = null,
    val errorMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = GitHubAppsRepository(application)
    private val prefs = application.getSharedPreferences("installapk_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        MainUiState(
            token = prefs.getString("github_token", "") ?: "",
            apps = repository.getInitialProjects(),
            needsInstallPermission = !ApkInstaller.canRequestPackageInstalls(application)
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        refreshApps()
    }

    fun onTokenChanged(newToken: String) {
        _uiState.update { it.copy(token = newToken) }
        prefs.edit().putString("github_token", newToken).apply()
    }

    fun checkPermissions() {
        val hasPermission = ApkInstaller.canRequestPackageInstalls(getApplication())
        _uiState.update { it.copy(needsInstallPermission = !hasPermission) }
    }

    fun refreshApps() {
        val currentToken = _uiState.value.token
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val currentList = _uiState.value.apps.ifEmpty { repository.getInitialProjects() }

            val updatedList = currentList.map { app ->
                repository.fetchLatestAppInfo(app, currentToken)
            }

            _uiState.update {
                it.copy(
                    apps = updatedList,
                    isLoading = false
                )
            }
        }
    }

    fun installOrUpdateApp(app: ProjectApp) {
        val currentToken = _uiState.value.token
        val context = getApplication<Application>()

        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            _uiState.update { it.copy(needsInstallPermission = true) }
            return
        }

        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    apps = state.apps.map {
                        if (it.id == app.id) it.copy(isUpdating = true, downloadProgress = 0f, statusText = "Téléchargement...") else it
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
                _uiState.update { state ->
                    state.copy(
                        apps = state.apps.map {
                            if (it.id == app.id) it.copy(isUpdating = false, statusText = "Prêt à installer") else it
                        }
                    )
                }
                ApkInstaller.installApk(context, apkFile)
            } else {
                _uiState.update { state ->
                    state.copy(
                        apps = state.apps.map {
                            if (it.id == app.id) it.copy(isUpdating = false, statusText = "Échec du téléchargement") else it
                        }
                    )
                }
            }
        }
    }

    fun updateAllApps() {
        val context = getApplication<Application>()
        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            _uiState.update { it.copy(needsInstallPermission = true) }
            return
        }

        val appsToUpdate = _uiState.value.apps.filter { it.downloadUrl != null }
        if (appsToUpdate.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingAll = true) }
            // Mettre à jour les applications tierces d'abord, et l'appli InstallAPK elle-même en dernier
            val sorted = appsToUpdate.sortedBy { if (it.isSelf) 1 else 0 }
            for (app in sorted) {
                installOrUpdateApp(app)
            }
            _uiState.update { it.copy(isUpdatingAll = false) }
        }
    }
}
