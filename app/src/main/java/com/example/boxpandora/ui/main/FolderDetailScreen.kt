package com.example.boxpandora.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.ui.common.AppHeader
import com.example.boxpandora.ui.common.DeleteConfirmationDialog
import com.example.boxpandora.ui.common.FolderSelectorDialog
import com.example.boxpandora.ui.common.RenameDialog
import com.example.boxpandora.ui.components.grid.MediaThumbnail
import com.example.boxpandora.ui.main.viewmodel.FolderDetailViewModel
import com.example.boxpandora.ui.main.viewmodel.FolderDetailViewModelFactory

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FolderDetailScreen(
    albumName: String,
    showHidden: Boolean = false,
    onBackClick: () -> Unit,
    onMediaClick: (List<MediaItem>, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val app = context.applicationContext as PandoraApp
    val viewModel: FolderDetailViewModel = viewModel(
        factory = FolderDetailViewModelFactory(app.repository, albumName)
    )
    val mediaItems by viewModel.mediaItems.collectAsState()
    val allAlbums by viewModel.allAlbums.collectAsState()
    val selectedUris by viewModel.selectedUris.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()

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

    Scaffold(
        topBar = {
            val showHideLabel = remember(selectedUris, mediaItems) {
                val selectedItems = mediaItems.filter { it.uri in selectedUris }
                if (selectedItems.isNotEmpty() && selectedItems.all { it.isHidden == 1 }) "Show" else "Hide"
            }

            AppHeader(
                title = albumName,
                onBackClick = onBackClick,
                onSearchClick = { },
                selectionCount = selectedUris.size,
                onClearSelection = { viewModel.clearSelection() },
                showHideOption = showHideLabel,
                onActionClick = { action ->
                    when (action) {
                        "delete" -> showDeleteDialog = true
                        "rename" -> showRenameDialog = true
                        "copy" -> { viewModel.loadAlbums(); showCopyDialog = true }
                        "move" -> { viewModel.loadAlbums(); showMoveDialog = true }
                        "hide_show" -> viewModel.toggleHiddenForSelected()
                        else -> viewModel.clearSelection()
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(1.dp)
            ) {
                itemsIndexed(mediaItems, key = { _, item -> item.uri }) { index, item ->
                    MediaThumbnail(
                        uri        = item.uri,
                        filePath   = item.filePath,
                        mediaType  = item.mediaType,
                        duration   = item.duration,
                        isFavorite = item.isFavorite,
                        isSelected = item.uri in selectedUris,
                        onPress = {
                            if (isSelectionMode) {
                                viewModel.toggleSelection(item.uri)
                            } else {
                                onMediaClick(mediaItems, index)
                            }
                        },
                        onLongPress = {
                            viewModel.toggleSelection(item.uri)
                        },
                        modifier = Modifier
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        DeleteConfirmationDialog(
            count = selectedUris.size,
            isFolder = false,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                viewModel.deleteSelectedItems()
                showDeleteDialog = false
            }
        )
    }

    if (showRenameDialog) {
        val item = mediaItems.find { it.uri in selectedUris }
        item?.let {
            RenameDialog(
                initialName = it.filename.substringBeforeLast("."),
                onDismiss = { showRenameDialog = false },
                onConfirm = { newName ->
                    viewModel.renameSelectedItem(newName)
                    showRenameDialog = false
                }
            )
        }
    }

    if (showCopyDialog) {
        FolderSelectorDialog(
            title = "Copy to",
            albums = allAlbums,
            onDismiss = { showCopyDialog = false },
            onConfirm = { album ->
                album.path?.let { viewModel.copySelectedItems(it) }
                showCopyDialog = false
            }
        )
    }

    if (showMoveDialog) {
        FolderSelectorDialog(
            title = "Move to",
            albums = allAlbums,
            onDismiss = { showMoveDialog = false },
            onConfirm = { album ->
                album.path?.let { viewModel.moveSelectedItems(it) }
                showMoveDialog = false
            }
        )
    }
}
