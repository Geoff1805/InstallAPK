package fr.installapk.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.installapk.app.R
import fr.installapk.app.model.ProjectApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestInstallPermission: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showTokenDialog by remember { mutableStateOf(false) }
    var showAddRepoDialog by remember { mutableStateOf(false) }
    var showPlayProtectInfo by remember { mutableStateOf(false) }
    var appToForceReinstall by remember { mutableStateOf<ProjectApp?>(null) }
    var tempToken by remember { mutableStateOf(uiState.token) }
    var newRepoName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.checkPermissions()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = "InstallAPK Logo",
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("InstallAPK", fontWeight = FontWeight.Bold)
                            Text(
                                "Mise à jour directe GitHub Actions",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAddRepoDialog = true },
                        enabled = !uiState.isLoading
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Ajouter un dépôt")
                    }
                    IconButton(
                        onClick = { viewModel.refreshApps() },
                        enabled = !uiState.isLoading
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Rafraîchir")
                        }
                    }
                    IconButton(onClick = {
                        tempToken = uiState.token
                        showTokenDialog = true
                    }) {
                        val isInvalid = uiState.tokenValidation?.isValid == false
                        BadgedBox(
                            badge = {
                                if (isInvalid) {
                                    Badge(containerColor = MaterialTheme.colorScheme.error) {
                                        Text("!")
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Key,
                                contentDescription = "GitHub Token",
                                tint = if (isInvalid) MaterialTheme.colorScheme.error else LocalContentColor.current
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    if (uiState.needsInstallPermission) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Autorisation requise",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        "Autoriser l'installation d'applications inconnues",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                TextButton(onClick = onRequestInstallPermission) {
                                    Text("Activer")
                                }
                            }
                        }
                    }

                    val pendingCount = uiState.pendingUpdatesCount
                    val isAllUpToDate = uiState.isAllUpToDate
                    val isUpdatingAll = uiState.isUpdatingAll

                    when {
                        // Cas 1 : File d'attente de mise à jour active
                        isUpdatingAll -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(56.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    enabled = false,
                                    colors = ButtonDefaults.buttonColors(
                                        disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        disabledContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    val inQueue = uiState.updateQueue.size
                                    Text(
                                        text = if (inQueue > 0) "Mise à jour séquentielle ($inQueue en attente)..." else "Finalisation de la mise à jour...",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = { viewModel.cancelQueue() },
                                    modifier = Modifier
                                        .size(56.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            shape = RoundedCornerShape(16.dp)
                                        )
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Annuler la file",
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }

                        // Cas 2 : Toutes les applications sont à jour (zéro réinstallation)
                        isAllUpToDate -> {
                            OutlinedButton(
                                onClick = { },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(16.dp),
                                enabled = false,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    disabledContentColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Toutes les applications sont à jour (${uiState.apps.size})",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Cas 3 : Mises à jour ou installations en attente
                        pendingCount > 0 -> {
                            Button(
                                onClick = { viewModel.updateAllApps() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(16.dp),
                                enabled = !uiState.isLoading,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.SystemUpdateAlt, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Tout mettre à jour ($pendingCount)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                        }

                        // Cas 4 : Aucune application ou état initial
                        else -> {
                            Button(
                                onClick = { },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(16.dp),
                                enabled = false
                            ) {
                                Text("Aucune mise à jour requise")
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Bandeau d'alerte Token expiré / invalide
            val validation = uiState.tokenValidation
            if (validation != null && !validation.isValid) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Clé GitHub Invalide ou Expirée !",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = validation.errorMessage ?: "Votre clé GitHub (PAT) n'est plus acceptée par l'API.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Alerte envoyée vers : ${uiState.alertEmail}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    tempToken = uiState.token
                                    showTokenDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Remplacer la clé")
                            }
                        }
                    }
                }
            } else if (validation != null && validation.isValid) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Clé GitHub Active (${validation.username})",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (!validation.expirationDate.isNullOrBlank()) {
                                    Text(
                                        "Expiration : ${validation.expirationDate}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bandeau de file d'attente séquentielle active
            if (uiState.isUpdatingAll || uiState.activeUpdateId != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Mise à jour en file d'attente séquentielle",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    "Les APKs sont traités un à un pour une installation fluide sans conflit.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }

            // Guide Play Protect / Avertissement Android
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (showPlayProtectInfo) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Avertissement Android « Application dangereuse » ?",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { showPlayProtectInfo = !showPlayProtectInfo }) {
                                Text(if (showPlayProtectInfo) "Masquer" else "Explications")
                            }
                        }
                        if (showPlayProtectInfo) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "ℹ️ Cet avertissement de Google Play Protect est tout à fait normal : vos applications sont compilées de manière privée par vos propres GitHub Actions et ne sont pas distribuées sur le Google Play Store public.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "👉 Pour continuer l'installation : appuyez sur « Plus de détails » puis sur « Installer quand même ».",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            items(uiState.apps, key = { it.id }) { app ->
                AppCard(
                    app = app,
                    onInstallOrUpdate = { viewModel.installOrUpdateApp(app) },
                    onRequestForceReinstall = { appToForceReinstall = app }
                )
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Dialogue de confirmation de réinstallation forcée
    if (appToForceReinstall != null) {
        val targetApp = appToForceReinstall!!
        AlertDialog(
            onDismissRequest = { appToForceReinstall = null },
            title = { Text("Réinstaller ${targetApp.displayName} ?") },
            text = {
                Text(
                    "Cette application est déjà à jour (v${targetApp.installedVersionName}). " +
                        "Voulez-vous quand même forcer le téléchargement et la réinstallation de l'APK ?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.installOrUpdateApp(targetApp, forceReinstall = true)
                        appToForceReinstall = null
                    }
                ) {
                    Text("Forcer la réinstallation")
                }
            },
            dismissButton = {
                TextButton(onClick = { appToForceReinstall = null }) {
                    Text("Annuler")
                }
            }
        )
    }

    if (showAddRepoDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddRepoDialog = false
                newRepoName = ""
            },
            title = { Text("Ajouter un dépôt GitHub") },
            text = {
                Column {
                    Text(
                        "Saisissez le nom d'un dépôt GitHub de votre compte (ex: Needix, Bullix, ou tout autre projet) pour le suivre :",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newRepoName,
                        onValueChange = { newRepoName = it },
                        label = { Text("Nom du dépôt (ex: MonApp)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newRepoName.trim()
                        if (name.isNotBlank()) {
                            viewModel.addCustomRepository(name)
                        }
                        showAddRepoDialog = false
                        newRepoName = ""
                    },
                    enabled = newRepoName.isNotBlank()
                ) {
                    Text("Ajouter et Synchroniser")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddRepoDialog = false
                    newRepoName = ""
                }) {
                    Text("Annuler")
                }
            }
        )
    }

    if (showTokenDialog) {
        AlertDialog(
            onDismissRequest = { showTokenDialog = false },
            title = { Text("Jeton GitHub (PAT)") },
            text = {
                Column {
                    Text(
                        "Renseignez votre Personal Access Token GitHub (avec les accès 'repo' et 'actions') pour télécharger les artefacts de vos dépôts privés :",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempToken,
                        onValueChange = { tempToken = it },
                        label = { Text("GitHub Token (ghp_...)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Email de notification en cas d'expiration : ${uiState.alertEmail}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.onTokenChanged(tempToken)
                    showTokenDialog = false
                    viewModel.refreshApps()
                }) {
                    Text("Enregistrer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTokenDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }
}

@Composable
fun AppCard(
    app: ProjectApp,
    onInstallOrUpdate: () -> Unit,
    onRequestForceReinstall: () -> Unit
) {
    val isInstalled = app.isInstalled
    val isUpToDate = app.isUpToDate
    val hasUpdate = app.hasUpdateAvailable
    val isQueued = app.isQueued
    val isUpdating = app.isUpdating

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Ligne Titre + Emoji + Badge Auto-Update
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(app.iconEmoji, fontSize = 24.sp)
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = app.displayName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        if (app.isSelf) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    "AUTO-UPDATE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = app.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Badge d'état de version (Material 3)
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    isQueued -> {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                "⏳ En file d'attente",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    isUpdating -> {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                "⬇ Téléchargement en cours...",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    isUpToDate -> {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                "✓ À jour (v${app.installedVersionName})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    hasUpdate -> {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                "⬆ Mise à jour disponible (v${app.installedVersionName} → v${app.latestVersionName})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    !isInstalled && app.downloadUrl != null -> {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                "Prêt à installer (v${app.latestVersionName ?: "?"})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    else -> {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Text(
                                "En attente du build CI",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))

            // Ligne de détails et boutons d'action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isInstalled) "Installé : v${app.installedVersionName} (build ${app.installedVersionCode})" else "Non installé sur le téléphone",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isInstalled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                    )
                    if (app.latestVersionName != null) {
                        Text(
                            text = "Dernière CI : v${app.latestVersionName} (build ${app.latestVersionCode})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (app.statusText != null && app.statusText != "Prêt") {
                        Text(
                            text = app.statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Boutons d'action contextuels
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        // En cours de téléchargement
                        isUpdating -> {
                            FilledTonalButton(
                                onClick = { },
                                enabled = false,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("En cours", fontSize = 13.sp)
                            }
                        }

                        // En file d'attente
                        isQueued -> {
                            FilledTonalButton(
                                onClick = { },
                                enabled = false,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("En attente", fontSize = 13.sp)
                            }
                        }

                        // Déjà à jour : bouton désactivé "À jour" + icône discrète pour forcer si besoin
                        isUpToDate -> {
                            FilledTonalButton(
                                onClick = { },
                                enabled = false,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("À jour", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }

                            // Bouton discret pour réinstallation manuelle si besoin
                            IconButton(
                                onClick = onRequestForceReinstall,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Forcer la réinstallation",
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Mise à jour disponible
                        hasUpdate -> {
                            Button(
                                onClick = onInstallOrUpdate,
                                enabled = app.downloadUrl != null,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(
                                    Icons.Default.SystemUpdateAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Mettre à jour", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Non installée
                        !isInstalled -> {
                            Button(
                                onClick = onInstallOrUpdate,
                                enabled = app.downloadUrl != null,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Installer", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        // Autre état (ex: pas d'APK)
                        else -> {
                            FilledTonalButton(
                                onClick = { },
                                enabled = false,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Indisponible", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // Barre de progression linéaire animée
            if (isUpdating && app.downloadProgress != null) {
                Spacer(modifier = Modifier.height(10.dp))
                val progressPercent = ((app.downloadProgress ?: 0f) * 100).toInt()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Téléchargement de l'APK...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "$progressPercent %",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { app.downloadProgress ?: 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }
        }
    }
}
