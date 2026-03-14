package com.example.boxpandora.ml.detection

/**
 * Tuning parameters shared by all detector engine implementations.
 *
 * A single [DetectorConfig] instance is passed to the engine at construction time so that
 * thresholds are never scattered across engine implementations.
 *
 * @param confidenceThreshold   Minimum detection score to keep a candidate [0, 1].
 * @param nmsIouThreshold       IoU threshold above which a lower-scoring box is suppressed.
 * @param minFaceHeightPx       Minimum bounding-box height in the source image (pixels).
 *                              Faces smaller than this are discarded as too small for ArcFace.
 * @param hasLandmarks          True when the engine is expected to produce 5-point landmarks.
 *                              Informational — engines always populate [DetectionResult.landmarks]
 *                              when the model supports it regardless of this flag.
 */
data class DetectorConfig(
    val confidenceThreshold: Float = 0.5f,
    val nmsIouThreshold:     Float = 0.3f,
    val minFaceHeightPx:     Int   = 30,
    val hasLandmarks:        Boolean = true
) {
    companion object {
        val DEFAULT = DetectorConfig()
    }
}
