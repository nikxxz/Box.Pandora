package com.example.boxpandora.ui.components.grid

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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.size.Size
import com.example.boxpandora.data.util.Formatters
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaThumbnail(
    uri: String,
    filePath: String?,
    thumbUri: String? = null,
    mediaType: String,
    duration: Double?,
    isFavorite: Int,
    isSelected: Boolean,
    onPress: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 0.dp
) {
    val context = LocalContext.current
    val cacheKey = remember(thumbUri, filePath, uri) { 
        thumbUri?.takeIf { it.isNotEmpty() } ?: filePath?.takeIf { it.isNotEmpty() } ?: uri 
    }
    
    val imageModel = remember(cacheKey) {
        thumbUri?.takeIf { it.isNotEmpty() } ?: 
        filePath?.takeIf { it.isNotEmpty() }?.let { File(it) } ?: 
        uri
    }

    val request = remember(cacheKey) {
        ImageRequest.Builder(context)
            .data(imageModel)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            // Reduced size for grid thumbnails to improve performance and memory usage.
            // 300x300 is sufficient for 4-column grid on most devices.
            .size(Size(300, 300))
            .apply { if (mediaType == "video") decoderFactory(VideoFrameDecoder.Factory()) }
            .crossfade(true)
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
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(if (isSelected) 12.dp else 0.dp)
            )
            .combinedClickable(
                onClick = onPress,
                onLongClick = onLongPress
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )

        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
                .graphicsLayer {
                    clip = true
                    shape = RoundedCornerShape(if (isSelected) 12.dp else 0.dp)
                }
        )

        // Overlay for better contrast
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (blurRadius > 0.dp) 0.25f else 0f))
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

        // Video badge
        if (mediaType == "video" && !isSelected) {
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
                        // MediaItem.duration is stored as seconds (Double). Formatters.formatDuration
                        // expects milliseconds, so convert here to ms.
                        text = duration?.let { Formatters.formatDuration((it * 1000.0).toLong()) } ?: "0:00",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Favorite badge
        if (isFavorite == 1 && !isSelected) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .size(18.dp)
            )
        }
    }
}
