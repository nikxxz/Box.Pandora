package com.example.boxpandora.ui.main

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.util.Formatters
import com.example.boxpandora.ui.components.media.VideoPlayer
import com.example.boxpandora.ui.theme.PandoraSpacing

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
    
    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
    ) {
        // Main Pager
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 16.dp,
            beyondBoundsPageCount = 1
        ) { page ->
            val item = items[page]
            MediaPage(
                item = item,
                isActive = page == pagerState.currentPage,
                onToggleUI = { isPanelVisible = !isPanelVisible }
            )
        }

        // Overlay Controls
        AnimatedVisibility(
            visible = !isPanelVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top Bar
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
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.3f))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    
                    IconButton(
                        onClick = { /* More options */ },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.3f))
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                    }
                }

                // Bottom Quick Info (Peek Strip)
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

        // Sliding Info Panel
        if (isPanelVisible && currentItem != null) {
            InfoPanel(
                item = currentItem,
                onDismiss = { isPanelVisible = false }
            )
        }
    }
}

@Composable
fun MediaPage(
    item: MediaItem,
    isActive: Boolean,
    onToggleUI: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onToggleUI() })
            },
        contentAlignment = Alignment.Center
    ) {
        if (item.mediaType == "video") {
            VideoPlayer(
                uri = item.uri,
                isPlaying = isActive,
                isMuted = true,
                onVideoClick = onToggleUI,
                onProgress = { _, _ -> }
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.uri)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoPanel(
    item: MediaItem,
    onDismiss: () -> Unit
) {
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
            // Header Row: Date and Time
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

            // Meta Grid
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
fun MetaCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
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
