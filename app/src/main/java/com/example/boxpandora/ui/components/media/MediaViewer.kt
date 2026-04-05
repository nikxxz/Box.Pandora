package com.example.boxpandora.ui.components.media

import android.annotation.SuppressLint
import android.widget.Toast
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.data.repository.RichSuggestion
import com.example.boxpandora.ui.common.components.shouldShowSuggestion
import com.example.boxpandora.data.util.Formatters
import com.example.boxpandora.data.manager.FileConflictResolution
import com.example.boxpandora.ui.common.AppAssetIcon
import com.example.boxpandora.ui.common.AppContextMenu
import com.example.boxpandora.ui.common.AppContextMenuItem
import com.example.boxpandora.ui.common.DeleteConfirmationDialog
import com.example.boxpandora.ui.common.FileConflictDialog
import com.example.boxpandora.ui.common.FolderSelectorDialog
import com.example.boxpandora.ui.common.MediaPropertiesSheet
import com.example.boxpandora.ui.common.ModalTextField
import com.example.boxpandora.ui.common.ModalHeader
import com.example.boxpandora.ui.common.ModalRichRow
import com.example.boxpandora.ui.common.ModalFooterAction
import com.example.boxpandora.ui.common.ModalDivider
import com.example.boxpandora.ui.common.RenameDialog
import com.example.boxpandora.ui.common.TagPopupChip
import com.example.boxpandora.ui.common.TagPopupDialog
import com.example.boxpandora.ui.common.TagPopupSectionLabel
import com.example.boxpandora.ui.common.tagPopupChipBackground
import com.example.boxpandora.ui.common.tagPopupChipBorder
import com.example.boxpandora.ui.theme.panelEnterTransition
import com.example.boxpandora.ui.theme.panelExitTransition
import com.example.boxpandora.ui.theme.PandoraSpacing
import com.example.boxpandora.ui.theme.ModalTokens
import com.example.boxpandora.ui.theme.boxPandoraModalTokens
import com.example.boxpandora.ml.ensemble.toLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle as DateTimeTextStyle
import java.util.Locale

private var sharedVideoVolume by mutableFloatStateOf(0.5f)
// Default videos to start muted on app start; runtime-global (not persisted)
private var sharedVideoMuted by mutableStateOf(true)

// Modal/visual tokens are provided via `boxPandoraModalTokens()` to support day/night theming.

private const val FLING_VELOCITY = 500f
private const val FallbackMaxFraction = 0.45f

// Snappy tween for info panel — avoids the slow spring tail that stutters near open position
private val PanelAnimationSpec = tween<Float>(
    durationMillis = 280,
    easing = FastOutSlowInEasing
)

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaViewer(
    items: List<MediaItem>,
    initialIndex: Int,
    onBackClick: () -> Unit,
    onNavigateToTag: (Long) -> Unit = {},
    onFindSimilar: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as PandoraApp
    val viewModel: MediaViewerViewModel = viewModel(
        factory = MediaViewerViewModelFactory(
            app.repository,
            app.repository.tagRepository,
            app.database.tagChangeHistoryDao(),
            app.database.tagCooccurrenceDao()
        )
    )

    if (items.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "No media items", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onBackClick, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        return
    }

    val safeInitial = initialIndex.coerceIn(0, items.size - 1)
    val pagerState = rememberPagerState(initialPage = safeInitial) { items.size }
    val currentItem = items.getOrNull(pagerState.currentPage)
    val scope = rememberCoroutineScope()

    var isControlsVisible by remember { mutableStateOf(true) }
    var isZoomed by remember { mutableStateOf(false) }
    val panelFraction = remember { Animatable(0f) }
    var contentHeightPx by remember { mutableFloatStateOf(0f) }

    // Dialog states
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showPropertiesSheet by remember { mutableStateOf(false) }
    val allAlbums by viewModel.allAlbums.collectAsState()
    val pendingConflict by viewModel.pendingConflict.collectAsState()

    LaunchedEffect(pagerState.currentPage) {
        isZoomed = false
        currentItem?.let { viewModel.setCurrentMedia(it.uri) }
    }

    LaunchedEffect(panelFraction.value) {
        if (panelFraction.value > 0.05f) isControlsVisible = false
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val screenHeightPx = constraints.maxHeight.toFloat()
        val maxFraction = if (contentHeightPx > 0f && screenHeightPx > 0f)
            (contentHeightPx / screenHeightPx).coerceIn(0.3f, 0.45f)
        else
            FallbackMaxFraction

        fun snapPanel(velocityY: Float = 0f) {
            scope.launch {
                val target = when {
                    velocityY < -FLING_VELOCITY -> maxFraction
                    velocityY >  FLING_VELOCITY -> 0f
                    panelFraction.value > maxFraction * 0.28f -> maxFraction
                    else -> 0f
                }
                panelFraction.animateTo(target, PanelAnimationSpec)
            }
        }

        val panelHeightDp = with(LocalDensity.current) {
            (screenHeightPx * panelFraction.value).toDp()
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    pageSpacing = 16.dp,
                    beyondBoundsPageCount = 1,
                    userScrollEnabled = !isZoomed
                ) { page ->
                    val item = items[page]
                    MediaPage(
                        item = item,
                        isActive = page == pagerState.currentPage,
                        isPanelOpen = panelFraction.value > 0.02f,
                        controlsVisible = isControlsVisible,
                        onToggleUI = { isControlsVisible = !isControlsVisible },
                        onZoomChanged = { zoomed ->
                            if (page == pagerState.currentPage && zoomed != isZoomed) isZoomed = zoomed
                        },
                        onDragEnd = { velocityY -> snapPanel(velocityY) }
                    )
                }

                ViewerHeader(
                    isVisible = isControlsVisible,
                    title = currentItem?.albumName?.uppercase() ?: "",
                    onBackClick = onBackClick,
                    onAction = { action ->
                        currentItem?.let { item ->
                            when (action) {
                                "Open With" -> viewModel.openWith(context, item)
                                "Share" -> viewModel.shareItem(context, item)
                                "Rename" -> showRenameDialog = true
                                "Copy To" -> { viewModel.loadAlbums(); showCopyDialog = true }
                                "Move To" -> { viewModel.loadAlbums(); showMoveDialog = true }
                                "Properties" -> showPropertiesSheet = true
                                "Delete" -> showDeleteDialog = true
                            }
                        }
                    }
                )
                // Gradient fade into panel background
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.surface)
                            )
                        )
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelHeightDp)
                    .background(boxPandoraModalTokens().background)
                    .clipToBounds()
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = panelFraction.value > 0.02f,
                    enter = fadeIn(animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing)),
                    exit = ExitTransition.None
                ) {
                    if (currentItem != null) {
                        InfoPanelContent(
                            item = currentItem,
                            viewModel = viewModel,
                            onNavigateToTag = onNavigateToTag,
                            onFindSimilar = onFindSimilar,
                            onDragPanel = { velocityY -> snapPanel(velocityY) },
                            onHeightMeasured = { if (it > 0f) contentHeightPx = it }
                        )
                    }
                }
            }
        }
    }

    // Dialogs
    if (showDeleteDialog && currentItem != null) {
        DeleteConfirmationDialog(
            count = 1,
            isFolder = false,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                viewModel.deleteItem(currentItem) {
                    if (items.size <= 1) onBackClick()
                }
                showDeleteDialog = false
            }
        )
    }

    if (showRenameDialog && currentItem != null) {
        RenameDialog(
            initialName = currentItem.filename.substringBeforeLast("."),
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                viewModel.renameItem(currentItem, newName)
                showRenameDialog = false
            }
        )
    }

    if (showCopyDialog) {
        FolderSelectorDialog(
            title = "Copy to",
            albums = allAlbums,
            onDismiss = { showCopyDialog = false },
            onConfirm = { album ->
                currentItem?.let { item ->
                    album.path?.let { viewModel.copyItem(item, it) }
                }
                showCopyDialog = false
            }
        )
    }

    if (showMoveDialog && currentItem != null) {
        FolderSelectorDialog(
            title = "Move to",
            albums = allAlbums,
            onDismiss = { showMoveDialog = false },
            onConfirm = { album ->
                showMoveDialog = false
                album.path?.let { path ->
                    viewModel.moveItem(currentItem, path) {
                        if (items.size <= 1) onBackClick()
                    }
                }
            }
        )
    }

    if (showPropertiesSheet && currentItem != null) {
        MediaPropertiesSheet(
            item = currentItem,
            onDismiss = { showPropertiesSheet = false }
        )
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

@Composable
private fun ViewerHeader(
    isVisible: Boolean,
    title: String,
    onBackClick: () -> Unit,
    onAction: (String) -> Unit
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = panelEnterTransition(),
        exit = panelExitTransition()
    ) {
        val tokens = boxPandoraModalTokens()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            )
            var showMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                }
                AppContextMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    AppContextMenuItem("Rename", onClick = { onAction("Rename"); showMenu = false }, assetIcon = "pencil.svg")
                    AppContextMenuItem("Open With", onClick = { onAction("Open With"); showMenu = false }, icon = Icons.AutoMirrored.Filled.OpenInNew)
                    AppContextMenuItem("Copy To", onClick = { onAction("Copy To"); showMenu = false }, assetIcon = "copy.svg")
                    AppContextMenuItem("Move To", onClick = { onAction("Move To"); showMenu = false }, assetIcon = "move.svg")
                    AppContextMenuItem("Properties", onClick = { onAction("Properties"); showMenu = false }, assetIcon = "info.svg")
                    AppContextMenuItem("Share", onClick = { onAction("Share"); showMenu = false }, assetIcon = "share.svg")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = tokens.divider)
                    AppContextMenuItem("Delete", onClick = { onAction("Delete"); showMenu = false }, assetIcon = "delete.svg", destructive = true)
                }
            }
        }
    }
}

@Composable
private fun MediaPage(
    item: MediaItem,
    isActive: Boolean,
    isPanelOpen: Boolean,
    controlsVisible: Boolean,
    onToggleUI: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onDragEnd: (velocityY: Float) -> Unit
) {
    if (item.mediaType == "video") {
        VideoPage(
            item = item,
            isActive = isActive,
            isPanelOpen = isPanelOpen,
            controlsVisible = controlsVisible,
            onToggleUI = onToggleUI,
            onDragEnd = onDragEnd
        )
        LaunchedEffect(isActive) { if (isActive) onZoomChanged(false) }
    } else {
        ZoomableImagePage(
            item = item,
            isPanelOpen = isPanelOpen,
            onToggleUI = {
                onToggleUI()
            },
            onZoomChanged = onZoomChanged,
            onDragEnd = onDragEnd
        )
    }
}

@Composable
private fun ZoomableImagePage(
    item: MediaItem,
    isPanelOpen: Boolean,
    onToggleUI: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onDragEnd: (velocityY: Float) -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current

    val imageRequest = remember(item.filePath, item.uri, item.deviceModifiedAt) {
        ImageRequest.Builder(context)
            .data(item.filePath?.let { File(it) } ?: item.uri)
            .crossfade(false)
            .build()
    }

    fun clamp(raw: Offset, s: Float): Offset {
        if (s <= 1f) return Offset.Zero
        val maxX = layoutSize.width * (s - 1f) / 2f
        val maxY = layoutSize.height * (s - 1f) / 2f
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { layoutSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggleUI() },
                    onDoubleTap = { tapOffset ->
                        if (scale > 1f) {
                            scale = 1f; offset = Offset.Zero; onZoomChanged(false)
                        } else {
                            val t = 3f
                            val center = Offset(layoutSize.width / 2f, layoutSize.height / 2f)
                            scale = t
                            offset = clamp((center - tapOffset) * (t - 1f) / t, t)
                            onZoomChanged(true)
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    val vt = VelocityTracker()
                    vt.addPosition(firstDown.uptimeMillis, firstDown.position)

                    var prevPositions = mutableMapOf<Long, Offset>()
                    var directionLocked = false
                    var isVerticalGesture = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed }
                        if (active.isEmpty()) break
                        val cur = active.associate { it.id.value to it.position }

                        when {
                            active.size >= 2 -> {
                                directionLocked = true; isVerticalGesture = false
                                val ids = active.take(2).map { it.id.value }
                                val p0p = prevPositions[ids[0]]; val p1p = prevPositions[ids[1]]
                                val p0c = cur[ids[0]];           val p1c = cur[ids[1]]
                                if (p0p != null && p1p != null && p0c != null && p1c != null) {
                                    val pd = (p1p - p0p).getDistance()
                                    val cd = (p1c - p0c).getDistance()
                                    val zoom = if (pd > 0f) cd / pd else 1f
                                    val pan  = (p0c + p1c) / 2f - (p0p + p1p) / 2f
                                    val ns   = (scale * zoom).coerceIn(1f, 5f)
                                    scale  = ns
                                    offset = if (ns > 1f) clamp(offset + pan, ns) else Offset.Zero
                                    onZoomChanged(ns > 1.01f)
                                }
                                active.forEach { it.consume() }
                            }
                            active.size == 1 -> {
                                val change = active[0]
                                vt.addPosition(change.uptimeMillis, change.position)
                                val prev = prevPositions[change.id.value]
                                if (prev != null) {
                                    val delta = change.position - prev
                                    if (!directionLocked) {
                                        val ax = kotlin.math.abs(delta.x)
                                        val ay = kotlin.math.abs(delta.y)
                                        if (ax > viewConfiguration.touchSlop || ay > viewConfiguration.touchSlop) {
                                            isVerticalGesture = ay > ax * 1.3f
                                            directionLocked = true
                                        }
                                    }
                                    when {
                                        scale > 1f -> {
                                            offset = clamp(offset + delta, scale)
                                            change.consume()
                                        }
                                        isVerticalGesture && directionLocked -> {
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        }
                        prevPositions = cur.toMutableMap()
                    }

                    if (directionLocked && isVerticalGesture && scale <= 1f) {
                        onDragEnd(vt.calculateVelocity().y)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            contentScale = if (isPanelOpen) ContentScale.Crop else ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                }
        )
    }
}


@Composable
private fun VideoPage(
    item: MediaItem,
    isActive: Boolean,
    isPanelOpen: Boolean,
    controlsVisible: Boolean,
    onToggleUI: () -> Unit,
    onDragEnd: (velocityY: Float) -> Unit
) {
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seekToRequest by remember { mutableStateOf<Long?>(null) }
    var isMuted by remember { mutableStateOf(sharedVideoMuted) }
    val context = LocalContext.current
    
    // Keep local state in sync if another video control changed the global mute
    LaunchedEffect(sharedVideoMuted) {
        if (isMuted != sharedVideoMuted) isMuted = sharedVideoMuted
    }

    LaunchedEffect(isActive) { if (!isActive) isPlaying = false }

    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) { delay(3500); onToggleUI() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val vt = VelocityTracker()
                    vt.addPosition(down.uptimeMillis, down.position)
                    var prevY = down.position.y
                    var isVertical = false
                    var locked = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed }
                        if (active.isEmpty()) break
                        if (active.size == 1) {
                            val change = active[0]
                            vt.addPosition(change.uptimeMillis, change.position)
                            val dy = change.position.y - prevY
                            val dx = change.position.x - change.previousPosition.x
                            if (!locked) {
                                val ay = kotlin.math.abs(dy)
                                val ax = kotlin.math.abs(dx)
                                if (ay > viewConfiguration.touchSlop || ay > ax * 1.3f) {
                                    isVertical = ay > ax * 1.3f
                                    locked = true
                                }
                            }
                            if (isVertical && locked) {
                                change.consume()
                            }
                            prevY = change.position.y
                        }
                    }
                    if (isVertical) onDragEnd(vt.calculateVelocity().y)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (isActive) {
            VideoPlayer(
                uri = item.uri,
                isPlaying = isPlaying,
                isMuted = isMuted,
                volume = sharedVideoVolume,
                seekTo = seekToRequest,
                cropToFill = isPanelOpen,
                onVideoClick = { onToggleUI() },
                onProgress = { p, d -> progress = p; duration = d }
            )
        } else {
            // Lightweight placeholder for non-active pages to avoid creating ExoPlayer instances.
            val thumbRequest = remember(item.filePath, item.uri, item.deviceModifiedAt) {
                ImageRequest.Builder(context)
                    .data(item.filePath?.let { File(it) } ?: item.uri)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = thumbRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        LaunchedEffect(seekToRequest) { if (seekToRequest != null) seekToRequest = null }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = panelEnterTransition(),
            exit = panelExitTransition()
        ) {
            val tokens = boxPandoraModalTokens()
            val isSplit = isPanelOpen
            // Always show white icons regardless of theme
            val iconTint = Color.White
            val primaryIconTint = Color.White
            val bottomPadding = if (isSplit) 18.dp else 32.dp
            val playButtonSize = if (isSplit) 56.dp else 72.dp
            val playIconSize = if (isSplit) 28.dp else 40.dp
            val smallIconSize = if (isSplit) 24.dp else 32.dp

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAAB0B0B0))))
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, tokens.scrim)))
                        .padding(horizontal = 24.dp)
                        .padding(bottom = bottomPadding)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(if (isSplit) 12.dp else 16.dp)
                ) {
                    Column {
                        Slider(
                            value = progress.toFloat(),
                            onValueChange = { progress = it.toLong() },
                            onValueChangeFinished = { seekToRequest = progress },
                            valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
                            colors = SliderDefaults.colors(
                                thumbColor = primaryIconTint,
                                activeTrackColor = primaryIconTint,
                                inactiveTrackColor = primaryIconTint.copy(alpha = 0.32f)
                            )
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatDuration(progress), color = iconTint, style = MaterialTheme.typography.labelSmall)
                            Text(formatDuration(duration), color = iconTint, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { isMuted = !isMuted; sharedVideoMuted = isMuted }) {
                            Icon(if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, null, tint = iconTint, modifier = Modifier.size(if (isSplit) 20.dp else 24.dp))
                        }
                        IconButton(onClick = { seekToRequest = (progress - 10000).coerceAtLeast(0) }) {
                            Icon(Icons.Default.Replay10, null, tint = iconTint, modifier = Modifier.size(smallIconSize))
                        }
                        IconButton(
                            onClick = { isPlaying = !isPlaying },
                            modifier = Modifier.size(playButtonSize).background(tokens.iconBackgroundNeutral, CircleShape)
                        ) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = primaryIconTint, modifier = Modifier.size(playIconSize))
                        }
                        IconButton(onClick = { seekToRequest = (progress + 10000).coerceAtMost(duration) }) {
                            Icon(Icons.Default.Forward10, null, tint = iconTint, modifier = Modifier.size(smallIconSize))
                        }
                        IconButton(onClick = { }) {
                            Icon(Icons.Default.Repeat, null, tint = iconTint, modifier = Modifier.size(if (isSplit) 20.dp else 24.dp))
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("NewApi")
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InfoPanelContent(
    item: MediaItem,
    viewModel: MediaViewerViewModel,
    onNavigateToTag: (Long) -> Unit,
    onFindSimilar: ((String) -> Unit)?,
    onDragPanel: (Float) -> Unit,
    onHeightMeasured: (Float) -> Unit
) {
    val tags by viewModel.tagsForSelectedMedia.collectAsState()
    val suggestionObjects by viewModel.suggestionObjectsForSelectedMedia.collectAsState()
    val suggestionsLoading by viewModel.suggestionsLoading.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val allAlbums by viewModel.allAlbums.collectAsState()
    var showTagPopup by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var favPopTrigger by remember(item.uri) { mutableIntStateOf(0) }
    // Optimistic local state so icon flips immediately on press without waiting for DB round-trip
    var isFavoriteLocal by remember(item.uri) { mutableStateOf(item.isFavorite == 1) }
    LaunchedEffect(item.isFavorite) { isFavoriteLocal = item.isFavorite == 1 }
    var tagQuery by remember { mutableStateOf("") }
    var pendingRemovalTagId by remember { mutableStateOf<Long?>(null) }
    val albumLabel = remember(item.albumName, item.filePath) { resolveMediaFolderLabel(item) }
    val aspectRatio = remember(item.width, item.height) { formatAspectRatio(item.width, item.height) }
    val orientation = remember(item.width, item.height) {
        when {
            item.width == item.height && item.width > 0 -> "Square"
            item.width > item.height -> "Landscape"
            item.height > item.width -> "Portrait"
            else -> "Unknown"
        }
    }
    val modifiedLabel = remember(item.deviceModifiedAt) { formatMediaTimestamp(item.deviceModifiedAt) }
    val createdLabel = remember(item.deviceCreatedAt) { formatMediaTimestamp(item.deviceCreatedAt) }
    val addedLabel = remember(item.indexedAt) { formatIndexedTimestamp(item.indexedAt) }
    val tokens = boxPandoraModalTokens()
    val context = LocalContext.current
    val tagMatches = remember(tagQuery, allTags, tags, suggestionObjects) {
        val query = tagQuery.trim()
        val available = allTags.filter { tag ->
            tags.none { currentTag -> currentTag.id == tag.id }
        }
        if (query.isBlank()) {
            available
                .sortedWith(compareByDescending<Tag> { it.usageCount }.thenBy { it.name.lowercase() })
                .take(12)
        } else {
            available
                .filter { tag -> tag.name.contains(query, ignoreCase = true) }
                .sortedWith(
                    compareBy<Tag> { !it.name.startsWith(query, ignoreCase = true) }
                        .thenByDescending { it.usageCount }
                        .thenBy { it.name.lowercase() }
                )
                .take(15)
        }
    }

    LaunchedEffect(pendingRemovalTagId) {
        if (pendingRemovalTagId != null) {
            delay(2200)
            pendingRemovalTagId = null
        }
    }

    val timestampSec = remember(item.deviceCreatedAt, item.deviceModifiedAt) {
        listOf(item.deviceCreatedAt, item.deviceModifiedAt)
            .firstOrNull { it != null && it > 946_684_800L }
    }

    val dayOfWeek = remember(timestampSec) {
        timestampSec?.let {
            Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())
                .dayOfWeek.getDisplayName(DateTimeTextStyle.FULL, Locale.getDefault())
        } ?: "—"
    }
    val dateString = remember(timestampSec) {
        timestampSec?.let {
            val z = Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())
            "%d %s %d  |  %02d:%02d".format(
                z.dayOfMonth,
                z.month.getDisplayName(DateTimeTextStyle.SHORT, Locale.getDefault()),
                z.year, z.hour, z.minute
            )
        }
    }

    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .onSizeChanged { if (it.height > 0) onHeightMeasured(it.height.toFloat()) }
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp)
            .imePadding()
            .navigationBarsPadding()
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Row 1: Quick Actions Tool Strip + Quick Info Row
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Folder pill on left
                if (albumLabel.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(tokens.cardBackground)
                            .border(1.dp, tokens.cardBorder, RoundedCornerShape(999.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "Folder",
                            tint = tokens.selectedAccent,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = albumLabel,
                            color = tokens.selectedAccent,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Action icons on the right — order: Favourite, Share, Open With, Delete, Options

                // Favourite
                QuickActionButton(
                    assetIcon = if (isFavoriteLocal) "heart-filled.svg" else "heart.svg",
                    contentDescription = "Favorite",
                    backgroundColor = tokens.cardBackground,
                    iconColor = if (isFavoriteLocal) tokens.selectedAccent else tokens.secondaryText,
                    onClick = {
                        val wasUnfav = !isFavoriteLocal
                        isFavoriteLocal = !isFavoriteLocal
                        if (wasUnfav) favPopTrigger++
                        viewModel.toggleFavorite(item)
                    },
                    popTrigger = favPopTrigger
                )

                // Find Similar (images only)
                if (item.mediaType == "image" && onFindSimilar != null) {
                    QuickActionButton(
                        icon = Icons.Default.Collections,
                        contentDescription = "Find Similar",
                        backgroundColor = tokens.cardBackground,
                        iconColor = tokens.secondaryText,
                        onClick = { onFindSimilar(item.uri) }
                    )
                }

                // Share
                QuickActionButton(
                    assetIcon = "share.svg",
                    contentDescription = "Share",
                    backgroundColor = tokens.cardBackground,
                    iconColor = tokens.secondaryText,
                    onClick = { viewModel.shareItem(context, item) }
                )

                // Open With
                QuickActionButton(
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open With",
                    backgroundColor = tokens.cardBackground,
                    iconColor = tokens.secondaryText,
                    onClick = { viewModel.openWith(context, item) }
                )

                // Delete
                var showLocalDeleteDialog by remember { mutableStateOf(false) }
                QuickActionButton(
                    assetIcon = "delete.svg",
                    contentDescription = "Delete",
                    backgroundColor = tokens.cardBackground,
                    iconColor = tokens.accentDim,
                    onClick = { showLocalDeleteDialog = true }
                )
                if (showLocalDeleteDialog) {
                    DeleteConfirmationDialog(
                        count = 1,
                        isFolder = false,
                        onDismiss = { showLocalDeleteDialog = false },
                        onConfirm = {
                            viewModel.deleteItem(item) {}
                            showLocalDeleteDialog = false
                        }
                    )
                }

                // Options dropdown (Move To / Copy To)
                var showOptionsMenu by remember { mutableStateOf(false) }
                Box {
                    QuickActionButton(
                        icon = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        backgroundColor = tokens.cardBackground,
                        iconColor = tokens.secondaryText,
                        onClick = { showOptionsMenu = true }
                    )
                    AppContextMenu(
                        expanded = showOptionsMenu,
                        onDismissRequest = { showOptionsMenu = false }
                    ) {
                        AppContextMenuItem(
                            label = "Move to",
                            assetIcon = "move.svg",
                            onClick = {
                                showOptionsMenu = false
                                viewModel.loadAlbums()
                                showMoveDialog = true
                            }
                        )
                        AppContextMenuItem(
                            label = "Copy to",
                            assetIcon = "copy.svg",
                            onClick = {
                                showOptionsMenu = false
                                viewModel.loadAlbums()
                                showCopyDialog = true
                            }
                        )
                    }
                }
            }
            // Quick media info row
            QuickInfoRow(item = item, tokens = tokens)
        }

        // Tags card
        TagsCard(
            tags = tags,
            tokens = tokens,
            onAddTagsClick = { showTagPopup = true },
            pendingRemovalTagId = pendingRemovalTagId,
            onNavigateToTag = onNavigateToTag,
            onRemoveTag = { tag ->
                if (pendingRemovalTagId == tag.id) {
                    viewModel.removeTag(item, tag)
                    pendingRemovalTagId = null
                    Toast.makeText(context, "Removed ${tag.name}", Toast.LENGTH_SHORT).show()
                } else {
                    pendingRemovalTagId = tag.id
                    Toast.makeText(context, "Press again to remove ${tag.name}", Toast.LENGTH_SHORT).show()
                }
            }
        )

        // Structured card: FILE (primary filename + three small fields)
        InfoSectionCard(
            title = "File",
            primaryValue = item.filename,
            entries = listOf(
                "Type" to item.mediaType.uppercase(),
                "Size" to formatFileSize(item.fileSize)
            ),
            columns = 2,
            modifier = Modifier.fillMaxWidth()
        )

        // Structured card: MEDIA (resolution / aspect / duration / bitrate)
        val durationMs = item.duration?.let { (it * 1000.0).toLong() } ?: 0L
        val mediaEntries = mutableListOf<Pair<String, String>>()
        mediaEntries.add("Resolution" to "${item.width} × ${item.height}")
        mediaEntries.add("Aspect Ratio" to "$aspectRatio • $orientation")
        if (item.mediaType == "video") {
            mediaEntries.add("Duration" to formatDuration(durationMs))
        }
        mediaEntries.add("Bitrate" to "3.2 Mbps")

        InfoSectionCard(
            title = "Media",
            entries = mediaEntries,
            columns = 2,
            modifier = Modifier.fillMaxWidth()
        )

        // Structured card: DATES (captured / modified / added)
        InfoSectionCard(
            title = "Dates",
            entries = listOf(
                "Captured" to createdLabel,
                "Modified" to modifiedLabel,
                "Added" to addedLabel
            ),
            columns = 3,
            modifier = Modifier.fillMaxWidth()
        )

        val visibleSuggestions = suggestionObjects.filter { shouldShowSuggestion(it) }
        if (visibleSuggestions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Suggested Tags",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.secondaryText,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    visibleSuggestions.forEach { suggestion ->
                        val fusedMeta = if (suggestion.isFused)
                            suggestion.agreementLevel?.toLabel(suggestion.contributingModelCount)
                        else null
                        SuggestionReviewChip(
                            text = suggestion.tagKey,
                            onAccept = { viewModel.acceptSuggestion(item, suggestion.tagKey) },
                            onReject = { viewModel.rejectSuggestion(item, suggestion.tagKey) },
                            metaLabel = fusedMeta,
                        )
                    }
                }
            }
        }

        item.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            InfoCard(label = "Notes", value = notes, modifier = Modifier.fillMaxWidth())
        }
    }
    } // end CompositionLocalProvider

    // Copy Dialog - unified with FolderSelectorDialog used across app
    if (showCopyDialog) {
        FolderSelectorDialog(
            title = "Copy to",
            albums = allAlbums,
            onDismiss = { showCopyDialog = false },
            onConfirm = { album ->
                album.path?.let { viewModel.copyItem(item, it) }
                showCopyDialog = false
            }
        )
    }

    // Move Dialog - unified with FolderSelectorDialog used across app
    if (showMoveDialog) {
        FolderSelectorDialog(
            title = "Move to",
            albums = allAlbums,
            onDismiss = { showMoveDialog = false },
            onConfirm = { album ->
                showMoveDialog = false
                album.path?.let { path ->
                    viewModel.moveItem(item, path) {}
                }
            }
        )
    }

    if (showTagPopup) {
        ManageTagsPopup(
            query = tagQuery,
            onQueryChange = { tagQuery = it },
            matches = tagMatches,
            suggestionObjects = suggestionObjects,
            currentTags = tags,
            onDismiss = {
                showTagPopup = false
                tagQuery = ""
            },
            onAddTypedTag = {
                val newTag = tagQuery.trim()
                if (newTag.isNotEmpty()) {
                    viewModel.addTag(item, newTag)
                    tagQuery = ""
                }
            },
            onSelectTag = { tagName ->
                viewModel.addTag(item, tagName)
                tagQuery = ""
            },
            onAcceptSuggestion = { tagName -> viewModel.acceptSuggestion(item, tagName) },
            onRejectSuggestion = { tagName -> viewModel.rejectSuggestion(item, tagName) },
            onRemoveTag = { tag -> viewModel.removeTag(item, tag) },
            onRenameTag = { tagId, newName -> viewModel.renameTag(tagId, newName) },
            onMergeTag = { sourceId, targetId -> viewModel.mergeTag(sourceId, targetId) },
            onViewTagGallery = { tagId ->
                showTagPopup = false
                tagQuery = ""
                onNavigateToTag(tagId)
            },
            onFindSimilar = onFindSimilar?.let { finder ->
                { finder(item.uri); showTagPopup = false; tagQuery = "" }
            },
            suggestionsLoading = suggestionsLoading,
            onRefreshSuggestions = { viewModel.refreshSuggestions() }
        )
    }
}

@Composable
private fun PanelDragHandle(onDragEnd: (Float) -> Unit) {
    val tokens = boxPandoraModalTokens()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 42.dp, height = 20.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val vt = VelocityTracker()
                        vt.addPosition(down.uptimeMillis, down.position)
                        var isVertical = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val active = event.changes.filter { it.pressed }
                            if (active.isEmpty()) break
                            val change = active.first()
                            vt.addPosition(change.uptimeMillis, change.position)
                            val dy = change.position.y - down.position.y
                            if (!isVertical && kotlin.math.abs(dy) > viewConfiguration.touchSlop) {
                                isVertical = true
                            }
                            if (isVertical) {
                                change.consume()
                            }
                        }

                        if (isVertical) {
                            onDragEnd(vt.calculateVelocity().y)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(width = 42.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(tokens.rowPressedBackground)
            )
        }
    }
}

@Composable
private fun SuggestionReviewChip(
    text: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    metaLabel: String? = null,
) {
    val tokens = boxPandoraModalTokens()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tokens.rowPressedBackground)
            .border(1.dp, tokens.border, RoundedCornerShape(999.dp))
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text.uppercase(),
            color = tokens.bodyText,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
        )
        if (metaLabel != null) {
            Text(
                metaLabel,
                color = tokens.secondaryText.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall
            )
        }
        IconButton(onClick = onAccept, modifier = Modifier.size(22.dp)) {
            Icon(Icons.Default.Check, null, tint = Color(0xFF8AE0A6), modifier = Modifier.size(14.dp))
        }
        IconButton(onClick = onReject, modifier = Modifier.size(22.dp)) {
            Icon(Icons.Default.Close, null, tint = Color(0xFFF36B6B), modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * Compact suggestion chip for the tag popup.
 * Tap the chip body to accept; tap the × icon to reject.
 * Shows confidence % and source label inline to keep the chip dense.
 */
@Composable
private fun TagSuggestionPopupChip(
    suggestion: RichSuggestion,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    val tokens = boxPandoraModalTokens()
    val accent = tokens.selectedAccent
    val confidencePct = (suggestion.score * 100).toInt()
    val sourceBadge: String? = when {
        suggestion.isFused -> suggestion.agreementLevel?.toLabel(suggestion.contributingModelCount)
        suggestion.source.contains("heuristic", ignoreCase = true)    -> "rule"
        suggestion.source.contains("cooccurrence", ignoreCase = true)  -> "related"
        suggestion.source.contains("prototype", ignoreCase = true)     -> "learned"
        suggestion.source.contains("scene", ignoreCase = true)         -> "scene"
        suggestion.source.contains("person", ignoreCase = true)        -> "person"
        else -> null // "ai" / generic – no badge needed
    }
    Surface(
        onClick = onAccept,
        shape = RoundedCornerShape(999.dp),
        color = accent.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.6f))
            )
            Text(
                text = suggestion.tagKey.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = tokens.bodyText
            )
            val metaText = if (sourceBadge != null) "$confidencePct% · $sourceBadge" else "$confidencePct%"
            Text(
                text = metaText,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.secondaryText.copy(alpha = 0.72f)
            )
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onReject),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Reject suggestion",
                    tint = tokens.secondaryText.copy(alpha = 0.55f),
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagActionChip(
    text: String,
    confirmRemoval: Boolean,
    onLongPress: () -> Unit,
    onRemoveClick: () -> Unit
) {
    val tokens = boxPandoraModalTokens()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val chipBg = when {
        confirmRemoval -> tokens.destructiveAccent.copy(alpha = 0.14f)
        pressed        -> tokens.selectedAccent.copy(alpha = 0.12f)
        else           -> tokens.cardBackground
    }
    val chipBorder = if (confirmRemoval) tokens.destructiveAccent else tokens.cardBorder
    val textColor  = if (confirmRemoval) tokens.destructiveAccent else tokens.bodyText
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(chipBg)
            .border(1.dp, chipBorder, RoundedCornerShape(999.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
                onLongClick = onLongPress
            )
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
        )
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (confirmRemoval) tokens.destructiveAccent else tokens.iconBackgroundNeutral)
                .clickable(onClick = onRemoveClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove tag",
                tint = if (confirmRemoval) tokens.background else tokens.bodyText,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun ManageTagsButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(boxPandoraModalTokens().background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Default.Tune,
            contentDescription = "Manage tags",
            tint = boxPandoraModalTokens().titleText,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = "Manage",
            color = boxPandoraModalTokens().bodyText,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ManageTagsPopup(
    query: String,
    onQueryChange: (String) -> Unit,
    matches: List<Tag>,
    suggestionObjects: List<RichSuggestion>,
    currentTags: List<Tag>,
    onDismiss: () -> Unit,
    onAddTypedTag: () -> Unit,
    onSelectTag: (String) -> Unit,
    onAcceptSuggestion: (String) -> Unit,
    onRejectSuggestion: (String) -> Unit,
    onRemoveTag: (Tag) -> Unit,
    onRenameTag: (Long, String) -> Unit,
    onMergeTag: (Long, Long) -> Unit,
    onViewTagGallery: (Long) -> Unit,
    onFindSimilar: (() -> Unit)? = null,
    suggestionsLoading: Boolean = false,
    onRefreshSuggestions: () -> Unit = {}
) {
    val trimmedQuery = query.trim()
    val tokens = boxPandoraModalTokens()

    var tagActionMenuTarget by remember { mutableStateOf<Tag?>(null) }
    var renameTarget by remember { mutableStateOf<Tag?>(null) }
    var renameText by remember { mutableStateOf("") }

    val filteredSuggestions = remember(trimmedQuery, suggestionObjects) {
        val visible = suggestionObjects.filter { shouldShowSuggestion(it) }
        if (trimmedQuery.isBlank()) visible
        else visible.filter { it.tagKey.contains(trimmedQuery, ignoreCase = true) }
    }

    TagPopupDialog(
        title = "Manage Tags",
        subtitle = if (query.isBlank()) {
            "Search, create, or confirm tags from the current suggestions."
        } else {
            "Filter existing tags as you type."
        },
        query = query,
        onQueryChange = onQueryChange,
        onDismiss = onDismiss,
        onAddClick = onAddTypedTag,
        addEnabled = trimmedQuery.isNotBlank(),
        footer = {
            if (onFindSimilar != null) {
                ModalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onFindSimilar,
                        colors = ButtonDefaults.textButtonColors(contentColor = tokens.selectedAccent)
                    ) {
                        Icon(
                            Icons.Default.Collections,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "Find Similar Images",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
                        )
                    }
                }
            }
        }
    ) {
        // ── Section A: Current Tags ──────────────────────────────────────────
        if (currentTags.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Current Tags",
                        color = tokens.bodyText,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "• ${currentTags.size}",
                        color = tokens.secondaryText.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    currentTags.forEach { tag ->
                        Box {
                            CurrentTagChip(
                                tag = tag,
                                tokens = tokens,
                                onTap = { onViewTagGallery(tag.id) },
                                onLongPress = { tagActionMenuTarget = tag }
                            )
                            DropdownMenu(
                                expanded = tagActionMenuTarget?.id == tag.id,
                                onDismissRequest = { tagActionMenuTarget = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Remove tag") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        onRemoveTag(tag)
                                        tagActionMenuTarget = null
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename tag") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        renameTarget = tag
                                        renameText = tag.name
                                        tagActionMenuTarget = null
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("View tag gallery") },
                                    leadingIcon = {
                                        Icon(Icons.Default.GridView, null, modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        tagActionMenuTarget = null
                                        onViewTagGallery(tag.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Section B: ML / Heuristic Suggestions ───────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Suggested Tags",
                    color = tokens.bodyText,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
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
                    } else if (filteredSuggestions.isNotEmpty()) {
                        Text(
                            text = "• ${filteredSuggestions.size}",
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
                            contentDescription = "Refresh suggestions",
                            modifier = Modifier.size(15.dp),
                            tint = tokens.secondaryText.copy(alpha = if (suggestionsLoading) 0.3f else 0.7f)
                        )
                    }
                }
            }
            if (suggestionsLoading) {
                // loading spinner is shown in header — no body needed
            } else if (filteredSuggestions.isEmpty()) {
                Text(
                    text = "No AI suggestions for this image. Tap ↻ to retry.",
                    color = tokens.secondaryText.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    filteredSuggestions.forEach { suggestion ->
                        TagSuggestionPopupChip(
                            suggestion = suggestion,
                            onAccept = { onAcceptSuggestion(suggestion.tagKey) },
                            onReject = { onRejectSuggestion(suggestion.tagKey) }
                        )
                    }
                }
            }
        }

        // ── Section C: Add Tags (search results / manual add) ───────────────
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TagPopupSectionLabel(
                title = if (query.isBlank()) "Add Tags" else "Matching Tags",
                meta = "${matches.size} shown"
            )
            if (matches.isEmpty() && query.isNotBlank()) {
                Text(
                    text = "No match. Tap + to create \"$trimmedQuery\".",
                    color = tokens.secondaryText.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    matches.forEach { tag ->
                        TagPopupChip(
                            label = tag.name.uppercase(),
                            count = tag.usageCount,
                            backgroundColor = tagPopupChipBackground(tag.color, tokens),
                            borderColor = tagPopupChipBorder(tag.color, tokens),
                            textColor = tokens.bodyText,
                            onClick = { onSelectTag(tag.name) }
                        )
                    }
                }
            }
        }
    }

    // ── Rename dialog ────────────────────────────────────────────────────────
    renameTarget?.let { tag ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Tag") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("New name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = renameText.trim()
                    if (n.isNotEmpty()) onRenameTag(tag.id, n)
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }
}

/** Tag chip that supports long-press for the tag actions menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CurrentTagChip(
    tag: Tag,
    tokens: ModalTokens,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val bg = tagPopupChipBackground(tag.color, tokens)
    val border = tagPopupChipBorder(tag.color, tokens)
    Surface(
        modifier = Modifier.combinedClickable(onClick = onTap, onLongClick = onLongPress),
        shape = RoundedCornerShape(999.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(tokens.bodyText.copy(alpha = 0.5f))
            )
            Text(
                text = tag.name.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = tokens.bodyText
            )
            Text(
                text = tag.usageCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.bodyText.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun InfoCard(label: String, value: String, modifier: Modifier = Modifier, isBold: Boolean = false) {
    val tokens = boxPandoraModalTokens()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(tokens.cardBackground)
            .border(1.dp, tokens.cardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            text = label,
            color = tokens.tertiaryText,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            color = tokens.bodyText,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (isBold) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 14.sp
            ),
            maxLines = 3
        )
    }
}

@Composable
private fun InfoSectionCard(
    title: String,
    primaryValue: String? = null,
    entries: List<Pair<String, String>>,
    columns: Int = 2,
    modifier: Modifier = Modifier
) {
    val tokens = boxPandoraModalTokens()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(tokens.cardBackground)
            .border(1.dp, tokens.cardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            color = tokens.tertiaryText,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(10.dp))

        primaryValue?.let {
            Text(
                text = it,
                color = tokens.bodyText,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        val rows = entries.chunked(columns)
        rows.forEachIndexed { ri, row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { (label, value) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = label,
                            color = tokens.secondaryText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = value,
                            color = tokens.bodyText,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            ),
                            maxLines = 2
                        )
                    }
                }

                // Fill remaining columns with empty space if row is short
                if (row.size < columns) repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
            if (ri != rows.lastIndex) Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun QuickInfoRow(item: MediaItem, tokens: ModalTokens) {
    val infoText = buildString {
        if (item.width > 0 && item.height > 0) append("${item.width}×${item.height} • ")
        append(formatFileSize(item.fileSize))
        append(" • ")
        append(item.mediaType.replaceFirstChar { it.uppercase() })
    }
    Text(
        text = infoText,
        color = tokens.secondaryText,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
        modifier = Modifier.padding(start = 2.dp)
    )
}

@Composable
private fun TagsCard(
    tags: List<Tag>,
    tokens: ModalTokens,
    onAddTagsClick: () -> Unit,
    pendingRemovalTagId: Long?,
    onNavigateToTag: (Long) -> Unit,
    onRemoveTag: (Tag) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tokens.cardBackground)
            .border(1.dp, tokens.cardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Tags",
            color = tokens.tertiaryText,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            fontSize = 12.sp
        )
        AddTagsButton(accent = tokens.selectedAccent, onClick = onAddTagsClick)
        if (tags.isEmpty()) {
            Text(
                text = "No tags yet",
                color = tokens.tertiaryText,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tags.forEach { tag ->
                    TagActionChip(
                        text = tag.name,
                        confirmRemoval = pendingRemovalTagId == tag.id,
                        onLongPress = { onNavigateToTag(tag.id) },
                        onRemoveClick = { onRemoveTag(tag) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddTagsButton(accent: Color, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, accent, RoundedCornerShape(12.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = "Add tags",
            tint = accent,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = "Add Tags",
            color = accent,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
        )
    }
}

@Composable
private fun QuickActionButton(
    icon: ImageVector? = null,
    assetIcon: String? = null,
    contentDescription: String?,
    backgroundColor: Color,
    iconColor: Color,
    onClick: () -> Unit,
    popTrigger: Int = 0
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.82f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )

    val popScale = remember { Animatable(1f) }

    // Pop animation on every press-release (gives all buttons the same feel)
    var hadPress by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) {
            hadPress = true
        } else if (hadPress) {
            hadPress = false
            popScale.snapTo(1f)
            popScale.animateTo(
                1.22f,
                spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessHigh)
            )
            popScale.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
            )
        }
    }

    // Larger explicit pop (e.g. favourite activated)
    LaunchedEffect(popTrigger) {
        if (popTrigger > 0) {
            popScale.snapTo(1f)
            popScale.animateTo(
                1.35f,
                spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessHigh)
            )
            popScale.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
            )
        }
    }

    val pressedOverlayAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.16f else 0f,
        animationSpec = tween(durationMillis = 80)
    )

    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = pressScale * popScale.value
                scaleY = pressScale * popScale.value
            }
            .clip(RoundedCornerShape(18.dp))
            .background(backgroundColor)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (pressedOverlayAlpha > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = pressedOverlayAlpha))
            )
        }
        if (assetIcon != null) {
            AppAssetIcon(
                assetIcon = assetIcon,
                tint = iconColor,
                modifier = Modifier.size(20.dp)
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

private fun resolveMediaFolderLabel(item: MediaItem): String {
    val albumName = item.albumName?.trim().orEmpty()
    val folderName = item.filePath
        ?.let { path -> File(path).parentFile?.name }
        ?.trim()
        .orEmpty()

    return when {
        folderName.isNotBlank() && (albumName.isBlank() || albumName.equals("Library", ignoreCase = true)) -> folderName
        albumName.isNotBlank() -> albumName
        folderName.isNotBlank() -> folderName
        else -> "Library"
    }
}

@Composable
private fun StatusPill(
    icon: ImageVector,
    label: String,
    accent: Color = Color.Unspecified
) {
    val tokens = boxPandoraModalTokens()
    val resolvedAccent = if (accent == Color.Unspecified) tokens.secondaryText else accent
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tokens.background)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = resolvedAccent, modifier = Modifier.size(14.dp))
        Text(
            text = label,
            color = tokens.bodyText,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
        )
    }
}

private fun formatAspectRatio(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return "Unknown"
    val divisor = gcd(width, height)
    return "${width / divisor}:${height / divisor}"
}

private fun gcd(first: Int, second: Int): Int {
    var left = first
    var right = second
    while (right != 0) {
        val remainder = left % right
        left = right
        right = remainder
    }
    return left.coerceAtLeast(1)
}

@SuppressLint("NewApi")
private fun formatMediaTimestamp(timestampSeconds: Long?): String {
    if (timestampSeconds == null || timestampSeconds <= 0) return "Unknown"
    val zoned = Instant.ofEpochSecond(timestampSeconds).atZone(ZoneId.systemDefault())
    return "%d %s %d".format(
        zoned.dayOfMonth,
        zoned.month.getDisplayName(DateTimeTextStyle.SHORT, Locale.getDefault()),
        zoned.year
    )
}

@SuppressLint("NewApi")
private fun formatIndexedTimestamp(timestampMillis: Long): String {
    if (timestampMillis <= 0L) return "Unknown"
    val zoned = Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault())
    return "%d %s %d".format(
        zoned.dayOfMonth,
        zoned.month.getDisplayName(DateTimeTextStyle.SHORT, Locale.getDefault()),
        zoned.year
    )
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024L      -> "%.1f KB".format(bytes / 1024.0)
    else                -> "$bytes B"
}

private fun formatDuration(millis: Long): String {
    val s = (millis / 1000) % 60
    val m = (millis / (1000 * 60)) % 60
    return "%02d:%02d".format(m, s)
}
