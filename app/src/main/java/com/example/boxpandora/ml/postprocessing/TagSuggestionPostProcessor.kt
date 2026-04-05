package com.example.boxpandora.ml.postprocessing

import com.example.boxpandora.data.repository.RichSuggestion

/**
 * Post-processing pipeline that transforms raw model suggestion lists into clean,
 * semantically ranked tag candidates ready for UI display.
 *
 * ### Pipeline stages
 * 1. **Synonym deduplication** — collapse synonym/alias variants into their canonical form
 *    when both appear (e.g. "canine" discarded if "dog" also present).
 * 2. **First-pass deduplication** — keep the highest-scoring entry per tag key.
 * 3. **Parent-child suppression** — remove generic umbrella tags when a more specific child
 *    is already present (e.g. suppress "pet" when "dog" exists).
 * 4. **Suppression list** — hard-filter known low-value visual fragments.
 * 5. **Bucket-based low-value filter** — remove anything bucketed as [TagBucket.LOW_VALUE_DETAIL].
 * 6. **Promotion rules** — synthesise new suggestions when multi-tag context cues fire.
 * 7. **Quality-aware ranking** — sort by composite score (raw confidence × bucket weight ±
 *    fusion and ambiguity adjustments), cap at [MAX_SUGGESTIONS].
 *
 * Raw model outputs are never destroyed — they are returned in [PostProcessResult.debugEntries]
 * for inspection, evaluation, and future model tuning.
 */
object TagSuggestionPostProcessor {

    /** Maximum number of suggestions returned to the UI. */
    private const val MAX_SUGGESTIONS = 6

    fun process(rawSuggestions: List<RichSuggestion>): PostProcessResult {
        if (rawSuggestions.isEmpty()) return PostProcessResult(emptyList(), emptyList())

        val assetId = rawSuggestions.first().assetId

        // ── Stage 1 & 2: synonym-aware deduplication ─────────────────────────────
        // Group by canonical key (synonym → canonical mapping used only for grouping).
        // Within each canonical group, prefer the suggestion whose tagKey already IS the
        // canonical form to avoid mutating DB-backed tagKey values.
        val deduped: List<RichSuggestion> = rawSuggestions
            .groupBy { s -> TagNormalizationConfig.synonymToCanonical[s.tagKey] ?: s.tagKey }
            .map { (canonicalKey, group) ->
                group.firstOrNull { it.tagKey == canonicalKey }
                    ?: group.maxByOrNull { it.score }!!
            }

        // Fast lookup: tagKey → suggestion (after dedup)
        val tagScoreMap: Map<String, RichSuggestion> = deduped.associateBy { it.tagKey }
        val presentTags: Set<String> = tagScoreMap.keys

        // ── Stage 3: parent-child suppression ────────────────────────────────────
        val parentSuppressed: Set<String> = TagNormalizationConfig.parentToChildren
            .filter { (parent, children) ->
                parent in presentTags && children.any { it in presentTags }
            }
            .keys

        // ── Stages 4 & 5: build debug entries, filter suppressed + low-value tags ─
        val debugEntries = mutableListOf<PostProcessDebugEntry>()
        val afterSuppression: List<RichSuggestion> = deduped.mapNotNull { s ->
            val bucket = TagNormalizationConfig.tagToBucket[s.tagKey] ?: TagBucket.GENERIC
            val qualityScore = qualityScore(s, bucket)

            val suppressionReason: String? = when {
                s.tagKey in TagNormalizationConfig.suppressionSet -> "low_value_fragment"
                s.tagKey in parentSuppressed                      -> "parent_suppressed_by_child"
                bucket == TagBucket.LOW_VALUE_DETAIL              -> "low_value_bucket"
                else                                              -> null
            }

            debugEntries += PostProcessDebugEntry(
                rawTagKey       = s.tagKey,
                rawScore        = s.score,
                bucket          = bucket,
                qualityScore    = qualityScore,
                suppressionReason = suppressionReason,
            )

            if (suppressionReason != null) null else s
        }

        // ── Stage 6: promotion rules ──────────────────────────────────────────────
        val promoted: List<RichSuggestion> = applyPromotionRules(
            afterSuppression = afterSuppression,
            tagScoreMap      = tagScoreMap,
            assetId          = assetId,
        )

        // ── Stage 7: quality-aware ranking, cap at MAX_SUGGESTIONS ───────────────
        val finalSuggestions: List<RichSuggestion> = (afterSuppression + promoted)
            .groupBy { it.tagKey }
            .map { (_, group) -> group.maxByOrNull { it.score }!! }
            .sortedByDescending { s ->
                val bucket = TagNormalizationConfig.tagToBucket[s.tagKey] ?: TagBucket.GENERIC
                qualityScore(s, bucket)
            }
            .take(MAX_SUGGESTIONS)

        return PostProcessResult(finalSuggestions, debugEntries)
    }

    /**
     * Computes a quality-aware score used solely for ranking.
     * The original [RichSuggestion.score] is preserved in the returned suggestion so that
     * UI confidence badges continue to reflect actual model confidence.
     */
    private fun qualityScore(suggestion: RichSuggestion, bucket: TagBucket): Double {
        val ambiguityPenalty = if (suggestion.isAmbiguous) 0.08 else 0.0
        val fusionBonus = if (suggestion.isFused && suggestion.contributingModelCount >= 2) 0.04 else 0.0
        return (suggestion.score * bucket.priorityWeight + fusionBonus - ambiguityPenalty)
            .coerceIn(0.0, 1.0)
    }

    private fun applyPromotionRules(
        afterSuppression: List<RichSuggestion>,
        tagScoreMap: Map<String, RichSuggestion>,
        assetId: String,
    ): List<RichSuggestion> {
        val currentKeys: Set<String> = afterSuppression.map { it.tagKey }.toSet()
        return TagNormalizationConfig.promotionRules.mapNotNull { rule ->
            // Skip if promoted tag is already present
            if (rule.promotedTag in currentKeys) return@mapNotNull null
            // All trigger tags must be present with sufficient confidence
            val allTriggersPresent = rule.triggerTags.all { trigger ->
                tagScoreMap[trigger]?.score?.let { it >= rule.minTriggerScore } == true
            }
            if (!allTriggersPresent) return@mapNotNull null

            RichSuggestion(
                id           = 0L,
                assetId      = assetId,
                tagKey       = rule.promotedTag,
                score        = rule.promotedScore,
                source       = "promoted",
                tagCategory  = "context",
            )
        }
    }
}

/**
 * Output of [TagSuggestionPostProcessor.process].
 *
 * @property finalSuggestions Cleaned, ranked suggestions ready for UI display.
 * @property debugEntries     Full trace of every raw tag and its disposition,
 *                            for debugging and model-quality evaluation.
 */
data class PostProcessResult(
    val finalSuggestions: List<RichSuggestion>,
    val debugEntries: List<PostProcessDebugEntry>,
)

/**
 * Per-tag diagnostic entry produced by the post-processor.
 *
 * @property rawTagKey         Original tag key from the model output.
 * @property rawScore          Original model confidence (0.0–1.0).
 * @property bucket            Semantic bucket assigned to this tag.
 * @property qualityScore      Composite quality-aware score used for ranking.
 * @property suppressionReason Non-null when the tag was filtered from final suggestions;
 *                             describes the suppression cause for debugging.
 */
data class PostProcessDebugEntry(
    val rawTagKey: String,
    val rawScore: Double,
    val bucket: TagBucket,
    val qualityScore: Double,
    val suppressionReason: String?,
)
