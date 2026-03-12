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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.ui.common.*
import com.example.boxpandora.ui.components.grid.FolderCard
import com.example.boxpandora.ui.components.grid.MediaThumbnail
import com.example.boxpandora.ui.main.viewmodel.*
import com.example.boxpandora.ui.theme.PandoraDimensions
import com.example.boxpandora.ui.theme.PandoraMotion
import com.example.boxpandora.ui.theme.inlineRevealEnter
import com.example.boxpandora.ui.theme.inlineRevealExit
import kotlinx.coroutines.launch

private fun hasStorageAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    val totalCount by viewModel.totalMediaCount.collectAsState()
    val selectedIds by viewModel.selectedAlbumIds.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val currentTab by viewModel.currentTab.collectAsState()

    val isSearchOpen by viewModel.isSearchOpen.collectAsState()
    val searchParams by viewModel.searchParams.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    val allMediaItems = viewModel.allMedia.collectAsLazyPagingItems()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }

    val pullToRefreshState = rememberPullToRefreshState()
    if (pullToRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            viewModel.refresh()
            pullToRefreshState.endRefresh()
        }
    }

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

    // Pager state for swipe navigation
    val pagerState = rememberPagerState(
        initialPage = currentTab.ordinal,
        pageCount = { HomeTab.entries.size }
    )
    val coroutineScope = rememberCoroutineScope()

    // Sync Pager with ViewModel
    LaunchedEffect(pagerState.currentPage) {
        viewModel.setTab(HomeTab.entries[pagerState.currentPage])
    }

    LaunchedEffect(currentTab) {
        if (currentTab.ordinal != pagerState.currentPage) {
            pagerState.animateScrollToPage(currentTab.ordinal)
        }
    }

    // Scrolling logic for collapsible TabRow and shrinking Header
    val tabRowHeight = 48.dp
    val tabRowHeightPx = with(LocalDensity.current) { tabRowHeight.toPx() }
    
    var scrollOffset by remember { mutableStateOf(0f) }
    
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // If search or selection is open, we don't want to collapse
                if (isSearchOpen || isSelectionMode) return Offset.Zero
                
                val delta = available.y
                val newOffset = scrollOffset + delta
                val minOffset = -tabRowHeightPx
                
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

    // Reset scroll when state changes significantly
    LaunchedEffect(isSearchOpen, isSelectionMode) {
        if (isSearchOpen || isSelectionMode) scrollOffset = 0f
    }

    val scrollProgress = (1f + scrollOffset / tabRowHeightPx).coerceIn(0f, 1f)

    Column(modifier = Modifier
        .fillMaxSize()
        .nestedScroll(nestedScrollConnection)
    ) {
        val showHideLabel = remember(selectedIds, albums) {
            val selectedAlbums = albums.filter { it.id in selectedIds }
            if (selectedAlbums.isNotEmpty() && selectedAlbums.all { it.isHidden }) "Show" else "Hide"
        }

        val subtitle = "Library • %,d items".format(totalCount)

        AppHeader(
            title = "pandora",
            subtitle = if (isSelectionMode) null else subtitle,
            onMenuClick = onOpenDrawer,
            onSearchClick = { viewModel.openSearch() },
            selectionCount = selectedIds.size,
            onClearSelection = { viewModel.clearSelection() },
            showHideOption = showHideLabel,
            isPinned = albums.find { it.id in selectedIds }?.isPinned == true,
            allowOpenWith = false,
            onActionClick = { action ->
                when (action) {
                    "delete" -> showDeleteDialog = true
                    "rename" -> showRenameDialog = true
                    "copy" -> showCopyDialog = true
                    "move" -> showMoveDialog = true
                    "hide_show" -> viewModel.toggleHiddenForSelected()
                    "pin" -> viewModel.togglePinSelectedAlbums()
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

        if (!isSearchOpen && !isSelectionMode) {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Color.Transparent,
                divider = {},
                indicator = { tabPositions ->
                    if (pagerState.currentPage < tabPositions.size) {
                        Box(
                            Modifier
                                .tabIndicatorOffset(tabPositions[pagerState.currentPage])
                                .fillMaxWidth()
                                .padding(horizontal = 48.dp)
                                .height(2.5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tabRowHeight * scrollProgress)
                    .alpha(scrollProgress)
            ) {
                HomeTab.entries.forEach { tab ->
                    Tab(
                        selected = pagerState.currentPage == tab.ordinal,
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(tab.ordinal) }
                        },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        text = {
                            Text(
                                text = if (tab == HomeTab.FOLDERS) "Folders" else "All Media",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = if (pagerState.currentPage == tab.ordinal) FontWeight.Bold else FontWeight.Normal,
                                    letterSpacing = 0.5.sp,
                                    fontSize = 14.sp
                                )
                            )
                        }
                    )
                }
            }
        }

        Box(modifier = Modifier
            .weight(1f)
            .nestedScroll(pullToRefreshState.nestedScrollConnection)
        ) {
            if (isSearchOpen) {
                SearchResultsGrid(
                    results = searchResults,
                    isLoading = isSearching,
                    onPress = { item ->
                        onMediaClick(searchResults, searchResults.indexOf(item))
                    },
                    onLongPress = { }
                )
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondBoundsPageCount = 1
                ) { pageIndex ->
                    when (HomeTab.entries[pageIndex]) {
                        HomeTab.FOLDERS -> {
                            AnimatedContent(
                                targetState = albums.isEmpty() && !isRefreshing,
                                transitionSpec = {
                                    fadeIn(animationSpec = PandoraMotion.fadeInTween) togetherWith
                                            fadeOut(animationSpec = PandoraMotion.fadeOutTween)
                                },
                                label = "FoldersContentTransition"
                            ) { isEmpty ->
                                if (isEmpty) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("No folders found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                } else {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(2),
                                        contentPadding = PaddingValues(
                                            start = PandoraDimensions.gridPadding,
                                            top = 16.dp,
                                            end = PandoraDimensions.gridPadding,
                                            bottom = 16.dp
                                        ),
                                        horizontalArrangement = Arrangement.spacedBy(PandoraDimensions.gridGap),
                                        verticalArrangement = Arrangement.spacedBy(PandoraDimensions.gridGap),
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
                        HomeTab.ALL_MEDIA -> {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                contentPadding = PaddingValues(bottom = 16.dp, top = 8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(
                                    count = allMediaItems.itemCount,
                                    span = { index ->
                                        val item = allMediaItems[index]
                                        if (item is AllMediaUiItem.Header) GridItemSpan(4) else GridItemSpan(1)
                                    }
                                ) { index ->
                                    val item = allMediaItems[index]
                                    when (item) {
                                        is AllMediaUiItem.Header -> {
                                            Text(
                                                text = item.title,
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                ),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                            )
                                        }
                                        is AllMediaUiItem.Media -> {
                                            MediaThumbnail(
                                                uri = item.item.uri,
                                                filePath = item.item.filePath,
                                                thumbUri = item.item.thumbUri,
                                                mediaType = item.item.mediaType,
                                                duration = item.item.duration,
                                                isFavorite = item.item.isFavorite,
                                                isSelected = false,
                                                onPress = {
                                                    val mediaList = mutableListOf<MediaItem>()
                                                    for (j in 0 until allMediaItems.itemCount) {
                                                        (allMediaItems[j] as? AllMediaUiItem.Media)?.item?.let { mediaList.add(it) }
                                                    }
                                                    onMediaClick(mediaList, mediaList.indexOf(item.item))
                                                },
                                                onLongPress = { }
                                            )
                                        }
                                        null -> {
                                            Box(
                                                Modifier
                                                    .aspectRatio(1f)
                                                    .padding(1.dp)
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (pullToRefreshState.verticalOffset > 0 || pullToRefreshState.isRefreshing) {
                PullToRefreshContainer(
                    state = pullToRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                )
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
        val selectedAlbums = albums.filter { it.id in selectedIds }
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
