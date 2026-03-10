package com.example.boxpandora.ui.components.grid

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.util.Formatters
import com.example.boxpandora.ui.theme.PandoraDimensions
import java.io.File
import java.util.Calendar

@Composable
fun FolderCard(
    album: Album,
    onPress: (Album) -> Unit,
    modifier: Modifier = Modifier
) {
    val cardWidth = PandoraDimensions.cardWidth()
    val cardHeight = cardWidth

    val density = LocalDensity.current
    val panelHeight = cardHeight * 0.52f
    val tabLift = maxOf(16.dp, panelHeight * 0.204f)
    val tabLiftPx = with(density) { tabLift.toPx() }
    val tabRadiusPx = with(density) { 10.dp.toPx() }

    val dateParts = Formatters.formatShortDateParts(album.lastModifiedAt)
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val isSameYear = dateParts.year == currentYear.toString()

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "cardScale"
    )

    val context = LocalContext.current
    val imageModel = remember(album.coverFilePath, album.coverUri) {
        album.coverFilePath?.let { File(it) } ?: album.coverUri
    }
    
    val cacheKey = remember(album.id, album.lastModifiedAt) {
        "cover-${album.id}-${album.lastModifiedAt}"
    }

    val request = remember(imageModel, cacheKey) {
        ImageRequest.Builder(context)
            .data(imageModel)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .decoderFactory(VideoFrameDecoder.Factory())
            .crossfade(200)
            .build()
    }

    Surface(
        modifier = modifier
            .size(width = cardWidth, height = cardHeight)
            .padding(bottom = 8.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(PandoraDimensions.cardBorderRadius),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) { onPress(album) }
                .border(
                    width = 5.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(PandoraDimensions.cardBorderRadius)
                )
                .clip(RoundedCornerShape(PandoraDimensions.cardBorderRadius - 5.dp))
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(panelHeight + tabLift)
                    .clip(FolderTabShape(tabLiftPx, tabRadiusPx))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .padding(start = (cardWidth.value * 0.089f).dp)
                        .height(tabLift)
                        .align(Alignment.TopStart),
                    verticalArrangement = Arrangement.Center
                ) {
                    if (isSameYear) {
                        Row {
                            Text(
                                text = dateParts.month,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = (cardWidth.value * 0.067f).sp
                                )
                            )
                            Text(
                                text = " ${dateParts.day}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = (cardWidth.value * 0.067f).sp
                                )
                            )
                        }
                    } else {
                        Text(
                            text = if (dateParts.year.isEmpty()) "--"
                                   else "${currentYear - dateParts.year.toInt()} years ago",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = (cardWidth.value * 0.067f).sp
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(
                            horizontal = (cardWidth.value * 0.078f).dp,
                            vertical = (cardWidth.value * 0.045f).dp
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.W400,
                            fontSize = (cardWidth.value * 0.1f).sp
                        ),
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = Formatters.formatCount(album.mediaCount),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = (cardWidth.value * 0.056f).sp
                        )
                    )
                }
            }

            if (album.isHidden) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "HIDDEN",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    )
                }
            }
        }
    }
}
