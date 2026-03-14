package com.example.boxpandora.ml.config

/**
 * Snapshot of all persisted AI-feature settings.
 *
 * Immutable value object — updates are made through [AiSettingsRepository].
 *
 * [sceneTaggingEnabled]       – run SceneIndexWorker; produce tag suggestions from scene embeddings
 * [faceProcessingEnabled]     – run FaceIndexWorker + FaceClusterWorker + PersonProfileWorker
 * [backgroundIndexingEnabled] – allow workers to run in background (not just on explicit trigger)
 * [confidenceThreshold]       – 0.0–1.0; suggestions below this score are suppressed in the UI
 * [autoIndexOnSync]           – schedule scene indexing automatically after each media sync
 * [wifiOnlyDownloads]         – block RemoteDownload model fetches unless on Wi-Fi
 * [faceDetectionInVideos]     – experimental: run face detection on video frames (default off;
 *                               requires frame-sampling strategy and higher battery tolerance)
 */
data class AiSettings(
    val sceneTaggingEnabled: Boolean = true,
    val faceProcessingEnabled: Boolean = false,
    val backgroundIndexingEnabled: Boolean = true,
    val confidenceThreshold: Float = 0.5f,
    val autoIndexOnSync: Boolean = true,
    val wifiOnlyDownloads: Boolean = true,
    val faceDetectionInVideos: Boolean = false
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
