package com.example.boxpandora.ml.ensemble

import android.util.Log
import com.example.boxpandora.data.local.AppDatabase
import com.example.boxpandora.data.local.entity.FusedIdentitySuggestion
import com.example.boxpandora.data.local.entity.FusedSceneSuggestion
import com.example.boxpandora.data.local.entity.ModelReliabilityStats
import com.example.boxpandora.ml.config.FusionThresholdConfig
import kotlin.math.min

private const val TAG = "ReliabilityUpdateService"

/**
 * Updates [ModelReliabilityStats] when the user accepts or rejects a fused suggestion.
 *
 * Conservative weighting policy
 * ─────────────────────────────
 *  - At least [FusionThresholdConfig.MIN_EVENTS_FOR_WEIGHT] real feedback events must
 *    accumulate before [ModelReliabilityStats.derivedWeight] moves away from 1.0.
 *  - Bayesian Beta(α, β) smoothing with prior α=β=3 prevents wild swings from early data
 *    and ensures a model cannot be gutted by 2–3 early rejects.
 *  - Weights are clamped to [[FusionThresholdConfig.WEIGHT_MIN], [FusionThresholdConfig.WEIGHT_MAX]].
 *  - Only models that contributed evidence to the accepted/rejected suggestion are updated,
 *    so non-contributing models are never penalised for results they didn't influence.
 *
 * Scene tag reliability is tracked per (modelId, tagCategory) — a model may be reliable
 * for "animal" while being shaky on "mood". Identity reliability is a single bucket per
 * recognizer because face identity does not subdivide by tag category.
 *
 * Call from a suspend function already on a background dispatcher — all methods perform
 * direct DB I/O without launching their own coroutines.
 */
class ReliabilityUpdateService(private val db: AppDatabase) {

    // ── Scene suggestion feedback ─────────────────────────────────────────────

    /** Records acceptance of a fused scene tag suggestion. */
    suspend fun onSceneSuggestionAccepted(suggestion: FusedSceneSuggestion) {
        updateScene(suggestion, accepted = true)
    }

    /** Records rejection of a fused scene tag suggestion. */
    suspend fun onSceneSuggestionRejected(suggestion: FusedSceneSuggestion) {
        updateScene(suggestion, accepted = false)
    }

    // ── Identity suggestion feedback ──────────────────────────────────────────

    /** Records acceptance of a fused identity (person) suggestion. */
    suspend fun onIdentitySuggestionAccepted(suggestion: FusedIdentitySuggestion) {
        updateIdentity(suggestion, accepted = true)
    }

    /** Records rejection of a fused identity (person) suggestion. */
    suspend fun onIdentitySuggestionRejected(suggestion: FusedIdentitySuggestion) {
        updateIdentity(suggestion, accepted = false)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun updateScene(suggestion: FusedSceneSuggestion, accepted: Boolean) {
        val tagCategory = suggestion.tagCategory.ifBlank { "misc" }
        val modelIds = suggestion.contributingModelIds
            .split(",").map { it.trim() }.filter { it.isNotBlank() }
        for (modelId in modelIds) {
            updateRecord(
                modelId          = modelId,
                pipelineCategory = ModelReliabilityStats.PIPELINE_SCENE,
                tagCategory      = tagCategory,
                accepted         = accepted,
            )
        }
    }

    private suspend fun updateIdentity(suggestion: FusedIdentitySuggestion, accepted: Boolean) {
        val recognizerIds = suggestion.contributingRecognizerIds
            .split(",").map { it.trim() }.filter { it.isNotBlank() }
        for (modelId in recognizerIds) {
            updateRecord(
                modelId          = modelId,
                pipelineCategory = ModelReliabilityStats.PIPELINE_IDENTITY,
                tagCategory      = ModelReliabilityStats.TAG_CAT_IDENTITY,
                accepted         = accepted,
            )
        }
    }

    private suspend fun updateRecord(
        modelId: String,
        pipelineCategory: String,
        tagCategory: String,
        accepted: Boolean,
    ) {
        val dao     = db.modelReliabilityStatsDao()
        val current = dao.getRecord(modelId, pipelineCategory, tagCategory)
            ?: ModelReliabilityStats(
                modelId          = modelId,
                pipelineCategory = pipelineCategory,
                tagCategory      = tagCategory,
            )

        val newAccepted  = current.acceptedCount      + if (accepted) 1 else 0
        val newRejected  = current.rejectedCount      + if (accepted) 0 else 1
        val newSurfaced  = current.totalSurfacedCount + 1
        val newWeight    = computeWeight(newAccepted, newRejected)

        dao.upsert(
            current.copy(
                acceptedCount      = newAccepted,
                rejectedCount      = newRejected,
                totalSurfacedCount = newSurfaced,
                derivedWeight      = newWeight,
                lastUpdatedAt      = System.currentTimeMillis(),
            )
        )

        Log.d(TAG, "[$modelId / $tagCategory] +${if (accepted) "accept" else "reject"} " +
            "→ accepted=$newAccepted rejected=$newRejected weight=${"%.3f".format(newWeight)}")
    }

    // ── Model health / staleness penalty ──────────────────────────────────────

    /**
     * Applies a mild health or staleness penalty to a model's derived weight without
     * touching its accept/reject feedback counts.
     *
     * The penalty is applied to **every** (tagCategory) row for the given
     * (modelId, pipelineCategory) pair so that all content-category slots are adjusted
     * uniformly. Records that don't yet exist are skipped — the penalty will be naturally
     * reflected when those records are first created (they start at [WEIGHT_DEFAULT]).
     *
     * The total single-call penalty is capped at [FusionThresholdConfig.MAX_STALENESS_PENALTY]
     * to prevent transient failure spikes from gutting a model's weight.
     *
     * Suggested callers:
     *  - [SceneEnsembleOrchestrator] — when a model runner throws during inference
     *    (use [FusionThresholdConfig.INFERENCE_FAILURE_PENALTY]).
     *  - [FaceEnsembleOrchestrator] — when an embedder throws during inference.
     *  - Future: model manifest / version checker — for [FusionThresholdConfig.STALE_VERSION_PENALTY]
     *    and [FusionThresholdConfig.CRASH_LOOP_PENALTY] once the manifest upgrade checker lands.
     *
     * @param modelId          Manifest ID of the model to penalise.
     * @param pipelineCategory [ModelReliabilityStats.PIPELINE_SCENE] or
     *                         [ModelReliabilityStats.PIPELINE_IDENTITY].
     * @param penalty          Weight reduction to apply (positive value; e.g.
     *                         [FusionThresholdConfig.INFERENCE_FAILURE_PENALTY]).
     * @param reason           Short label logged for diagnostics (e.g. "inference_failure").
     * @return Number of records updated.
     */
    suspend fun applyModelPenalty(
        modelId: String,
        pipelineCategory: String,
        penalty: Float,
        reason: String,
    ): Int {
        val dao        = db.modelReliabilityStatsDao()
        val records    = dao.getRecordsForModelPipeline(modelId, pipelineCategory)
        val cappedPenalty = min(penalty, FusionThresholdConfig.MAX_STALENESS_PENALTY)
        var updated    = 0

        for (record in records) {
            val newWeight = (record.derivedWeight - cappedPenalty)
                .coerceAtLeast(FusionThresholdConfig.WEIGHT_MIN)
            if (newWeight != record.derivedWeight) {
                dao.upsert(record.copy(
                    derivedWeight = newWeight,
                    lastUpdatedAt = System.currentTimeMillis(),
                ))
                updated++
            }
        }

        Log.d(TAG, "[$modelId / $pipelineCategory] penalty -${"%.3f".format(cappedPenalty)} " +
            "for $reason → $updated record(s) updated")
        return updated
    }

    /**
     * Recomputes [ModelReliabilityStats.derivedWeight] for every record in the table using
     * the current [computeWeight] formula and the already-stored accept/reject counts.
     *
     * Call this after changing [FusionThresholdConfig] constants, or as a maintenance action
     * to repair weights that diverged from formula changes. Does NOT alter accept/reject counts
     * or any user feedback.
     *
     * @return Number of records updated.
     */
    suspend fun recalculateAllWeights(): Int {
        val dao = db.modelReliabilityStatsDao()
        // getAllSceneWeights + getAllIdentityWeights covers all records written by this service
        val all = dao.getAllSceneWeights() + dao.getAllIdentityWeights()
        if (all.isEmpty()) return 0

        var updated = 0
        for (record in all) {
            val newWeight = computeWeight(record.acceptedCount, record.rejectedCount)
            if (newWeight != record.derivedWeight) {
                dao.upsert(record.copy(
                    derivedWeight  = newWeight,
                    lastUpdatedAt  = System.currentTimeMillis(),
                ))
                updated++
            }
        }
        Log.d(TAG, "recalculateAllWeights: checked ${all.size} records, updated $updated")
        return updated
    }

    /**
     * Bayesian-smoothed precision → weight mapping.
     *
     * Prior: Beta(α=3, β=3) — equivalent to starting with 3 accepts and 3 rejects already
     * on the books. Keeps the derived weight near neutral until real signal accumulates.
     *
     * Formula: smoothed_precision = (accepted + α) / (accepted + rejected + α + β)
     *          weight = WEIGHT_MIN + (WEIGHT_MAX − WEIGHT_MIN) × smoothed_precision
     *
     * Behaviour:
     *   0 real events  → 0.5 precision → weight ≈ 1.0  (neutral)
     *   10/0 events    → 0.93 precision → weight ≈ 1.42 (strong positive)
     *   0/10 events    → 0.23 precision → weight ≈ 0.65 (moderate negative)
     *   20/20 events   → 0.5  precision → weight ≈ 1.0  (still neutral; balanced feedback)
     *
     * The weight does not move until [FusionThresholdConfig.MIN_EVENTS_FOR_WEIGHT] real
     * events have accumulated, preventing a fresh install or sparse-feedback model from
     * getting an unearned penalty.
     */
    private fun computeWeight(accepted: Int, rejected: Int): Float {
        val total = accepted + rejected
        if (total < FusionThresholdConfig.MIN_EVENTS_FOR_WEIGHT) {
            return FusionThresholdConfig.WEIGHT_DEFAULT
        }
        val alpha     = FusionThresholdConfig.BAYES_PRIOR_ALPHA
        val beta      = FusionThresholdConfig.BAYES_PRIOR_BETA
        val precision = (accepted + alpha) / (accepted + rejected + alpha + beta)
        val weight    = FusionThresholdConfig.WEIGHT_MIN +
            (FusionThresholdConfig.WEIGHT_MAX - FusionThresholdConfig.WEIGHT_MIN) * precision
        return weight.coerceIn(FusionThresholdConfig.WEIGHT_MIN, FusionThresholdConfig.WEIGHT_MAX)
    }
}
