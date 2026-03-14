package com.example.boxpandora.ui.settings

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.ui.common.AppLockModeSheet
import com.example.boxpandora.ui.common.AppLockRemovalConfirmationDialog
import com.example.boxpandora.ui.common.AppLockTimeoutSheet
import com.example.boxpandora.ui.common.AppPasscodeSetupDialog
import com.example.boxpandora.ui.common.AppPasscodeVerificationDialog
import com.example.boxpandora.ui.common.createDeviceCredentialIntent
import com.example.boxpandora.ui.main.Screen
import com.example.boxpandora.ui.main.viewmodel.AccentColor
import com.example.boxpandora.ui.main.viewmodel.AppLockMode
import com.example.boxpandora.ui.main.viewmodel.AppLockViewModel
import com.example.boxpandora.ui.main.viewmodel.AppLockViewModelFactory
import com.example.boxpandora.ui.main.viewmodel.MaintenanceViewModel
import com.example.boxpandora.ui.main.viewmodel.MaintenanceViewModelFactory
import com.example.boxpandora.ui.main.viewmodel.SortOrder
import com.example.boxpandora.ui.main.viewmodel.ThemeMode
import com.example.boxpandora.ui.main.viewmodel.ThemeViewModel
import com.example.boxpandora.ui.main.viewmodel.AboutStats
import com.example.boxpandora.ui.main.viewmodel.AboutViewModel
import com.example.boxpandora.ui.main.viewmodel.AboutViewModelFactory
import com.example.boxpandora.ui.main.viewmodel.BackupDataViewModel
import com.example.boxpandora.ui.main.viewmodel.BackupDataViewModelFactory
import com.example.boxpandora.ui.main.viewmodel.BackupOpState
import com.example.boxpandora.ui.main.viewmodel.PerformanceOpState
import com.example.boxpandora.ui.main.viewmodel.PerformanceViewModel
import com.example.boxpandora.ui.main.viewmodel.PerformanceViewModelFactory
import com.example.boxpandora.ui.main.viewmodel.ThemeViewModelFactory
import com.example.boxpandora.ui.theme.boxPandoraModalTokens
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSubScreen(
    title: String,
    navController: NavController,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = boxPandoraModalTokens()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.padding(start = 8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(tokens.iconBackgroundNeutral)
                                .border(1.dp, tokens.border, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        },
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            content()
        }
    }
}

@Composable
fun LibrarySettingsScreen(navController: NavController) {
    val activity = LocalContext.current as ComponentActivity
    val app = activity.application as PandoraApp
    val themeViewModel: ThemeViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = ThemeViewModelFactory(app.database.userPreferenceDao())
    )
    val maintenanceViewModel: MaintenanceViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = MaintenanceViewModelFactory(app.repository)
    )

    val showHidden     by themeViewModel.showHidden.collectAsState()
    val showImages     by themeViewModel.showImages.collectAsState()
    val showVideos     by themeViewModel.showVideos.collectAsState()
    val showGifs       by themeViewModel.showGifs.collectAsState()
    val excludedFolders by themeViewModel.excludedFolders.collectAsState()

    SettingsSubScreen("Library Scanning", navController) {
        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp, top = 4.dp)) {
            item { SettingSectionHeader("Library scanning", "Configure how your media is discovered") }
            item {
                NavigationRow(
                    "Included Directories",
                    "View folders currently in your library",
                    Icons.Default.Folder
                ) { navController.navigate(Screen.LibraryIncludedDirs.route) }
            }
            item {
                ActionRow(
                    "Refresh Library",
                    "Manually trigger a full library refresh",
                    Icons.Default.Refresh
                ) {
                    maintenanceViewModel.reindex(
                        showImages    = showImages,
                        showVideos    = showVideos,
                        showGifs      = showGifs,
                        excludedPaths = excludedFolders
                    )
                }
            }
            item {
                NavigationRow(
                    "Excluded Folders",
                    if (excludedFolders.isEmpty()) "No folders excluded"
                    else "${excludedFolders.size} folder(s) excluded",
                    Icons.Default.VisibilityOff
                ) { navController.navigate(Screen.LibraryExcludedFolders.route) }
            }
            item {
                ToggleRow(
                    "Show Hidden Folders",
                    "Display system folders and dotfiles",
                    checked = showHidden,
                    onCheckedChange = { themeViewModel.setShowHidden(it) }
                )
            }
            item { SettingSectionHeader("Media types", "Choose what appears in your gallery") }
            item {
                NavigationRow(
                    "Filter Media Types",
                    buildString {
                        val on = listOf("Images" to showImages, "Videos" to showVideos, "GIFs" to showGifs)
                            .filter { it.second }.map { it.first }
                        if (on.isEmpty()) append("Nothing shown")
                        else append(on.joinToString(", "))
                        append(" visible")
                    },
                    Icons.Default.FilterList
                ) { navController.navigate(Screen.LibraryFilterTypes.route) }
            }
        }
    }
}

// ─── Library sub-screens ─────────────────────────────────────────────────────

@Composable
fun IncludedDirectoriesScreen(navController: NavController) {
    val activity = LocalContext.current as ComponentActivity
    val app = activity.application as PandoraApp
    val albumDao = remember { app.database.albumDao() }
    val albums by albumDao.getAllAlbumsFlow().collectAsState(initial = emptyList())

    SettingsSubScreen("Included Directories", navController) {
        if (albums.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No folders found. Run a library refresh to discover media.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 20.dp, top = 4.dp)) {
                item {
                    SettingSectionHeader(
                        "Discovered Folders",
                        "${albums.size} folder(s) in your library — add paths to Excluded Folders to hide them"
                    )
                }
                items(albums) { album ->
                    LibraryFolderRow(
                        title    = album.name,
                        subtitle = album.path ?: "Unknown path",
                        info     = "${album.mediaCount} items"
                    )
                }
            }
        }
    }
}

@Composable
fun ExcludedFoldersScreen(navController: NavController) {
    val activity = LocalContext.current as ComponentActivity
    val app = activity.application as PandoraApp
    val themeViewModel: ThemeViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = ThemeViewModelFactory(app.database.userPreferenceDao())
    )
    val maintenanceViewModel: MaintenanceViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = MaintenanceViewModelFactory(app.repository)
    )

    val excludedFolders by themeViewModel.excludedFolders.collectAsState()
    val showImages      by themeViewModel.showImages.collectAsState()
    val showVideos      by themeViewModel.showVideos.collectAsState()
    val showGifs        by themeViewModel.showGifs.collectAsState()

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val path = safUriToFilePath(uri)
            if (path != null) {
                themeViewModel.addExcludedFolder(path)
                maintenanceViewModel.reindex(
                    showImages    = showImages,
                    showVideos    = showVideos,
                    showGifs      = showGifs,
                    excludedPaths = excludedFolders + path
                )
            }
        }
    }

    SettingsSubScreen("Excluded Folders", navController) {
        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp, top = 4.dp)) {
            item {
                SettingSectionHeader(
                    "Excluded Paths",
                    "Media in these folders will be hidden from your library. Changes trigger a re-scan."
                )
            }
            if (excludedFolders.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text(
                            "No folders excluded. Tap \"Choose Folder\" to add one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(excludedFolders.toList()) { path ->
                    ExcludedFolderRow(
                        path = path,
                        onRemove = {
                            themeViewModel.removeExcludedFolder(path)
                            maintenanceViewModel.reindex(
                                showImages    = showImages,
                                showVideos    = showVideos,
                                showGifs      = showGifs,
                                excludedPaths = excludedFolders - path
                            )
                        }
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(onClick = { folderPickerLauncher.launch(null) }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Choose Folder")
                    }
                }
            }
        }
    }
}

/** Converts a SAF tree URI (from [ActivityResultContracts.OpenDocumentTree]) to an
 *  absolute file-system path, e.g. "primary:DCIM/Camera" → "/storage/emulated/0/DCIM/Camera".
 *  Returns null if the URI cannot be resolved.
 */
private fun safUriToFilePath(uri: Uri): String? {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(uri)  // e.g. "primary:DCIM/Camera"
        val colonIdx = docId.indexOf(':')
        if (colonIdx == -1) return null
        val volumeId   = docId.substring(0, colonIdx)
        val relativePath = docId.substring(colonIdx + 1)
        val root = if (volumeId.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volumeId"
        }
        if (relativePath.isEmpty()) root else "$root/$relativePath"
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun ExcludedFolderRow(path: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Default.FolderOff,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text     = path,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Remove",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    )
}

@Composable
fun FilterMediaTypesScreen(navController: NavController) {
    val activity = LocalContext.current as ComponentActivity
    val app = activity.application as PandoraApp
    val themeViewModel: ThemeViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = ThemeViewModelFactory(app.database.userPreferenceDao())
    )
    val maintenanceViewModel: MaintenanceViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = MaintenanceViewModelFactory(app.repository)
    )

    val showImages      by themeViewModel.showImages.collectAsState()
    val showVideos      by themeViewModel.showVideos.collectAsState()
    val showGifs        by themeViewModel.showGifs.collectAsState()
    val excludedFolders by themeViewModel.excludedFolders.collectAsState()

    SettingsSubScreen("Filter Media Types", navController) {
        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp, top = 4.dp)) {
            item {
                SettingSectionHeader(
                    "Visible Types",
                    "Toggle which media types appear in your gallery. Changes trigger a re-scan."
                )
            }
            item {
                ToggleRow(
                    title    = "Images",
                    subtitle = "JPEG, PNG, WEBP, HEIC, BMP, and other still photos",
                    checked  = showImages,
                    onCheckedChange = { enabled ->
                        if (!enabled && !showVideos) return@ToggleRow  // prevent all-off
                        themeViewModel.setShowImages(enabled)
                        maintenanceViewModel.reindex(
                            showImages    = enabled,
                            showVideos    = showVideos,
                            showGifs      = showGifs,
                            excludedPaths = excludedFolders
                        )
                    }
                )
            }
            item {
                ToggleRow(
                    title    = "Videos",
                    subtitle = "MP4, MKV, MOV, AVI, WEBM and other video files",
                    checked  = showVideos,
                    onCheckedChange = { enabled ->
                        if (!enabled && !showImages) return@ToggleRow
                        themeViewModel.setShowVideos(enabled)
                        maintenanceViewModel.reindex(
                            showImages    = showImages,
                            showVideos    = enabled,
                            showGifs      = showGifs,
                            excludedPaths = excludedFolders
                        )
                    }
                )
            }
            item {
                ToggleRow(
                    title    = "GIFs",
                    subtitle = "Animated GIF files (subset of Images)",
                    checked  = showGifs,
                    onCheckedChange = { enabled ->
                        themeViewModel.setShowGifs(enabled)
                        maintenanceViewModel.reindex(
                            showImages    = showImages,
                            showVideos    = showVideos,
                            showGifs      = enabled,
                            excludedPaths = excludedFolders
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun LibraryFolderRow(title: String, subtitle: String, info: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = title,
                style    = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color    = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text     = subtitle,
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text  = info,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    )
}

// ─── AI Settings helpers ──────────────────────────────────────────────────────

@Composable
private fun SuspendedModelBanner(categoryName: String, onReset: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "$categoryName model suspended",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    "Repeated failures detected \u2014 indexing paused to prevent crashes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            TextButton(onClick = onReset) {
                Text("Reset", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun PipelineStatRow(stat: com.example.boxpandora.worker.IndexingRunStats) {
    val label = stat.pipeline.replaceFirstChar { it.uppercase() }
    val dateStr = if (stat.lastRunAt > 0L) {
        java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(stat.lastRunAt))
    } else "Never"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f)
            )
            Text(
                dateStr,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            buildString {
                append("${stat.indexedCount} indexed")
                if (stat.skippedCount > 0) append(" \u00b7 ${stat.skippedCount} skipped")
                if (stat.inferenceFailures > 0) append(" \u00b7 ${stat.inferenceFailures} failures")
                if (stat.avgProcessingTimeMs > 0L) append(" \u00b7 ${stat.avgProcessingTimeMs}ms avg")
                if (stat.cancellationCount > 0) append(" \u00b7 ${stat.cancellationCount} cancelled")
                append(" \u00b7 run #${stat.totalRunCount}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
    }
}

// ─── End Library sub-screens ─────────────────────────────────────────────────

@Composable
fun TaggingAISettingsScreen(navController: NavController) {
    val activity = LocalContext.current as ComponentActivity
    val app = activity.application as PandoraApp
    val aiViewModel: com.example.boxpandora.ui.settings.viewmodel.AiSettingsViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = com.example.boxpandora.ui.settings.viewmodel.AiSettingsViewModelFactory(
            repository   = app.aiSettingsRepository,
            database     = app.database,
            statsStore   = app.indexingStatsStore,
            modelManager = app.modelManager,
            appContext   = app
        )
    )
    val settings       by aiViewModel.settings.collectAsState()
    val indexingStats  by aiViewModel.indexingStats.collectAsState()
    val suspendedModels by aiViewModel.suspendedModels.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        aiViewModel.refreshStats()
        aiViewModel.refreshModelHealth()
    }

    // Confirmation dialogs for destructive actions
    var showClearConfirm    by remember { mutableStateOf(false) }
    var showFullRescanConfirm by remember { mutableStateOf(false) }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear All AI Data?") },
            text  = {
                Text(
                    "This will delete all scene embeddings, tag prototypes, tag suggestions, " +
                    "detected faces, face embeddings, and face clusters. Your manually applied " +
                    "tags and rejections are not affected. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    aiViewModel.clearAllAiData()
                    scope.launch { snackbarHostState.showSnackbar("AI data cleared") }
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showFullRescanConfirm) {
        AlertDialog(
            onDismissRequest = { showFullRescanConfirm = false },
            title = { Text("Full AI Rescan?") },
            text  = {
                Text(
                    "This will clear all AI data and re-run every pipeline stage from scratch. " +
                    "Processing will take several minutes. Your manually applied tags are not affected."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showFullRescanConfirm = false
                    aiViewModel.fullAiRescan()
                    scope.launch { snackbarHostState.showSnackbar("Full AI rescan queued") }
                }) { Text("Rescan", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showFullRescanConfirm = false }) { Text("Cancel") }
            }
        )
    }

    SettingsSubScreen("Tagging & AI", navController, snackbarHostState) {
        val runStats = indexingStats.filter { it.totalRunCount > 0 }
        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp, top = 4.dp)) {

            // ── Model suspension warnings ──────────────────────────────────
            items(suspendedModels, key = { it.id }) { category ->
                SuspendedModelBanner(
                    categoryName = category.displayName,
                    onReset = {
                        aiViewModel.clearModelSuspension(category)
                        scope.launch {
                            snackbarHostState.showSnackbar("Failure counter reset for ${category.displayName}")
                        }
                    }
                )
            }

            // ── General AI ────────────────────────────────────────────────
            item {
                SettingSectionHeader(
                    "General AI",
                    "Core controls for what the AI analyses and how it runs"
                )
            }

            item {
                NavigationRow(
                    title = "Model Management",
                    subtitle = "Download, activate, and verify AI model files",
                    icon = Icons.Default.Storage
                ) { navController.navigate(com.example.boxpandora.ui.main.Screen.ModelManagement.route) }
            }

            item {
                ToggleRow(
                    title = "Enable Scene Suggestions",
                    subtitle = "Analyse image content to propose tags automatically",
                    checked = settings.sceneTaggingEnabled,
                    onCheckedChange = { aiViewModel.setSceneTaggingEnabled(it) }
                )
            }

            item {
                ToggleRow(
                    title = "Enable People Suggestions",
                    subtitle = "Detect faces and suggest who appears in your photos",
                    checked = settings.faceProcessingEnabled,
                    onCheckedChange = { aiViewModel.setFaceProcessingEnabled(it) }
                )
            }

            item {
                ToggleRow(
                    title = "Background Indexing",
                    subtitle = "Analyse new media when the device is idle and charging",
                    checked = settings.backgroundIndexingEnabled,
                    onCheckedChange = { aiViewModel.setBackgroundIndexingEnabled(it) }
                )
            }

            item {
                ValueSelectorRow(
                    title = "Suggestion Confidence",
                    value = with(com.example.boxpandora.ml.config.AiSettings.Companion) {
                        settings.confidenceThreshold.toConfidenceLabel()
                    },
                    subtitle = "Minimum confidence required before a suggestion is shown"
                ) {
                    val next = when {
                        settings.confidenceThreshold < 0.35f -> 0.5f
                        settings.confidenceThreshold < 0.65f -> 0.75f
                        else -> 0.2f
                    }
                    aiViewModel.setConfidenceThreshold(next)
                }
            }

            item {
                ToggleRow(
                    title = "Auto-index on Sync",
                    subtitle = "Start scene indexing automatically after each library sync",
                    checked = settings.autoIndexOnSync,
                    onCheckedChange = { aiViewModel.setAutoIndexOnSync(it) }
                )
            }

            item {
                ToggleRow(
                    title = "Wi-Fi Only Downloads",
                    subtitle = "Fetch model files only when on an unmetered connection",
                    checked = settings.wifiOnlyDownloads,
                    onCheckedChange = { aiViewModel.setWifiOnlyDownloads(it) }
                )
            }

            item {
                NavigationRow(
                    title = "Review AI Suggestions",
                    subtitle = "Accept or reject pending tag proposals",
                    icon = Icons.Default.AutoAwesome
                ) { navController.navigate(com.example.boxpandora.ui.main.Screen.AiSuggestions.route) }
            }

            // ── Experimental ──────────────────────────────────────────────
            item {
                SettingSectionHeader(
                    "Experimental",
                    "Features that may use more battery or produce lower-quality results"
                )
            }

            item {
                ToggleRow(
                    title = "People Detection in Videos",
                    subtitle = "Detect and identify faces in video frames — increases battery use",
                    checked = settings.faceDetectionInVideos,
                    onCheckedChange = { aiViewModel.setFaceDetectionInVideos(it) }
                )
            }

            // ── AI Maintenance ────────────────────────────────────────────
            item {
                SettingSectionHeader(
                    "AI Maintenance",
                    "All actions enqueue background workers and return immediately. " +
                    "Requires battery not low + storage not low."
                )
            }

            item {
                ActionRow(
                    title = "Scan New Media",
                    subtitle = "Pick up any media added since the last indexing run",
                    icon = Icons.Default.Refresh
                ) {
                    aiViewModel.scanNewMedia()
                    scope.launch { snackbarHostState.showSnackbar("Media scan queued") }
                }
            }

            item {
                ActionRow(
                    title = "Repair Stale AI Data",
                    subtitle = "Re-run all workers to fill gaps from partial failures",
                    icon = Icons.Default.Build
                ) {
                    aiViewModel.repairStaleAiData()
                    scope.launch { snackbarHostState.showSnackbar("Repair queued") }
                }
            }

            item {
                ActionRow(
                    title = "Rebuild Scene Embeddings",
                    subtitle = "Clear and re-embed all images from scratch",
                    icon = Icons.Default.ImageSearch
                ) {
                    aiViewModel.rebuildSceneEmbeddings()
                    scope.launch { snackbarHostState.showSnackbar("Scene embeddings rebuild queued") }
                }
            }

            item {
                ActionRow(
                    title = "Rebuild Tag Prototypes",
                    subtitle = "Recompute per-tag embedding centroids from confirmed tags",
                    icon = Icons.Default.Category
                ) {
                    aiViewModel.rebuildTagPrototypes()
                    scope.launch { snackbarHostState.showSnackbar("Tag prototypes rebuild queued") }
                }
            }

            item {
                ActionRow(
                    title = "Rebuild Tag Suggestions",
                    subtitle = "Rescore all images against current tag prototypes",
                    icon = Icons.Default.AutoAwesome
                ) {
                    aiViewModel.rebuildTagSuggestions()
                    scope.launch { snackbarHostState.showSnackbar("Tag suggestions rebuild queued") }
                }
            }

            item {
                ActionRow(
                    title = "Re-scan Faces",
                    subtitle = "Clear and re-detect + re-embed all faces",
                    icon = Icons.Default.Face
                ) {
                    aiViewModel.rebuildFaceIndex()
                    scope.launch { snackbarHostState.showSnackbar("Face re-scan queued") }
                }
            }

            item {
                ActionRow(
                    title = "Rebuild People Matching",
                    subtitle = "Rebuild person profiles and regenerate match suggestions",
                    icon = Icons.Default.People
                ) {
                    aiViewModel.rebuildPeopleMatching()
                    scope.launch { snackbarHostState.showSnackbar("People matching rebuild queued") }
                }
            }

            item {
                ActionRow(
                    title = "Full AI Rescan",
                    subtitle = "Clear all AI data and reprocess everything from scratch",
                    icon = Icons.Default.Autorenew,
                    iconColor = MaterialTheme.colorScheme.error
                ) {
                    showFullRescanConfirm = true
                }
            }

            // ── Last Run Statistics ────────────────────────────────────────
            item {
                SettingSectionHeader(
                    "Last Run Statistics",
                    "Per-pipeline summary of the most recent worker run"
                )
            }
            if (runStats.isEmpty()) {
                item {
                    Text(
                        "No indexing runs recorded yet",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(runStats, key = { it.pipeline }) { stat ->
                    PipelineStatRow(stat)
                }
            }

            // ── Danger Zone ───────────────────────────────────────────────
            item { SettingSectionHeader("Danger Zone", "Destructive operations — cannot be undone") }

            item {
                ActionRow(
                    title = "Clear All AI Data",
                    subtitle = "Delete all embeddings, prototypes, suggestions, faces and clusters",
                    icon = Icons.Default.DeleteSweep,
                    iconColor = MaterialTheme.colorScheme.error
                ) {
                    showClearConfirm = true
                }
            }
        }
    }
}

@Composable
fun DisplaySettingsScreen(navController: NavController) {
    val activity = LocalContext.current as ComponentActivity
    val app      = activity.application as PandoraApp
    val themeViewModel: ThemeViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = ThemeViewModelFactory(app.database.userPreferenceDao())
    )

    val themeMode   by themeViewModel.themeMode.collectAsState()
    val sortOrder   by themeViewModel.sortOrder.collectAsState()
    val accentColor by themeViewModel.accentColor.collectAsState()

    var showSortPicker by remember { mutableStateOf(false) }

    if (showSortPicker) {
        SortOrderDialog(
            current  = sortOrder,
            onSelect = { themeViewModel.setSortOrder(it); showSortPicker = false },
            onDismiss = { showSortPicker = false }
        )
    }

    SettingsSubScreen("Display Settings", navController) {
        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp, top = 4.dp)) {

            // ── Sorting ────────────────────────────────────────────────────
            item { SettingSectionHeader("Sorting", "Default sort for folders and media") }
            item {
                ValueSelectorRow(
                    title    = "Sort Order",
                    value    = sortOrder.label(),
                    subtitle = "Applied on app start and in new folders"
                ) { showSortPicker = true }
            }

            // ── Visual Theme ───────────────────────────────────────────────
            item { SettingSectionHeader("Visual Theme", "Personalize the app appearance") }

            // App Theme segment selector
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text     = "APP THEME",
                            style    = MaterialTheme.typography.labelSmall.copy(
                                letterSpacing = 1.1.sp,
                                fontWeight    = FontWeight.SemiBold
                            ),
                            color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        Surface(
                            shape    = RoundedCornerShape(16.dp),
                            color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(4.dp)) {
                                listOf(
                                    ThemeMode.AUTO  to "Auto",
                                    ThemeMode.LIGHT to "Light",
                                    ThemeMode.DARK  to "Dark"
                                ).forEach { (mode, label) ->
                                    val isSelected = themeMode == mode
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(36.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary
                                                else Color.Transparent
                                            )
                                            .clickable { themeViewModel.setThemeMode(mode) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text  = label,
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                                    else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color    = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
                    )
                }
            }

            // Accent color swatches
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        Text(
                            text     = "ACCENT COLOR",
                            style    = MaterialTheme.typography.labelSmall.copy(
                                letterSpacing = 1.1.sp,
                                fontWeight    = FontWeight.SemiBold
                            ),
                            color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.padding(bottom = 14.dp)
                        )
                        AccentColor.values().toList().chunked(5).forEach { rowColors ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                rowColors.forEach { c ->
                                    Box(
                                        modifier         = Modifier.weight(1f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AccentSwatch(
                                            color      = c,
                                            isSelected = accentColor == c,
                                            onClick    = { themeViewModel.setAccentColor(c) }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        Text(
                            text     = accentColor.displayName,
                            style    = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color    = Color(accentColor.colorLong),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color    = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
                    )
                }
            }

            item { ValueSelectorRow("Animation Scale", "1.0x", "Speed of UI transitions") {} }
        }
    }
}

private fun SortOrder.label(): String = when (this) {
    SortOrder.DATE_DESC  -> "Activity"
    SortOrder.DATE_ASC   -> "Date (oldest first)"
    SortOrder.NAME_ASC   -> "Name A–Z"
    SortOrder.COUNT_DESC -> "Most items"
    SortOrder.SIZE_DESC  -> "Largest first"
}

@Composable
private fun SortOrderDialog(
    current: SortOrder,
    onSelect: (SortOrder) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = MaterialTheme.colorScheme.surface,
        title = { Text("Sort Order", style = MaterialTheme.typography.titleMedium) },
        text  = {
            Column {
                listOf(
                    SortOrder.DATE_DESC  to "Activity",
                    SortOrder.DATE_ASC   to "Date (oldest first)",
                    SortOrder.NAME_ASC   to "Name A–Z",
                    SortOrder.COUNT_DESC to "Most items",
                    SortOrder.SIZE_DESC  to "Largest first"
                ).forEach { (order, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(order) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = current == order,
                            onClick  = { onSelect(order) },
                            colors   = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}

@Composable
private fun AccentSwatch(color: AccentColor, isSelected: Boolean, onClick: () -> Unit) {
    val swatchColor = Color(color.colorLong)
    val checkTint   = if (swatchColor.luminance() > 0.5f) Color(0xFF333333) else Color.White
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(swatchColor)
            .then(
                if (isSelected)
                    Modifier.border(3.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f), CircleShape)
                else
                    Modifier
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                imageVector        = Icons.Default.Check,
                contentDescription = null,
                tint               = checkTint,
                modifier           = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun PerformanceSettingsScreen(navController: NavController) {
    val activity = LocalContext.current as ComponentActivity
    val app = activity.application as PandoraApp
    val vm: PerformanceViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = PerformanceViewModelFactory(app.repository)
    )

    val opState by vm.opState.collectAsState()
    val isRunning = opState is PerformanceOpState.Running
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Surface result messages via snackbar, then return to Idle
    LaunchedEffect(opState) {
        when (val s = opState) {
            is PerformanceOpState.Done  -> { snackbarHostState.showSnackbar(s.message); vm.clearResult() }
            is PerformanceOpState.Error -> { snackbarHostState.showSnackbar(s.message);  vm.clearResult() }
            else -> {}
        }
    }

    SettingsSubScreen("Performance", navController, snackbarHostState) {
        // Progress bar spans the full width while any operation is in flight
        if (isRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp, top = 4.dp)) {
            item { SettingSectionHeader("Storage Maintenance", "Keep the app running smoothly") }
            item {
                ActionRow(
                    title     = "Clear Thumbnail Cache",
                    subtitle  = if (isRunning && opState is PerformanceOpState.Running &&
                                    (opState as PerformanceOpState.Running).label.contains("cache", ignoreCase = true))
                                    "Working…" else "Removes cached previews to free up space",
                    icon      = Icons.Default.DeleteSweep,
                    iconColor = MaterialTheme.colorScheme.error
                ) { if (!isRunning) vm.clearThumbnailCache() }
            }
            item {
                ActionRow(
                    title    = "Optimize Database",
                    subtitle = if (isRunning && opState is PerformanceOpState.Running &&
                                   (opState as PerformanceOpState.Running).label.contains("Optimis", ignoreCase = true))
                                   "Working…" else "Vacuum and rebuild indexes for better speed",
                    icon     = Icons.Default.Storage
                ) { if (!isRunning) vm.optimizeDatabase() }
            }

            item { SettingSectionHeader("Advanced Cleanup", "Deep maintenance tasks") }
            item {
                ActionRow(
                    title    = "Run Smart Clean-up",
                    subtitle = if (isRunning && opState is PerformanceOpState.Running &&
                                   (opState as PerformanceOpState.Running).label.contains("clean", ignoreCase = true))
                                   "Working…" else "Removes orphan tags and broken references",
                    icon     = Icons.Default.CleaningServices
                ) { if (!isRunning) vm.runSmartCleanup() }
            }
        }
    }
}

@Composable
fun PrivacySettingsScreen(navController: NavController) {
    val activity = LocalContext.current as ComponentActivity
    val app = activity.application as PandoraApp
    val vm: AppLockViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = AppLockViewModelFactory(app.database.userPreferenceDao())
    )
    val settings by vm.settings.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showMethodSheet by remember { mutableStateOf(false) }
    var showTimeoutSheet by remember { mutableStateOf(false) }
    var showSetPinDialog by remember { mutableStateOf(false) }
    var showChangePinDialog by remember { mutableStateOf(false) }
    var showRemoveConfirmation by remember { mutableStateOf(false) }
    var showVerifyPinRemoval by remember { mutableStateOf(false) }
    var removePinError by remember { mutableStateOf<String?>(null) }
    var pendingCredentialAction by remember { mutableStateOf<PrivacyCredentialAction?>(null) }

    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingCredentialAction
        pendingCredentialAction = null
        if (result.resultCode == Activity.RESULT_OK) {
            when (action) {
                PrivacyCredentialAction.ENABLE_OR_SWITCH -> vm.enableDeviceCredentialLock()
                PrivacyCredentialAction.REMOVE -> vm.disableLock()
                null -> Unit
            }
        }
    }

    SettingsSubScreen("Privacy", navController, snackbarHostState) {
        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp, top = 4.dp)) {
            item { SettingSectionHeader("App Lock", "Protect Pandora with your phone lock or a private 4-digit passcode") }
            item {
                ToggleRow(
                    title = "Enable app lock",
                    subtitle = if (settings.isEnabled) {
                        "Currently using ${settings.statusLabel.lowercase()}"
                    } else {
                        "Require authentication before opening Pandora"
                    },
                    checked = settings.isEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            showMethodSheet = true
                        } else {
                            showRemoveConfirmation = true
                        }
                    }
                )
            }
            item {
                ValueSelectorRow(
                    title = "Unlock method",
                    value = if (settings.isEnabled) settings.statusLabel else "Not configured",
                    subtitle = "Choose between phone lock and a custom passcode",
                    onClick = { showMethodSheet = true }
                )
            }
            if (settings.isEnabled) {
                item {
                    ValueSelectorRow(
                        title = "Re-lock after",
                        value = settings.timeout.displayName,
                        subtitle = "How long Pandora stays unlocked after backgrounding",
                        onClick = { showTimeoutSheet = true }
                    )
                }
                if (settings.mode == AppLockMode.PIN) {
                    item {
                        ActionRow(
                            title = "Change passcode",
                            subtitle = "Replace the current 4-digit passcode",
                            icon = Icons.Default.Edit,
                            onClick = { showChangePinDialog = true }
                        )
                    }
                }
                item {
                    ActionRow(
                        title = "Remove app lock",
                        subtitle = if (settings.mode == AppLockMode.PIN) {
                            "Requires your current 4-digit passcode"
                        } else {
                            "Requires your phone lock"
                        },
                        icon = Icons.Default.LockOpen,
                        iconColor = MaterialTheme.colorScheme.error,
                        onClick = { showRemoveConfirmation = true }
                    )
                }
            }

            item { SettingSectionHeader("Content Protection", "Privacy controls for private media and AI processing") }
            item { NavigationRow("Sensitive Tags", "Manage tags that mark items as private", Icons.Default.NoEncryption) {} }
            item { NavigationRow("Excluded AI Paths", "Prevent AI from scanning specific folders", Icons.Default.PsychologyAlt) {} }
        }
    }

    if (showMethodSheet) {
        AppLockModeSheet(
            selectedMode = settings.mode,
            onDismiss = { showMethodSheet = false },
            onSelect = { mode ->
                showMethodSheet = false
                when (mode) {
                    AppLockMode.PIN -> showSetPinDialog = true
                    AppLockMode.DEVICE_CREDENTIAL -> {
                        val intent = createDeviceCredentialIntent(
                            context = activity,
                            title = "Confirm phone lock",
                            description = "Use your phone lock to secure Pandora."
                        )
                        if (intent == null) {
                            pendingCredentialAction = null
                            scope.launch {
                                snackbarHostState.showSnackbar("Set up a phone screen lock in system settings first.")
                            }
                        } else {
                            pendingCredentialAction = PrivacyCredentialAction.ENABLE_OR_SWITCH
                            credentialLauncher.launch(intent)
                        }
                    }
                    AppLockMode.NONE -> Unit
                }
            }
        )
    }

    if (showTimeoutSheet) {
        AppLockTimeoutSheet(
            selectedTimeout = settings.timeout,
            onDismiss = { showTimeoutSheet = false },
            onSelect = {
                vm.setLockTimeout(it)
                showTimeoutSheet = false
            }
        )
    }

    if (showSetPinDialog) {
        AppPasscodeSetupDialog(
            title = if (settings.mode == AppLockMode.PIN) "Change passcode" else "Set passcode",
            subtitle = "Create a 4-digit passcode stored in Pandora's database-backed settings.",
            confirmLabel = if (settings.mode == AppLockMode.PIN) "Save passcode" else "Enable passcode",
            onDismiss = { showSetPinDialog = false },
            onConfirm = { pin ->
                vm.enablePinLock(pin)
                showSetPinDialog = false
            }
        )
    }

    if (showChangePinDialog) {
        AppPasscodeSetupDialog(
            title = "Change passcode",
            subtitle = "Set a new 4-digit passcode for Pandora.",
            confirmLabel = "Save passcode",
            onDismiss = { showChangePinDialog = false },
            onConfirm = { pin ->
                vm.enablePinLock(pin)
                showChangePinDialog = false
            }
        )
    }

    if (showRemoveConfirmation && settings.isEnabled) {
        AppLockRemovalConfirmationDialog(
            mode = settings.mode,
            onDismiss = { showRemoveConfirmation = false },
            onConfirm = {
                showRemoveConfirmation = false
                if (settings.mode == AppLockMode.PIN) {
                    removePinError = null
                    showVerifyPinRemoval = true
                } else {
                    val intent = createDeviceCredentialIntent(
                        context = activity,
                        title = "Remove app lock",
                        description = "Use your phone lock to remove Pandora app lock."
                    )
                    if (intent == null) {
                        pendingCredentialAction = null
                        scope.launch {
                            snackbarHostState.showSnackbar("Set up a phone screen lock in system settings first.")
                        }
                    } else {
                        pendingCredentialAction = PrivacyCredentialAction.REMOVE
                        credentialLauncher.launch(intent)
                    }
                }
            }
        )
    }

    if (showVerifyPinRemoval) {
        AppPasscodeVerificationDialog(
            title = "Confirm passcode",
            subtitle = "Enter the current 4-digit passcode to remove Pandora app lock.",
            confirmLabel = "Remove app lock",
            errorMessage = removePinError,
            onDismiss = {
                showVerifyPinRemoval = false
                removePinError = null
            },
            onConfirm = { pin ->
                if (vm.verifyPin(pin)) {
                    vm.disableLock()
                    showVerifyPinRemoval = false
                    removePinError = null
                } else {
                    removePinError = "Incorrect passcode."
                }
            }
        )
    }
}

private enum class PrivacyCredentialAction {
    ENABLE_OR_SWITCH,
    REMOVE
}

@Composable
fun BackupDataSettingsScreen(navController: NavController) {
    val activity = LocalContext.current as ComponentActivity
    val app      = activity.application as PandoraApp
    val vm: BackupDataViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = BackupDataViewModelFactory(
            context       = activity,
            database      = app.database,
            tagRepository = app.repository.tagRepository
        )
    )

    val opState  by vm.opState.collectAsState()
    val isRunning = opState is BackupOpState.Running
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(opState) {
        when (val s = opState) {
            is BackupOpState.Done  -> { snackbar.showSnackbar(s.message); vm.clearResult() }
            is BackupOpState.Error -> { snackbar.showSnackbar(s.message); vm.clearResult() }
            else -> {}
        }
    }

    // ── SAF launchers (must be declared outside click handlers) ──────────────
    val exportTagsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { vm.exportTagMetadata(it) } }

    val importTagsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importTagMetadata(it) } }

    val backupDbLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let { vm.backupDatabase(it) } }

    val restoreDbLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.restoreDatabase(it) } }

    SettingsSubScreen("Backup & Data", navController, snackbar) {
        if (isRunning) {
            val label = (opState as? BackupOpState.Running)?.label ?: "Working…"
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Text(
                text     = label,
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
            )
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp, top = 4.dp)) {

            // ── Portability ────────────────────────────────────────────────
            item { SettingSectionHeader("Portability", "Export or import your tag metadata") }
            item {
                ActionRow(
                    title    = "Export Tag Metadata",
                    subtitle = "Save all tag assignments to a JSON file",
                    icon     = Icons.Default.Upload
                ) { if (!isRunning) exportTagsLauncher.launch(vm.suggestedTagsExportName()) }
            }
            item {
                ActionRow(
                    title    = "Import Tag Metadata",
                    subtitle = "Restore tag assignments from a previously exported file",
                    icon     = Icons.Default.Download
                ) { if (!isRunning) importTagsLauncher.launch(arrayOf("application/json", "*/*")) }
            }

            // ── Full Backups ───────────────────────────────────────────────
            item { SettingSectionHeader("Full Backups", "Back up or restore the entire database") }
            item {
                ActionRow(
                    title    = "Backup Database",
                    subtitle = "Copy the full database to a file you choose",
                    icon     = Icons.Default.CloudUpload
                ) { if (!isRunning) backupDbLauncher.launch(vm.suggestedDbBackupName()) }
            }
            item {
                ActionRow(
                    title    = "Restore Database",
                    subtitle = "Replace the database from a backup — app will restart",
                    icon     = Icons.Default.CloudDownload
                ) { if (!isRunning) restoreDbLauncher.launch(arrayOf("application/octet-stream", "*/*")) }
            }
        }
    }
}

@Composable
fun AboutSettingsScreen(navController: NavController) {
    val activity = LocalContext.current as ComponentActivity
    val app = activity.application as PandoraApp
    val vm: AboutViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = AboutViewModelFactory(activity, app.database, app.thumbnailManager)
    )
    val stats by vm.stats.collectAsState()

    SettingsSubScreen("App Information", navController) {
        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp, top = 4.dp)) {

            // ─── Storage ──────────────────────────────────────────────────
            item { SettingSectionHeader("Storage", "Disk space usage") }
            item {
                ActionRow(
                    title    = "Thumbnail Cache",
                    subtitle = if (!stats.isLoaded) "Loading…"
                               else "${formatBytes(stats.cacheSizeBytes)} · ${stats.cacheFileCount} file(s)",
                    icon     = Icons.Default.Dns
                ) {}
            }
            item {
                ActionRow(
                    title    = "Database Size",
                    subtitle = if (!stats.isLoaded) "Loading…"
                               else formatBytes(stats.dbSizeBytes),
                    icon     = Icons.Default.Storage
                ) {}
            }

            // ─── Library stats ────────────────────────────────────────────
            item { SettingSectionHeader("Library", "Your media & tag overview") }
            item {
                ActionRow(
                    title    = "Media Items",
                    subtitle = if (!stats.isLoaded) "Loading…"
                               else "${stats.mediaCount} item(s) indexed",
                    icon     = Icons.Default.Image
                ) {}
            }
            item {
                ActionRow(
                    title    = "Tags Defined",
                    subtitle = if (!stats.isLoaded) "Loading…"
                               else "${stats.tagCount} unique tag(s)",
                    icon     = Icons.AutoMirrored.Filled.Label
                ) {}
            }

            // ─── App info ─────────────────────────────────────────────────
            item { SettingSectionHeader("App", "Technical information") }
            item {
                ActionRow(
                    title    = "Version",
                    subtitle = if (!stats.isLoaded) "Loading…"
                               else "${stats.versionName} (build ${stats.versionCode})",
                    icon     = Icons.Default.Info
                ) {}
            }
            item {
                ActionRow(
                    title    = "Open Source Licenses",
                    subtitle = "Third-party library attributions",
                    icon     = Icons.Default.Description
                ) {}
            }

            // ─── Support ──────────────────────────────────────────────────
            item { SettingSectionHeader("Support", "Get in touch") }
            item {
                ActionRow(
                    title    = "Report an Issue",
                    subtitle = "Send feedback to the developers",
                    icon     = Icons.Default.Email
                ) {}
            }
        }
    }
}

/** Format a byte count into a human-readable string (KB / MB / GB). */
private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024L               -> "$bytes B"
    bytes < 1_048_576L           -> "${ "%.1f".format(bytes / 1_024f) } KB"
    bytes < 1_073_741_824L       -> "${ "%.1f".format(bytes / 1_048_576f) } MB"
    else                         -> "${ "%.2f".format(bytes / 1_073_741_824f) } GB"
}
