package com.example.boxpandora.ui.components.grid

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.painter.ColorPainter
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.util.Formatters
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaThumbnail(
    item: MediaItem,
    isSelected: Boolean = false,
    onPress: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val imageModel = remember(item.filePath, item.uri) {
        item.filePath?.takeIf { it.isNotEmpty() }?.let { File(it) } ?: item.uri
    }

    // Cache key uses only the file path (or URI as fallback) — stable across syncs.
    // Including deviceModifiedAt/fileSize caused cache misses: after every sync
    // the Room Flow re-emits a new MediaItem object; if those fields differed
    // even slightly between the old DB value and the fresh MediaStore value, Coil
    // would miss the memory cache, reload from disk (~300 ms), and show the
    // placeholder — producing the "thumbnails flash blank" effect.
    // The file path alone uniquely identifies the cached thumbnail.
    val cacheKey = remember(item.filePath, item.uri) {
        item.filePath ?: item.uri
    }

    val request = remember(imageModel, cacheKey) {
        ImageRequest.Builder(context)
            .data(imageModel)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .decoderFactory(VideoFrameDecoder.Factory())
            .crossfade(150)
            .build()
    }

    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "thumbnailScale"
    )

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(if (isSelected) 8.dp else 1.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(if (isSelected) 12.dp else 0.dp))
            .combinedClickable(
                onClick = onPress,
                onLongClick = onLongPress
            )
    ) {
        val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // Show the theme surface colour immediately so the grid is never
            // blank while Coil decodes. The crossfade in the request fades
            // the real image in over the placeholder once ready.
            placeholder = remember(placeholderColor) { ColorPainter(placeholderColor) },
            error       = remember(placeholderColor) { ColorPainter(placeholderColor) },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    clip = true
                    shape = RoundedCornerShape(if (isSelected) 12.dp else 0.dp)
                }
        )

        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            )
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(24.dp)
            )
        }

        // Video Badge
        if (item.mediaType == "video" && !isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = item.duration?.let { Formatters.formatDuration((it * 1000).toLong()) } ?: "0:00",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Favorite Badge
        if (item.isFavorite == 1 && !isSelected) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                tint = Color(0xFFFFD700),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .size(18.dp)
            )
        }
    }
}
