package com.example.boxpandora.ml.ensemble

/**
 * Normalized evidence from a single face detector for one detection event.
 *
 * Produced by each enabled detector during an ensemble face-index run and consumed
 * by [FaceDetectionFusionEngine]. Not persisted — the fusion engine merges these
 * into [FusedFaceRecord]s, which are persisted as [FusedFace] entities.
 *
 * @param assetId           URI of the source media item.
 * @param detectorId        [ModelMetadata.id] of the detector that produced this detection.
 * @param detectorVersion   [ModelMetadata.roomVersionKey] of the detector.
 * @param leftNorm          Left edge of the bounding box, normalized to [0, 1].
 * @param topNorm           Top edge of the bounding box, normalized to [0, 1].
 * @param rightNorm         Right edge of the bounding box, normalized to [0, 1].
 * @param bottomNorm        Bottom edge of the bounding box, normalized to [0, 1].
 * @param confidence        Detector confidence score (0.0–1.0).
 * @param landmarks         5 (x, y) landmark pairs from the detector, normalized [0, 1].
 *                          Empty when the detector does not produce landmarks.
 */
data class FaceDetectionEvidenceRecord(
    val assetId: String,
    val detectorId: String,
    val detectorVersion: String,
    val leftNorm: Float,
    val topNorm: Float,
    val rightNorm: Float,
    val bottomNorm: Float,
    val confidence: Float,
    val landmarks: List<Pair<Float, Float>> = emptyList(),
) {
    val widthNorm:  Float get() = rightNorm  - leftNorm
    val heightNorm: Float get() = bottomNorm - topNorm
    val area:       Float get() = widthNorm * heightNorm
}
