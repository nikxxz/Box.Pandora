package com.example.boxpandora.ml.ensemble

import android.util.Log
import com.example.boxpandora.data.local.AppDatabase
import com.example.boxpandora.data.local.entity.FusedSceneSuggestion
import com.example.boxpandora.data.local.entity.ModelInferenceEvidence
import com.example.boxpandora.data.local.entity.ModelReliabilityStats
import com.example.boxpandora.ml.config.AiPipelineConfig
import com.example.boxpandora.ml.config.FusionCalibration
import com.example.boxpandora.ml.config.FusionThresholdConfig
import com.example.boxpandora.ml.manager.ModelManager
import com.example.boxpandora.ml.model.ModelCategory
import com.example.boxpandora.ml.policy.EnsembleModelCooldownStore
import java.util.UUID

private const val TAG = "SceneEnsembleOrchestrator"

/**
 * Orchestrates scene tag suggestion generation across all enabled scene models in
 * [AiPipelineMode.ENSEMBLE_ALL_ENABLED] mode.
 *
 * Responsibilities
 * ────────────────
 *  1. Build a [SceneModelRunner] for every enabled installed scene model.
 *  2. For each target asset, run all runners sequentially.
 *  3. Collect [SceneModelEvidence] from all runners, tolerating individual model failures.
 *  4. Load tag-category lookup (canonical tag key → category) and per-model reliability
 *     weights from the database (loaded lazily, once per orchestrator lifetime).
 *  5. Pass aggregated evidence plus lookups to [SuggestionFusionEngine].
 *  6. Persist raw evidence in [model_inference_evidence] and fused results (with
 *     [tagCategory] and [isAmbiguous] populated) in [fused_scene_suggestions].
 *
 * This component is only invoked when [AiPipelineConfig.isEnsembleReady] is true.
 * In [AiPipelineMode.SINGLE_ACTIVE] mode [TagSuggestionWorker] keeps its current path
 * and this orchestrator is never called.
 *
 * Error handling
 * ──────────────
 *  - If a single runner throws, the error is logged and execution continues with
 *    the remaining models.
 *  - If ALL runners fail for an asset, the asset is marked failed (returns empty list).
 *  - A partial result (≥1 model produced evidence) is still fused and persisted.
 *
 * Concurrency
 * ───────────
 *  Runners are executed **sequentially** per asset to avoid NPU/GPU thrashing.
 *  The caller ([TagSuggestionWorker]) handles batch concurrency externally.
 *
 * @param config        Assembled pipeline configuration (pipeline mode, enabled model IDs,
 *                      confidence threshold, etc.).
 * @param modelManager  Source of installed scene model metadata.
 * @param db            Database handle for evidence and fused suggestion persistence.
 * @param cooldownStore Per-model-ID consecutive-failure tracker; models in cooldown are
 *                      skipped for the current ensemble batch and recover automatically.
 * @param calibration   Active fusion calibration overlay. Defaults to [FusionCalibration.DEFAULT].
 */
class SceneEnsembleOrchestrator(
    private val config: AiPipelineConfig,
    private val modelManager: ModelManager,
    private val db: AppDatabase,
    private val cooldownStore: EnsembleModelCooldownStore,
    private val calibration: FusionCalibration = FusionCalibration.DEFAULT,
) {

    private val fusionEngine        = SuggestionFusionEngine()
    private val reliabilityService  by lazy { ReliabilityUpdateService(db) }

    // ── Runner cache (built once per orchestrator instance) ───────────────────

    private val runners: List<SceneModelRunner> by lazy {
        val installedSceneModels = modelManager
            .getInstalledModels()
            .filter { it.metadata.category == ModelCategory.SCENE_EMBEDDING }

        installedSceneModels
            .filter { it.metadata.id in config.enabledSceneModelIds }
            .map { installed ->
                DefaultSceneModelRunner(
                    installed  = installed,
                    isEnabled  = true,
                )
            }
            .also { list ->
                Log.i(TAG, "Ensemble runners ready: ${list.map { it.modelId }}")
            }
    }

    // ── Lazy lookups (loaded once per orchestrator run) ───────────────────────

    /** Maps canonical tag key → content category (e.g. "animal", "mood"). */
    private var tagCategoryLookup: Map<String, String>? = null

    /** Maps model manifest ID → derived reliability weight. */
    private var sceneReliabilityWeights: Map<String, Float>? = null

    private suspend fun getTagCategoryLookup(): Map<String, String> {
        tagCategoryLookup?.let { return it }
        return db.tagDao().getAll()
            .associate { it.normalizedName to it.category }
            .also { tagCategoryLookup = it }
    }

    private suspend fun getSceneReliabilityWeights(): Map<String, Float> {
        sceneReliabilityWeights?.let { return it }
        return db.modelReliabilityStatsDao().getAllSceneWeights()
            .associate { it.modelId to it.derivedWeight }
            .also { sceneReliabilityWeights = it }
    }

    /**
     * Returns true when ensemble mode is configured and at least one enabled runner is
     * available. Call this before invoking [processAsset] to surface the zero-model error
     * at a higher level.
     */
    fun hasEnabledRunners(): Boolean = runners.isNotEmpty()

    /**
     * Processes one media item through all enabled scene models, fuses evidence, and
     * persists the results.
     *
     * @param assetId             URI of the media item to process.
     * @param existingTagKeys     Tags already applied to this asset — excluded from evidence.
     * @param rejectedTagKeys     Tags the user has previously rejected — excluded from evidence.
     * @return                    Fused suggestions written; empty list if no evidence was produced.
     */
    suspend fun processAsset(
        assetId: String,
        existingTagKeys: Set<String>,
        rejectedTagKeys: Set<String>,
    ): List<FusedSuggestionResult> {
        val pipelineRunId = UUID.randomUUID().toString()
        val allEvidence   = mutableListOf<SceneModelEvidence>()
        var failedModels  = 0

        // 1. Run each enabled model sequentially (conservative concurrency)
        for (runner in runners) {
            // Skip models that are still in their cooldown window
            if (cooldownStore.isCooledDown(runner.modelId)) {
                val reason = cooldownStore.getCooldownReason(runner.modelId)
                Log.d(TAG, "Skipping cooled-down model ${runner.modelId} for $assetId — $reason")
                continue
            }

            try {
                val evidence = runner.runForAsset(
                    assetId             = assetId,
                    existingTagKeys     = existingTagKeys,
                    rejectedTagKeys     = rejectedTagKeys,
                    confidenceThreshold = config.confidenceThreshold,
                    db                  = db,
                )
                allEvidence += evidence
                // Successful run clears any prior failure streak for this model
                cooldownStore.clearFailures(runner.modelId)
                Log.d(TAG, "Runner ${runner.modelId}: ${evidence.size} evidence records for $assetId")
            } catch (e: Exception) {
                failedModels++
                Log.w(TAG, "Runner ${runner.modelId} failed for $assetId — continuing", e)
                val nowCooledDown = cooldownStore.recordFailure(runner.modelId)
                if (nowCooledDown) {
                    Log.w(TAG, "Model ${runner.modelId} entered cooldown — will be skipped for ~30 min")
                }
                // Apply mild inference-failure penalty so this model's fusion weight nudges down
                reliabilityService.applyModelPenalty(
                    modelId          = runner.modelId,
                    pipelineCategory = ModelReliabilityStats.PIPELINE_SCENE,
                    penalty          = FusionThresholdConfig.INFERENCE_FAILURE_PENALTY,
                    reason           = "inference_failure",
                )
            }
        }

        if (allEvidence.isEmpty()) {
            if (failedModels == runners.size && runners.isNotEmpty()) {
                Log.w(TAG, "All ${runners.size} model(s) failed for $assetId")
            } else {
                Log.d(TAG, "No evidence produced for $assetId (embeddings may not exist yet)")
            }
            return emptyList()
        }

        // 2. Persist raw evidence
        val evidenceEntities = allEvidence.map { ev ->
            ModelInferenceEvidence(
                assetId          = ev.assetId,
                canonicalTagKey  = ev.canonicalTagKey,
                rawScore         = ev.rawScore,
                modelId          = ev.sourceModelId,
                modelVersion     = ev.sourceModelVersion,
                scoreType        = ev.scoreType,
                pipelineRunId    = pipelineRunId,
                inferredAt       = ev.inferenceTimestamp,
            )
        }
        db.modelInferenceEvidenceDao().insertAll(evidenceEntities)

        // 3. Load category lookup and reliability weights (cached after first call)
        val categoryLookup       = getTagCategoryLookup()
        val reliabilityWeights   = getSceneReliabilityWeights()

        // 4. Fuse evidence into one ranked suggestion list
        val fusedResults = fusionEngine.fuse(
            evidence             = allEvidence,
            confidenceThreshold  = config.confidenceThreshold,
            tagCategoryLookup    = categoryLookup,
            reliabilityWeights   = reliabilityWeights,
            calibration          = calibration,
        )

        if (fusedResults.isEmpty()) {
            Log.d(TAG, "No suggestions above threshold for $assetId")
            return emptyList()
        }

        // 5. Replace previous fused suggestions for this asset and write the new set
        db.fusedSceneSuggestionDao().deleteForAsset(assetId)
        val fusedEntities = fusedResults.map { r ->
            FusedSceneSuggestion(
                assetId                = r.assetId,
                canonicalTagKey        = r.canonicalTagKey,
                tagCategory            = r.tagCategory,
                fusedScore             = r.fusedScore,
                contributingModelIds   = r.contributingModelIds.joinToString(","),
                contributingModelCount = r.contributingModelCount,
                strongestScore         = r.strongestScore,
                isAmbiguous            = r.isAmbiguous,
                agreementLevelOrdinal  = r.agreementLevel.ordinal,
                status                 = "pending",
                pipelineRunId          = pipelineRunId,
            )
        }
        db.fusedSceneSuggestionDao().insertAll(fusedEntities)

        Log.d(TAG, "Wrote ${fusedResults.size} fused suggestions for $assetId " +
            "(runId=$pipelineRunId, models=${runners.size - failedModels}/${runners.size})")

        return fusedResults
    }
}
