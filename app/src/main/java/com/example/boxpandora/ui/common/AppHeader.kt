package com.example.boxpandora.ui.common

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppHeader(
    title: String = "pandora",
    subtitle: String? = null,
    onMenuClick: (() -> Unit)? = null,
    onBackClick: (() -> Unit)? = null,
    onSearchClick: () -> Unit = {},
    selectionCount: Int = 0,
    onClearSelection: () -> Unit = {},
    showHideOption: String = "Hide", // "Hide" or "Show"
    isPinned: Boolean = false,
    allowOpenWith: Boolean = true,
    onActionClick: (String) -> Unit = {},
    scrollProgress: Float = 1f // 1.0 = expanded, 0.0 = shrunk
) {
    val isSelectionMode = selectionCount > 0
    var showMenu by remember { mutableStateOf(false) }

    // Interpolate values based on scrollProgress
    val titleSize = (22 + (12 * scrollProgress)).sp // 22sp to 34sp
    val titleLetterSpacing = (0.5 + (3.5 * scrollProgress)).sp // 0.5sp to 4sp
    
    // Adjusted padding for better vertical centering when collapsed
    val verticalPaddingTop = (0 + (8 * scrollProgress)).dp // 0dp to 8dp
    val verticalPaddingBottom = (0 + (12 * scrollProgress)).dp // 0dp to 12dp
    
    val iconScale = 0.75f + (0.25f * scrollProgress) // 0.75 to 1.0

    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = verticalPaddingTop, bottom = verticalPaddingBottom)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    AnimatedContent(
                        targetState = isSelectionMode,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "header_content"
                    ) { selecting ->
                        if (selecting) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onClearSelection) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                                Text(
                                    text = "$selectionCount selected",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier.padding(start = 8.dp),
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (onBackClick != null) {
                                    IconButton(
                                        onClick = onBackClick,
                                        modifier = Modifier
                                            .size(if (scrollProgress < 0.5f) 32.dp else 48.dp)
                                            .graphicsLayer {
                                                scaleX = iconScale
                                                scaleY = iconScale
                                            }
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back",
                                            tint = MaterialTheme.colorScheme.onBackground
                                        )
                                    }
                                    if (scrollProgress > 0.5f) {
                                        Spacer(Modifier.width(4.dp))
                                    }
                                }
                                
                                Column(
                                    modifier = Modifier.wrapContentHeight(),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = title.lowercase(),
                                        style = MaterialTheme.typography.displayMedium.copy(
                                            fontWeight = FontWeight.W200,
                                            letterSpacing = titleLetterSpacing,
                                            fontSize = titleSize
                                        ),
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                        modifier = Modifier.align(Alignment.Start)
                                    )
                                    
                                    val subtitleAlpha = (scrollProgress * 2f - 1f).coerceIn(0f, 1f)
                                    
                                    if (subtitle != null && subtitleAlpha > 0.01f) {
                                        Text(
                                            text = subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f * subtitleAlpha),
                                            modifier = Modifier
                                                .padding(start = 2.dp)
                                                .graphicsLayer {
                                                    alpha = subtitleAlpha
                                                    translationY = (1f - subtitleAlpha) * -5f
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    modifier = Modifier.graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    }
                ) {
                    if (!isSelectionMode) {
                        IconButton(
                            onClick = onSearchClick,
                            modifier = Modifier.size(if (scrollProgress < 0.5f) 32.dp else 48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        if (onMenuClick != null) {
                            IconButton(
                                onClick = onMenuClick,
                                modifier = Modifier.size(if (scrollProgress < 0.5f) 32.dp else 48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Menu",
                                    modifier = Modifier.size(26.dp),
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    } else {
                        IconButton(onClick = { onActionClick("pin") }) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = "Pin/Unpin",
                                modifier = Modifier.size(22.dp),
                                tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                            )
                        }

                        IconButton(onClick = { onActionClick("share") }) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        IconButton(onClick = { onActionClick("hide_show") }) {
                            Icon(
                                imageVector = if (showHideOption == "Show") Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = showHideOption,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        IconButton(onClick = { onActionClick("tag") }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Label,
                                contentDescription = "Tag",
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        IconButton(onClick = { onActionClick("delete") }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }

                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More options",
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            AppContextMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                if (selectionCount == 1 && allowOpenWith) {
                                    AppContextMenuItem(
                                        label = "Open With",
                                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                                        onClick = { onActionClick("open_with"); showMenu = false }
                                    )
                                }
                                AppContextMenuItem(
                                    label = "Copy To",
                                    icon = Icons.Default.ContentCopy,
                                    onClick = { onActionClick("copy"); showMenu = false }
                                )
                                AppContextMenuItem(
                                    label = "Move To",
                                    icon = Icons.Default.FolderOpen,
                                    onClick = { onActionClick("move"); showMenu = false }
                                )
                                if (selectionCount == 1) {
                                    AppContextMenuItem(
                                        label = "Rename",
                                        icon = Icons.Default.Edit,
                                        onClick = { onActionClick("rename"); showMenu = false }
                                    )
                                    AppContextMenuItem(
                                        label = "Properties",
                                        icon = Icons.Default.Info,
                                        onClick = { onActionClick("properties"); showMenu = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
