package com.example.boxpandora.ml.config

import com.example.boxpandora.ml.config.AiSettings
import com.example.boxpandora.ml.detection.MediaType

/**
 * Compile-time feature flags for AI pipeline capabilities.
 *
 * All media-type gating for face detection must go through this object — no scattered
 * hardcoded checks in workers, services, or UI components.
 *
 * To enable a flag in a future phase, flip the constant here; all callers update automatically.
 */
object AiFeatureFlags {

    /**
     * Whether face detection is enabled for animated GIF files.
     * Off by default: each frame would require a separate inference pass; cost/value ratio is poor
     * until frame-sampling and result caching are implemented.
     */
    const val FACE_DETECTION_ON_GIF: Boolean = false

    /**
     * Whether face detection is enabled for video files.
     * Off by default: requires frame extraction and a per-frame sampling strategy before
     * inference overhead becomes acceptable.
     */
    const val FACE_DETECTION_ON_VIDEO: Boolean = false

    /**
     * Returns true when face detection may proceed for [mediaType].
     * This is the single call-site for all detection media-type gating.
     *
     * Prefer the [AiSettings] overload when settings are already loaded — it respects the
     * user's runtime [AiSettings.faceDetectionInVideos] toggle for video files.
     */
    fun isFaceDetectionAllowed(mediaType: MediaType): Boolean = when (mediaType) {
        MediaType.IMAGE -> true
        MediaType.GIF   -> FACE_DETECTION_ON_GIF
        MediaType.VIDEO -> FACE_DETECTION_ON_VIDEO
    }

    /**
     * Settings-aware overload. For [MediaType.VIDEO], consults [AiSettings.faceDetectionInVideos]
     * (the user-controlled experimental toggle) instead of the compile-time constant.
     * For IMAGE and GIF, behaves identically to the no-settings overload.
     */
    fun isFaceDetectionAllowed(mediaType: MediaType, settings: AiSettings): Boolean = when (mediaType) {
        MediaType.IMAGE -> true
        MediaType.GIF   -> FACE_DETECTION_ON_GIF
        MediaType.VIDEO -> settings.faceDetectionInVideos
    }

    // ── Face embedding (people recognition) ──────────────────────────────────

    /**
     * Whether face embedding is enabled for animated GIF frames.
     * Off by default: GIF frame faces are not admitted to the people recognition pipeline
     * until frame-sampling quality and identity consistency have been validated.
     */
    const val FACE_EMBEDDING_ON_GIF: Boolean = false

    /**
     * Whether face embedding is enabled for video frames.
     * Off by default: video faces are not admitted to the people recognition pipeline
     * until frame-level identity deduplication is in place.
     */
    const val FACE_EMBEDDING_ON_VIDEO: Boolean = false

    /**
     * Returns true when face embedding (people recognition) may proceed for [mediaType].
     * This is the single call-site for all embedding media-type gating.
     */
    fun isFaceEmbeddingAllowed(mediaType: MediaType): Boolean = when (mediaType) {
        MediaType.IMAGE -> true
        MediaType.GIF   -> FACE_EMBEDDING_ON_GIF
        MediaType.VIDEO -> FACE_EMBEDDING_ON_VIDEO
    }
}
