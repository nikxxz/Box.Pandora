package com.example.boxpandora.ml.ensemble

import android.util.Log
import com.example.boxpandora.ml.config.FusionCalibration
import com.example.boxpandora.ml.config.FusionThresholdConfig

private const val TAG = "SuggestionFusionEngine"

/**
 * Merges [SceneModelEvidence] from multiple scene models into one unified
 * [FusedSuggestionResult] list for a single media item.
 *
 * Phase 3 fusion rules
 * ────────────────────
 *
 * A. Canonicalize first
 *    Evidence is expected to have already had alias resolution applied
 *    ([DefaultSceneModelRunner] does this). Tags are grouped by [canonicalTagKey].
 *
 * B. Combine scores with reliability weighting + category-aware suppression
 *    For each canonical tag:
 *     - If the number of distinct contributing models is less than
 *       [FusionThresholdConfig.CategoryThresholds.minModelCount], the tag is suppressed
 *       entirely. This prevents single-model noise in categories that require consensus.
 *     - Compute a reliability-weighted average of [rawScore] values. Each model's
 *       score is weighted by its [reliabilityWeights] entry (default 1.0 if absent).
 *     - Add an agreement bonus ([AGREEMENT_BONUS_PER_EXTRA_MODEL] × (count - 1) ×
 *       category.agreementBonusMultiplier), capped at [MAX_AGREEMENT_BONUS].
 *     - Apply category-specific minimum confidence floor (the stricter of
 *       [confidenceThreshold] and [FusionThresholdConfig.CategoryThresholds.minFusedConfidence]).
 *     - Compute a pre-liminary [AgreementLevel] from model count, score spread, and
 *       consensus ratio. The ambiguity factor is applied in the second pass below.
 *
 * C. Ambiguity detection (per category) + final agreement level
 *    For the top-ranked tag in each category: if its lead over the second-best in that
 *    category is less than [FusionThresholdConfig.CategoryThresholds.secondBestMargin],
 *    [FusedSuggestionResult.isAmbiguous] is set to true and the [AgreementLevel] is
 *    downgraded by one bucket.
 *
 * D. Per-category result cap
 *    At most [FusionThresholdConfig.CategoryThresholds.maxPerAsset] results are returned
 *    per content category.
 *
 * E. Rank and global cap
 *    - Sort descending by fused score across all categories.
 *    - Cap at [maxResults] total.
 *
 * Do NOT call this with raw un-canonicalized tag keys — aliases must be resolved
 * before evidence reaches this engine.
 */
class SuggestionFusionEngine {

    companion object {
        /** Base additive bonus per *additional* model that agrees on the same canonical tag. */
        private const val AGREEMENT_BONUS_PER_EXTRA_MODEL = 0.03f

        /** Hard cap on how much agreement bonus can boost a score (before category scaling). */
        private const val MAX_AGREEMENT_BONUS = 0.09f

        /** Default upper bound on returned fused suggestions per asset. */
        const val DEFAULT_MAX_RESULTS = 20
    }

    /**
     * Fuse [evidence] from one or more models into a ranked, threshold-filtered list.
     *
     * @param evidence             All per-model evidence for a single asset. Must not mix
     *                             evidence from different assets.
     * @param confidenceThreshold  User-configured global minimum fused score. The effective
     *                             threshold for a given category is `max(confidenceThreshold,
     *                             categoryThresholds.minFusedConfidence × calibration multiplier)`.
     * @param tagCategoryLookup    Map from canonical tag key to content category string
     *                             (e.g. "animal", "mood"). Tags absent from this map are
     *                             treated as "misc".
     * @param reliabilityWeights   Map from model manifest ID to derived reliability weight.
     *                             Absent models default to [FusionThresholdConfig.WEIGHT_DEFAULT].
     * @param calibration          Active calibration overlay. Defaults to [FusionCalibration.DEFAULT]
     *                             (no overrides). See [FusionCalibration] for field semantics.
     * @param maxResults           Maximum number of results returned in total.
     * @return                     Ranked list of fused suggestions, empty if none pass threshold.
     */
    fun fuse(
        evidence: List<SceneModelEvidence>,
        confidenceThreshold: Float,
        tagCategoryLookup: Map<String, String> = emptyMap(),
        reliabilityWeights: Map<String, Float> = emptyMap(),
        calibration: FusionCalibration = FusionCalibration.DEFAULT,
        maxResults: Int = DEFAULT_MAX_RESULTS,
    ): List<FusedSuggestionResult> {
        if (evidence.isEmpty()) return emptyList()

        val assetId = evidence.first().assetId

        // A. Group by canonical tag key
        val byTag: Map<String, List<SceneModelEvidence>> = evidence.groupBy { it.canonicalTagKey }

        // B. Compute fused score and base agreement level for each tag
        val candidates = byTag.mapNotNull { (canonicalKey, entries) ->
            val tagCategory = tagCategoryLookup[canonicalKey] ?: "misc"
            val thresholds  = FusionThresholdConfig.forScene(tagCategory)

            // Apply calibration: per-category threshold multiplier
            val catThreshMult = calibration.categoryThresholdMultipliers[tagCategory] ?: 1.0f
            val effectiveThreshold = maxOf(
                confidenceThreshold,
                thresholds.minFusedConfidence * catThreshMult,
            )

            // Category-aware support-count suppression: require minModelCount distinct models
            val distinctModels = entries.distinctBy { it.sourceModelId }
            if (distinctModels.size < thresholds.minModelCount) return@mapNotNull null

            // Reliability-weighted average with calibration strength interpolation:
            //   strength=0 → all weights flat 1.0; strength=1 → raw Bayesian weight;
            //   strength>1 → differences amplified beyond the raw value.
            val strength = calibration.reliabilityWeightStrength
            val weights = entries.map { ev ->
                val raw = reliabilityWeights[ev.sourceModelId] ?: FusionThresholdConfig.WEIGHT_DEFAULT
                1.0f + (raw - 1.0f) * strength
            }
            val totalWeight  = weights.sum().coerceAtLeast(1e-6f)
            val weightedSum  = entries.zip(weights).sumOf { (ev, w) ->
                (ev.rawScore * w).toDouble()
            }
            val weightedAvg  = (weightedSum / totalWeight).toFloat()

            // Agreement bonus (scaled by category multiplier × global calibration scale)
            val extraModels = (distinctModels.size - 1).coerceAtLeast(0)
            val bonus = (AGREEMENT_BONUS_PER_EXTRA_MODEL * extraModels
                * thresholds.agreementBonusMultiplier
                * calibration.agreementBonusScale)
                .coerceAtMost(MAX_AGREEMENT_BONUS)

            val fusedScore = (weightedAvg + bonus).coerceIn(0f, 1f)

            if (fusedScore < effectiveThreshold) return@mapNotNull null

            // Score spread and consensus ratio for agreement classification
            val rawScores    = entries.map { it.rawScore }
            val minRaw       = rawScores.min()
            val maxRaw       = rawScores.max()
            val scoreSpread  = maxRaw - minRaw
            val consensusRatio = if (maxRaw > 1e-6f) weightedAvg / maxRaw else 1f

            // Pre-compute base agreement (isAmbiguous not yet known; applied in second pass)
            val baseAgreementLevel = AgreementLevel.fromEvidence(
                contributingCount = distinctModels.size,
                scoreSpread       = scoreSpread,
                isAmbiguous       = false,
                consensusRatio    = consensusRatio,
            )

            FusedSuggestionResult(
                assetId              = assetId,
                canonicalTagKey      = canonicalKey,
                tagCategory          = tagCategory,
                fusedScore           = fusedScore,
                contributingModelIds = distinctModels.map { it.sourceModelId },
                strongestScore       = maxRaw,
                isAmbiguous          = false, // resolved in second pass below
                agreementLevel       = baseAgreementLevel,
            )
        }.sortedByDescending { it.fusedScore }

        // C. Determine second-best per category for ambiguity detection
        val firstByCategory  = mutableMapOf<String, Float>()
        val secondByCategory = mutableMapOf<String, Float>()
        for (c in candidates) {
            val cat = c.tagCategory
            if (!firstByCategory.containsKey(cat)) {
                firstByCategory[cat] = c.fusedScore
            } else if (!secondByCategory.containsKey(cat)) {
                secondByCategory[cat] = c.fusedScore
            }
        }

        // D & E. Apply per-category maxPerAsset cap, mark ambiguity, apply global cap
        val countByCategory = mutableMapOf<String, Int>()
        val result          = mutableListOf<FusedSuggestionResult>()

        for (candidate in candidates) {
            if (result.size >= maxResults) break

            val cat        = candidate.tagCategory
            val thresholds = FusionThresholdConfig.forScene(cat)
            val count      = countByCategory.getOrDefault(cat, 0)

            if (count >= thresholds.maxPerAsset) continue

            // Only the top-ranked tag in each category can be flagged ambiguous;
            // calibration ambiguity margin multiplier scales how aggressively the flag fires.
            val isAmbiguous = if (count == 0) {
                val second = secondByCategory[cat] ?: 0f
                val effectiveMargin = thresholds.secondBestMargin * calibration.ambiguityMarginMultiplier
                (candidate.fusedScore - second) < effectiveMargin
            } else {
                false
            }

            // Apply ambiguity downgrade to the agreement level
            val finalAgreementLevel = if (isAmbiguous) candidate.agreementLevel.downgrade()
                                      else candidate.agreementLevel

            result.add(candidate.copy(isAmbiguous = isAmbiguous, agreementLevel = finalAgreementLevel))
            countByCategory[cat] = count + 1
        }

        Log.d(TAG, "Fused ${evidence.size} evidence records into ${result.size} suggestions " +
            "for asset=$assetId (threshold=$confidenceThreshold)")

        return result
    }
}
