package com.example.boxpandora.ml.ensemble

import com.example.boxpandora.ml.detection.DetectionResult

/**
 * Merges face bounding boxes from multiple detectors using Intersection-over-Union (IoU).
 *
 * Algorithm (greedy, confidence-first):
 *  1. Sort all detections by confidence descending.
 *  2. For each unassigned detection, start a new merge group.
 *  3. Assign any remaining unassigned detections with IoU > [iouThreshold] to that group.
 *  4. Compute the weighted-average box for each group (weights = confidence scores).
 *  5. The highest-confidence detection in each group becomes the "anchor" for face alignment.
 *
 * Single-detector detections with confidence < [singleDetectorMinConfidence] are dropped.
 * Detections that appear in 2+ detectors (IoU > threshold) are always kept regardless of
 * individual confidence — multi-detector agreement is strong positive evidence.
 *
 * @param iouThreshold                IoU threshold for box merging (default 0.40).
 * @param singleDetectorMinConfidence Minimum confidence for a single-detector detection to
 *                                    survive (default 0.60).
 */
class FaceDetectionFusionEngine(
    private val iouThreshold: Float = 0.40f,
    private val singleDetectorMinConfidence: Float = 0.60f,
) {

    /**
     * Merges [evidenceList] into a list of [FusedFaceRecord]s.
     *
     * @param assetId      URI of the source asset.
     * @param evidenceList All detections from all enabled detectors for this asset, paired with
     *                     their original [DetectionResult] for alignment access.
     * @return Merged [FusedFaceRecord] list, sorted by fusedConfidence descending.
     */
    fun fuse(
        assetId: String,
        evidenceList: List<Pair<FaceDetectionEvidenceRecord, DetectionResult>>,
    ): List<FusedFaceRecord> {
        if (evidenceList.isEmpty()) return emptyList()

        // Sort by confidence descending for greedy matching
        val sorted = evidenceList.sortedByDescending { it.first.confidence }
        val assigned = BooleanArray(sorted.size)
        val groups = mutableListOf<List<Pair<FaceDetectionEvidenceRecord, DetectionResult>>>()

        for (i in sorted.indices) {
            if (assigned[i]) continue
            assigned[i] = true
            val group = mutableListOf(sorted[i])
            for (j in (i + 1) until sorted.size) {
                if (assigned[j]) continue
                if (iou(sorted[i].first, sorted[j].first) > iouThreshold) {
                    group.add(sorted[j])
                    assigned[j] = true
                }
            }
            groups.add(group)
        }

        val results = mutableListOf<FusedFaceRecord>()
        var faceIndex = 0

        for (group in groups) {
            // Drop weak single-detector detections
            if (group.size == 1 && group[0].first.confidence < singleDetectorMinConfidence) continue

            val anchorPair    = group.maxByOrNull { it.first.confidence }!!
            val anchor        = anchorPair.first
            val anchorResult  = anchorPair.second
            val totalWeight   = group.sumOf { it.first.confidence.toDouble() }.toFloat()

            val fusedLeft    = group.sumOf { (it.first.leftNorm   * it.first.confidence).toDouble() }.toFloat() / totalWeight
            val fusedTop     = group.sumOf { (it.first.topNorm    * it.first.confidence).toDouble() }.toFloat() / totalWeight
            val fusedRight   = group.sumOf { (it.first.rightNorm  * it.first.confidence).toDouble() }.toFloat() / totalWeight
            val fusedBottom  = group.sumOf { (it.first.bottomNorm * it.first.confidence).toDouble() }.toFloat() / totalWeight
            val fusedConf    = group.sumOf { it.first.confidence.toDouble() }.toFloat() / group.size

            results.add(
                FusedFaceRecord(
                    assetId                 = assetId,
                    fusedFaceId             = "${assetId}_fused_${faceIndex}",
                    faceIndex               = faceIndex,
                    leftNorm                = fusedLeft,
                    topNorm                 = fusedTop,
                    rightNorm               = fusedRight,
                    bottomNorm              = fusedBottom,
                    fusedConfidence         = fusedConf,
                    strongestConfidence     = anchor.confidence,
                    contributingDetectorIds = group.map { it.first.detectorId },
                    anchorDetectorId        = anchor.detectorId,
                    anchorDetectorVersion   = anchor.detectorVersion,
                    anchorDetectionResult   = anchorResult,
                )
            )
            faceIndex++
        }

        return results.sortedByDescending { it.fusedConfidence }
    }

    // ── IoU helper ────────────────────────────────────────────────────────────

    private fun iou(a: FaceDetectionEvidenceRecord, b: FaceDetectionEvidenceRecord): Float {
        val interLeft   = maxOf(a.leftNorm,   b.leftNorm)
        val interTop    = maxOf(a.topNorm,    b.topNorm)
        val interRight  = minOf(a.rightNorm,  b.rightNorm)
        val interBottom = minOf(a.bottomNorm, b.bottomNorm)

        val interW = maxOf(0f, interRight  - interLeft)
        val interH = maxOf(0f, interBottom - interTop)
        val interArea = interW * interH

        if (interArea == 0f) return 0f

        val unionArea = a.area + b.area - interArea
        return if (unionArea <= 0f) 0f else interArea / unionArea
    }
}
