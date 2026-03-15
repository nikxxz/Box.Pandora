package com.example.boxpandora.ml.ensemble

/**
 * In-memory result of fusing evidence from multiple scene models for a single (asset, tag) pair.
 *
 * Produced by [SuggestionFusionEngine] and persisted as a [FusedSceneSuggestion] row.
 * This is an ephemeral pipeline object — it exists only during an orchestration run.
 *
 * @param assetId               URI of the media item.
 * @param canonicalTagKey       Alias-resolved, normalized tag name.
 * @param tagCategory           Content category of the tag (e.g. "animal", "place"); "misc" if unknown.
 * @param fusedScore            Combined confidence after reliability-weighted fusion + agreement bonus.
 * @param contributingModelIds  Manifest IDs of models that contributed evidence.
 * @param strongestScore        Highest raw score among contributing models (provenance).
 * @param isAmbiguous           True when the top tag in this category leads the second-best by less
 *                              than the category-specific [FusionThresholdConfig.CategoryThresholds.secondBestMargin].
 * @param agreementLevel        Pre-computed ensemble coherence level (LOW/MEDIUM/HIGH), factoring in
 *                              model count, score spread, ambiguity, and consensus ratio.
 *
 * The [contributingModelCount] is `contributingModelIds.size` — kept as a property to
 * avoid recomputing it at the persistence layer.
 */
data class FusedSuggestionResult(
    val assetId: String,
    val canonicalTagKey: String,
    val tagCategory: String = "misc",
    val fusedScore: Float,
    val contributingModelIds: List<String>,
    val strongestScore: Float,
    val isAmbiguous: Boolean = false,
    val agreementLevel: AgreementLevel = AgreementLevel.LOW,
) {
    val contributingModelCount: Int get() = contributingModelIds.size
}
