package com.example.boxpandora.ui.main

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.ui.components.grid.FolderCard
import com.example.boxpandora.ui.main.viewmodel.FoldersViewModel
import com.example.boxpandora.ui.main.viewmodel.FoldersViewModelFactory
import com.example.boxpandora.ui.theme.PandoraDimensions

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FoldersScreen(
    onFolderClick: (Album) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PandoraApp
    val viewModel: FoldersViewModel = viewModel(
        factory = FoldersViewModelFactory(app.repository)
    )
    val albums by viewModel.albums.collectAsState()

    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) viewModel.refresh()
    }

    LaunchedEffect(Unit) {
        launcher.launch(permissionsToRequest)
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // Loading spinner — fades out once albums arrive
        AnimatedVisibility(
            visible = albums.isEmpty(),
            enter = fadeIn(tween(200)),
            exit  = fadeOut(tween(300))
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        // Grid — fades in once data is ready; items animate into place on recompose
        AnimatedVisibility(
            visible = albums.isNotEmpty(),
            enter = fadeIn(tween(350)),
            exit  = fadeOut(tween(200))
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    horizontal = PandoraDimensions.gridPadding,
                    vertical = 8.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(PandoraDimensions.gridGap),
                modifier = Modifier.fillMaxSize()
            ) {
                items(albums, key = { it.name }) { album ->
                    FolderCard(
                        album = album,
                        onPress = { onFolderClick(album) },
                        modifier = Modifier.animateItemPlacement()
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { viewModel.refresh() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "Sync")
        }
    }
}
