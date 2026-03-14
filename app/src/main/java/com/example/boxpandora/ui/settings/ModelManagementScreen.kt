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
import com.example.boxpandora.ml.config.AiFeatureFlags
import com.example.boxpandora.ml.config.AiSettings
import com.example.boxpandora.ml.detection.MediaType
import com.example.boxpandora.ml.model.ModelCategory
import com.example.boxpandora.ml.model.ModelMetadata
import com.example.boxpandora.ui.settings.viewmodel.ModelManagerViewModel
import com.example.boxpandora.ui.settings.viewmodel.ModelManagerViewModelFactory
import com.example.boxpandora.ui.settings.viewmodel.ModelStatus
import com.example.boxpandora.ui.settings.viewmodel.ModelUiState

// ── Per-model plain-language descriptions ────────────────────────────────────

private data class ModelDescription(
    /** One or two sentences: what this model does for the user. */
    val summary: String,
    /** Pro: the main reason to pick this model. */
    val pro: String,
    /** Con: the main trade-off or caveat. */
    val con: String
)

private val MODEL_DESCRIPTIONS: Map<String, ModelDescription> = mapOf(

    "mobilenet_v3_scene" to ModelDescription(
        summary = "Powers the tag suggestions and \"find similar\" features. Analyses photo content on-device to understand what's in each image.",
        pro     = "Lightweight (~8 MB). After install it works fully offline.",
        con     = "Suggestions are broad — it understands general scenes, not fine-grained details like specific objects or text."
    ),

    "arcface_resnet100_fp16" to ModelDescription(
        summary = "Recognises people across your photos so the app can group them and let you search by face. This is the recommended people model.",
        pro     = "Half the size of the float32 version (~120 MB) with minimal accuracy difference for most photos.",
        con     = "Requires People Detection to be enabled in AI Settings. Large download — best done over Wi-Fi."
    ),

    "arcface_resnet100_fp32" to ModelDescription(
        summary = "Higher-precision version of the people recognition model. Use this if the default float16 model gives noticeably poor results.",
        pro     = "Marginally better identity matching in difficult conditions (poor lighting, partial faces).",
        con     = "Twice the download size (~240 MB) with little real-world gain for most users. Not recommended unless float16 is failing."
    ),

    "arcface_resnet100_onnx" to ModelDescription(
        summary = "ONNX-format version of the people recognition model, for devices where the TFLite version is unreliable.",
        pro     = "May perform better on devices whose NPU has strong ONNX support.",
        con     = "~240 MB download. Only needed as a fallback if the .tflite models cause inference errors on your device."
    ),

    "yunet_face_detection" to ModelDescription(
        summary = "Scans photos to find and locate faces. Required for any People features to work — without it no faces are detected.",
        pro     = "Tiny download (~345 KB), fast inference, and accurate on well-lit front-facing photos.",
        con     = "May miss very small, angled, or partially obscured faces. Try SCRFD if you notice frequent misses."
    ),

    "scrfd_10g_face_detection" to ModelDescription(
        summary = "Alternative face detector that performs better on small, distant, or partially obscured faces.",
        pro     = "Noticeably better recall on group shots, far-away subjects, and side profiles.",
        con     = "~17 MB and significantly slower than YuNet. Only switch if YuNet misses faces regularly in your library."
    )
)

// ── Screen ────────────────────────────────────────────────────────────────────

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
        factory = ModelManagerViewModelFactory(app.modelManager, app, app.aiSettingsRepository)
    )
    val models by viewModel.models.collectAsState()
    val settings by viewModel.settings.collectAsState()
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
                        subtitle = "Understands photo content for tag suggestions and visual similarity search"
                    )
                }
                items(entries, key = { "${it.meta.id}-${it.meta.version}" }) { entry ->
                    ModelCard(
                        state       = entry,
                        description = MODEL_DESCRIPTIONS[entry.meta.id],
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
                        title = "Face Recognition",
                        subtitle = "Learns to recognise people so the app can group photos by who's in them"
                    )
                }
                items(entries, key = { "${it.meta.id}-${it.meta.version}" }) { entry ->
                    ModelCard(
                        state       = entry,
                        description = MODEL_DESCRIPTIONS[entry.meta.id],
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
                        subtitle = "Finds and locates faces in photos — required for the People feature"
                    )
                }
                items(entries, key = { "${it.meta.id}-${it.meta.version}" }) { entry ->
                    ModelCard(
                        state       = entry,
                        description = MODEL_DESCRIPTIONS[entry.meta.id],
                        onDownload  = { viewModel.download(it) },
                        onActivate  = { viewModel.activate(it) },
                        onDelete    = { viewModel.delete(it) },
                    )
                }
            }

            // Face pipeline coverage diagnostics
            item {
                SettingSectionHeader(
                    title    = "Face Pipeline Coverage",
                    subtitle = "Which media types are scanned for faces"
                )
                FacePipelineDiagnosticsSection(settings = settings)
            }
        }
    }
}

// ── Model card ───────────────────────────────────────────────────────────────

@Composable
private fun ModelCard(
    state: ModelUiState,
    description: ModelDescription?,
    onDownload: (ModelMetadata) -> Unit,
    onActivate: (ModelMetadata) -> Unit,
    onDelete: (ModelMetadata) -> Unit,
) {
    val meta = state.meta
    var infoExpanded by remember { mutableStateOf(false) }
    var diagExpanded by remember { mutableStateOf(false) }

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

            // ── Plain-language description ────────────────────────────────────
            if (description != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = description.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                // Expandable pros/cons
                TextButton(
                    onClick = { infoExpanded = !infoExpanded },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text(
                        text = if (infoExpanded) "Less info" else "More info",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                AnimatedVisibility(visible = infoExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "+",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                text = description.pro,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "−",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = description.con,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Metadata row ─────────────────────────────────────────────────
            val sizeLabel = when {
                meta.sizeBytes > 0 -> "%.1f MB".format(meta.sizeBytes / 1_048_576.0)
                else -> "size unknown"
            }
            val inputLabel = "${meta.inputWidth}×${meta.inputHeight} → dim ${meta.outputDim}"
            Text(
                text = "$sizeLabel  ·  ${meta.format.uppercase()}  ·  $inputLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // ── Diagnostics toggle + content ──────────────────────────────────
            Spacer(Modifier.height(2.dp))
            TextButton(
                onClick = { diagExpanded = !diagExpanded },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(24.dp)
            ) {
                Text(
                    text = if (diagExpanded) "Hide diagnostics" else "Diagnostics",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            AnimatedVisibility(visible = diagExpanded) {
                ModelDiagnosticsChecklist(state = state)
            }

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

// ── Model diagnostics checklist ───────────────────────────────────────────────

/**
 * Expandable checklist showing per-model health indicators:
 *  - Installed / Active
 *  - Runtime format supported
 *  - Checksum present in manifest
 *  - Expected size present in manifest
 *  - On-disk file size matches manifest (when installed)
 *  - Crash-loop failure count
 */
@Composable
private fun ModelDiagnosticsChecklist(state: ModelUiState) {
    val meta = state.meta
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        DiagRow(
            ok    = state.status != ModelStatus.NOT_INSTALLED,
            label = if (state.status != ModelStatus.NOT_INSTALLED) "Installed" else "Not installed"
        )
        DiagRow(
            ok    = state.status == ModelStatus.ACTIVE,
            label = if (state.status == ModelStatus.ACTIVE) "Active" else "Not active"
        )
        DiagRow(
            ok    = state.isRuntimeSupported,
            label = if (state.isRuntimeSupported)
                "Runtime supported (${meta.format.uppercase()})"
            else
                "${meta.format.uppercase()} runtime not available on this device — model cannot run"
        )
        DiagRow(
            ok    = state.isChecksumInManifest,
            label = if (state.isChecksumInManifest)
                "Checksum in manifest (SHA-256 …${meta.sha256.takeLast(8)})"
            else
                "Checksum missing from manifest — integrity unverifiable"
        )
        DiagRow(
            ok    = state.isSizeInManifest,
            label = if (state.isSizeInManifest)
                "Size in manifest (%.1f MB)".format(meta.sizeBytes / 1_048_576.0)
            else
                "Size missing from manifest — download size unknown"
        )
        state.fileSizeMatchesManifest?.let { matches ->
            DiagRow(
                ok    = matches,
                label = if (matches) "File size matches manifest" else "File size mismatch — file may be corrupt or partially downloaded"
            )
        }
        if (state.failureCount > 0) {
            DiagRow(
                ok    = false,
                label = "Inference failures: ${state.failureCount}/3 — ${if (state.failureCount >= 3) "suspended" else "approaching limit"}"
            )
        }
    }
}

@Composable
private fun DiagRow(ok: Boolean, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
        )
    }
}

// ── Face pipeline diagnostics section ────────────────────────────────────────

/**
 * Shows which media types are currently eligible for face detection,
 * based on [AiFeatureFlags] compile-time constants and the user's [AiSettings].
 */
@Composable
private fun FacePipelineDiagnosticsSection(settings: AiSettings) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Images — always on
            FacePipelineRow(
                label  = "Images (JPEG, PNG, HEIC, WebP)",
                state  = PipelineState.ENABLED,
                detail = "Face detection runs on all still images"
            )

            // GIFs — compile-time flag
            val gifState = when {
                AiFeatureFlags.FACE_DETECTION_ON_GIF -> PipelineState.EXPERIMENTAL
                else                                  -> PipelineState.DISABLED
            }
            FacePipelineRow(
                label  = "Animated GIFs",
                state  = gifState,
                detail = when (gifState) {
                    PipelineState.DISABLED     -> "Disabled — frame-sampling for GIFs is not yet implemented"
                    PipelineState.EXPERIMENTAL -> "Experimental — compile-time flag enabled; frame sampling in progress"
                    else                        -> ""
                }
            )

            // Videos — compile-time flag AND user runtime toggle
            val videoCompileEnabled = AiFeatureFlags.FACE_DETECTION_ON_VIDEO
            val videoUserEnabled    = settings.faceDetectionInVideos
            val videoState = when {
                !videoCompileEnabled           -> PipelineState.DISABLED
                videoUserEnabled               -> PipelineState.EXPERIMENTAL
                else                           -> PipelineState.DISABLED
            }
            FacePipelineRow(
                label  = "Videos",
                state  = videoState,
                detail = when {
                    !videoCompileEnabled  -> "Disabled — frame extraction for videos is not yet implemented"
                    !videoUserEnabled     -> "Disabled — toggle 'People detection in videos' in AI Settings to enable"
                    else                  -> "Experimental — enabled via AI Settings; frame-level sampling in progress"
                }
            )
        }
    }
}

private enum class PipelineState { ENABLED, EXPERIMENTAL, DISABLED }

@Composable
private fun FacePipelineRow(label: String, state: PipelineState, detail: String) {
    Column(modifier = Modifier.padding(vertical = 5.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (icon, tint, badge, badgeBg, badgeFg) = when (state) {
                PipelineState.ENABLED      -> FiveTuple(
                    Icons.Default.CheckCircle,
                    MaterialTheme.colorScheme.tertiary,
                    "Enabled",
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer
                )
                PipelineState.EXPERIMENTAL -> FiveTuple(
                    Icons.Default.Info,
                    MaterialTheme.colorScheme.secondary,
                    "Experimental",
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer
                )
                PipelineState.DISABLED     -> FiveTuple(
                    Icons.Default.Block,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    "Disabled",
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(icon, null, Modifier.size(16.dp), tint = tint)
            Text(
                label,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f)
            )
            Surface(shape = RoundedCornerShape(999.dp), color = badgeBg) {
                Text(
                    badge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeFg
                )
            }
        }
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp)
            )
        }
    }
}

// Destructured data carrier for FacePipelineRow local variables
private data class FiveTuple(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tint: androidx.compose.ui.graphics.Color,
    val badge: String,
    val badgeBg: androidx.compose.ui.graphics.Color,
    val badgeFg: androidx.compose.ui.graphics.Color
)

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
