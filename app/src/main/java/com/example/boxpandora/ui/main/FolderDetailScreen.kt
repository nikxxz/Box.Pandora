package com.example.boxpandora.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.ui.common.AppHeader
import com.example.boxpandora.ui.common.DeleteConfirmationDialog
import com.example.boxpandora.ui.common.FolderSelectorDialog
import com.example.boxpandora.ui.common.RenameDialog
import com.example.boxpandora.ui.components.grid.MediaThumbnail
import com.example.boxpandora.ui.main.viewmodel.FolderDetailViewModel
import com.example.boxpandora.ui.main.viewmodel.FolderDetailViewModelFactory
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FolderDetailScreen(
    albumId: Long,
    albumName: String,
    showHidden: Boolean = false,
    onBackClick: () -> Unit,
    onMediaClick: (List<MediaItem>, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val app = context.applicationContext as PandoraApp
    val viewModel: FolderDetailViewModel = viewModel(
        factory = FolderDetailViewModelFactory(app.repository, albumId)
    )
    val pagingItems = viewModel.pagedMediaItems.collectAsLazyPagingItems()
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
            val showHideLabel = remember(selectedUris, pagingItems.itemCount) {
                // Heuristic: check currently loaded items for hidden state
                val selectedItemsInSnapshot = pagingItems.itemSnapshotList.items.filter { it.uri in selectedUris }
                if (selectedItemsInSnapshot.isNotEmpty() && selectedItemsInSnapshot.all { it.isHidden == 1 }) "Show" else "Hide"
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
                        "share" -> {
                            val items = pagingItems.itemSnapshotList.items.filter { it.uri in selectedUris }
                            shareMediaItems(context, items)
                            viewModel.clearSelection()
                        }
                        "open_with" -> {
                            val item = pagingItems.itemSnapshotList.items.find { it.uri in selectedUris }
                            item?.let { openMediaItem(context, it) }
                            viewModel.clearSelection()
                        }
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
                items(
                    count = pagingItems.itemCount,
                    key = pagingItems.itemKey { it.uri }
                ) { index ->
                    val item = pagingItems[index]
                    if (item != null) {
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
                                    onMediaClick(pagingItems.itemSnapshotList.items.filterNotNull(), index)
                                }
                            },
                            onLongPress = {
                                viewModel.toggleSelection(item.uri)
                            },
                            modifier = Modifier
                        )
                    } else {
                        // Placeholder
                        Box(modifier = Modifier.aspectRatio(1f).padding(1.dp))
                    }
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
        val item = pagingItems.itemSnapshotList.items.find { it.uri in selectedUris }
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

private fun shareMediaItems(context: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    
    val intent = if (items.size == 1) {
        val item = items[0]
        val uri = getUriForFile(context, item) ?: return
        Intent(Intent.ACTION_SEND).apply {
            type = if (item.mediaType == "video") "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        val uris = ArrayList<Uri>(items.mapNotNull { getUriForFile(context, it) })
        if (uris.isEmpty()) return
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    context.startActivity(Intent.createChooser(intent, "Share via"))
}

private fun openMediaItem(context: Context, item: MediaItem) {
    val uri = getUriForFile(context, item) ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, if (item.mediaType == "video") "video/*" else "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Open with"))
}

private fun getUriForFile(context: Context, item: MediaItem): Uri? {
    val file = item.filePath?.let { File(it) } ?: return null
    return try {
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    } catch (e: Exception) {
        Uri.fromFile(file)
    }
}
