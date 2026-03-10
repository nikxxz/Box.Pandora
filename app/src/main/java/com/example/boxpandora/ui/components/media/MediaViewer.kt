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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.util.Formatters
import com.example.boxpandora.ui.theme.PandoraSpacing
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

// Velocity (px/s) required to trigger a fling open/close
private const val FLING_VELOCITY = 500f
// Fallback fraction when content height not yet measured
private const val FallbackMaxFraction = 0.52f

// ─────────────────────────────────────────────────────────────────────────────

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaViewer(
    items: List<MediaItem>,
    initialIndex: Int,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(initialPage = initialIndex) { items.size }
    val currentItem = items.getOrNull(pagerState.currentPage)
    val scope = rememberCoroutineScope()

    var isControlsVisible by remember { mutableStateOf(true) }
    var isZoomed by remember { mutableStateOf(false) }

    val panelFraction = remember { Animatable(0f) }

    // Measured natural height of the info panel content (px); drives the snap target.
    var contentHeightPx by remember { mutableFloatStateOf(0f) }

    // Only reset zoom on page change — panel stays open across swipes
    LaunchedEffect(pagerState.currentPage) {
        isZoomed = false
    }

    // Hide controls once panel starts opening
    LaunchedEffect(panelFraction.value) {
        if (panelFraction.value > 0.05f) isControlsVisible = false
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val screenHeightPx = constraints.maxHeight.toFloat()

        // Dynamic max fraction: snaps panel to exactly the content height.
        val maxFraction = if (contentHeightPx > 0f && screenHeightPx > 0f)
            (contentHeightPx / screenHeightPx).coerceIn(0.28f, 0.88f)
        else
            FallbackMaxFraction

        fun snapPanel(velocityY: Float = 0f) {
            scope.launch {
                // Panel is binary: open = maxFraction, closed = 0f
                // Velocity check first; fall back to current position for borderline cases
                val target = when {
                    velocityY < -FLING_VELOCITY -> maxFraction   // fast swipe up  → open
                    velocityY >  FLING_VELOCITY -> 0f            // fast swipe down → close
                    panelFraction.value > maxFraction * 0.28f -> maxFraction // mostly open → open
                    else -> 0f                                               // mostly closed → close
                }
                panelFraction.animateTo(
                    target,
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                )
            }
        }

        val panelHeightDp = with(LocalDensity.current) {
            (screenHeightPx * panelFraction.value).toDp()
        }

        // ── Column layout: media shrinks upward as panel grows ───────────────
        // ContentScale.Crop fills whatever height remains — no black bars in either state.
        Column(modifier = Modifier.fillMaxSize()) {

            // Media area — weight(1f) gives it all space minus the panel
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
                        controlsVisible = isControlsVisible,
                        onToggleUI = { isControlsVisible = !isControlsVisible },
                        onZoomChanged = { zoomed ->
                            if (page == pagerState.currentPage && zoomed != isZoomed) isZoomed = zoomed
                        },
                        onDragEnd = { velocityY -> snapPanel(velocityY) }
                    )
                }

                // Header overlaid on media — standalone to avoid ColumnScope.AnimatedVisibility
                ViewerHeader(
                    isVisible = isControlsVisible,
                    title = currentItem?.albumName?.uppercase() ?: "",
                    onBackClick = onBackClick
                )
            }

            // Info panel — grows from bottom, pushing media up
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelHeightDp)
                    .background(PanelBg)
                    .pointerInput(Unit) {
                        // Panel drag: close on meaningful downward displacement OR fling — no resize
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
                                // Close if swiped down meaningfully (displacement) OR fast fling down
                                val shouldClose = totalDy > viewConfiguration.touchSlop * 4 ||
                                    velocity > FLING_VELOCITY / 2
                                scope.launch {
                                    panelFraction.animateTo(
                                        if (shouldClose) 0f else maxFraction,
                                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                                    )
                                }
                            }
                        }
                    }
            ) {
                if (currentItem != null) {
                    InfoPanelContent(
                        item = currentItem,
                        // Never overwrite a real height with 0 (fires when panel Box collapses to 0)
                        onHeightMeasured = { if (it > 0f) contentHeightPx = it }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header — extracted to avoid ColumnScope.AnimatedVisibility
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ViewerHeader(isVisible: Boolean, title: String, onBackClick: () -> Unit) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
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
            IconButton(onClick = { }, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MediaPage dispatcher
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MediaPage(
    item: MediaItem,
    isActive: Boolean,
    controlsVisible: Boolean,
    onToggleUI: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onDragEnd: (velocityY: Float) -> Unit
) {
    if (item.mediaType == "video") {
        VideoPage(
            item = item,
            isActive = isActive,
            controlsVisible = controlsVisible,
            onToggleUI = onToggleUI,
            onDragEnd = onDragEnd
        )
        LaunchedEffect(isActive) { if (isActive) onZoomChanged(false) }
    } else {
        ZoomableImagePage(
            item = item,
            onToggleUI = onToggleUI,
            onZoomChanged = onZoomChanged,
            onDragEnd = onDragEnd
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Zoomable image with direction-lock + velocity-aware swipe
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ZoomableImagePage(
    item: MediaItem,
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
                                            // Just consume — no live resize, snap happens on release
                                            change.consume()
                                        }
                                        // Horizontal at scale=1 — don't consume; HorizontalPager navigates
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
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Video page
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VideoPage(
    item: MediaItem,
    isActive: Boolean,
    controlsVisible: Boolean,
    onToggleUI: () -> Unit,
    onDragEnd: (velocityY: Float) -> Unit
) {
    var isPlaying by remember { mutableStateOf(isActive) }
    var progress by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seekToRequest by remember { mutableStateOf<Long?>(null) }
    var isMuted by remember { mutableStateOf(false) }

    LaunchedEffect(isActive) { isPlaying = isActive }

    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) { delay(3500); onToggleUI() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Velocity-aware vertical swipe for panel — taps pass through (no consume until slop)
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
                                // Just consume — snap happens on release
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

// ─────────────────────────────────────────────────────────────────────────────
// Info panel content — wrapContentHeight so panel matches content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InfoPanelContent(item: MediaItem, onHeightMeasured: (Float) -> Unit) {
    val dayOfWeek = remember(item.deviceCreatedAt) {
        Instant.ofEpochMilli(item.deviceCreatedAt ?: 0L)
            .atZone(ZoneId.systemDefault())
            .dayOfWeek
            .getDisplayName(DateTimeTextStyle.FULL, Locale.getDefault())
    }
    val dateTime = remember(item.deviceCreatedAt) {
        val zdt = Instant.ofEpochMilli(item.deviceCreatedAt ?: 0L).atZone(ZoneId.systemDefault())
        val month = zdt.month.getDisplayName(DateTimeTextStyle.FULL, Locale.getDefault())
        "%d %s %d  |  %02d:%02d".format(zdt.dayOfMonth, month, zdt.year, zdt.hour, zdt.minute)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .onSizeChanged { onHeightMeasured(it.height.toFloat()) }
            .padding(horizontal = 20.dp)
            .padding(top = 14.dp)
            .navigationBarsPadding()
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Day + favorite
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = dayOfWeek,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 34.sp
                    )
                )
                Text(text = dateTime, color = LabelColor, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = { }) {
                Icon(
                    imageVector = if (item.isFavorite == 1) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (item.isFavorite == 1) Color(0xFFFF375F) else LabelColor
                )
            }
        }

        // Dimensions + format strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardBg)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${item.width} × ${item.height}",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(item.extension.uppercase())
                Pill(Formatters.formatCount((item.fileSize / 1024).toInt()) + " KB")
            }
        }

        // Storage + name cards
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard(
                label = "STORAGE PATH",
                value = item.filePath?.substringBeforeLast("/") ?: "—",
                modifier = Modifier.weight(1f)
            )
            InfoCard(label = "NAME", value = item.filename, modifier = Modifier.weight(1f))
        }

        // Type + size cards
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard(label = "TYPE", value = item.mediaType.uppercase(), modifier = Modifier.weight(1f))
            InfoCard(
                label = "FILE SIZE",
                value = Formatters.formatCount(item.fileSize.toInt()) + " B",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun Pill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF3A3A3C))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun InfoCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .padding(horizontal = 14.dp, vertical = 14.dp)
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

// ─────────────────────────────────────────────────────────────────────────────

private fun formatDuration(millis: Long): String {
    val s = (millis / 1000) % 60
    val m = (millis / (1000 * 60)) % 60
    return "%02d:%02d".format(m, s)
}
