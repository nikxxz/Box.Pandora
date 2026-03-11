package com.example.boxpandora.ui.common

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppHeader(
    title: String = "pandora",
    onMenuClick: (() -> Unit)? = null,
    onBackClick: (() -> Unit)? = null,
    onSearchClick: () -> Unit = {},
    selectionCount: Int = 0,
    onClearSelection: () -> Unit = {},
    showHideOption: String = "Hide", // "Hide" or "Show"
    onActionClick: (String) -> Unit = {}
) {
    val isSelectionMode = selectionCount > 0
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        AnimatedContent(
            targetState = isSelectionMode,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "header_content"
        ) { selecting ->
            if (selecting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClearSelection) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = "$selectionCount selected",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = title.lowercase(),
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.W200,
                            letterSpacing = 4.sp,
                            fontSize = 34.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (!isSelectionMode) {
                IconButton(onClick = onSearchClick) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        modifier = Modifier.size(22.dp)
                    )
                }
                if (onMenuClick != null) {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu",
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            } else {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        if (selectionCount == 1) {
                            DropdownMenuItem(
                                text = { Text("Open With") },
                                onClick = { onActionClick("open_with"); showMenu = false }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Share") },
                            onClick = { onActionClick("share"); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy To") },
                            onClick = { onActionClick("copy"); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Move To") },
                            onClick = { onActionClick("move"); showMenu = false }
                        )
                        if (selectionCount == 1) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = { onActionClick("rename"); showMenu = false }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(showHideOption) },
                            onClick = { onActionClick("hide_show"); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Tag") },
                            onClick = { onActionClick("tag"); showMenu = false }
                        )
                        if (selectionCount == 1) {
                            DropdownMenuItem(
                                text = { Text("Properties") },
                                onClick = { onActionClick("properties"); showMenu = false }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = { onActionClick("delete"); showMenu = false }
                        )
                    }
                }
            }
        }
    }
}
