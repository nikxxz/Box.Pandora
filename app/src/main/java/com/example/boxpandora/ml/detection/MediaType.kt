package com.example.boxpandora.ml.detection

/**
 * Broad classification of the media item being submitted to face detection.
 *
 * Used by [com.example.boxpandora.ml.config.AiFeatureFlags.isFaceDetectionAllowed] to
 * gate which media types the face detection pipeline will process.
 */
enum class MediaType {
    /** Standard still image — JPEG, PNG, HEIC, WebP, etc. */
    IMAGE,
    /** Animated GIF. */
    GIF,
    /** Video file — MP4, MKV, MOV, etc. */
    VIDEO
}
