package com.example.boxpandora.ui.components.grid

import android.util.Log
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
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import com.example.boxpandora.data.util.Formatters
import java.io.File

private const val TAG = "MediaThumbnail"

/**
 * Grid thumbnail cell.
 *
 * Accepts individual stable primitive fields instead of a full MediaItem object.
 * This makes the composable SKIPPABLE by the Compose compiler: if none of the
 * declared parameters change between recompositions, Compose skips the body
 * entirely — no request rebuild, no Coil lookup, no placeholder flash.
 *
 * Stability contract:
 *   String / String? / Double? / Int / Boolean are all stable Compose types.
 *   Lambdas are stable when they do not capture mutable state (they are hoisted
 *   from the call site).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaThumbnail(
    uri: String,
    filePath: String?,
    mediaType: String,
    duration: Double?,
    isFavorite: Int,
    isSelected: Boolean,
    onPress: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // The cache key is the stable identity used by both Coil memory and disk caches.
    // Computed from filePath (preferred) or uri (fallback) — identical logic to before,
    // but now the remember key is the final string rather than derived inputs.
    val cacheKey = remember(filePath, uri) { filePath?.takeIf { it.isNotEmpty() } ?: uri }

    // imageModel is the data Coil actually loads from.  File is preferred over URI
    // because it lets Coil skip ContentResolver overhead.
    val imageModel = remember(cacheKey) {
        filePath?.takeIf { it.isNotEmpty() }?.let { File(it) } ?: uri
    }

    // The request is memoised by cacheKey alone.  As long as the file path / uri
    // does not change, the exact same ImageRequest object is returned — Coil's
    // AsyncImage uses referential equality on the model to decide whether to
    // re-execute, so a stable reference here prevents spurious re-decodes.
    val request = remember(cacheKey) {
        ImageRequest.Builder(context)
            .data(imageModel)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            // Fixed decode size prevents pileup during fast scroll — Coil can start
            // decoding before Compose finishes measuring the cell.
            .size(Size(600, 600))
            // VideoFrameDecoder is only needed for video items; attaching it to image
            // requests adds unnecessary factory-probe overhead.
            .apply { if (mediaType == "video") decoderFactory(VideoFrameDecoder.Factory()) }
            // No crossfade: even 150 ms is visible when cells remap after a list
            // update and some items reload from disk cache.
            .crossfade(false)
            // --- TEMPORARY DEBUG LISTENER — remove once flash is confirmed fixed ---
            .listener(object : ImageRequest.Listener {
                override fun onStart(request: ImageRequest) {
                    Log.d(TAG, "START  key=${cacheKey.takeLast(40)}")
                }
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    Log.d(TAG, "HIT    key=${cacheKey.takeLast(40)} src=${result.dataSource}")
                }
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    Log.d(TAG, "ERROR  key=${cacheKey.takeLast(40)}")
                }
            })
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
        // Placeholder color lives here as a permanent background layer, NOT as the
        // AsyncImage placeholder parameter.  This prevents Coil from ever "reverting"
        // to a placeholder state during recomposition: the surfaceVariant colour is
        // always visible underneath; the decoded image paints on top and stays there.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )

        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // No placeholder or error painter: the surfaceVariant Box behind this
            // composable is permanently visible and handles both states.  Passing
            // placeholder here would let Coil swap back to it during recomposition,
            // which is exactly the flash we are eliminating.
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
                        text = duration?.let { Formatters.formatDuration((it * 1000).toLong()) } ?: "0:00",
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
                tint = Color(0xFFFFD700),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .size(18.dp)
            )
        }
    }
}
