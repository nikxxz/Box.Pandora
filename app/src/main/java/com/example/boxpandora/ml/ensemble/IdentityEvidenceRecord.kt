package com.example.boxpandora.ml.ensemble

/**
 * Per-recognizer identity evidence for a single fused face.
 *
 * Produced by each enabled face recognizer during ensemble face processing.
 * Consumed by [IdentityFusionEngine] to produce [FusedIdentityResult]s.
 *
 * @param fusedFaceId       ID of the [FusedFaceRecord] this evidence belongs to.
 * @param recognizerId      [ModelMetadata.id] of the recognizer that produced this result.
 * @param recognizerVersion [ModelMetadata.roomVersionKey] of the recognizer.
 * @param clusterId         ID of the matched [FaceCluster].
 * @param tagKey            Normalized person tag key (e.g. "alice_smith").
 * @param tagName           Display name for the person tag.
 * @param tagId             Room tag ID (FK into the tags table).
 * @param similarityScore   Cosine similarity between face embedding and cluster centroid (0.0–1.0).
 */
data class IdentityEvidenceRecord(
    val fusedFaceId: String,
    val recognizerId: String,
    val recognizerVersion: String,
    val clusterId: String,
    val tagKey: String,
    val tagName: String,
    val tagId: Long,
    val similarityScore: Float,
)
