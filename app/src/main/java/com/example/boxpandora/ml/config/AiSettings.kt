package com.example.boxpandora.ml.config

/**
 * Snapshot of all persisted AI-feature settings.
 *
 * Immutable value object — updates are made through [AiSettingsRepository].
 *
 * [sceneTaggingEnabled]           – run SceneIndexWorker; produce tag suggestions from scene embeddings
 * [faceProcessingEnabled]         – run FaceIndexWorker + FaceClusterWorker + PersonProfileWorker
 * [backgroundIndexingEnabled]     – allow workers to run in background (not just on explicit trigger)
 * [confidenceThreshold]           – 0.0–1.0; suggestions below this score are suppressed in the UI
 * [autoIndexOnSync]               – schedule scene indexing automatically after each media sync
 * [wifiOnlyDownloads]             – block RemoteDownload model fetches unless on Wi-Fi
 * [faceDetectionInVideos]         – experimental: run face detection on video frames (default off;
 *                                   requires frame-sampling strategy and higher battery tolerance)
 * [pipelineMode]                  – [AiPipelineMode.SINGLE_ACTIVE] (default) or
 *                                   [AiPipelineMode.ENSEMBLE_ALL_ENABLED]; never infer from UI
 *                                   toggle state alone.
 * [disabledEnsembleModelIds]      – model IDs the user has explicitly disabled from ensemble
 *                                   execution; newly installed models default to enabled (not
 *                                   in this set). Only meaningful when [pipelineMode] is
 *                                   [AiPipelineMode.ENSEMBLE_ALL_ENABLED].
 * [ensembleOnlyWhileCharging]     – background ensemble scans require the device to be charging
 *                                   (default on; protects battery life).
 * [pauseEnsembleOnBatterySaver]   – background ensemble scans are skipped when battery saver is
 *                                   active (default on). Phase 1 respects this at scheduling time.
 * [disabledEnsembleDetectorIds]   – face detector model IDs excluded from ensemble execution.
 *                                   Newly installed detectors default to enabled (not in this set).
 *                                   Only meaningful when [pipelineMode] is [ENSEMBLE_ALL_ENABLED].
 * [disabledEnsembleRecognizerIds] – face recognizer model IDs excluded from ensemble execution.
 *                                   Same semantics as [disabledEnsembleDetectorIds].
 */
data class AiSettings(
    val sceneTaggingEnabled: Boolean = true,
    val faceProcessingEnabled: Boolean = false,
    val backgroundIndexingEnabled: Boolean = true,
    val confidenceThreshold: Float = 0.5f,
    val autoIndexOnSync: Boolean = true,
    val wifiOnlyDownloads: Boolean = true,
    val faceDetectionInVideos: Boolean = false,
    // ── Ensemble mode ─────────────────────────────────────────────────────────
    val pipelineMode: AiPipelineMode = AiPipelineMode.SINGLE_ACTIVE,
    val disabledEnsembleModelIds: Set<String> = emptySet(),
    val ensembleOnlyWhileCharging: Boolean = true,
    val pauseEnsembleOnBatterySaver: Boolean = true,
    // ── Face ensemble model participation ─────────────────────────────────────
    val disabledEnsembleDetectorIds: Set<String> = emptySet(),
    val disabledEnsembleRecognizerIds: Set<String> = emptySet(),
) {
    companion object {
        val DEFAULT = AiSettings()

        /** Maps [confidenceThreshold] to a display label used in the settings UI. */
        fun Float.toConfidenceLabel(): String = when {
            this < 0.35f -> "Low"
            this < 0.65f -> "Medium"
            else -> "High"
        }
    }
}
