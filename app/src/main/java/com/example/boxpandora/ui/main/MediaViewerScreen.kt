package com.example.boxpandora.ui.main

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.util.Formatters
import com.example.boxpandora.ui.components.media.VideoPlayer
import com.example.boxpandora.ui.theme.PandoraSpacing
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState

private var sharedVideoVolume by mutableFloatStateOf(0f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaViewerScreen(
    items: List<MediaItem>,
    initialIndex: Int,
    onBackClick: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex) { items.size }
    val currentItem = items.getOrNull(pagerState.currentPage)

    var isPanelVisible by remember { mutableStateOf(false) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var isZoomed by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        isZoomed = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 16.dp,
            beyondBoundsPageCount = 1,
            userScrollEnabled = !isPanelVisible && !isZoomed
        ) { page ->
            val item = items[page]
            MediaPage(
                item = item,
                isActive = page == pagerState.currentPage,
                controlsVisible = isControlsVisible,
                onToggleUI = { isControlsVisible = !isControlsVisible },
                onZoomChanged = { zoomed ->
                    if (page == pagerState.currentPage && zoomed != isZoomed) {
                        isZoomed = zoomed
                    }
                }
            )
        }

        AnimatedVisibility(
            visible = isControlsVisible && !isPanelVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(PandoraSpacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBackClick,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.3f)
                        )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    IconButton(
                        onClick = { /* More options */ },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.3f)
                        )
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                    }
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures { isPanelVisible = true }
                        },
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(PandoraSpacing.md)
                            .navigationBarsPadding(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp, 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.outline)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = currentItem?.filename ?: "",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        if (isPanelVisible && currentItem != null) {
            InfoPanel(item = currentItem, onDismiss = { isPanelVisible = false })
        }
    }
}

@Composable
fun MediaPage(
    item: MediaItem,
    isActive: Boolean,
    controlsVisible: Boolean,
    onToggleUI: () -> Unit,
    onZoomChanged: (Boolean) -> Unit
) {
    if (item.mediaType == "video") {
        VideoPage(
            item = item, 
            isActive = isActive, 
            controlsVisible = controlsVisible,
            onToggleUI = onToggleUI
        )
        LaunchedEffect(isActive) { if (isActive) onZoomChanged(false) }
    } else {
        ZoomableImagePage(item = item, onToggleUI = onToggleUI, onZoomChanged = onZoomChanged)
    }
}

@Composable
fun ZoomableImagePage(
    item: MediaItem,
    onToggleUI: () -> Unit,
    onZoomChanged: (Boolean) -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current

    val imageRequest = remember(item.filePath, item.uri, item.deviceModifiedAt) {
        ImageRequest.Builder(context)
            .data(item.filePath?.let { File(it) } ?: item.uri)
            .memoryCacheKey("${item.filePath ?: item.uri}-${item.deviceModifiedAt}-${item.fileSize}")
            .diskCacheKey("${item.filePath ?: item.uri}-${item.deviceModifiedAt}-${item.fileSize}")
            .crossfade(true)
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
            .onSizeChanged { layoutSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggleUI() },
                    onDoubleTap = { tapOffset ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                            onZoomChanged(false)
                        } else {
                            val targetScale = 3f
                            val center = Offset(layoutSize.width / 2f, layoutSize.height / 2f)
                            val rawOffset = (center - tapOffset) * (targetScale - 1f) / targetScale
                            scale = targetScale
                            offset = clamp(rawOffset, targetScale)
                            onZoomChanged(true)
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var prevPositions = mutableMapOf<Long, Offset>()

                    while (true) {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed }
                        if (active.isEmpty()) break
                        val currentPositions = active.associate { it.id.value to it.position }

                        when {
                            active.size >= 2 -> {
                                val ids = active.take(2).map { it.id.value }
                                val p0prev = prevPositions[ids[0]]; val p1prev = prevPositions[ids[1]]
                                val p0curr = currentPositions[ids[0]]; val p1curr = currentPositions[ids[1]]

                                if (p0prev != null && p1prev != null && p0curr != null && p1curr != null) {
                                    val prevDist = (p1prev - p0prev).getDistance()
                                    val currDist = (p1curr - p0curr).getDistance()
                                    val zoom = if (prevDist > 0f) currDist / prevDist else 1f
                                    val prevCentroid = (p0prev + p1prev) / 2f
                                    val currCentroid = (p0curr + p1curr) / 2f
                                    val pan = currCentroid - prevCentroid

                                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                                    scale = newScale
                                    offset = if (newScale > 1f) clamp(offset + pan, newScale) else Offset.Zero
                                    onZoomChanged(newScale > 1.01f)
                                }
                                active.forEach { it.consume() }
                            }
                            active.size == 1 && scale > 1f -> {
                                val change = active[0]
                                val prev = prevPositions[change.id.value]
                                if (prev != null) {
                                    val pan = change.position - prev
                                    offset = clamp(offset + pan, scale)
                                }
                                change.consume()
                            }
                        }
                        prevPositions = currentPositions.toMutableMap()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            contentScale = ContentScale.Fit,
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
fun VideoPage(
    item: MediaItem,
    isActive: Boolean,
    controlsVisible: Boolean,
    onToggleUI: () -> Unit
) {
    var isPlaying by remember { mutableStateOf(isActive) }
    var progress by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seekToRequest by remember { mutableStateOf<Long?>(null) }
    
    LaunchedEffect(isActive) {
        isPlaying = isActive
    }

    // Auto-hide local video controls after delay
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(3500)
            if (controlsVisible) {
                onToggleUI()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        VideoPlayer(
            uri = item.uri,
            isPlaying = isPlaying,
            isMuted = false,
            volume = sharedVideoVolume,
            seekTo = seekToRequest,
            onVideoClick = {
                onToggleUI()
            },
            onProgress = { p, d ->
                progress = p
                duration = d
            }
        )

        LaunchedEffect(seekToRequest) {
            if (seekToRequest != null) seekToRequest = null
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f))
            ) {
                IconButton(
                    onClick = { isPlaying = !isPlaying },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(80.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 100.dp)
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = if (sharedVideoVolume == 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Volume",
                            tint = Color.White
                        )
                        Slider(
                            value = sharedVideoVolume,
                            onValueChange = { sharedVideoVolume = it },
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )
                    }

                    if (duration > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = formatDuration(progress),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Slider(
                                value = progress.toFloat(),
                                onValueChange = { progress = it.toLong() },
                                onValueChangeFinished = { seekToRequest = progress },
                                valueRange = 0f..duration.toFloat(),
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                )
                            )
                            Text(
                                text = formatDuration(duration),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoPanel(item: MediaItem, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    val dateParts = Formatters.formatShortDateParts(item.deviceCreatedAt)
                    Text(
                        text = dateParts.month + " " + dateParts.day,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 34.sp
                        )
                    )
                    Text(
                        text = dateParts.year,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { /* Toggle Favorite */ }) {
                    Icon(
                        imageVector = if (item.isFavorite == 1) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (item.isFavorite == 1) Color.Red else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetaCard(label = "FILENAME", value = item.filename.substringBeforeLast("."), modifier = Modifier.weight(1f))
                MetaCard(label = "EXTENSION", value = item.extension.uppercase(), modifier = Modifier.weight(0.4f))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetaCard(label = "TYPE", value = item.mediaType.uppercase(), modifier = Modifier.weight(1f))
                MetaCard(label = "DIMENSIONS", value = "${item.width} x ${item.height}", modifier = Modifier.weight(1f))
                MetaCard(label = "FILE SIZE", value = Formatters.formatCount(item.fileSize.toInt()) + " B", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun MetaCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = 86.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3
            )
        }
    }
}

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
