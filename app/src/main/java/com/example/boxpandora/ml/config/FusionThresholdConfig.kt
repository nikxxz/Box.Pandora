package com.example.boxpandora.ml.config

/**
 * Internal threshold configuration for ensemble fusion, keyed by tag category.
 *
 * Not exposed in the settings UI. These values encode the pipeline's conservative
 * stance per content category. The user-facing [AiSettings.confidenceThreshold] acts
 * as an additional global floor — category-specific values only ever add strictness
 * above it, never relax it below what the user has chosen.
 *
 * Phase 3: replaces the single global confidenceThreshold for fused ensemble suggestions
 * with per-category control at the fusion layer.
 */
object FusionThresholdConfig {

    /**
     * Per-category thresholds for fused scene tag suggestions.
     *
     * @param minFusedConfidence        Minimum fused score to surface a suggestion for this category.
     * @param secondBestMargin          Minimum score lead the top tag in this category must have
     *                                  over the second-best in the same category to avoid
     *                                  [FusedSuggestionResult.isAmbiguous] being set.
     * @param agreementBonusMultiplier  Scales the per-extra-model agreement bonus. Values < 1.0
     *                                  dampen the bonus for categories where multi-model agreement
     *                                  is less informative.
     * @param maxPerAsset               Maximum suggestions surfaced per media item for this
     *                                  category. Prevents one dominant category flooding results.
     * @param minModelCount             Minimum number of *distinct* contributing models (or
     *                                  recognizers) required for a fused result to be surfaced.
     *                                  Default 1 (single-model evidence is allowed). Set to 2
     *                                  for categories where multi-model consensus is required
     *                                  to reduce low-confidence noise.
     */
    data class CategoryThresholds(
        val minFusedConfidence: Float,
        val secondBestMargin: Float,
        val agreementBonusMultiplier: Float = 1.0f,
        val maxPerAsset: Int = Int.MAX_VALUE,
        val minModelCount: Int = 1,
    )

    /**
     * Scene tag category thresholds, ordered most-to-least strict.
     *
     * Category-specific rules:
     *  - "people"  : Strictest. Scene models should not be the primary evidence for person
     *                identity. General people/crowd descriptors are fine but need strong agreement.
     *  - "animal"  : Requires at least 2 models — single-model animal tags frequently mislabel.
     *  - "object"  : Same as animal — common objects are easily confused; multi-model support
     *                significantly reduces false positives.
     *  - "style"   : Raised floor and 2-model minimum because style tags are subjective and
     *                can multiply rapidly when evidence is weak or overlapping.
     *  - "mood"    : Capped at 2 per asset — mood can flood results without a hard limit.
     *  - "clothing": Moderate cap; clothing detail overlap is high but generally harmless.
     *  - "place"   : Moderate cap; scenes often have a dominant place context.
     *  - "pose"    : Low cap; a single pose usually dominates an image.
     */
    val SCENE: Map<String, CategoryThresholds> = mapOf(
        "people"   to CategoryThresholds(
            minFusedConfidence       = 0.65f,
            secondBestMargin         = 0.12f,
            agreementBonusMultiplier = 0.70f,
            maxPerAsset              = 5,
            minModelCount            = 1,
        ),
        "animal"   to CategoryThresholds(
            minFusedConfidence = 0.55f,
            secondBestMargin   = 0.10f,
            maxPerAsset        = 6,
            minModelCount      = 2,
        ),
        "object"   to CategoryThresholds(
            minFusedConfidence = 0.55f,
            secondBestMargin   = 0.10f,
            maxPerAsset        = 6,
            minModelCount      = 2,
        ),
        "clothing" to CategoryThresholds(
            minFusedConfidence = 0.55f,
            secondBestMargin   = 0.10f,
            maxPerAsset        = 4,
        ),
        "place"    to CategoryThresholds(
            minFusedConfidence = 0.50f,
            secondBestMargin   = 0.08f,
            maxPerAsset        = 4,
        ),
        "pose"     to CategoryThresholds(
            minFusedConfidence = 0.50f,
            secondBestMargin   = 0.08f,
            maxPerAsset        = 3,
        ),
        "style"    to CategoryThresholds(
            minFusedConfidence       = 0.50f,   // raised from 0.45 — style tags need stronger signal
            secondBestMargin         = 0.09f,
            agreementBonusMultiplier = 0.80f,
            maxPerAsset              = 3,
            minModelCount            = 2,        // overlapping style evidence needs multi-model support
        ),
        "mood"     to CategoryThresholds(
            minFusedConfidence = 0.48f,
            secondBestMargin   = 0.08f,
            maxPerAsset        = 2,              // mood can multiply like weeds — hard cap at 2
        ),
        "misc"     to CategoryThresholds(
            minFusedConfidence = 0.45f,
            secondBestMargin   = 0.07f,
        ),
    )

    /** Fallback applied when the tag category is absent or unrecognised. */
    val DEFAULT_SCENE = CategoryThresholds(minFusedConfidence = 0.50f, secondBestMargin = 0.08f)

    /**
     * Identity threshold for fused face-based person identity suggestions.
     *
     * Stricter than all scene categories. The wider margin (0.12) ensures the ambiguity
     * flag fires more aggressively for person identity — the cost of a wrong person label
     * is higher than the cost of a wrong scene tag.
     *
     * [maxPerAsset] = 1: only the best-matched identity is surfaced per fused face.
     * Surfacing multiple competing identity candidates per face would confuse the user
     * and undermine trust in the feature.
     */
    val IDENTITY = CategoryThresholds(
        minFusedConfidence       = 0.60f,
        secondBestMargin         = 0.12f,
        agreementBonusMultiplier = 0.90f,
        maxPerAsset              = 1,
        minModelCount            = 1,
    )

    /** Returns the scene thresholds for [tagCategory], falling back to [DEFAULT_SCENE]. */
    fun forScene(tagCategory: String): CategoryThresholds =
        SCENE[tagCategory] ?: DEFAULT_SCENE

    // ── Reliability weight constants (used by ReliabilityUpdateService) ───────

    /** Minimum reliability weight any model can reach from feedback. */
    const val WEIGHT_MIN     = 0.40f
    /** Maximum reliability weight any model can reach from feedback. */
    const val WEIGHT_MAX     = 1.50f
    /** Neutral weight — applied before sufficient feedback accumulates. */
    const val WEIGHT_DEFAULT = 1.00f
    /**
     * Minimum number of (accepted + rejected) feedback events required before
     * [derivedWeight] is allowed to move away from [WEIGHT_DEFAULT].
     * Prevents wild weight swings from a handful of early events.
     */
    const val MIN_EVENTS_FOR_WEIGHT = 10
    /**
     * Bayesian Beta prior shape parameters — equivalent to starting with 3 accepts
     * and 3 rejects already on the books. Keeps the weight near neutral until real
     * signal accumulates and prevents a model from being trashed by 2–3 early rejects.
     */
    const val BAYES_PRIOR_ALPHA = 3f
    const val BAYES_PRIOR_BETA  = 3f

    // ── Model staleness / health penalty constants ────────────────────────────

    /**
     * Weight reduction applied each time a model fails during a single inference run.
     * Mild — intended to nudge, not exile. Multiple failures on the same run stack.
     */
    const val INFERENCE_FAILURE_PENALTY = 0.03f

    /**
     * Weight reduction applied when a model is detected to be in a crash loop
     * (failure count ≥ ModelManager.MAX_MODEL_FAILURES). Stronger than a single
     * inference failure but still within the weight floor.
     */
    const val CRASH_LOOP_PENALTY = 0.10f

    /**
     * Weight reduction for a model whose manifest version is known to be stale relative
     * to the latest available version. Nudges the ensemble toward more up-to-date models
     * without hard-blocking stale ones.
     *
     * Phase 3: hook is defined here; version staleness detection is wired in a later phase
     * when the manifest upgrade checker is available.
     */
    const val STALE_VERSION_PENALTY = 0.05f

    /**
     * Maximum total staleness/health penalty that can be applied in a single penalty call.
     * Caps one-shot quality signals so a model cannot be gutted by a transient failure spike.
     */
    const val MAX_STALENESS_PENALTY = 0.20f
}
