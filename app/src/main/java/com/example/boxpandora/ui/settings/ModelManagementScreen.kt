package com.example.boxpandora.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.ml.model.ModelCategory
import com.example.boxpandora.ml.model.ModelMetadata
import com.example.boxpandora.ui.settings.viewmodel.ModelManagerViewModel
import com.example.boxpandora.ui.settings.viewmodel.ModelManagerViewModelFactory
import com.example.boxpandora.ui.settings.viewmodel.ModelStatus
import com.example.boxpandora.ui.settings.viewmodel.ModelUiState

/**
 * Settings screen for managing AI model files.
 *
 * Displays every manifest entry grouped by category with install state, version,
 * checksum info, and action buttons (Download / Activate / Delete).
 *
 * No file I/O occurs here — all operations are delegated to [ModelManagerViewModel].
 */
@Composable
fun ModelManagementScreen(navController: NavController) {
    val activity = LocalContext.current as ComponentActivity
    val app = activity.application as PandoraApp
    val viewModel: ModelManagerViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = ModelManagerViewModelFactory(app.modelManager, app)
    )
    val models by viewModel.models.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Refresh on entry so install state is up-to-date
    LaunchedEffect(Unit) { viewModel.refresh() }

    SettingsSubScreen("Model Management", navController, snackbarHostState) {
        if (models.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No models found in manifest.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@SettingsSubScreen
        }

        val byCategory = models.groupBy { it.meta.category }

        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp, top = 4.dp)) {
            // Scene Embedding
            byCategory[ModelCategory.SCENE_EMBEDDING]?.let { entries ->
                item {
                    SettingSectionHeader(
                        title = "Scene Embedding",
                        subtitle = "Generates image content vectors for tag suggestions and similarity search"
                    )
                }
                items(entries, key = { "${it.meta.id}-${it.meta.version}" }) { entry ->
                    ModelCard(
                        state = entry,
                        onDownload  = { viewModel.download(it) },
                        onActivate  = { viewModel.activate(it) },
                        onDelete    = { viewModel.delete(it) },
                    )
                }
            }

            // Face Embedding
            byCategory[ModelCategory.FACE_EMBEDDING]?.let { entries ->
                item {
                    SettingSectionHeader(
                        title = "Face Embedding",
                        subtitle = "Encodes aligned face crops for person clustering (ArcFace)"
                    )
                }
                items(entries, key = { "${it.meta.id}-${it.meta.version}" }) { entry ->
                    ModelCard(
                        state = entry,
                        onDownload  = { viewModel.download(it) },
                        onActivate  = { viewModel.activate(it) },
                        onDelete    = { viewModel.delete(it) },
                    )
                }
            }

            // Face Detection
            byCategory[ModelCategory.FACE_DETECTION]?.let { entries ->
                item {
                    SettingSectionHeader(
                        title = "Face Detection",
                        subtitle = "Locates faces and produces 5-point landmarks (YuNet) — not yet available"
                    )
                }
                items(entries, key = { "${it.meta.id}-${it.meta.version}" }) { entry ->
                    ModelCard(
                        state = entry,
                        onDownload  = { viewModel.download(it) },
                        onActivate  = { viewModel.activate(it) },
                        onDelete    = { viewModel.delete(it) },
                    )
                }
            }
        }
    }
}

// ── Model card ───────────────────────────────────────────────────────────────

@Composable
private fun ModelCard(
    state: ModelUiState,
    onDownload: (ModelMetadata) -> Unit,
    onActivate: (ModelMetadata) -> Unit,
    onDelete: (ModelMetadata) -> Unit,
) {
    val meta = state.meta
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Header row ────────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = meta.displayName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "v${meta.version}  ·  ${meta.format.uppercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ModelStatusBadge(state.status)
            }

            Spacer(Modifier.height(8.dp))

            // ── Metadata row ─────────────────────────────────────────────────
            val sizeLabel = when {
                meta.sizeBytes > 0 -> "%.1f MB".format(meta.sizeBytes / 1_048_576.0)
                else -> "size unknown"
            }
            val checksumLabel = when {
                meta.sha256.isNotBlank() -> "SHA-256: …${meta.sha256.takeLast(8)}"
                else -> "No checksum in manifest"
            }
            val inputLabel = "${meta.inputWidth}×${meta.inputHeight} → dim ${meta.outputDim}"

            Text(
                text = "$sizeLabel  ·  $inputLabel  ·  $checksumLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // ── Failure reason ────────────────────────────────────────────────
            if (!state.failureReason.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = state.failureReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // ── Download progress bar ─────────────────────────────────────────
            AnimatedVisibility(visible = state.status == ModelStatus.DOWNLOADING) {
                val progress by animateFloatAsState(
                    targetValue = state.downloadProgress ?: 0f,
                    label = "download_progress"
                )
                Column {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                    )
                    if ((state.downloadProgress ?: 0f) > 0f) {
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Action buttons ────────────────────────────────────────────────
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.status) {
                    ModelStatus.NOT_INSTALLED, ModelStatus.INVALID, ModelStatus.FAILED -> {
                        if (state.isDownloadable) {
                            FilledTonalButton(
                                onClick = { onDownload(meta) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Download")
                            }
                        } else {
                            Text(
                                "Not available — URL not configured",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    ModelStatus.INSTALLED -> {
                        FilledTonalButton(
                            onClick = { onActivate(meta) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Set Active")
                        }
                        OutlinedButton(
                            onClick = { onDelete(meta) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }

                    ModelStatus.ACTIVE -> {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    null,
                                    Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Active",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { onDelete(meta) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }

                    ModelStatus.DOWNLOADING -> {
                        // Progress indicator above — no actions while downloading
                        Text(
                            "Downloading…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── Status badge ─────────────────────────────────────────────────────────────

@Composable
private fun ModelStatusBadge(status: ModelStatus) {
    val (label, bg, fg) = when (status) {
        ModelStatus.NOT_INSTALLED -> Triple("Not Installed",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant)
        ModelStatus.DOWNLOADING   -> Triple("Downloading",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer)
        ModelStatus.INSTALLED     -> Triple("Installed",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer)
        ModelStatus.ACTIVE        -> Triple("Active",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer)
        ModelStatus.INVALID       -> Triple("Invalid",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer)
        ModelStatus.FAILED        -> Triple("Failed",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer)
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = bg,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = fg
        )
    }
}
