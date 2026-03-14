package com.example.boxpandora.ml.detection

/**
 * Unified face detection result returned by all detector engines.
 *
 * All coordinates are normalised [0, 1] relative to the source bitmap's dimensions.
 *
 * @param score                 Detector confidence in [0, 1].
 * @param leftNorm              Normalised left edge of the bounding box.
 * @param topNorm               Normalised top edge of the bounding box.
 * @param rightNorm             Normalised right edge of the bounding box.
 * @param bottomNorm            Normalised bottom edge of the bounding box.
 * @param landmarks             5 (x, y) pairs in standard order:
 *                              right_eye(image-left), left_eye(image-right),
 *                              nose_tip, right_mouth, left_mouth.
 *                              Empty when the active detector does not produce landmarks.
 * @param detectorId            [ModelMetadata.id] of the detector that produced this result.
 * @param detectorModelVersion  [ModelMetadata.roomVersionKey] written into DetectedFace rows.
 */
data class DetectionResult(
    val score: Float,
    val leftNorm: Float,
    val topNorm: Float,
    val rightNorm: Float,
    val bottomNorm: Float,
    val landmarks: List<Pair<Float, Float>> = emptyList(),
    val detectorId: String,
    val detectorModelVersion: String
) {
    val widthNorm:  Float get() = rightNorm  - leftNorm
    val heightNorm: Float get() = bottomNorm - topNorm
}
