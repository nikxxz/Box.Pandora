package com.example.boxpandora.ml.ensemble

/**
 * In-memory result of fusing identity evidence from multiple recognizers for a single face.
 *
 * Produced by [IdentityFusionEngine]; persisted to [FusedIdentitySuggestion] Row by
 * [FaceEnsembleOrchestrator].
 *
 * @param fusedFaceId               ID of the [FusedFaceRecord] this result belongs to.
 * @param assetId                   URI of the source media item.
 * @param clusterId                 ID of the matched [FaceCluster].
 * @param tagKey                    Normalized person tag key.
 * @param tagName                   Display name for the person tag.
 * @param tagId                     Room tag ID.
 * @param fusedScore                Fused identity confidence score (0.0–1.0).
 * @param contributingRecognizerIds IDs of recognizers that contributed a score for this identity.
 * @param strongestScore            Highest individual recognizer similarity score.
 * @param isAmbiguous               True when the second-best margin is below the ambiguity
 *                                  threshold — the face lies close to two identity centroids.
 * @param agreementLevel            Pre-computed ensemble coherence level (LOW/MEDIUM/HIGH),
 *                                  factoring in recognizer count, score spread, ambiguity,
 *                                  and consensus ratio.
 */
data class FusedIdentityResult(
    val fusedFaceId: String,
    val assetId: String,
    val clusterId: String,
    val tagKey: String,
    val tagName: String,
    val tagId: Long,
    val fusedScore: Float,
    val contributingRecognizerIds: List<String>,
    val strongestScore: Float,
    val isAmbiguous: Boolean = false,
    val agreementLevel: AgreementLevel = AgreementLevel.LOW,
)
