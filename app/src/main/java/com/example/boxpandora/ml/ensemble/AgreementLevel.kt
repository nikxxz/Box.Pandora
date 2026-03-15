package com.example.boxpandora.ml.ensemble

/**
 * Coarse signal of how much the contributing models agreed on a fused suggestion.
 *
 * Computed at fusion time from four factors:
 *  1. **Contributing count** — how many distinct models produced evidence.
 *  2. **Score spread** — max − min of individual raw scores; wide spread signals disagreement.
 *  3. **Ambiguity** — whether the second-best margin rule fired for this result.
 *  4. **Consensus ratio** — weighted average / strongest individual score; values far below
 *     1.0 indicate one strong outlier pulling the result up while others were weak.
 *
 * Determination is deliberately conservative: any negative signal (wide spread, ambiguous
 * margin, poor consensus) downgrades the level by one bucket, regardless of model count.
 * The intent is a cheap, trust-building summary for the user — not a scientific truth meter.
 *
 * Ordinal encoding (for DB storage): LOW=0, MEDIUM=1, HIGH=2.
 *
 * | Level  | Label in UI         |
 * |--------|---------------------|
 * | LOW    | "1 model"           |
 * | MEDIUM | "2 models"          |
 * | HIGH   | "High agreement"    |
 */
enum class AgreementLevel {
    LOW,
    MEDIUM,
    HIGH;

    companion object {
        /** Simple count-based classification — use when evidence factors are unavailable. */
        fun from(contributingCount: Int): AgreementLevel = when {
            contributingCount >= 3 -> HIGH
            contributingCount == 2 -> MEDIUM
            else                   -> LOW
        }

        /**
         * Richer classification that factors in score coherence, not just model count.
         *
         * Algorithm:
         *  1. Start with the count-based level.
         *  2. Downgrade once if [scoreSpread] > [SPREAD_DOWNGRADE_THRESHOLD] (models disagreed
         *     significantly on raw scores).
         *  3. Downgrade once if [isAmbiguous] is true (second-best margin too narrow).
         *  4. Downgrade once if [consensusRatio] < [CONSENSUS_DOWNGRADE_THRESHOLD] (one strong
         *     model is carrying the result while others contributed weak signal).
         *
         * Downgrades are independent — a result can be downgraded by all three factors,
         * but the floor is always [LOW] regardless of model count.
         *
         * @param contributingCount  Number of distinct models / recognizers that contributed.
         * @param scoreSpread        Difference between highest and lowest raw scores (0..1).
         * @param isAmbiguous        True when the second-best margin rule fired for this result.
         * @param consensusRatio     Reliability-weighted average score divided by the strongest
         *                           individual raw score. Range (0..1]; 1.0 = perfect consensus.
         */
        fun fromEvidence(
            contributingCount: Int,
            scoreSpread: Float,
            isAmbiguous: Boolean,
            consensusRatio: Float,
        ): AgreementLevel {
            var level = from(contributingCount)
            if (scoreSpread    > SPREAD_DOWNGRADE_THRESHOLD)    level = level.downgrade()
            if (isAmbiguous)                                     level = level.downgrade()
            if (consensusRatio < CONSENSUS_DOWNGRADE_THRESHOLD) level = level.downgrade()
            return level
        }

        /** Score spread above which the agreement level is downgraded by one bucket. */
        const val SPREAD_DOWNGRADE_THRESHOLD = 0.25f

        /**
         * Consensus ratio below which the agreement level is downgraded by one bucket.
         * A ratio of 0.70 means the weighted average was less than 70% of the peak individual
         * score — indicating that the strongest model is largely responsible for the result.
         */
        const val CONSENSUS_DOWNGRADE_THRESHOLD = 0.70f
    }
}

/** Downgrades this level by one bucket; cannot go below [AgreementLevel.LOW]. */
fun AgreementLevel.downgrade(): AgreementLevel = when (this) {
    AgreementLevel.HIGH   -> AgreementLevel.MEDIUM
    AgreementLevel.MEDIUM,
    AgreementLevel.LOW    -> AgreementLevel.LOW
}

/** Compact UI label. Uses [contributingCount] for precise wording on LOW/MEDIUM. */
fun AgreementLevel.toLabel(contributingCount: Int): String = when (this) {
    AgreementLevel.HIGH   -> "High agreement"
    AgreementLevel.MEDIUM -> "$contributingCount models"
    AgreementLevel.LOW    -> "1 model"
}
