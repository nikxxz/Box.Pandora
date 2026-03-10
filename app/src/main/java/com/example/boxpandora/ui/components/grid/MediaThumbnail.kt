package com.example.boxpandora.ui.components.grid

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onPress: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val imageModel = remember(item.filePath, item.uri) {
        item.filePath?.takeIf { it.isNotEmpty() }?.let { File(it) } ?: item.uri
    }

    val cacheKey = remember(item.filePath, item.uri, item.deviceModifiedAt, item.fileSize) {
        "${item.filePath ?: item.uri}-${item.deviceModifiedAt}-${item.fileSize}"
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

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .combinedClickable(
                onClick = onPress,
                onLongClick = onLongPress
            )
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        if (item.mediaType == "video") {
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

        if (item.isFavorite == 1) {
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
