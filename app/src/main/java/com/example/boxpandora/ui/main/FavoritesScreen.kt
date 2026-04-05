package com.example.boxpandora.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.ui.common.*
import com.example.boxpandora.ui.components.grid.MediaThumbnail
import com.example.boxpandora.ui.main.viewmodel.FavoritesViewModel
import com.example.boxpandora.ui.main.viewmodel.FavoritesViewModelFactory
import com.example.boxpandora.ui.theme.inlineRevealEnter
import com.example.boxpandora.ui.theme.inlineRevealExit

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoritesScreen(
    showHidden: Boolean,
    onMediaClick: (List<MediaItem>, Int) -> Unit,
    onOpenDrawer: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PandoraApp
    val viewModel: FavoritesViewModel = viewModel(
        factory = FavoritesViewModelFactory(app.repository)
    )

    val favorites by viewModel.favorites.collectAsState()
    val isSearchOpen by viewModel.isSearchOpen.collectAsState()
    val searchParams by viewModel.searchParams.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val selectedUris by viewModel.selectedUris.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    var showPropertiesSheet by remember { mutableStateOf(false) }

    LaunchedEffect(showHidden) {
        viewModel.setShowHidden(showHidden)
    }

    if (isSelectionMode) {
        BackHandler { viewModel.clearSelection() }
    }

    if (isSearchOpen) {
        BackHandler { viewModel.closeSearch() }
    }

    // ── Collapsible header logic (same as FoldersScreen) ────────────────────
    val collapseRangeDp = 48.dp
    val collapseRangePx = with(LocalDensity.current) { collapseRangeDp.toPx() }
    var scrollOffset by remember { mutableStateOf(0f) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (isSearchOpen || isSelectionMode) return Offset.Zero

                val delta = available.y
                val newOffset = scrollOffset + delta
                val minOffset = -collapseRangePx

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
        if (isSearchOpen || isSelectionMode) scrollOffset = 0f
    }

    val scrollProgress = (1f + scrollOffset / collapseRangePx).coerceIn(0f, 1f)

    // ── Screen layout ────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .nestedScroll(nestedScrollConnection)
    ) {
        val subtitle = if (favorites.isNotEmpty()) "%,d items".format(favorites.size) else null

        AppHeader(
            title = "favourites",
            subtitle = if (isSelectionMode) null else subtitle,
            onMenuClick = onOpenDrawer,
            onSearchClick = { viewModel.openSearch() },
            selectionCount = selectedUris.size,
            onClearSelection = { viewModel.clearSelection() },
            canSelectAll = if (isSearchOpen) {
                searchResults.isNotEmpty() && selectedUris.size < searchResults.size
            } else {
                favorites.isNotEmpty() && selectedUris.size < favorites.size
            },
            onSelectAll = {
                val visibleItems = if (isSearchOpen) searchResults else favorites
                viewModel.selectItems(visibleItems.map { it.uri })
            },
            allowOpenWith = false,
            onActionClick = { action ->
                when (action) {
                    "properties" -> showPropertiesSheet = true
                    "unfavourite" -> viewModel.removeFromFavoritesSelected()
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

        Box(modifier = Modifier.weight(1f)) {
            if (isSearchOpen) {
                SearchResultsGrid(
                    results = searchResults,
                    selectedUris = selectedUris,
                    isLoading = isSearching,
                    onPress = { item ->
                        onMediaClick(searchResults, searchResults.indexOf(item))
                    },
                    onLongPress = { item -> viewModel.toggleSelection(item.uri) }
                )
            } else {
                FavoritesGrid(
                    items = favorites,
                    selectedUris = selectedUris,
                    onPress = { item ->
                        if (isSelectionMode) {
                            viewModel.toggleSelection(item.uri)
                        } else {
                            onMediaClick(favorites, favorites.indexOf(item))
                        }
                    },
                    onLongPress = { item -> viewModel.toggleSelection(item.uri) }
                )
            }
        }
    }

    if (showPropertiesSheet) {
        val item = (if (isSearchOpen) searchResults else favorites).find { it.uri in selectedUris }
        item?.let {
            MediaPropertiesSheet(
                item = it,
                onDismiss = { showPropertiesSheet = false }
            )
        }
    }
}

// ── Favourites grid ───────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoritesGrid(
    items: List<MediaItem>,
    selectedUris: Set<String>,
    onPress: (MediaItem) -> Unit,
    onLongPress: (MediaItem) -> Unit
) {
    if (items.isEmpty()) {
        FavoritesEmptyState()
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp, top = 8.dp)
    ) {
        // Section header
        item(
            key = "fav_header",
            span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }
        ) {
            FavoritesSectionHeader(count = items.size)
        }

        items(items, key = { it.uri }) { item ->
            MediaThumbnail(
                uri = item.uri,
                filePath = item.filePath,
                thumbUri = item.thumbUri,
                mediaType = item.mediaType,
                duration = item.duration,
                isFavorite = item.isFavorite,
                isSelected = item.uri in selectedUris,
                onPress = { onPress(item) },
                onLongPress = { onLongPress(item) },
                modifier = Modifier.animateItemPlacement()
            )
        }
    }
}

// ── Section header bar ────────────────────────────────────────────────────────

@Composable
private fun FavoritesSectionHeader(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Text(
                text = "All Favourites",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "%,d".format(count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun FavoritesEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.FavoriteBorder,
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .alpha(0.18f),
            tint = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "No favourites yet",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Light,
                letterSpacing = 0.5.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Items you mark as favourite\nwill appear here",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}
