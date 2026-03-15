package com.example.boxpandora.ml.config

/**
 * Internal calibration overlay for [com.example.boxpandora.ml.ensemble.SuggestionFusionEngine].
 *
 * All fields default to their identity (no change from [FusionThresholdConfig] defaults).
 * This layer exists so thresholds can be refined systematically in a later phase without
 * rewriting the fusion engine. The [version] string is embedded in every
 * [com.example.boxpandora.ml.ensemble.EnsembleRunManifest] so that fused outputs can be
 * correlated with the exact calibration snapshot that produced them.
 *
 * These values are not surfaced directly in the user-facing settings UI.
 *
 * @param version                      Opaque string stamped into run manifests. Changing any
 *                                     field should produce a new [version] so re-runs are
 *                                     distinguishable from the previous calibration.
 * @param categoryThresholdMultipliers Per-category multiplier applied on top of each category's
 *                                     [FusionThresholdConfig.CategoryThresholds.minFusedConfidence].
 *                                     Values < 1.0 relax the floor; > 1.0 tighten it.
 *                                     Missing categories default to 1.0 (no change).
 * @param ambiguityMarginMultiplier    Multiplier applied to each category's [secondBestMargin].
 *                                     < 1.0 → ambiguity flag fires less; > 1.0 → fires more.
 * @param agreementBonusScale          Global scale on the per-extra-model agreement bonus.
 *                                     0.0 disables the bonus entirely; 1.0 = default strength.
 * @param reliabilityWeightStrength    Interpolates between flat weights (0.0) and full
 *                                     reliability-derived weights (1.0). Values > 1.0 amplify
 *                                     weight differences beyond the Bayesian-derived values.
 * @param identityThresholdMultiplier  Multiplier applied to [FusionThresholdConfig.IDENTITY]'s
 *                                     [minFusedConfidence] and [secondBestMargin].
 */
data class FusionCalibration(
    val version: String,
    val categoryThresholdMultipliers: Map<String, Float> = emptyMap(),
    val ambiguityMarginMultiplier: Float = 1.0f,
    val agreementBonusScale: Float = 1.0f,
    val reliabilityWeightStrength: Float = 1.0f,
    val identityThresholdMultiplier: Float = 1.0f,
) {
    companion object {
        /** Identity calibration — all fields at default, no overrides active. */
        val DEFAULT = FusionCalibration(version = "default")
    }
}
