package fr.installapk.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import fr.installapk.app.R
import fr.installapk.app.model.ProjectApp

/**
 * Composant d'icône d'application réutilisable (Principe de réutilisation : Coil / Now in Android / Obtainium).
 * Tente de charger l'avatar GitHub / icône distante via Coil avec gestion du cache,
 * et bascule de manière transparente sur l'emoji local configuré en cas de déconnexion ou d'erreur.
 */
@Composable
fun AppIcon(
    app: ProjectApp,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 46.dp,
    shape: Shape = RoundedCornerShape(12.dp),
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer
) {
    Surface(
        shape = shape,
        color = containerColor,
        modifier = modifier.size(size)
    ) {
        val iconUrl = app.effectiveIconUrl
        if (iconUrl.isNotBlank()) {
            SubcomposeAsyncImage(
                model = iconUrl,
                contentDescription = app.displayName,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape),
                contentScale = ContentScale.Crop,
                loading = {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(app.iconEmoji, fontSize = (size.value * 0.48f).sp)
                    }
                },
                error = {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(app.iconEmoji, fontSize = (size.value * 0.48f).sp)
                    }
                }
            )
        } else {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(app.iconEmoji, fontSize = (size.value * 0.48f).sp)
            }
        }
    }
}

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
    var appToUninstall by remember { mutableStateOf<ProjectApp?>(null) }
    var appToShowBuildInfo by remember { mutableStateOf<ProjectApp?>(null) }
    var tempToken by remember { mutableStateOf(uiState.token) }
    var newRepoName by remember { mutableStateOf("") }
    val context = LocalContext.current

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

                    val pendingCount = uiState.pendingManagedUpdatesCount
                    val isAllUpToDate = uiState.isAllManagedUpToDate
                    val isUpdatingAll = uiState.isUpdatingAll

                    when {
                        // Cas 1 : File d'attente de mise à jour active
                        isUpdatingAll -> {
                            val nextApp = uiState.nextQueueApp
                            val isWaiting = uiState.isWaitingForUserInstall
                            val activeApp = uiState.activeApp

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isWaiting && nextApp != null) {
                                    // Bouton actif pour enchaîner directement sur l'application suivante
                                    Button(
                                        onClick = { viewModel.skipToNextInQueue() },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(56.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Installer suivante : ${nextApp.displayName} (${uiState.updateQueue.size})",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            maxLines = 1
                                        )
                                    }
                                } else {
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
                                            text = when {
                                                isWaiting -> "Installateur ouvert ($inQueue en attente)..."
                                                activeApp != null -> "Téléchargement : ${activeApp.displayName}..."
                                                inQueue > 0 -> "File en cours ($inQueue en attente)..."
                                                else -> "Finalisation..."
                                            },
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                    }
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

                        // Cas 2 : Toutes les applications gérées sont déjà à jour
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
                                    "Toutes vos applications sont à jour (${uiState.managedApps.size})",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Cas 3 : Mises à jour ou installations en attente pour les applications gérées
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

                        // Cas 4 : Aucune application gérée
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
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
                val activeApp = uiState.activeApp
                val nextApp = uiState.nextQueueApp
                val isWaitingInstall = uiState.isWaitingForUserInstall

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (activeApp != null) "File active : ${activeApp.displayName}" else "Mise à jour en file d'attente",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = when {
                                            isWaitingInstall && nextApp != null -> "Installateur Android ouvert. Prêt pour l'app suivante."
                                            isWaitingInstall -> "En attente de validation dans l'installateur Android..."
                                            activeApp?.isUpdating == true -> "Téléchargement en cours..."
                                            else -> "Traitement séquentiel un par un pour éviter les conflits."
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            if (nextApp != null || isWaitingInstall) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (nextApp != null) {
                                        Button(
                                            onClick = { viewModel.skipToNextInQueue() },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary
                                            )
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                "Installer : ${nextApp.displayName}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1
                                            )
                                        }
                                    }

                                    FilledTonalButton(
                                        onClick = { viewModel.retryCurrentInQueue() },
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "Relancer",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = { viewModel.cancelQueue() },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Arrêter",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // SECTION 1 : CARTE DÉDIÉE INSTALLAPK (Auto-mise à jour traitée différemment)
            val selfApp = uiState.selfApp
            if (selfApp != null) {
                item {
                    SelfAppCard(
                        app = selfApp,
                        onUpdate = { viewModel.updateSelfApp() },
                        onRequestForceReinstall = { appToForceReinstall = selfApp },
                        onUninstall = { appToUninstall = selfApp }
                    )
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
                                "ℹ️ Cet avertissement de Google Play Protect survient car vos applications sont compilées de manière privée sur vos propres GitHub Actions et ne proviennent pas du catalogue public du Play Store.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "👉 Option 1 (Recommandée) : Lors de l'alerte rouge, appuyez sur « Plus de détails » (ou la petite flèche) puis sur « Installer quand même ».",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "👉 Option 2 (Si aucun bouton n'apparaît / blocage strict) : Ouvrez Google Play Store > Photo de profil > Play Protect > Roue crantée ⚙️ en haut à droite > Désactivez temporairement « Analyser les applications avec Play Protect ».",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // SECTION 2 : VOS APPLICATIONS GÉRÉES (Bullix, Needix, Antigravity Mobile...)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Applications gérées (${uiState.managedApps.size})",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (uiState.pendingManagedUpdatesCount > 0) {
                        Text(
                            "${uiState.pendingManagedUpdatesCount} mise(s) à jour en priorité",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            "GitHub Actions",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            // Barre de Recherche & Filtres Rapides (Inspiré d'Obtainium et Neo-Store)
            item {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Rechercher une application...", fontSize = 14.sp) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Rechercher",
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotBlank()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Effacer la recherche",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = uiState.filterMode == AppFilterMode.ALL,
                        onClick = { viewModel.setFilterMode(AppFilterMode.ALL) },
                        label = { Text("Toutes (${uiState.managedApps.size})", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = uiState.filterMode == AppFilterMode.UPDATES,
                        onClick = { viewModel.setFilterMode(AppFilterMode.UPDATES) },
                        label = { Text("Mises à jour (${uiState.pendingManagedUpdatesCount})", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = uiState.filterMode == AppFilterMode.INSTALLED,
                        onClick = { viewModel.setFilterMode(AppFilterMode.INSTALLED) },
                        label = { Text("Installées (${uiState.managedApps.count { it.isInstalled }})", fontSize = 12.sp) }
                    )
                }
            }

            if (uiState.filteredManagedApps.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Aucune application ne correspond aux critères.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(uiState.filteredManagedApps, key = { it.id }) { app ->
                AppCard(
                    app = app,
                    onInstallOrUpdate = { viewModel.installOrUpdateApp(app) },
                    onOpenApp = { viewModel.openApp(app) },
                    onUninstallApp = { appToUninstall = app },
                    onRequestForceReinstall = { appToForceReinstall = app },
                    onOpenBuildStatus = { appToShowBuildInfo = app }
                )
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Dialogue d'information sur le build CI en attente (ex: Antigravity Mobile)
    if (appToShowBuildInfo != null) {
        val target = appToShowBuildInfo!!
        AlertDialog(
            onDismissRequest = { appToShowBuildInfo = null },
            icon = {
                Icon(
                    Icons.Default.CloudQueue,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Build CI de ${target.displayName}") },
            text = {
                Column {
                    Text(
                        "Aucun artefact APK n'est encore disponible sur GitHub Actions pour le dépôt ${target.repoName}.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Dès que le workflow GitHub Actions aura compilé le projet avec succès, l'APK apparaîtra automatiquement ici prêt à être installé en un clic.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${target.repoOwner}/${target.repoName}/actions"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    appToShowBuildInfo = null
                }) {
                    Text("Voir sur GitHub Actions")
                }
            },
            dismissButton = {
                TextButton(onClick = { appToShowBuildInfo = null }) {
                    Text("Fermer")
                }
            }
        )
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

    // Dialogue de confirmation de désinstallation
    if (appToUninstall != null) {
        val targetApp = appToUninstall!!
        AlertDialog(
            onDismissRequest = { appToUninstall = null },
            icon = {
                Icon(
                    Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Désinstaller ${targetApp.displayName} ?") },
            text = {
                Column {
                    Text(
                        "Voulez-vous désinstaller cette application de votre appareil ?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Package : ${targetApp.packageName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Le système Android supprimera l'application et les données locales associées.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.uninstallApp(targetApp)
                        appToUninstall = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Désinstaller")
                }
            },
            dismissButton = {
                TextButton(onClick = { appToUninstall = null }) {
                    Text("Annuler")
                }
            }
        )
    }

    // Dialogue d'information / erreur
    if (uiState.errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearErrorMessage() },
            icon = {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Information") },
            text = { Text(uiState.errorMessage ?: "") },
            confirmButton = {
                Button(onClick = { viewModel.clearErrorMessage() }) {
                    Text("Compris")
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

    val newlyDetectedApp = uiState.newlyDetectedApp
    if (newlyDetectedApp != null) {
        AlertDialog(
            onDismissRequest = { viewModel.postponeNewApp(newlyDetectedApp) },
            icon = {
                Icon(
                    Icons.Default.Celebration,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    "🎉 Nouveau projet Android détecté !",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column {
                    Text(
                        "Un nouveau dépôt GitHub d'application Android a été identifié :",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(
                                app = newlyDetectedApp,
                                size = 44.dp,
                                shape = RoundedCornerShape(10.dp),
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        newlyDetectedApp.displayName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    ) {
                                        Text(
                                            "NOUVEAU",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    newlyDetectedApp.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                if (newlyDetectedApp.downloadUrl != null) {
                                    val sizeMb = (newlyDetectedApp.latestArtifactSize ?: 0L).toDouble() / (1024 * 1024)
                                    Text(
                                        "📦 APK prêt au téléchargement (%.1f Mo)".format(sizeMb),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                } else {
                                    Text(
                                        "⏳ En attente du premier build GitHub Actions",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        if (newlyDetectedApp.downloadUrl != null)
                            "Souhaitez-vous installer cette application dès maintenant sur votre appareil ?"
                        else
                            "Souhaitez-vous ajouter ce nouveau dépôt à votre liste d'applications suivies ?",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                if (newlyDetectedApp.downloadUrl != null) {
                    Button(
                        onClick = { viewModel.acceptNewAppInstall(newlyDetectedApp) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Installer l'APK")
                    }
                } else {
                    Button(
                        onClick = { viewModel.dismissNewApp(newlyDetectedApp) }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ajouter aux suivis")
                    }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.postponeNewApp(newlyDetectedApp) }) {
                        Text("Plus tard")
                    }
                    TextButton(onClick = { viewModel.dismissNewApp(newlyDetectedApp) }) {
                        Text("Ignorer")
                    }
                }
            }
        )
    }
}

/**
 * Carte dédiée pour InstallAPK (l'application gestionnaire elle-même).
 * Traitée avec un statut propre, séparée des applications clientes.
 */
@Composable
fun SelfAppCard(
    app: ProjectApp,
    onUpdate: () -> Unit,
    onRequestForceReinstall: () -> Unit,
    onUninstall: () -> Unit
) {
    val isUpToDate = app.isUpToDate
    val hasUpdate = app.hasUpdateAvailable
    val isUpdating = app.isUpdating

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (hasUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (hasUpdate) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                AppIcon(
                    app = app,
                    size = 46.dp,
                    shape = RoundedCornerShape(12.dp),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "InstallAPK (Cette application)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                "GESTIONNAIRE",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                    val installedName = app.installedVersionName?.let { ProjectApp.cleanVersion(it) } ?: "?"
                    val latestName = app.latestVersionName?.let { ProjectApp.cleanVersion(it) } ?: "?"
                    val subtitle = when {
                        isUpToDate -> "Version actuelle : v$installedName (À jour)"
                        installedName != latestName && installedName != "?" && latestName != "?" ->
                            "Mise à jour disponible : v$installedName ➔ v$latestName"
                        else -> "Nouveau build CI disponible : v$installedName"
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                when {
                    isUpdating -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    hasUpdate -> {
                        Button(
                            onClick = onUpdate,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Mettre à jour", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    isUpToDate -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("À jour", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            IconButton(
                                onClick = onRequestForceReinstall,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Réinstaller",
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(
                                onClick = onUninstall,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Désinstaller InstallAPK",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    else -> {
                        Text("Prêt", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (isUpdating && app.downloadProgress != null) {
                Spacer(modifier = Modifier.height(10.dp))
                val progressPercent = ((app.downloadProgress ?: 0f) * 100).toInt()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Téléchargement de l'auto-mise à jour...", style = MaterialTheme.typography.labelSmall)
                    Text("$progressPercent %", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { app.downloadProgress ?: 0f },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

/**
 * Carte individuelle pour chaque application gérée (Bullix, Needix, Antigravity Mobile...).
 */
@Composable
fun AppCard(
    app: ProjectApp,
    onInstallOrUpdate: () -> Unit,
    onOpenApp: () -> Unit,
    onUninstallApp: () -> Unit,
    onRequestForceReinstall: () -> Unit,
    onOpenBuildStatus: () -> Unit
) {
    val isInstalled = app.isInstalled
    val isUpToDate = app.isUpToDate
    val hasUpdate = app.hasUpdateAvailable
    val isQueued = app.isQueued
    val isUpdating = app.isUpdating
    val hasNoApkYet = app.downloadUrl == null
    var showChangelog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (app.isNew) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (app.isNew) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Ligne Titre + Emoji
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                AppIcon(
                    app = app,
                    size = 48.dp,
                    shape = RoundedCornerShape(12.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = app.displayName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        if (app.isNew) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    "✨ NOUVEAU",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
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
                    hasNoApkYet -> {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                        ) {
                            Text(
                                "⏳ En attente du premier build CI",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.secondary,
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
                                "✓ À jour (v${ProjectApp.cleanVersion(app.installedVersionName ?: "?")})",
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
                            val cleanInst = ProjectApp.cleanVersion(app.installedVersionName)
                            val cleanLat = ProjectApp.cleanVersion(app.latestVersionName)
                            val badgeText = when {
                                cleanInst.isNotBlank() && cleanLat.isNotBlank() && !cleanInst.equals(cleanLat, ignoreCase = true) ->
                                    "⬆ Mise à jour disponible (v$cleanInst → v$cleanLat)"
                                cleanInst.isNotBlank() ->
                                    "⬆ Nouveau build CI disponible (v$cleanInst)"
                                else ->
                                    "⬆ Mise à jour disponible (v${app.latestVersionName ?: "?"})"
                            }
                            Text(
                                badgeText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    !isInstalled -> {
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
                }
            }

            // Notes de version / Changelog (Inspiré d'Obtainium)
            if (!app.releaseNotes.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { showChangelog = !showChangelog }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Article,
                        contentDescription = "Changelog",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (showChangelog) "Masquer les nouveautés" else "Voir les nouveautés (${app.latestVersionName ?: ""})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (showChangelog) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Text(
                            text = app.releaseNotes ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))

            // Ligne de détails et boutons d'action
            val installedCodeStr = if (app.installedVersionCode != null && app.installedVersionCode > 0) " (build ${app.installedVersionCode})" else ""
            val latestCodeStr = if (app.latestVersionCode != null && app.latestVersionCode > 0) " (build ${app.latestVersionCode})" else ""
            val installedNameStr = if (app.installedVersionName.isNullOrBlank()) "" else "v${ProjectApp.cleanVersion(app.installedVersionName)}"
            val latestNameStr = if (app.latestVersionName.isNullOrBlank()) "" else {
                if (app.latestVersionName.startsWith("Run #", ignoreCase = true)) app.latestVersionName else "v${ProjectApp.cleanVersion(app.latestVersionName)}"
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isInstalled) "Installé : $installedNameStr$installedCodeStr" else "Non installé sur le téléphone",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isInstalled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                    )
                    if (app.latestVersionName != null) {
                        Text(
                            text = "Dernière CI : $latestNameStr$latestCodeStr",
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

                        // Aucun APK encore compilé (ex: Antigravity Mobile en attente de CI)
                        hasNoApkYet -> {
                            OutlinedButton(
                                onClick = onOpenBuildStatus,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.secondary
                                )
                            ) {
                                Icon(
                                    Icons.Default.CloudQueue,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Statut CI", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        // Déjà à jour : Bouton principal "Ouvrir" + bouton "Désinstaller" + bouton discret "Réinstaller"
                        isUpToDate -> {
                            Button(
                                onClick = onOpenApp,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Launch,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Ouvrir", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.width(2.dp))

                            IconButton(
                                onClick = onRequestForceReinstall,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Forcer la réinstallation",
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(17.dp)
                                )
                            }

                            IconButton(
                                onClick = onUninstallApp,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Désinstaller",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Mise à jour disponible : Bouton principal "Mettre à jour" + bouton "Ouvrir" + bouton "Désinstaller"
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

                            Spacer(modifier = Modifier.width(4.dp))

                            FilledTonalButton(
                                onClick = onOpenApp,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Launch,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Ouvrir", fontSize = 12.sp)
                            }

                            IconButton(
                                onClick = onUninstallApp,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Désinstaller",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Non installée : Bouton "Installer"
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

                        // Autre état de repli
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
