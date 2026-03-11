package com.example.boxpandora.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.data.repository.RelatedTag
import com.example.boxpandora.ui.common.*
import com.example.boxpandora.ui.components.grid.MediaThumbnail
import com.example.boxpandora.ui.main.viewmodel.*
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
                        onAddTag = { showBulkTagDialog = true },
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
                                "Merge tag" -> showMergeSheet = true
                                "Delete tag" -> showDeleteTagDialog = true
                            }
                        }
                    )
                }
                HorizontalDivider(modifier = Modifier.alpha(0.12f))
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            AnimatedVisibility(visible = isSearchActive && !uiState.isSelectionMode) {
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
            } else if (uiState.tag != null) {
                TagMediaGrid(
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
                            "Delete unused" -> showDeleteTagDialog = true
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
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBulkTagDialog = false },
            title = { Text("Add Tag to Selection") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Enter tag name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.addTagsToSelected(listOf(input))
                    showBulkTagDialog = false
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showBulkTagDialog = false }) { Text("Cancel") }
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

    CenterAlignedTopAppBar(
        title = {
            Text(
                text = tagName,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
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
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    listOf("Rename tag", "Merge tag", "Edit description", "Delete tag").forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action) },
                            onClick = {
                                onMenuAction(action)
                                showMenu = false
                            }
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
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
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )
    }
}

@Composable
fun TagMediaGrid(
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
    val columns = if (uiState.gridMode == GridMode.COMPACT) 4 else 3
    val tag = uiState.tag ?: return

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 12.dp,
            bottom = 80.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Upper Header Section
        item(span = { GridItemSpan(columns) }) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                TagHeroCard(tag = tag, media = uiState.media)
                Spacer(Modifier.height(12.dp))
                TagStatisticsRow(tag = tag)
                Spacer(Modifier.height(16.dp))
                TagControlsRow(
                    currentSort = uiState.sortMode,
                    gridMode = uiState.gridMode,
                    filters = uiState.filters,
                    onSortChange = onSortChange,
                    onGridModeToggle = onGridModeToggle,
                    onFilterChange = onFilterChange
                )
            }
        }

        // Empty State below controls if needed
        if (uiState.media.isEmpty()) {
            item(span = { GridItemSpan(columns) }) {
                TagEmptyMediaState(onBrowseClick = onBrowseClick)
            }
        } else {
            itemsIndexed(items = uiState.media, key = { _, item -> item.uri }) { index, item ->
                MediaThumbnail(
                    uri = item.uri,
                    filePath = item.filePath,
                    mediaType = item.mediaType,
                    duration = item.duration,
                    isFavorite = item.isFavorite,
                    isSelected = item.uri in uiState.selectedUris,
                    onPress = { onMediaClick(uiState.media, index) },
                    onLongPress = { onMediaLongClick(item) }
                )
            }
        }

        // Footer Sections (Management)
        item(span = { GridItemSpan(columns) }) {
            Column(modifier = Modifier.padding(top = 24.dp)) {
                RelatedTagsSection(
                    relatedTags = uiState.relatedTags,
                    onTagClick = onTagClick
                )
                
                Spacer(Modifier.height(24.dp))
                
                TagDescriptionBlock(
                    description = tag.description,
                    onEdit = onEditDescription
                )
                
                Spacer(Modifier.height(24.dp))
                
                TagActionsPanel(onActionClick = onActionClick)
                
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RelatedTagsSection(
    relatedTags: List<RelatedTag>,
    onTagClick: (Long) -> Unit
) {
    if (relatedTags.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "RELATED TAGS",
            style = MaterialTheme.typography.labelLarge.copy(
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            relatedTags.forEach { related ->
                val tag = related.tag
                
                Surface(
                    onClick = { onTagClick(tag.id) },
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .height(36.dp)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tag.name,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "(${related.cooccurrenceCount})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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
    onEdit: (String) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(description ?: "") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ABOUT THIS TAG",
                    style = MaterialTheme.typography.labelLarge.copy(
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    if (isEditing) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Add description...") },
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
                        Text(
                            text = if (description.isNullOrBlank()) "Add description" else description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (description.isNullOrBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        )
                        
                        TextButton(
                            onClick = { 
                                text = description ?: ""
                                isEditing = true 
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Edit description", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            
            if (!isExpanded && description.isNullOrBlank()) {
                Text(
                    text = "Add description",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp).clickable { isExpanded = true; isEditing = true }
                )
            }
        }
    }
}

@Composable
fun TagActionsPanel(onActionClick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "TAG ACTIONS",
            style = MaterialTheme.typography.labelLarge.copy(
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(8.dp))
        
        val actions = listOf(
            Triple(Icons.Default.Edit, "Rename tag", null),
            Triple(Icons.AutoMirrored.Filled.CallMerge, "Merge tag", null),
            Triple(Icons.Default.Category, "Change category", null),
            Triple(Icons.AutoMirrored.Filled.Label, "Add alias", null),
            Triple(Icons.Default.Delete, "Delete unused", MaterialTheme.colorScheme.error)
        )

        actions.forEachIndexed { index, (icon, label, color) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable { onActionClick(label) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon, 
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = color ?: MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = label, 
                    style = MaterialTheme.typography.bodyMedium,
                    color = color ?: MaterialTheme.colorScheme.onSurface
                )
            }
            if (index < actions.size - 1) {
                HorizontalDivider(modifier = Modifier.alpha(0.05f), thickness = 0.5.dp)
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Sort
            var showSortMenu by remember { mutableStateOf(false) }
            Box {
                Surface(
                    onClick = { showSortMenu = true },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Sort: ${currentSort.label}",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(20.dp))
                    }
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    TagGallerySort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort.label) },
                            onClick = {
                                onSortChange(sort)
                                showSortMenu = false
                            }
                        )
                    }
                }
            }
            
            Spacer(Modifier.width(16.dp))
            
            // Filter
            var showFilterMenu by remember { mutableStateOf(false) }
            Box {
                Surface(
                    onClick = { showFilterMenu = true },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Filter",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Icon(Icons.Default.FilterList, null, modifier = Modifier.size(20.dp))
                    }
                }
                DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Images") },
                        onClick = { onFilterChange(filters.copy(type = "image")); showFilterMenu = false },
                        trailingIcon = if (filters.type == "image") { { Icon(Icons.Default.Check, null) } } else null
                    )
                    DropdownMenuItem(
                        text = { Text("Videos") },
                        onClick = { onFilterChange(filters.copy(type = "video")); showFilterMenu = false },
                        trailingIcon = if (filters.type == "video") { { Icon(Icons.Default.Check, null) } } else null
                    )
                    DropdownMenuItem(
                        text = { Text("All Types") },
                        onClick = { onFilterChange(filters.copy(type = "all")); showFilterMenu = false },
                        trailingIcon = if (filters.type == "all") { { Icon(Icons.Default.Check, null) } } else null
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Favorites") },
                        onClick = { onFilterChange(filters.copy(favoritesOnly = !filters.favoritesOnly)); showFilterMenu = false },
                        trailingIcon = if (filters.favoritesOnly) { { Icon(Icons.Default.Check, null) } } else null
                    )
                }
            }
        }

        IconButton(onClick = onGridModeToggle) {
            Icon(
                imageVector = if (gridMode == GridMode.COMPACT) Icons.Default.GridView else Icons.Default.GridOn,
                contentDescription = "Toggle Grid",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun TagHeroCard(
    tag: Tag,
    media: List<MediaItem>,
    modifier: Modifier = Modifier
) {
    val coverImage = media.firstOrNull()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = tag.name,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${tag.usageCount} items",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = categoryColor(tag.category).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = tag.category.replaceFirstChar { it.uppercase() },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = categoryColor(tag.category)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(0.8f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp))
            ) {
                if (coverImage == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(categoryColor(tag.category).copy(alpha = 0.05f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = categoryIcon(tag.category),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = categoryColor(tag.category).copy(alpha = 0.2f)
                        )
                    }
                } else {
                    AsyncImage(
                        model = coverImage.thumbUri ?: coverImage.uri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun TagStatisticsRow(tag: Tag, modifier: Modifier = Modifier) {
    val lastUsed = tag.updatedAt ?: tag.createdAt
    val created = tag.createdAt
    
    val dateFmt = SimpleDateFormat("d MMM", Locale.getDefault())
    
    val statsText = buildString {
        append("${tag.usageCount} ITEMS • ")
        append("LAST USED ${formatRelativeTime(lastUsed).uppercase()} • ")
        append("CREATED ${dateFmt.format(Date(created)).uppercase()}")
    }

    Text(
        text = statsText,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 12.sp,
            letterSpacing = 0.5.sp,
            fontWeight = FontWeight.Medium
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
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

private fun categoryIcon(category: String) = when (category.lowercase()) {
    "people"    -> Icons.Default.Person
    "character" -> Icons.Default.Face
    "style"     -> Icons.Default.Palette
    "clothing"  -> Icons.Default.Style
    "pose"      -> Icons.Default.FitnessCenter
    "place"     -> Icons.Default.LocationOn
    "animal"    -> Icons.Default.Pets
    "object"    -> Icons.Default.Category
    "mood"      -> Icons.Default.Mood
    else        -> Icons.AutoMirrored.Filled.Label
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
