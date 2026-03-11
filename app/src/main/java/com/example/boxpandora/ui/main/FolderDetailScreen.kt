package com.example.boxpandora.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.ui.common.*
import com.example.boxpandora.ui.components.grid.MediaThumbnail
import com.example.boxpandora.ui.main.viewmodel.FolderDetailViewModel
import com.example.boxpandora.ui.main.viewmodel.FolderDetailViewModelFactory
import java.io.File
import kotlin.math.roundToInt

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
        factory = FolderDetailViewModelFactory(app.repository, app.repository.tagRepository, albumId)
    )
    val pagingItems = viewModel.pagedMediaItems.collectAsLazyPagingItems()
    val allAlbums by viewModel.allAlbums.collectAsState()
    val selectedUris by viewModel.selectedUris.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val allTags by viewModel.allTags.collectAsState()

    val isSearchOpen by viewModel.isSearchOpen.collectAsState()
    val searchParams by viewModel.searchParams.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showBulkTagDialog by remember { mutableStateOf(false) }

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

    // Scrolling logic
    val toolbarHeightPx = with(LocalDensity.current) { 80.dp.roundToPx().toFloat() }
    var scrollOffset by remember { mutableStateOf(0f) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (isSearchOpen || isSelectionMode) return Offset.Zero
                
                val delta = available.y
                val newOffset = scrollOffset + delta
                val minOffset = -(toolbarHeightPx * 0.4f)
                
                return if (delta < 0 && scrollOffset > minOffset) {
                    val consumed = if (newOffset < minOffset) minOffset - scrollOffset else delta
                    scrollOffset += consumed
                    Offset(0f, consumed)
                } else if (delta > 0 && scrollOffset < 0) {
                    val consumed = if (newOffset > 0) -scrollOffset else delta
                    scrollOffset += consumed
                    Offset(0f, consumed)
                } else {
                    Offset.Zero
                }
            }
        }
    }

    LaunchedEffect(isSearchOpen, isSelectionMode) {
        scrollOffset = 0f
    }

    val scrollProgress = ((scrollOffset + toolbarHeightPx * 0.4f) / (toolbarHeightPx * 0.4f)).coerceIn(0f, 1f)

    Scaffold(
        topBar = {
            Column(modifier = Modifier.offset { IntOffset(0, scrollOffset.roundToInt()) }) {
                val showHideLabel = remember(selectedUris, pagingItems.itemCount) {
                    val selectedItemsInSnapshot = pagingItems.itemSnapshotList.items.filter { it.uri in selectedUris }
                    if (selectedItemsInSnapshot.isNotEmpty() && selectedItemsInSnapshot.all { it.isHidden == 1 }) "Show" else "Hide"
                }

                val subtitle = "%,d items".format(pagingItems.itemCount)

                AppHeader(
                    title = albumName,
                    subtitle = if (isSelectionMode) null else subtitle,
                    onBackClick = onBackClick,
                    onSearchClick = { viewModel.openSearch() },
                    selectionCount = selectedUris.size,
                    onClearSelection = { viewModel.clearSelection() },
                    showHideOption = showHideLabel,
                    onActionClick = { action ->
                        when (action) {
                            "delete" -> showDeleteDialog = true
                            "rename" -> showRenameDialog = true
                            "copy" -> {
                                viewModel.loadAlbums(); showCopyDialog = true
                            }
                            "move" -> {
                                viewModel.loadAlbums(); showMoveDialog = true
                            }
                            "hide_show" -> viewModel.toggleHiddenForSelected()
                            "tag" -> showBulkTagDialog = true
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
                    },
                    scrollProgress = scrollProgress
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
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.nestedScroll(nestedScrollConnection)
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).offset { 
            val yOffset = if (isSearchOpen || isSelectionMode) 0 else scrollOffset.roundToInt()
            IntOffset(0, yOffset) 
        }) {
            if (isSearchOpen) {
                SearchResultsGrid(
                    results = searchResults,
                    selectedUris = selectedUris,
                    isLoading = isSearching,
                    onPress = { item ->
                        if (isSelectionMode) {
                            viewModel.toggleSelection(item.uri)
                        } else {
                            onMediaClick(searchResults, searchResults.indexOf(item))
                        }
                    },
                    onLongPress = { viewModel.toggleSelection(it.uri) }
                )
            } else {
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
                                uri = item.uri,
                                filePath = item.filePath,
                                mediaType = item.mediaType,
                                duration = item.duration,
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
                            Box(modifier = Modifier
                                .aspectRatio(1f)
                                .padding(1.dp))
                        }
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

    if (showBulkTagDialog) {
        BulkTagDialog(
            allTags = allTags,
            onDismiss = { showBulkTagDialog = false },
            onConfirm = { tags ->
                viewModel.bulkAttachTags(tags)
                showBulkTagDialog = false
            }
        )
    }
}

@Composable
fun BulkTagDialog(
    allTags: List<Tag>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    var input by remember { mutableStateOf("") }
    val selectedTagNames = remember { mutableStateListOf<String>() }
    
    val suggestions = remember(input, allTags) {
        if (input.isBlank()) emptyList()
        else allTags.filter { 
            it.name.contains(input, ignoreCase = true) && 
            !selectedTagNames.contains(it.name)
        }.take(5)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1C1C1E),
        shape = RoundedCornerShape(20.dp),
        title = {
            Text("Bulk Tag", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (selectedTagNames.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        selectedTagNames.forEach { tagName ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color(0xFF48484A))
                                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(tagName, color = Color.White, style = MaterialTheme.typography.labelMedium)
                                IconButton(
                                    onClick = { selectedTagNames.remove(tagName) },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(Icons.Default.Close, null, tint = Color(0xFF8E8E93), modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("New tag…", color = Color(0xFF8E8E93)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.White.copy(alpha = 0.3f),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color.White
                            )
                        )
                        IconButton(
                            onClick = {
                                val tag = input.trim()
                                if (tag.isNotEmpty() && !selectedTagNames.contains(tag)) {
                                    selectedTagNames.add(tag)
                                    input = ""
                                }
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF48484A))
                        ) {
                            Icon(Icons.Default.Add, null, tint = Color.White)
                        }
                    }

                    if (suggestions.isNotEmpty()) {
                        Text("Suggestions", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8E8E93))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            suggestions.forEach { tag ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color.White.copy(alpha = 0.1f))
                                        .clickable { 
                                            selectedTagNames.add(tag.name)
                                            input = ""
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(tag.name, color = Color.White, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedTagNames.toList()) }) { 
                Text("Apply", color = Color.White) 
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { 
                Text("Cancel", color = Color(0xFF8E8E93)) 
            }
        }
    )
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
