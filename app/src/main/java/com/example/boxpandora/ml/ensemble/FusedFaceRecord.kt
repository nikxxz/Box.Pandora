package com.example.boxpandora.ml.ensemble

import com.example.boxpandora.ml.detection.DetectionResult

/**
 * In-memory result of merging [FaceDetectionEvidenceRecord]s from multiple detectors.
 *
 * Produced by [FaceDetectionFusionEngine]; persisted to [FusedFace] Room entity
 * by [FaceEnsembleOrchestrator].
 *
 * @param assetId                   URI of the source media item.
 * @param fusedFaceId               Stable identifier: "${assetId}_fused_${faceIndex}".
 * @param faceIndex                 Sequential index within the asset (0-based).
 * @param leftNorm                  Weighted-average left edge, normalized [0, 1].
 * @param topNorm                   Weighted-average top edge.
 * @param rightNorm                 Weighted-average right edge.
 * @param bottomNorm                Weighted-average bottom edge.
 * @param fusedConfidence           Average confidence across contributing detectors.
 * @param strongestConfidence       Highest individual detector confidence.
 * @param contributingDetectorIds   IDs of all detectors that contributed to this box.
 * @param anchorDetectorId          ID of the highest-confidence detector (used for alignment).
 * @param anchorDetectorVersion     Version key of [anchorDetectorId].
 * @param anchorDetectionResult     Original [DetectionResult] from the anchor detector; used to
 *                                  call [FaceDetectionEngine.alignFace] for the face crop.
 */
data class FusedFaceRecord(
    val assetId: String,
    val fusedFaceId: String,
    val faceIndex: Int,
    val leftNorm: Float,
    val topNorm: Float,
    val rightNorm: Float,
    val bottomNorm: Float,
    val fusedConfidence: Float,
    val strongestConfidence: Float,
    val contributingDetectorIds: List<String>,
    val anchorDetectorId: String,
    val anchorDetectorVersion: String,
    /** Kept for alignment — not persisted. */
    val anchorDetectionResult: DetectionResult,
)
