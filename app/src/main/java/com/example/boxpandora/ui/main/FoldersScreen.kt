package com.example.boxpandora.ui.main

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.ui.components.grid.FolderCard
import com.example.boxpandora.ui.main.viewmodel.FoldersViewModel
import com.example.boxpandora.ui.main.viewmodel.FoldersViewModelFactory
import com.example.boxpandora.ui.theme.PandoraDimensions

private fun hasStorageAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FoldersScreen(
    showHidden: Boolean,
    onFolderClick: (Album) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PandoraApp
    val viewModel: FoldersViewModel = viewModel(
        factory = FoldersViewModelFactory(app.repository)
    )
    val albums by viewModel.albums.collectAsState()

    LaunchedEffect(showHidden) {
        viewModel.setShowHidden(showHidden)
    }

    var permissionGranted by remember { mutableStateOf(hasStorageAccess()) }

    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        permissionGranted = hasStorageAccess()
        if (permissionGranted) viewModel.refresh()
    }

    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) viewModel.refresh()
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                legacyStorageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        } else {
            viewModel.refresh()
        }
    }

    if (!permissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        AllFilesPermissionGate(
            onGrant = {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                allFilesLauncher.launch(intent)
            }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Use AnimatedContent for smoother state transitions between Loading and Content
        AnimatedContent(
            targetState = albums.isEmpty(),
            transitionSpec = {
                fadeIn(animationSpec = tween(220, delayMillis = 90)) togetherWith
                fadeOut(animationSpec = tween(90))
            },
            label = "FoldersContentTransition"
        ) { isLoading ->
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(
                        horizontal = PandoraDimensions.gridPadding,
                        vertical = 8.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(PandoraDimensions.gridGap),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(albums, key = { it.id }) { album -> // Changed key to ID
                        FolderCard(
                            album = album,
                            onPress = { onFolderClick(album) },
                            modifier = Modifier.animateItemPlacement()
                        )
                    }
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

@Composable
private fun AllFilesPermissionGate(onGrant: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FolderOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )

            Text(
                text = "All Files Access Required",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Pandora needs All Files Access to discover folders " +
                       "that contain a .nomedia file (hidden albums).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Button(
                onClick = onGrant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Settings", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
