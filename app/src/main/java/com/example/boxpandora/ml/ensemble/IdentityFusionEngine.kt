package com.example.boxpandora.ml.ensemble

import com.example.boxpandora.ml.config.FusionThresholdConfig

/**
 * Fuses identity candidates from multiple face recognizers for a single fused face.
 *
 * Phase 3 algorithm:
 *  1. Collect all [IdentityEvidenceRecord]s for the face, grouped by [clusterId].
 *  2. Skip any cluster that was seen by fewer recognizers than
 *     [FusionThresholdConfig.IDENTITY.minModelCount] — enforces a support-count floor
 *     so that single-recognizer hunches don't surface as identity suggestions.
 *  3. For each surviving cluster, compute a reliability-weighted average score across all
 *     recognizers that produced a candidate for it. Each recognizer's similarity score
 *     is weighted by its entry in [reliabilityWeights] (default 1.0 if absent).
 *  4. Apply an agreement bonus: +[AGREEMENT_BONUS_PER_MODEL] × agreementBonusMultiplier per
 *     additional recognizer beyond the first, capped at [MAX_AGREEMENT_BONUS].
 *  5. Sort by fused score descending; return top-[maxPerAsset] (currently 1) identity above
 *     threshold. Only the single best-matched identity is surfaced per fused face.
 *  6. Second-best margin rule: if the gap between the best and second-best fused scores is
 *     below [FusionThresholdConfig.IDENTITY.secondBestMargin], the best result is marked
 *     [FusedIdentityResult.isAmbiguous] and its [AgreementLevel] is downgraded.
 *  7. Compute [AgreementLevel] from recognizer count, score spread, ambiguity, and consensus
 *     ratio — a richer signal than count alone.
 *
 * All threshold constants come from [FusionThresholdConfig.IDENTITY] to keep them in
 * one place and consistent with scene fusion policy.
 */
class IdentityFusionEngine {

    companion object {
        private const val AGREEMENT_BONUS_PER_MODEL = 0.03f
        private const val MAX_AGREEMENT_BONUS       = 0.09f
    }

    /**
     * Fuses [evidence] into a ranked list of [FusedIdentityResult]s for [fusedFaceId].
     *
     * @param fusedFaceId         ID of the fused face these results belong to.
     * @param assetId             URI of the source asset.
     * @param evidence            All identity evidence records across all recognizers for this face.
     * @param confidenceThreshold Minimum fused score to include a result. Effective threshold
     *                            is `max(confidenceThreshold, FusionThresholdConfig.IDENTITY.minFusedConfidence)`.
     * @param reliabilityWeights  Map from recognizer manifest ID to derived reliability weight.
     *                            Absent recognizers default to [FusionThresholdConfig.WEIGHT_DEFAULT].
     * @return Ranked list of fused identity results, best-first. At most
     *         [FusionThresholdConfig.IDENTITY.maxPerAsset] results (currently 1).
     */
    fun fuse(
        fusedFaceId: String,
        assetId: String,
        evidence: List<IdentityEvidenceRecord>,
        confidenceThreshold: Float = FusionThresholdConfig.IDENTITY.minFusedConfidence,
        reliabilityWeights: Map<String, Float> = emptyMap(),
    ): List<FusedIdentityResult> {
        if (evidence.isEmpty()) return emptyList()

        val identityConfig     = FusionThresholdConfig.IDENTITY
        val effectiveThreshold = maxOf(confidenceThreshold, identityConfig.minFusedConfidence)

        // Group by clusterId (one person = one cluster in the confirmed-cluster layer)
        val byCluster = evidence.groupBy { it.clusterId }

        val candidates = byCluster.mapNotNull { (clusterId, records) ->
            val first = records.first()

            // Support-count suppression: skip if fewer recognizers contributed than required
            val distinctRecognizers = records.distinctBy { it.recognizerId }
            if (distinctRecognizers.size < identityConfig.minModelCount) return@mapNotNull null

            // Reliability-weighted average similarity
            val weights     = records.map { reliabilityWeights[it.recognizerId]
                ?: FusionThresholdConfig.WEIGHT_DEFAULT }
            val totalWeight = weights.sum().coerceAtLeast(1e-6f)
            val weightedSum = records.zip(weights).sumOf { (ev, w) ->
                (ev.similarityScore * w).toDouble()
            }
            val weightedAvg = (weightedSum / totalWeight).toFloat()

            // Agreement bonus (scaled by identity agreement multiplier)
            val extraRecognizers = (distinctRecognizers.size - 1).coerceAtLeast(0)
            val bonus = (AGREEMENT_BONUS_PER_MODEL * extraRecognizers
                * identityConfig.agreementBonusMultiplier)
                .coerceAtMost(MAX_AGREEMENT_BONUS)

            val fusedScore = (weightedAvg + bonus).coerceIn(0f, 1f)
            if (fusedScore < effectiveThreshold) return@mapNotNull null

            // Score spread and consensus ratio for agreement classification
            val rawScores    = records.map { it.similarityScore }
            val minRaw       = rawScores.min()
            val maxRaw       = rawScores.max()
            val scoreSpread  = maxRaw - minRaw
            val consensusRatio = if (maxRaw > 1e-6f) weightedAvg / maxRaw else 1f

            // Base agreement level (isAmbiguous unknown until second-best check below)
            val baseAgreementLevel = AgreementLevel.fromEvidence(
                contributingCount = distinctRecognizers.size,
                scoreSpread       = scoreSpread,
                isAmbiguous       = false,
                consensusRatio    = consensusRatio,
            )

            FusedIdentityResult(
                fusedFaceId               = fusedFaceId,
                assetId                   = assetId,
                clusterId                 = clusterId,
                tagKey                    = first.tagKey,
                tagName                   = first.tagName,
                tagId                     = first.tagId,
                fusedScore                = fusedScore,
                contributingRecognizerIds = distinctRecognizers.map { it.recognizerId },
                strongestScore            = maxRaw,
                isAmbiguous               = false,   // set below
                agreementLevel            = baseAgreementLevel,
            )
        }.sortedByDescending { it.fusedScore }
            .take(identityConfig.maxPerAsset)

        if (candidates.size < 2) return candidates

        // Second-best margin rule: flag ambiguity + downgrade agreement on the top result
        val best       = candidates[0].fusedScore
        val secondBest = candidates[1].fusedScore
        return if ((best - secondBest) < identityConfig.secondBestMargin) {
            candidates.mapIndexed { i, r ->
                if (i == 0) r.copy(
                    isAmbiguous    = true,
                    agreementLevel = r.agreementLevel.downgrade(),
                ) else r
            }
        } else {
            candidates
        }
    }
}
