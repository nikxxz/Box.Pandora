package com.example.boxpandora.ui.main

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.ui.common.*
import com.example.boxpandora.ui.components.grid.FolderCard
import com.example.boxpandora.ui.main.viewmodel.FoldersViewModel
import com.example.boxpandora.ui.main.viewmodel.FoldersViewModelFactory
import com.example.boxpandora.ui.theme.PandoraDimensions

private fun hasStorageAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FoldersScreen(
    showHidden: Boolean,
    onFolderClick: (Album) -> Unit,
    onMediaClick: (List<MediaItem>, Int) -> Unit,
    onOpenDrawer: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PandoraApp
    val viewModel: FoldersViewModel = viewModel(
        factory = FoldersViewModelFactory(app.repository)
    )
    val albums by viewModel.albums.collectAsState()
    val selectedIds by viewModel.selectedAlbumIds.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()

    val isSearchOpen by viewModel.isSearchOpen.collectAsState()
    val searchParams by viewModel.searchParams.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(showHidden) {
        viewModel.setShowHidden(showHidden)
    }

    if (isSelectionMode) {
        BackHandler {
            viewModel.clearSelection()
        }
    }

    if (isSearchOpen) {
        BackHandler {
            viewModel.closeSearch()
        }
    }

    var permissionGranted by remember { mutableStateOf(hasStorageAccess()) }

    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        permissionGranted = hasStorageAccess()
        if (permissionGranted) viewModel.refresh()
    }

    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) viewModel.refresh()
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                legacyStorageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        } else {
            viewModel.refresh()
        }
    }

    if (!permissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        AllFilesPermissionGate(
            onGrant = {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                allFilesLauncher.launch(intent)
            }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val showHideLabel = remember(selectedIds, albums) {
            val selectedAlbums = albums.filter { it.id in selectedIds }
            if (selectedAlbums.isNotEmpty() && selectedAlbums.all { it.isHidden }) "Show" else "Hide"
        }

        AppHeader(
            onMenuClick = onOpenDrawer,
            onSearchClick = { viewModel.openSearch() },
            selectionCount = selectedIds.size,
            onClearSelection = { viewModel.clearSelection() },
            showHideOption = showHideLabel,
            allowOpenWith = false,
            onActionClick = { action ->
                when (action) {
                    "delete" -> showDeleteDialog = true
                    "rename" -> showRenameDialog = true
                    "copy" -> showCopyDialog = true
                    "move" -> showMoveDialog = true
                    "hide_show" -> viewModel.toggleHiddenForSelected()
                    else -> viewModel.clearSelection()
                }
            }
        )

        AnimatedVisibility(
            visible = isSearchOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            MediaSearchPanel(
                params = searchParams,
                onParamsChange = { viewModel.updateSearchParams(it) },
                onClose = { viewModel.closeSearch() }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (isSearchOpen) {
                SearchResultsGrid(
                    results = searchResults,
                    isLoading = isSearching,
                    onPress = { item ->
                        onMediaClick(searchResults, searchResults.indexOf(item))
                    },
                    onLongPress = { /* Search selection? Not yet implemented in VM */ }
                )
            } else {
                AnimatedContent(
                    targetState = albums.isEmpty(),
                    transitionSpec = {
                        fadeIn(animationSpec = tween(220, delayMillis = 90)) togetherWith
                                fadeOut(animationSpec = tween(90))
                    },
                    label = "FoldersContentTransition"
                ) { isLoading ->
                    if (isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(
                                horizontal = PandoraDimensions.gridPadding,
                                vertical = 8.dp
                            ),
                            horizontalArrangement = Arrangement.spacedBy(PandoraDimensions.gridGap),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(albums, key = { it.id }) { album ->
                                FolderCard(
                                    album = album,
                                    isSelected = album.id in selectedIds,
                                    onPress = {
                                        if (isSelectionMode) {
                                            viewModel.toggleSelection(album.id)
                                        } else {
                                            onFolderClick(album)
                                        }
                                    },
                                    onLongPress = {
                                        viewModel.toggleSelection(album.id)
                                    },
                                    modifier = Modifier.animateItemPlacement()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        DeleteConfirmationDialog(
            count = selectedIds.size,
            isFolder = true,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                viewModel.deleteSelectedAlbums()
                showDeleteDialog = false
            }
        )
    }

    if (showRenameDialog) {
        val album = albums.find { it.id in selectedIds }
        album?.let {
            RenameDialog(
                initialName = it.name,
                onDismiss = { showRenameDialog = false },
                onConfirm = { newName ->
                    viewModel.renameSelectedAlbum(newName)
                    showRenameDialog = false
                }
            )
        }
    }

    if (showCopyDialog) {
        FolderSelectorDialog(
            title = "Copy to",
            albums = albums.filter { it.id !in selectedIds },
            onDismiss = { showCopyDialog = false },
            onConfirm = { album ->
                album.path?.let { viewModel.copySelectedAlbums(it) }
                showCopyDialog = false
            }
        )
    }

    if (showMoveDialog) {
        FolderSelectorDialog(
            title = "Move to",
            albums = albums.filter { it.id !in selectedIds },
            onDismiss = { showMoveDialog = false },
            onConfirm = { album ->
                album.path?.let { viewModel.moveSelectedAlbums(it) }
                showMoveDialog = false
            }
        )
    }
}

@Composable
private fun AllFilesPermissionGate(onGrant: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FolderOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )

            Text(
                text = "All Files Access Required",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Pandora needs All Files Access to discover folders " +
                       "that contain a .nomedia file (hidden albums).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Button(
                onClick = onGrant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Settings", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
