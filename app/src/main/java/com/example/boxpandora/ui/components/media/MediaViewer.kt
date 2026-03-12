package com.example.boxpandora.ui.components.media

import android.annotation.SuppressLint
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.data.util.Formatters
import com.example.boxpandora.ui.common.AppDialog
import com.example.boxpandora.ui.common.AppContextMenu
import com.example.boxpandora.ui.common.AppContextMenuItem
import com.example.boxpandora.ui.common.DeleteConfirmationDialog
import com.example.boxpandora.ui.common.FolderSelectorDialog
import com.example.boxpandora.ui.common.ModalChip
import com.example.boxpandora.ui.common.ModalHeader
import com.example.boxpandora.ui.common.ModalSection
import com.example.boxpandora.ui.common.ModalTextField
import com.example.boxpandora.ui.common.RenameDialog
import com.example.boxpandora.ui.theme.PandoraSpacing
import com.example.boxpandora.ui.theme.boxPandoraModalTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle as DateTimeTextStyle
import java.util.Locale

private var sharedVideoVolume by mutableFloatStateOf(0.5f)

private val PanelBg    = Color(0xFF080808)
private val CardBg     = Color(0xFF1C1C1E)

private val LabelColor = Color(0xFF8E8E93)

private const val FLING_VELOCITY = 500f
private const val FallbackMaxFraction = 0.45f

// Calmer animation spec for info panel
private val PanelAnimationSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow
)

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaViewer(
    items: List<MediaItem>,
    initialIndex: Int,
    onBackClick: () -> Unit,
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

    val pagerState = rememberPagerState(initialPage = initialIndex) { items.size }
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
    val allAlbums by viewModel.allAlbums.collectAsState()

    LaunchedEffect(pagerState.currentPage) {
        isZoomed = false
    }

    LaunchedEffect(panelFraction.value) {
        if (panelFraction.value > 0.05f) isControlsVisible = false
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val screenHeightPx = constraints.maxHeight.toFloat()
        val maxFraction = if (contentHeightPx > 0f && screenHeightPx > 0f)
            (contentHeightPx / screenHeightPx).coerceIn(0.35f, 0.65f)
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
                                "Delete" -> showDeleteDialog = true
                            }
                        }
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelHeightDp)
                    .background(PanelBg)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val vt = VelocityTracker()
                            vt.addPosition(down.uptimeMillis, down.position)
                            var prevY = down.position.y
                            var totalDy = 0f
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
                                    if (!locked) {
                                        if (kotlin.math.abs(dy) > viewConfiguration.touchSlop) {
                                            isVertical = true; locked = true
                                        }
                                    }
                                    if (isVertical) {
                                        totalDy += dy
                                        change.consume()
                                    }
                                    prevY = change.position.y
                                }
                            }
                            if (isVertical) {
                                val velocity = vt.calculateVelocity().y
                                val shouldClose = totalDy > viewConfiguration.touchSlop * 4 ||
                                    velocity > FLING_VELOCITY / 2
                                scope.launch {
                                    panelFraction.animateTo(
                                        if (shouldClose) 0f else maxFraction,
                                        PanelAnimationSpec
                                    )
                                }
                            }
                        }
                    }
            ) {
                if (currentItem != null) {
                    InfoPanelContent(
                        item = currentItem,
                        viewModel = viewModel,
                        onHeightMeasured = { if (it > 0f) contentHeightPx = it }
                    )
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
                album.path?.let { path ->
                    viewModel.moveItem(currentItem, path) {
                        if (items.size <= 1) onBackClick()
                    }
                }
                showMoveDialog = false
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
    var showMenu by remember { mutableStateOf(false) }
    val options = listOf("Open With", "Share", "Rename", "Copy To", "Move To", "Delete")
    val density = LocalDensity.current

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(180)) +
                slideInVertically(
                    initialOffsetY = { with(density) { -24.dp.roundToPx() } },
                    animationSpec = tween(180)
                ),
        exit = fadeOut(animationSpec = tween(150)) +
               slideOutVertically(
                   targetOffsetY = { with(density) { -24.dp.roundToPx() } },
                   animationSpec = tween(150)
               )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(0.6f), Color.Transparent)))
                .statusBarsPadding()
                .padding(PandoraSpacing.md)
        ) {
            IconButton(onClick = onBackClick, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            if (title.isNotEmpty()) {
                Text(
                    text = title,
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
                    color = Color.White
                )
            }
            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                }
                AppContextMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    AppContextMenuItem("Open With", onClick = { showMenu = false; onAction("Open With") }, icon = Icons.AutoMirrored.Filled.OpenInNew)
                    AppContextMenuItem("Share", onClick = { showMenu = false; onAction("Share") }, icon = Icons.Default.Share)
                    AppContextMenuItem("Rename", onClick = { showMenu = false; onAction("Rename") }, icon = Icons.Default.Edit)
                    AppContextMenuItem("Copy To", onClick = { showMenu = false; onAction("Copy To") }, icon = Icons.Default.ContentCopy)
                    AppContextMenuItem("Move To", onClick = { showMenu = false; onAction("Move To") }, icon = Icons.Default.FolderOpen)
                    AppContextMenuItem("Delete", onClick = { showMenu = false; onAction("Delete") }, icon = Icons.Default.Delete, destructive = true)
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
            onToggleUI = onToggleUI,
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
    var isMuted by remember { mutableStateOf(false) }

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
                                if (ay > viewConfiguration.touchSlop || ax > viewConfiguration.touchSlop) {
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

        LaunchedEffect(seekToRequest) { if (seekToRequest != null) seekToRequest = null }

        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.8f))))
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 32.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column {
                        Slider(
                            value = progress.toFloat(),
                            onValueChange = { progress = it.toLong() },
                            onValueChangeFinished = { seekToRequest = progress },
                            valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(0.3f)
                            )
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatDuration(progress), color = Color.White, style = MaterialTheme.typography.labelSmall)
                            Text(formatDuration(duration), color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { isMuted = !isMuted }) {
                            Icon(if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.White)
                        }
                        IconButton(onClick = { seekToRequest = (progress - 10000).coerceAtLeast(0) }) {
                            Icon(Icons.Default.Replay10, null, tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        IconButton(
                            onClick = { isPlaying = !isPlaying },
                            modifier = Modifier.size(72.dp).background(Color.White, CircleShape)
                        ) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(40.dp))
                        }
                        IconButton(onClick = { seekToRequest = (progress + 10000).coerceAtMost(duration) }) {
                            Icon(Icons.Default.Forward10, null, tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        IconButton(onClick = { }) {
                            Icon(Icons.Default.Repeat, null, tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoPanelContent(item: MediaItem, viewModel: MediaViewerViewModel, onHeightMeasured: (Float) -> Unit) {
    val tags by viewModel.getTagsForMedia(item.uri).collectAsState(initial = emptyList())
    val suggestions by viewModel.getSuggestionsForMedia(item.uri).collectAsState()
    var showTagsDialog by remember { mutableStateOf(false) }
    val albumLabel = item.albumName ?: "Library"
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
    val addedLabel = remember(item.indexedAt) { formatIndexedTimestamp(item.indexedAt) }
    val tokens = boxPandoraModalTokens()

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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .onSizeChanged { if (it.height > 0) onHeightMeasured(it.height.toFloat()) }
            .padding(horizontal = 18.dp)
            .padding(top = 10.dp)
            .navigationBarsPadding()
            .padding(bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = dayOfWeek,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Medium, fontSize = 30.sp
                    )
                )
                dateString?.let {
                    Text(text = it, color = LabelColor, style = MaterialTheme.typography.bodyMedium)
                }
            }
            StatusPill(
                icon = if (item.isFavorite == 1) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                label = if (item.isFavorite == 1) "Favorite" else "Normal",
                accent = if (item.isFavorite == 1) Color(0xFFFF375F) else tokens.secondaryText
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusPill(icon = Icons.Default.Folder, label = albumLabel)
            StatusPill(icon = Icons.AutoMirrored.Filled.Label, label = if (tags.isEmpty()) "No tags" else "${tags.size} tag${if (tags.size == 1) "" else "s"}")
            if (item.mediaType == "video" && item.duration != null) {
                StatusPill(icon = Icons.Default.PlayArrow, label = formatDuration(item.duration.toLong()))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ManageTagsButton(onClick = { showTagsDialog = true })
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (tags.isEmpty()) {
                    Text(
                        text = "Manage tags",
                        color = LabelColor.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    tags.forEach { tag -> 
                        TagPill(tag.name) {
                            viewModel.removeTag(item, tag)
                        }
                    }
                }
            }
        }

        if (suggestions.isNotEmpty()) {
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
                    suggestions.forEach { tagKey ->
                        SuggestionReviewChip(
                            text = tagKey,
                            onAccept = { viewModel.acceptSuggestion(item, tagKey) },
                            onReject = { viewModel.rejectSuggestion(item, tagKey) }
                        )
                    }
                }
            }
        }

        InfoCard(label = "FILENAME", value = item.filename, modifier = Modifier.fillMaxWidth())

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoCard(label = "DIMENSIONS", value = "${item.width} × ${item.height}", modifier = Modifier.weight(1f))
            InfoCard(label = "ASPECT", value = "$aspectRatio • $orientation", modifier = Modifier.weight(1f))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoCard(label = "TYPE",      value = item.mediaType.uppercase(),  modifier = Modifier.weight(1f))
            InfoCard(label = "EXTENSION", value = item.extension.uppercase(),  modifier = Modifier.weight(1f))
            InfoCard(label = "FILE SIZE", value = formatFileSize(item.fileSize), modifier = Modifier.weight(1f))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoCard(label = "ALBUM", value = albumLabel, modifier = Modifier.weight(1f))
            InfoCard(label = "MODIFIED", value = modifiedLabel, modifier = Modifier.weight(1f))
            InfoCard(label = "ADDED", value = addedLabel, modifier = Modifier.weight(1f))
        }

        item.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            InfoCard(label = "NOTES", value = notes, modifier = Modifier.fillMaxWidth())
        }
    }

    if (showTagsDialog) {
        TagsDialog(
            item = item,
            currentTags = tags,
            viewModel = viewModel,
            onDismiss = { showTagsDialog = false }
        )
    }
}

@Composable
private fun TagPill(text: String, onRemove: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF202024))
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .clickable { onRemove() }
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun SuggestionReviewChip(
    text: String,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF17171B))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium)
        IconButton(onClick = onAccept, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Check, null, tint = Color(0xFF8AE0A6), modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onReject, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Close, null, tint = Color(0xFFF36B6B), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun TagsDialog(
    item: MediaItem,
    currentTags: List<Tag>,
    viewModel: MediaViewerViewModel,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val allTags by viewModel.allTags.collectAsState()
    val tokens = boxPandoraModalTokens()
    
    val suggestions = remember(input, allTags, currentTags) {
        if (input.isBlank()) emptyList()
        else allTags.filter { 
            it.name.contains(input, ignoreCase = true) && 
            currentTags.none { ct -> ct.id == it.id }
        }.take(5)
    }

    AppDialog(onDismiss = onDismiss) {
        ModalHeader(title = "Tags")

        if (currentTags.isNotEmpty()) {
            ModalSection(title = "Current") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    currentTags.forEach { tag ->
                        ModalChip(
                            label = tag.name,
                            trailingIcon = Icons.Default.Close,
                            trailingTint = tokens.secondaryText,
                            onClick = { viewModel.removeTag(item, tag) }
                        )
                    }
                }
            }
        }

        ModalSection {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModalTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = "New tag",
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    val tag = input.trim()
                    if (tag.isNotEmpty()) {
                        viewModel.addTag(item, tag)
                        input = ""
                    }
                }) {
                    Icon(Icons.Default.Add, null, tint = tokens.selectedAccent)
                }
            }

            if (suggestions.isNotEmpty()) {
                ModalSection(title = "Suggestions") {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        suggestions.forEach { tag ->
                            ModalChip(
                                label = tag.name,
                                onClick = {
                                    viewModel.addTag(item, tag.name)
                                    input = ""
                                }
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) {
                Text("Done", color = tokens.selectedAccent)
            }
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun InfoCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .padding(horizontal = 13.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            color = LabelColor,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 3
        )
    }
}

@Composable
private fun ManageTagsButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Default.Tune, contentDescription = "Manage tags", tint = Color.White, modifier = Modifier.size(16.dp))
        Text(
            text = "Manage",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
        )
    }
}

@Composable
private fun StatusPill(
    icon: ImageVector,
    label: String,
    accent: Color = LabelColor
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(CardBg)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
        Text(
            text = label,
            color = Color.White,
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

private fun formatMediaTimestamp(timestampSeconds: Long?): String {
    if (timestampSeconds == null || timestampSeconds <= 0) return "Unknown"
    val zoned = Instant.ofEpochSecond(timestampSeconds).atZone(ZoneId.systemDefault())
    return "%d %s %d".format(
        zoned.dayOfMonth,
        zoned.month.getDisplayName(DateTimeTextStyle.SHORT, Locale.getDefault()),
        zoned.year
    )
}

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
