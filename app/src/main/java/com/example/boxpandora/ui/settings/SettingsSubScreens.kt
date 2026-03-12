package com.example.boxpandora.ui.settings

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
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
import com.example.boxpandora.ui.main.Screen
import com.example.boxpandora.ui.main.viewmodel.AccentColor
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
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.padding(start = 8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
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
            item { SettingSectionHeader("Library Scanning", "Configure how your media is discovered") }
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
            item { SettingSectionHeader("Media Types", "Choose what appears in your gallery") }
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

// ─── End Library sub-screens ─────────────────────────────────────────────────

@Composable
fun TaggingAISettingsScreen(navController: NavController) {
    SettingsSubScreen("Tagging & AI", navController) {
        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp, top = 4.dp)) {
            item { SettingSectionHeader("AI Analysis", "Automatic categorization of your photos") }
            item { ValueSelectorRow("Suggestion Confidence", "Medium", "Threshold for automatic tag proposals") {} }
            item { 
                var checked by remember { mutableStateOf(true) }
                ToggleRow("Auto-merge Synonyms", "Groups similar tags like 'cat' and 'feline'", checked = checked, onCheckedChange = { checked = it }) 
            }
            item { 
                var checked by remember { mutableStateOf(true) }
                ToggleRow("Discovery Mode", "Suggest tags based on visual similarities", checked = checked, onCheckedChange = { checked = it }) 
            }
            item { 
                var checked by remember { mutableStateOf(true) }
                ToggleRow("Background Tagging", "Analyze new media immediately when added", checked = checked, onCheckedChange = { checked = it }) 
            }
            
            item { SettingSectionHeader("Tag Database", "Maintain your taxonomy") }
            item { NavigationRow("Manage Categories", "Organize tags into custom groups", Icons.Default.Category) {} }
            item { ActionRow("Rebuild Search Index", "Optimize for faster tag searching", Icons.Default.Build) {} }
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
    SettingsSubScreen("Privacy", navController) {
        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp, top = 4.dp)) {
            item { SettingSectionHeader("Content Protection", "Secure your private media") }
            item { NavigationRow("Sensitive Tags", "Manage tags that mark items as private", Icons.Default.NoEncryption) {} }
            item { ValueSelectorRow("App Lock", "Fingerprint / PIN", "Secure access with biometrics") {} }
            item { NavigationRow("Excluded AI Paths", "Prevent AI from scanning specific folders", Icons.Default.PsychologyAlt) {} }
        }
    }
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
