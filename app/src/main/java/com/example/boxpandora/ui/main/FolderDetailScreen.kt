package com.example.boxpandora.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.ui.components.grid.MediaThumbnail
import com.example.boxpandora.ui.main.viewmodel.FolderDetailViewModel
import com.example.boxpandora.ui.main.viewmodel.FolderDetailViewModelFactory

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FolderDetailScreen(
    albumName: String,
    onBackClick: () -> Unit,
    onMediaClick: (List<MediaItem>, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val app = context.applicationContext as PandoraApp
    val viewModel: FolderDetailViewModel = viewModel(
        factory = FolderDetailViewModelFactory(app.repository, albumName)
    )
    val mediaItems by viewModel.mediaItems.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(albumName) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            AnimatedVisibility(
                visible = mediaItems.isNotEmpty(),
                enter = fadeIn(tween(350))
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(1.dp)
                ) {
                    itemsIndexed(mediaItems, key = { _, item -> item.uri }) { index, item ->
                        MediaThumbnail(
                            item = item,
                            onPress = { onMediaClick(mediaItems, index) },
                            onLongPress = { /* Select */ },
                            modifier = Modifier.animateItemPlacement()
                        )
                    }
                }
            }
        }
    }
}
