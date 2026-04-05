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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.data.manager.FileConflictResolution
import com.example.boxpandora.data.repository.RichSuggestion
import com.example.boxpandora.ui.common.*
import com.example.boxpandora.ui.common.components.shouldShowSuggestion
import com.example.boxpandora.ui.components.grid.DynamicMediaGrid
import com.example.boxpandora.ui.components.grid.MediaThumbnail
import com.example.boxpandora.ui.main.viewmodel.FolderDetailViewModel
import com.example.boxpandora.ui.main.viewmodel.FolderDetailViewModelFactory
import com.example.boxpandora.ui.theme.boxPandoraModalTokens
import com.example.boxpandora.ui.theme.inlineRevealEnter
import com.example.boxpandora.ui.theme.inlineRevealExit
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
    val mediaItems by viewModel.mediaItems.collectAsState()
    val allAlbums by viewModel.allAlbums.collectAsState()
    val selectedUris by viewModel.selectedUris.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val bulkSuggestions by viewModel.suggestionObjectsForSelected.collectAsState()
    val bulkSuggestionsLoading by viewModel.bulkSuggestionsLoading.collectAsState()

    val isSearchOpen by viewModel.isSearchOpen.collectAsState()
    val searchParams by viewModel.searchParams.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val pendingConflict by viewModel.pendingConflict.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showBulkTagDialog by remember { mutableStateOf(false) }
    var showPropertiesSheet by remember { mutableStateOf(false) }
    var showFilenames by remember { mutableStateOf(false) }

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
                val subtitle = "%,d items".format(pagingItems.itemCount)

                AppHeader(
                    title = albumName,
                    subtitle = if (isSelectionMode) null else subtitle,
                    onBackClick = onBackClick,
                    onSearchClick = { viewModel.openSearch() },
                    selectionCount = selectedUris.size,
                    onClearSelection = { viewModel.clearSelection() },
                    canSelectAll = if (isSearchOpen) {
                        searchResults.isNotEmpty() && selectedUris.size < searchResults.size
                    } else {
                        mediaItems.isNotEmpty() && selectedUris.size < mediaItems.size
                    },
                    onSelectAll = {
                        val visibleItems = if (isSearchOpen) searchResults else mediaItems
                        viewModel.selectItems(visibleItems.map { it.uri })
                    },
                    showFilenamesButton = true,
                    showFilenames = showFilenames,
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
                            "properties" -> showPropertiesSheet = true
                            "toggle_filenames" -> showFilenames = !showFilenames
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
                    enter = inlineRevealEnter(),
                    exit = inlineRevealExit()
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (isSearchOpen) {
                SearchResultsGrid(
                    results = searchResults,
                    selectedUris = selectedUris,
                    isLoading = isSearching,
                    showFilenames = showFilenames,
                    onPress = { item ->
                        if (isSelectionMode) {
                            viewModel.toggleSelection(item.uri)
                        } else {
                            onMediaClick(searchResults, searchResults.indexOf(item))
                        }
                    },
                    onLongPress = { viewModel.toggleSelection(it.uri) }
                )
            } else if (mediaItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(52.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                        )
                        Text(
                            text = "This folder is empty",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "Add photos or videos to see them here",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        )
                    }
                }
            } else {
                DynamicMediaGrid(
                    items         = mediaItems,
                    selectedUris  = selectedUris,
                    modifier      = Modifier.fillMaxSize(),
                    showFilenames = showFilenames,
                    onPress       = { item, index ->
                        if (isSelectionMode) viewModel.toggleSelection(item.uri)
                        else onMediaClick(mediaItems, index)
                    },
                    onLongPress   = { item -> viewModel.toggleSelection(item.uri) }
                )
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
            suggestionObjects = bulkSuggestions,
            suggestionsLoading = bulkSuggestionsLoading,
            onRefreshSuggestions = { viewModel.refreshBulkSuggestions() },
            onDismiss = { showBulkTagDialog = false },
            onConfirm = { tags ->
                viewModel.bulkAttachTags(tags)
                showBulkTagDialog = false
            }
        )
    }

    if (showPropertiesSheet) {
        val item = (if (isSearchOpen) searchResults else mediaItems).find { it.uri in selectedUris }
        item?.let {
            MediaPropertiesSheet(
                item = it,
                onDismiss = { showPropertiesSheet = false }
            )
        }
    }

    pendingConflict?.let { conflict ->
        FileConflictDialog(
            conflict = conflict,
            onResolve = { resolution, applyToAll ->
                viewModel.resolveConflict(resolution, applyToAll)
            },
            onDismiss = {
                viewModel.resolveConflict(FileConflictResolution.SKIP, false)
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BulkTagDialog(
    allTags: List<Tag>,
    suggestionObjects: List<RichSuggestion> = emptyList(),
    suggestionsLoading: Boolean = false,
    onRefreshSuggestions: () -> Unit = {},
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    var input by remember { mutableStateOf("") }
    val selectedTagNames = remember { mutableStateListOf<String>() }
    val tokens = boxPandoraModalTokens()

    val normalizedInput = input.trim()
    val selectedSnapshot = selectedTagNames.toList()
    val suggestions = remember(normalizedInput, allTags, selectedSnapshot) {
        val available = allTags.filterNot { selectedTagNames.contains(it.name) }
        if (normalizedInput.isBlank()) {
            available
                .sortedWith(compareByDescending<Tag> { it.usageCount }.thenBy { it.name.lowercase() })
                .take(12)
        } else {
            available
                .filter { it.name.contains(normalizedInput, ignoreCase = true) }
                .sortedWith(
                    compareBy<Tag> { !it.name.startsWith(normalizedInput, ignoreCase = true) }
                        .thenByDescending { it.usageCount }
                        .thenBy { it.name.lowercase() }
                )
                .take(15)
        }
    }

    val canCreateTypedTag = remember(normalizedInput, selectedTagNames) {
        normalizedInput.isNotEmpty() && selectedTagNames.none { it.equals(normalizedInput, ignoreCase = true) }
    }

    fun addTag(tagName: String) {
        val trimmed = tagName.trim()
        if (trimmed.isNotEmpty() && selectedTagNames.none { it.equals(trimmed, ignoreCase = true) }) {
            selectedTagNames.add(trimmed)
            input = ""
        }
    }

    TagPopupDialog(
        title = "Add Tags",
        subtitle = if (selectedTagNames.isEmpty()) {
            "Search or pick from the most relevant tags below."
        } else {
            "${selectedTagNames.size} tag${if (selectedTagNames.size == 1) "" else "s"} ready to apply."
        },
        query = input,
        onQueryChange = { input = it },
        onDismiss = onDismiss,
        onAddClick = { addTag(normalizedInput) },
        addEnabled = canCreateTypedTag,
        placeholder = "Type to search tags",
        footer = {
            if (canCreateTypedTag) {
                Surface(
                    onClick = { addTag(normalizedInput) },
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent,
                    tonalElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = tokens.selectedAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Create \"$normalizedInput\"",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = tokens.selectedAccent
                        )
                    }
                }
            }
            TagPopupFooter(
                dismissLabel = "Cancel",
                confirmLabel = "Apply",
                onDismiss = onDismiss,
                onConfirm = { onConfirm(selectedTagNames.toList()) }
            )
        }
    ) {
        if (selectedTagNames.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TagPopupSectionLabel(
                    title = "Selected",
                    meta = "Tap a tag to remove it"
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    selectedTagNames.forEach { tagName ->
                        TagPopupChip(
                            label = tagName,
                            backgroundColor = tokens.selectedAccent.copy(alpha = 0.12f),
                            borderColor = tokens.selectedAccent.copy(alpha = 0.24f),
                            textColor = tokens.bodyText,
                            trailingIcon = Icons.Default.Close,
                            trailingTint = tokens.secondaryText,
                            onClick = { selectedTagNames.remove(tagName) }
                        )
                    }
                }
            }
        }

        // ── AI Suggestions (model output, shown when query is blank) ─────────
        val visibleAiSuggestions = remember(normalizedInput, suggestionObjects, selectedSnapshot, suggestions) {
            if (normalizedInput.isNotBlank()) emptyList()
            else suggestionObjects
                .filter { shouldShowSuggestion(it) }
                .filter { s -> selectedTagNames.none { it.equals(s.tagKey, ignoreCase = true) } }
                .filter { s -> suggestions.none { it.name.equals(s.tagKey, ignoreCase = true) } }
        }
        if (normalizedInput.isBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AI Suggestions",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = tokens.bodyText
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (suggestionsLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 1.5.dp,
                                color = tokens.secondaryText.copy(alpha = 0.6f)
                            )
                        } else if (visibleAiSuggestions.isNotEmpty()) {
                            Text(
                                text = "• ${visibleAiSuggestions.size}",
                                color = tokens.secondaryText.copy(alpha = 0.78f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        IconButton(
                            onClick = onRefreshSuggestions,
                            modifier = Modifier.size(28.dp),
                            enabled = !suggestionsLoading
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh AI suggestions",
                                modifier = Modifier.size(15.dp),
                                tint = tokens.secondaryText.copy(alpha = if (suggestionsLoading) 0.3f else 0.7f)
                            )
                        }
                    }
                }
                if (!suggestionsLoading && visibleAiSuggestions.isEmpty()) {
                    Text(
                        text = "No AI suggestions for the selection. Tap ↻ to retry.",
                        color = tokens.secondaryText.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (visibleAiSuggestions.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        visibleAiSuggestions.forEach { suggestion ->
                            TagPopupChip(
                                label = suggestion.tagKey.uppercase(),
                                backgroundColor = tokens.selectedAccent.copy(alpha = 0.08f),
                                borderColor = tokens.selectedAccent.copy(alpha = 0.22f),
                                textColor = tokens.bodyText,
                                onClick = { addTag(suggestion.tagKey) }
                            )
                        }
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TagPopupSectionLabel(
                title = if (normalizedInput.isBlank()) "Top Suggestions" else "Matching Tags",
                meta = "${suggestions.size} shown"
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                suggestions.forEach { tag ->
                    TagPopupChip(
                        label = tag.name.uppercase(),
                        count = tag.usageCount,
                        backgroundColor = tagPopupChipBackground(tag.color, tokens),
                        borderColor = tagPopupChipBorder(tag.color, tokens),
                        textColor = tokens.bodyText,
                        onClick = { addTag(tag.name) }
                    )
                }
            }
        }
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
