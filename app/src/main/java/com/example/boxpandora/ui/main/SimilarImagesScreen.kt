package com.example.boxpandora.ui.main

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.ui.main.viewmodel.SimilarImagesViewModel
import com.example.boxpandora.ui.main.viewmodel.SimilarImagesViewModelFactory
import com.example.boxpandora.ui.main.viewmodel.SimilarMediaItem
import com.example.boxpandora.ui.main.viewmodel.SimilarMediaFilter
import com.example.boxpandora.ui.main.viewmodel.SimilarSortOrder
import com.example.boxpandora.ui.main.viewmodel.similarityBand
import com.example.boxpandora.ui.theme.boxPandoraModalTokens

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SimilarImagesScreen(
    encodedUri: String,
    onBackClick: () -> Unit,
    onMediaClick: (List<MediaItem>, Int) -> Unit
) {
    val queryUri = android.net.Uri.decode(encodedUri)
    val context = LocalContext.current
    val app = context.applicationContext as PandoraApp
    val vm: SimilarImagesViewModel = viewModel(
        key = queryUri,
        factory = SimilarImagesViewModelFactory(
            queryUri = queryUri,
            database = app.database,
            mediaRepository = app.repository,
            modelManager = app.modelManager,
            tagRepository = app.repository.tagRepository
        )
    )
    val state by vm.state.collectAsState()
    val tokens = boxPandoraModalTokens()

    // Bottom sheet for long-press actions on a result tile
    var bottomSheetTarget by remember { mutableStateOf<SimilarMediaItem?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (bottomSheetTarget != null) {
        ModalBottomSheet(
            onDismissRequest = { bottomSheetTarget = null },
            sheetState = sheetState
        ) {
            val t = bottomSheetTarget!!
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    t.item.filename,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                ListItem(
                    headlineContent = { Text("Open image") },
                    leadingContent = { Icon(Icons.Default.OpenInNew, null) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            bottomSheetTarget = null
                            val idx = state.results.indexOf(t)
                            onMediaClick(state.legacyResults, if (idx >= 0) idx else 0)
                        }
                )
                ListItem(
                    headlineContent = { Text("Add same tags") },
                    leadingContent = { Icon(Icons.Default.Label, null) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            vm.toggleSelection(t.item.uri)
                            vm.requestBatchTagApply()
                            bottomSheetTarget = null
                        }
                )
                ListItem(
                    headlineContent = { Text("Compare with source") },
                    leadingContent = { Icon(Icons.Default.CompareArrows, null) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            bottomSheetTarget = null
                            val sourceIdx = 0 // source is passed as first item
                            val items = listOfNotNull(state.sourceItem, t.item)
                            onMediaClick(items, 1)
                        }
                )
                ListItem(
                    headlineContent = { Text("Select for batch") },
                    leadingContent = { Icon(Icons.Default.SelectAll, null) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            vm.toggleSelection(t.item.uri)
                            bottomSheetTarget = null
                        }
                )
            }
        }
    }

    // Batch tag review dialog
    state.pendingBatchTags?.let { tags ->
        AlertDialog(
            onDismissRequest = { vm.cancelBatchTag() },
            title = { Text("Apply Tags") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Apply ${tags.size} tag(s) to ${state.selectedUris.size} selected item(s):",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        tags.joinToString(", ") { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmBatchTagApply() }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelBatchTag() }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "Similar Images",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            if (!state.isLoading && state.hasEmbedding) {
                                Text(
                                    "${state.results.size} found",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick, modifier = Modifier.padding(start = 8.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(tokens.iconBackgroundNeutral),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        if (state.selectedUris.isNotEmpty()) {
                            TextButton(onClick = { vm.requestBatchTagApply() }) {
                                Icon(Icons.Default.Label, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Tag ${state.selectedUris.size}")
                            }
                            TextButton(onClick = { vm.clearSelection() }) { Text("Clear") }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
                // ── Filter row ────────────────────────────────────────────────
                if (!state.isLoading && state.hasEmbedding) {
                    SimilarFilterRow(
                        mediaFilter = state.mediaFilter,
                        sortOrder = state.sortOrder,
                        onFilterChange = { vm.setMediaFilter(it) },
                        onSortChange = { vm.setSortOrder(it) }
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            !state.hasEmbedding -> {
                NoEmbeddingState(Modifier.fillMaxSize().padding(padding))
            }
            state.results.isEmpty() -> {
                EmptyResultsState(Modifier.fillMaxSize().padding(padding))
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(
                        start = 2.dp, end = 2.dp,
                        top = 2.dp, bottom = 80.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Source image card
                    state.sourceItem?.let { source ->
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                            SourceImageCard(
                                item = source,
                                onTagThis = {
                                    val idx = state.results.indexOfFirst { it.item.uri == source.uri }
                                    if (idx >= 0) onMediaClick(state.legacyResults, idx)
                                    else onMediaClick(listOf(source), 0)
                                },
                                onBack = onBackClick,
                                tokens = tokens
                            )
                        }
                    }

                    itemsIndexed(state.results) { index, similarItem ->
                        SimilarResultTile(
                            item = similarItem,
                            isSelected = similarItem.item.uri in state.selectedUris,
                            onTap = {
                                if (state.selectedUris.isNotEmpty()) {
                                    vm.toggleSelection(similarItem.item.uri)
                                } else {
                                    onMediaClick(state.legacyResults, index)
                                }
                            },
                            onLongPress = { bottomSheetTarget = similarItem }
                        )
                    }
                }
            }
        }
    }
}

// ── Source image card ──────────────────────────────────────────────────────────

@Composable
private fun SourceImageCard(
    item: MediaItem,
    onBack: () -> Unit,
    onTagThis: () -> Unit,
    tokens: com.example.boxpandora.ui.theme.ModalTokens
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(tokens.cardBackground)
            .border(1.dp, tokens.cardBorder, RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AsyncImage(
            model = Uri.parse(item.uri),
            contentDescription = "Source image",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.filename,
                color = tokens.bodyText,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Source image",
                color = tokens.secondaryText,
                style = MaterialTheme.typography.labelSmall
            )
        }
        IconButton(onClick = onTagThis, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Label, "Tag this image", tint = tokens.selectedAccent, modifier = Modifier.size(18.dp))
        }
    }
}

// ── Filter row ────────────────────────────────────────────────────────────────

@Composable
private fun SimilarFilterRow(
    mediaFilter: SimilarMediaFilter,
    sortOrder: SimilarSortOrder,
    onFilterChange: (SimilarMediaFilter) -> Unit,
    onSortChange: (SimilarSortOrder) -> Unit
) {
    val tokens = boxPandoraModalTokens()
    var showFilterMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Media type filter
        Box {
            FilterChip(
                selected = mediaFilter != SimilarMediaFilter.IMAGES_ONLY,
                onClick = { showFilterMenu = true },
                label = {
                    Text(
                        when (mediaFilter) {
                            SimilarMediaFilter.IMAGES_ONLY   -> "Images only"
                            SimilarMediaFilter.INCLUDE_GIFS  -> "Include GIFs"
                            SimilarMediaFilter.INCLUDE_VIDEOS -> "Include Videos"
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                leadingIcon = { Icon(Icons.Default.GridView, null, modifier = Modifier.size(14.dp)) }
            )
            DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Images only") },
                    onClick = { onFilterChange(SimilarMediaFilter.IMAGES_ONLY); showFilterMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("Include GIFs") },
                    onClick = { onFilterChange(SimilarMediaFilter.INCLUDE_GIFS); showFilterMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("Include Videos") },
                    onClick = { onFilterChange(SimilarMediaFilter.INCLUDE_VIDEOS); showFilterMenu = false }
                )
            }
        }

        // Sort order
        Box {
            FilterChip(
                selected = sortOrder != SimilarSortOrder.CLOSEST_FIRST,
                onClick = { showSortMenu = true },
                label = {
                    Text(
                        when (sortOrder) {
                            SimilarSortOrder.CLOSEST_FIRST -> "Closest first"
                            SimilarSortOrder.NEWEST_FIRST  -> "Newest first"
                            SimilarSortOrder.SAME_FOLDER   -> "Same folder"
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Closest first") },
                    onClick = { onSortChange(SimilarSortOrder.CLOSEST_FIRST); showSortMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("Newest first") },
                    onClick = { onSortChange(SimilarSortOrder.NEWEST_FIRST); showSortMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("Same folder priority") },
                    onClick = { onSortChange(SimilarSortOrder.SAME_FOLDER); showSortMenu = false }
                )
            }
        }
    }
}

// ── Result tile ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SimilarResultTile(
    item: SimilarMediaItem,
    isSelected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val band = similarityBand(item.similarity)

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
    ) {
        AsyncImage(
            model = Uri.parse(item.item.uri),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(onClick = onTap, onLongClick = onLongPress)
        )

        // Rank + band overlay (bottom-left)
        if (band != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 5.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "#${item.rank}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                )
                Text(
                    text = band,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
                )
            }
        }

        // Selection indicator
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            )
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
            )
        }
    }
}

// ── Empty states ──────────────────────────────────────────────────────────────

@Composable
private fun NoEmbeddingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Collections,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "No scene data for this image",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Run Scene Index from Tagging & AI settings\nto enable similarity search.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun EmptyResultsState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Collections,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "No similar images found",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Index more images to improve results.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}
