package com.example.boxpandora.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.ui.common.FolderSelectorDialog
import com.example.boxpandora.ui.components.grid.DynamicMediaGrid
import com.example.boxpandora.ui.main.viewmodel.FolderDetailViewModel
import com.example.boxpandora.ui.main.viewmodel.FolderDetailViewModelFactory
import com.example.boxpandora.ui.main.viewmodel.FoldersViewModel
import com.example.boxpandora.ui.main.viewmodel.FoldersViewModelFactory
import com.example.boxpandora.ui.theme.boxPandoraModalTokens

// ─── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGridTestScreen(
    showHidden: Boolean = false,
    onBackClick: () -> Unit,
    onMediaClick: (List<MediaItem>, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val app = context.applicationContext as PandoraApp
    val tokens = boxPandoraModalTokens()

    val foldersViewModel: FoldersViewModel = viewModel(
        factory = FoldersViewModelFactory(app.repository)
    )
    val albums by foldersViewModel.albums.collectAsState()

    var selectedAlbum by remember { mutableStateOf<Album?>(null) }
    var showFolderPicker by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selectedAlbum?.name ?: "Media Grid Test",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(tokens.iconBackgroundNeutral)
                                .border(1.dp, tokens.border, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showFolderPicker = true }) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Change folder",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (selectedAlbum != null) {
                key(selectedAlbum!!.id) {
                    MediaGridTestContent(
                        albumId = selectedAlbum!!.id,
                        showHidden = showHidden,
                        onMediaClick = onMediaClick
                    )
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Select a folder to preview",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }

    if (showFolderPicker) {
        FolderSelectorDialog(
            title = "Select Folder",
            albums = albums,
            onDismiss = {
                showFolderPicker = false
                if (selectedAlbum == null) onBackClick()
            },
            onConfirm = { album ->
                selectedAlbum = album
                showFolderPicker = false
            }
        )
    }
}

@Composable
private fun MediaGridTestContent(
    albumId: Long,
    showHidden: Boolean,
    onMediaClick: (List<MediaItem>, Int) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PandoraApp
    val viewModel: FolderDetailViewModel = viewModel(
        factory = FolderDetailViewModelFactory(app.repository, app.repository.tagRepository, albumId)
    )
    LaunchedEffect(showHidden) { viewModel.setShowHidden(showHidden) }

    val items by viewModel.mediaItems.collectAsState()
    // Local favorites are screen-only — do not persist to the database.
    var localFavorites by remember { mutableStateOf(emptySet<String>()) }

    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        DynamicMediaGrid(
            items        = items,
            bigItemUris  = localFavorites,
            modifier     = Modifier.fillMaxSize(),
            hint         = "Long press any item to toggle 2×2",
            onPress      = { _, index -> onMediaClick(items, index) },
            onLongPress  = { item ->
                localFavorites = if (item.uri in localFavorites)
                    localFavorites - item.uri else localFavorites + item.uri
            }
        )
    }
}
