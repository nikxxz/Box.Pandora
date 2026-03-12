package com.example.boxpandora.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.data.repository.RelatedTag
import com.example.boxpandora.ui.common.*
import com.example.boxpandora.ui.components.grid.MediaThumbnail
import com.example.boxpandora.ui.main.viewmodel.*
import com.example.boxpandora.ui.theme.inlineRevealEnter
import com.example.boxpandora.ui.theme.inlineRevealExit
import com.example.boxpandora.ui.theme.boxPandoraModalTokens
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagGalleryScreen(
    tagId: Long,
    showHidden: Boolean,
    onBackClick: () -> Unit,
    onMediaClick: (List<MediaItem>, Int) -> Unit,
    onNavigateToTag: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as PandoraApp
    val viewModel: TagGalleryViewModel = viewModel(
        key = "tag_gallery_$tagId",
        factory = TagGalleryViewModelFactory(
            tagId = tagId,
            mediaRepository = app.repository,
            tagRepository = app.repository.tagRepository,
            showHidden = showHidden
        )
    )

    val uiState by viewModel.uiState.collectAsState()

    var isSearchActive by remember { mutableStateOf(false) }
    
    // Management Dialog States
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMergeSheet by remember { mutableStateOf(false) }
    var showDeleteTagDialog by remember { mutableStateOf(false) }
    var showDeleteMediaDialog by remember { mutableStateOf(false) }
    var showBulkTagDialog by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var showAliasDialog by remember { mutableStateOf(false) }

    // Edge Case: Tag deleted - Navigate back automatically
    LaunchedEffect(uiState.tag, uiState.isLoading) {
        if (!uiState.isLoading && uiState.tag == null) {
            onBackClick()
        }
    }

    if (uiState.isSelectionMode) {
        BackHandler { viewModel.clearSelection() }
    }

    Scaffold(
        topBar = {
            Column {
                if (uiState.isSelectionMode) {
                    SelectionToolbar(
                        selectedCount = uiState.selectedUris.size,
                        onClearSelection = { viewModel.clearSelection() },
                        onRemoveTag = { viewModel.removeTagFromSelected() },
                        onAddTag = { viewModel.ensureAllTagsLoaded(); showBulkTagDialog = true },
                        onFavorite = { viewModel.toggleFavoriteSelected() },
                        onDelete = { showDeleteMediaDialog = true }
                    )
                } else {
                    TagGalleryHeader(
                        tagName = uiState.tag?.name ?: "Tag",
                        isSearchActive = isSearchActive,
                        onBackClick = onBackClick,
                        onSearchToggle = { isSearchActive = !isSearchActive },
                        onMenuAction = { action ->
                            when (action) {
                                "Rename tag" -> showRenameDialog = true
                                "Merge tag" -> { viewModel.ensureAllTagsLoaded(); showMergeSheet = true }
                                "Change category" -> showCategorySheet = true
                                "Add alias" -> showAliasDialog = true
                                "Delete tag" -> showDeleteTagDialog = true
                                "Edit description" -> { /* Handled in TagDescriptionBlock */ }
                            }
                        }
                    )
                }
                HorizontalDivider(modifier = Modifier.alpha(0.05f))
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            AnimatedVisibility(
                visible = isSearchActive && !uiState.isSelectionMode,
                enter = inlineRevealEnter(),
                exit = inlineRevealExit()
            ) {
                TagSearchHeader(
                    query = uiState.searchQuery,
                    onQueryChange = { viewModel.setSearchQuery(it) },
                    onClose = { 
                        isSearchActive = false
                        viewModel.setSearchQuery("")
                    }
                )
            }

            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                TagGalleryContent(
                    uiState = uiState,
                    onSortChange = { viewModel.setSortMode(it) },
                    onGridModeToggle = { viewModel.toggleGridMode() },
                    onMediaClick = { items, index ->
                        if (uiState.isSelectionMode) {
                            viewModel.toggleSelection(items[index].uri)
                        } else {
                            onMediaClick(items, index)
                        }
                    },
                    onMediaLongClick = { viewModel.toggleSelection(it.uri) },
                    onTagClick = onNavigateToTag,
                    onEditDescription = { viewModel.updateDescription(it) },
                    onActionClick = { action ->
                         when (action) {
                            "Rename tag" -> showRenameDialog = true
                            "Merge tag" -> showMergeSheet = true
                            "Delete tag" -> showDeleteTagDialog = true
                            "Change category" -> showCategorySheet = true
                            "Add alias" -> showAliasDialog = true
                        }
                    },
                    onFilterChange = { viewModel.updateFilters(it) },
                    onBrowseClick = onBackClick
                )
            }
        }
    }

    // Sheets & Dialogs
    if (showRenameDialog) {
        RenameDialog(
            initialName = uiState.tag?.name ?: "",
            title = "Rename Tag",
            onDismiss = { showRenameDialog = false },
            onConfirm = { 
                viewModel.renameTag(it)
                showRenameDialog = false
            }
        )
    }

    if (showMergeSheet) {
        TagSelectorSheet(
            title = "Select target tag for merge",
            tags = uiState.allTags,
            excludeTagId = tagId,
            onDismiss = { showMergeSheet = false },
            onConfirm = { target ->
                viewModel.mergeTag(target.id)
                showMergeSheet = false
            }
        )
    }

    if (showCategorySheet) {
        CategorySelectorSheet(
            currentCategory = uiState.tag?.category ?: "misc",
            onDismiss = { showCategorySheet = false },
            onConfirm = { category ->
                viewModel.updateCategory(category)
                showCategorySheet = false
            }
        )
    }

    if (showAliasDialog) {
        RenameDialog(
            initialName = "",
            title = "Add Alias",
            onDismiss = { showAliasDialog = false },
            onConfirm = { alias ->
                viewModel.addAlias(alias)
                showAliasDialog = false
            }
        )
    }

    if (showDeleteTagDialog) {
        DeleteConfirmationDialog(
            count = 1,
            isFolder = false,
            title = "Delete Tag",
            onDismiss = { showDeleteTagDialog = false },
            onConfirm = {
                viewModel.deleteTag()
                showDeleteTagDialog = false
            }
        )
    }

    if (showDeleteMediaDialog) {
        DeleteConfirmationDialog(
            count = uiState.selectedUris.size,
            isFolder = false,
            onDismiss = { showDeleteMediaDialog = false },
            onConfirm = {
                viewModel.deleteSelectedMedia()
                showDeleteMediaDialog = false
            }
        )
    }
    
    if (showBulkTagDialog) {
        BulkTagDialog(
            allTags = uiState.allTags,
            onDismiss = { showBulkTagDialog = false },
            onConfirm = { tags ->
                viewModel.addTagsToSelected(tags)
                showBulkTagDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagGalleryHeader(
    tagName: String,
    isSearchActive: Boolean,
    onBackClick: () -> Unit,
    onSearchToggle: () -> Unit,
    onMenuAction: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Text(
                text = tagName,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = onSearchToggle) {
                Icon(if (isSearchActive) Icons.Default.Close else Icons.Default.Search, contentDescription = "Search")
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                AppContextMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    AppContextMenuItem("Rename tag", onClick = { onMenuAction("Rename tag"); showMenu = false }, icon = Icons.Default.Edit)
                    AppContextMenuItem("Merge tag", onClick = { onMenuAction("Merge tag"); showMenu = false }, icon = Icons.AutoMirrored.Filled.CallMerge)
                    AppContextMenuItem("Change category", onClick = { onMenuAction("Change category"); showMenu = false }, icon = Icons.Default.Category)
                    AppContextMenuItem("Add alias", onClick = { onMenuAction("Add alias"); showMenu = false }, icon = Icons.AutoMirrored.Filled.Label)
                    AppContextMenuItem("Add description", onClick = { onMenuAction("Add description"); showMenu = false }, icon = Icons.Default.Description)
                    AppContextMenuItem("Delete tag", onClick = { onMenuAction("Delete tag"); showMenu = false }, icon = Icons.Default.Delete, destructive = true)
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionToolbar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onRemoveTag: () -> Unit,
    onAddTag: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    TopAppBar(
        title = { Text("$selectedCount selected", style = MaterialTheme.typography.titleMedium) },
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(Icons.Default.Close, contentDescription = "Clear")
            }
        },
        actions = {
            IconButton(onClick = onRemoveTag) {
                Icon(Icons.AutoMirrored.Filled.LabelOff, contentDescription = "Remove Tag")
            }
            IconButton(onClick = onAddTag) {
                Icon(Icons.AutoMirrored.Filled.Label, contentDescription = "Add Tag")
            }
            IconButton(onClick = onFavorite) {
                Icon(Icons.Default.FavoriteBorder, contentDescription = "Favorite")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    )
}

@Composable
fun TagSearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    val tokens = boxPandoraModalTokens()

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search within tag...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, null)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = tokens.iconBackgroundNeutral,
                unfocusedContainerColor = tokens.iconBackgroundNeutral,
                focusedBorderColor = tokens.border,
                unfocusedBorderColor = tokens.border,
                focusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TagGalleryContent(
    uiState: TagGalleryUiState,
    onSortChange: (TagGallerySort) -> Unit,
    onGridModeToggle: () -> Unit,
    onMediaClick: (List<MediaItem>, Int) -> Unit,
    onMediaLongClick: (MediaItem) -> Unit,
    onTagClick: (Long) -> Unit,
    onEditDescription: (String) -> Unit,
    onActionClick: (String) -> Unit,
    onFilterChange: (TagFilters) -> Unit,
    onBrowseClick: () -> Unit
) {
    val tag = uiState.tag ?: return
    val columns = if (uiState.gridMode == GridMode.COMPACT) 4 else 3
    val effectiveColumns = if (uiState.media.size <= 4) 2 else columns
    
    val mediaRows = uiState.media.chunked(effectiveColumns)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        item {
            TagMetaOverview(
                tag = tag,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp)
            )
        }

        item {
            TagDescriptionBlock(
                description = tag.description,
                onEdit = onEditDescription,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
            )
        }
        
        item { Spacer(Modifier.height(4.dp)) }

        stickyHeader {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth()
            ) {
                TagControlsRow(
                    currentSort = uiState.sortMode,
                    gridMode = uiState.gridMode,
                    filters = uiState.filters,
                    onSortChange = onSortChange,
                    onGridModeToggle = onGridModeToggle,
                    onFilterChange = onFilterChange,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }

        if (uiState.media.isEmpty()) {
            item {
                TagEmptyMediaState(onBrowseClick = onBrowseClick)
            }
        } else {
            items(mediaRows) { rowItems ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    rowItems.forEach { item ->
                        Box(modifier = Modifier.weight(1f)) {
                            MediaThumbnail(
                                uri = item.uri,
                                filePath = item.filePath,
                                thumbUri = item.thumbUri,
                                mediaType = item.mediaType,
                                duration = item.duration,
                                isFavorite = item.isFavorite,
                                isSelected = item.uri in uiState.selectedUris,
                                onPress = { 
                                    val index = uiState.media.indexOf(item)
                                    onMediaClick(uiState.media, index)
                                },
                                onLongPress = { onMediaLongClick(item) }
                            )
                        }
                    }
                    // Fill remaining space if row is not full
                    repeat(effectiveColumns - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        item {
            Column(modifier = Modifier.padding(top = 28.dp, bottom = 16.dp, start = 16.dp, end = 16.dp)) {
                RelatedTagsSection(
                    relatedTags = uiState.relatedTags,
                    onTagClick = onTagClick
                )
            }
        }
    }
}

@Composable
fun TagMetaOverview(
    tag: Tag,
    modifier: Modifier = Modifier
) {
    val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    val accent = categoryColor(tag.category)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TagMetaChip(
                label = tag.category.uppercase(),
                accent = accent,
                modifier = Modifier.weight(1f, fill = false)
            )
            TagMetaChip(
                label = "${tag.usageCount} ${if (tag.usageCount == 1) "item" else "items"}",
                modifier = Modifier.weight(1f, fill = false)
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TagDetailRow(label = "Aliases", value = "none")
                TagDetailRow(label = "Created", value = dateFmt.format(Date(tag.createdAt)))
                TagDetailRow(label = "Last used", value = formatRelativeTime(tag.updatedAt ?: tag.createdAt))
            }
        }
    }
}

@Composable
fun TagDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
        )
    }
}

@Composable
fun TagMetaChip(
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Surface(
        modifier = modifier,
        color = accent.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 10.sp,
                letterSpacing = 0.6.sp
            ),
            color = accent
        )
    }
}

@Composable
fun RelatedTagsSection(
    relatedTags: List<RelatedTag>,
    onTagClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (relatedTags.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "RELATED TAGS",
                style = MaterialTheme.typography.labelLarge.copy(
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
        }
        Spacer(Modifier.height(12.dp))
        
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 16.dp)
        ) {
            items(relatedTags) { related ->
                val tag = related.tag
                
                Surface(
                    onClick = { onTagClick(tag.id) },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f))
                ) {
                    Row(
                        modifier = Modifier
                            .height(36.dp)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tag.name,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = related.cooccurrenceCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TagDescriptionBlock(
    description: String?,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isEditing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(description ?: "") }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "ABOUT THIS TAG",
            style = MaterialTheme.typography.labelLarge.copy(
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(8.dp))
        
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (!isEditing) isEditing = true },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                if (isEditing) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Enter tag description...") },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        trailingIcon = {
                            IconButton(onClick = { 
                                onEdit(text)
                                isEditing = false
                            }) {
                                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    )
                } else {
                    if (description.isNullOrBlank()) {
                        Text(
                            text = "No description provided for this tag.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text("Add description", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            minLines = 2,
                            lineHeight = 22.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TagControlsRow(
    currentSort: TagGallerySort,
    gridMode: GridMode,
    filters: TagFilters,
    onSortChange: (TagGallerySort) -> Unit,
    onGridModeToggle: () -> Unit,
    onFilterChange: (TagFilters) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            var showSortMenu by remember { mutableStateOf(false) }
            Box {
                TagToolbarChip(
                    label = currentSort.label,
                    icon = Icons.AutoMirrored.Filled.Sort,
                    trailingIcon = Icons.Default.ArrowDropDown,
                    onClick = { showSortMenu = true }
                )
                AppContextMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    TagGallerySort.entries.forEach { sort ->
                        AppContextMenuItem(
                            label = sort.label,
                            selected = sort == currentSort,
                            onClick = {
                                onSortChange(sort)
                                showSortMenu = false
                            }
                        )
                    }
                }
            }
            
            var showFilterMenu by remember { mutableStateOf(false) }
            Box {
                TagToolbarChip(
                    label = when {
                        filters.favoritesOnly -> "Favorites"
                        filters.type != "all" -> filters.type.replaceFirstChar { it.uppercase() }
                        else -> "Filter"
                    },
                    icon = Icons.Default.FilterList,
                    onClick = { showFilterMenu = true }
                )
                AppContextMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                    AppContextMenuItem(
                        label = "Images",
                        selected = filters.type == "image",
                        onClick = { onFilterChange(filters.copy(type = "image")); showFilterMenu = false },
                        icon = Icons.Default.Image
                    )
                    AppContextMenuItem(
                        label = "Videos",
                        selected = filters.type == "video",
                        onClick = { onFilterChange(filters.copy(type = "video")); showFilterMenu = false },
                        icon = Icons.Default.PlayCircleOutline
                    )
                    AppContextMenuItem(
                        label = "All Types",
                        selected = filters.type == "all",
                        onClick = { onFilterChange(filters.copy(type = "all")); showFilterMenu = false },
                        icon = Icons.Default.Collections
                    )
                    AppContextMenuDivider()
                    AppContextMenuItem(
                        label = "Favorites",
                        selected = filters.favoritesOnly,
                        onClick = { onFilterChange(filters.copy(favoritesOnly = !filters.favoritesOnly)); showFilterMenu = false },
                        icon = Icons.Default.FavoriteBorder
                    )
                }
            }
        }

        Surface(
            onClick = onGridModeToggle,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (gridMode == GridMode.COMPACT) Icons.Default.GridView else Icons.Default.GridOn,
                    contentDescription = "Toggle Grid",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
                )
            }
        }
    }
}

@Composable
private fun TagToolbarChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    trailingIcon: ImageVector? = null
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
            )
            if (trailingIcon != null) {
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun TagEmptyMediaState(
    modifier: Modifier = Modifier,
    onBrowseClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp, horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "No media with this tag",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onBrowseClick,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("Browse media")
        }
    }
}

// --- Helpers ---

private fun categoryColor(category: String): Color {
    return when (category.lowercase()) {
        "people"    -> Color(0xFF5C7CFA)
        "character" -> Color(0xFF82C91E)
        "style"     -> Color(0xFFCC5DE8)
        "clothing"  -> Color(0xFFFF6B6B)
        "pose"      -> Color(0xFF339AF0)
        "place"     -> Color(0xFF20C997)
        "animal"    -> Color(0xFF94D82D)
        "object"    -> Color(0xFFFF922B)
        "mood"      -> Color(0xFFF59F00)
        else        -> Color(0xFF868E96)
    }
}

private fun formatRelativeTime(timestampMs: Long): String {
    val nowMs = System.currentTimeMillis()
    val diffDays = ((nowMs - timestampMs) / (1000L * 60 * 60 * 24)).toInt()
    return when {
        diffDays == 0 -> "Today"
        diffDays == 1 -> "Yesterday"
        diffDays < 7  -> "$diffDays days ago"
        else -> {
            val cal = Calendar.getInstance().apply { timeInMillis = timestampMs }
            val sameYear = cal.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
            val fmt = if (sameYear) "d MMM" else "d MMM yyyy"
            SimpleDateFormat(fmt, Locale.getDefault()).format(Date(timestampMs))
        }
    }
}
