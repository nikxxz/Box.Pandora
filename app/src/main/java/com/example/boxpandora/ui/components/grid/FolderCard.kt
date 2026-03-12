package com.example.boxpandora.ui.components.grid

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.util.Formatters
import com.example.boxpandora.ui.theme.PandoraDimensions
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderCard(
    album: Album,
    isSelected: Boolean = false,
    onPress: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardWidth = PandoraDimensions.cardWidth()
    val cardHeight = cardWidth

    val density = LocalDensity.current
    val panelHeight = cardHeight * 0.55f
    val tabLift = maxOf(16.dp, panelHeight * 0.13f)
    val tabLiftPx = with(density) { tabLift.toPx() }
    val tabRadiusPx = with(density) { 10.dp.toPx() }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed || isSelected) 0.97f else 1f,
        animationSpec = if (isPressed) {
            tween(durationMillis = 120)
        } else {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        },
        label = "cardScale"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        label = "borderColor"
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
        tonalElevation = if (isSelected) 8.dp else 4.dp,
        shadowElevation = if (isSelected) 12.dp else 8.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onPress,
                    onLongClick = onLongPress
                )
                .border(
                    width = if (isSelected) 6.dp else 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(PandoraDimensions.cardBorderRadius)
                )
                .clip(RoundedCornerShape(PandoraDimensions.cardBorderRadius))
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .blur(if (isSelected) 5.dp else 5.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (isSelected) 0.5f else 0.35f))
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight * 0.65f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                        )
                    )
            )

            // Selection Overlay
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                )
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .size(28.dp)
                )
            }

            if (album.isPinned) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = "Pinned",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(18.dp)
                        .graphicsLayer(rotationZ = 45f)
                )
            }

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
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(
                            horizontal = (cardWidth.value * 0.078f).dp,
                            vertical   = (cardWidth.value * 0.055f).dp
                        )
                ) {
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(
                            text     = album.name,
                            style    = MaterialTheme.typography.bodyMedium.copy(
                                color      = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                                fontSize   = (cardWidth.value * 0.088f).sp
                            ),
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text  = Formatters.formatCount(album.mediaCount),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color      = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize   = (cardWidth.value * 0.062f).sp
                                )
                            )
                            Text(
                                text  = " items",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f),
                                    fontSize = (cardWidth.value * 0.052f).sp
                                )
                            )
                        }
                    }
                }
            }

            if (album.isHidden) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "HIDDEN",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            fontSize = 8.sp
                        )
                    )
                }
            }
        }
    }
}
