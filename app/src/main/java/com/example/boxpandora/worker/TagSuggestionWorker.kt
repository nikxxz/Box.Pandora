package com.example.boxpandora.worker

import android.app.ActivityManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import android.content.Context
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.TagSuggestion
import com.example.boxpandora.ml.config.AiPipelineConfig
import com.example.boxpandora.ml.config.AiPipelineMode
import com.example.boxpandora.ml.engine.EmbeddingUtils
import com.example.boxpandora.ml.engine.TagSuggestionEngine
import com.example.boxpandora.ml.ensemble.EnsembleRunManifest
import com.example.boxpandora.ml.ensemble.SceneEnsembleOrchestrator
import com.example.boxpandora.ml.policy.EnsembleModelCooldownStore
import com.example.boxpandora.ml.model.ModelCategory
import com.example.boxpandora.ml.policy.DynamicConcurrencyController
import com.example.boxpandora.ml.policy.EnsembleRuntimePolicy
import com.example.boxpandora.ml.policy.PolicyDecision
import com.example.boxpandora.worker.KEY_IS_FOREGROUND
import com.example.boxpandora.worker.EnsembleCheckpointStore
import com.example.boxpandora.worker.EnsembleRunType
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "TagSuggestionWorker"

/** Batch size used for the single-active path (not thermal-aware). */
private const val SINGLE_ACTIVE_BATCH_SIZE = 16

/**
 * Scores all indexed media against tag prototypes and writes suggestion rows.
 *
 * Pipeline branching:
 *  - [AiPipelineMode.SINGLE_ACTIVE]        → existing path: active model, [TagSuggestion] rows.
 *  - [AiPipelineMode.ENSEMBLE_ALL_ENABLED] → [SceneEnsembleOrchestrator] path: all enabled
 *    models, [FusedSceneSuggestion] rows via [SuggestionFusionEngine].
 *
 * Design rules (both modes):
 *  - Never overwrites or removes user-applied tags.
 *  - Never writes suggestions for tags already applied to the asset.
 *  - Never writes suggestions for (asset, tag) pairs the user has rejected.
 *  - Idempotent: safe to run multiple times.
 *
 * Ensemble guard:
 *  - If zero scene models are enabled in ensemble mode, the worker fails with a clear
 *    log message and returns [Result.failure]. It does NOT silently fall back to the
 *    active model.
 */
class TagSuggestionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app      = applicationContext as PandoraApp
        val db       = app.database
        val settings = app.aiSettingsRepository.settings.first()

        // ── Build pipeline config ──────────────────────────────────────────────
        val installedSceneIds = app.modelManager
            .getInstalledModels()
            .filter { it.metadata.category == ModelCategory.SCENE_EMBEDDING }
            .map { it.metadata.id }
            .toSet()
        val activeSceneId = app.modelManager
            .getActiveModel(ModelCategory.SCENE_EMBEDDING)
            ?.metadata?.id
        val pipelineConfig = AiPipelineConfig.from(
            settings             = settings,
            installedSceneModelIds = installedSceneIds,
            activeSceneModelId   = activeSceneId,
        )

        return@withContext when (pipelineConfig.pipelineMode) {
            AiPipelineMode.ENSEMBLE_ALL_ENABLED -> {
                val isForeground = inputData.getBoolean(KEY_IS_FOREGROUND, false)
                runEnsemblePath(app, db, pipelineConfig, isForeground)
            }
            AiPipelineMode.SINGLE_ACTIVE ->
                runSingleActivePath(app, db, pipelineConfig)
        }
    }

    // ── Single-active path (existing behaviour, unchanged) ────────────────────

    private suspend fun runSingleActivePath(
        app: PandoraApp,
        db: com.example.boxpandora.data.local.AppDatabase,
        config: AiPipelineConfig,
    ): Result {
        val activeModel = app.modelManager.getActiveModel(ModelCategory.SCENE_EMBEDDING)
        if (activeModel == null) {
            Log.i(TAG, "No active scene_embedding model — skipping suggestion scoring")
            return Result.success(workDataOf("skipped" to true))
        }

        val modelVersion = activeModel.metadata.roomVersionKey
        val threshold    = config.confidenceThreshold

        val engine = TagSuggestionEngine(
            tagPrototypeDao    = db.tagPrototypeDao(),
            tagDao             = db.tagDao(),
            tagCooccurrenceDao = db.tagCooccurrenceDao()
        )

        val prototypes = engine.loadPrototypes()
        if (prototypes.isEmpty()) {
            Log.i(TAG, "No prototypes available — run PrototypeBuildWorker first")
            return Result.success(workDataOf("skipped" to true))
        }

        val totalAssets = db.imageEmbeddingDao().countIndexed(modelVersion)
        Log.i(TAG, "Single-active: scoring $totalAssets assets (model=$modelVersion, threshold=$threshold)")

        var processed = 0
        var suggestionsWritten = 0
        var offset = 0

        while (coroutineContext.isActive) {
            val uris = db.imageEmbeddingDao().getIndexedAssetUris(modelVersion, SINGLE_ACTIVE_BATCH_SIZE, offset)
            if (uris.isEmpty()) break

            for (uri in uris) {
                if (!coroutineContext.isActive) break

                val embeddingRow = db.imageEmbeddingDao().getForAssetAndModel(uri, modelVersion)
                    ?: continue
                val assetEmbedding = EmbeddingUtils.bytesToFloatArray(embeddingRow.embedding)

                val existingTagKeys = db.mediaTagDao().getMediaTagsForUri(uri)
                    .mapNotNull { mt -> db.tagDao().getById(mt.tagId)?.normalizedName }
                    .toSet()
                val rejectedTagKeys = db.tagRejectionDao().getForAsset(uri)
                    .map { it.tagKey }
                    .toSet()

                val scores = engine.score(
                    assetEmbedding      = assetEmbedding,
                    prototypes          = prototypes,
                    existingTagKeys     = existingTagKeys,
                    rejectedTagKeys     = rejectedTagKeys,
                    confidenceThreshold = threshold,
                )

                db.tagSuggestionDao().deleteForAssetAndModel(uri, modelVersion)

                if (scores.isNotEmpty()) {
                    db.tagSuggestionDao().insertAll(
                        scores.map { s ->
                            TagSuggestion(
                                assetId      = uri,
                                tagKey       = s.tagKey,
                                score        = s.finalConfidence.toDouble(),
                                source       = s.source,
                                modelVersion = modelVersion,
                            )
                        }
                    )
                    suggestionsWritten += scores.size
                }

                processed++
            }

            setProgress(workDataOf("processed" to processed, "total" to totalAssets))
            offset += SINGLE_ACTIVE_BATCH_SIZE
        }

        Log.i(TAG, "Single-active complete — $processed assets, $suggestionsWritten suggestions")
        return Result.success(workDataOf("processed" to processed, "suggestionsWritten" to suggestionsWritten))
    }

    // ── Ensemble path ─────────────────────────────────────────────────────────

    private suspend fun runEnsemblePath(
        app: PandoraApp,
        db: com.example.boxpandora.data.local.AppDatabase,
        config: AiPipelineConfig,
        isForeground: Boolean,
    ): Result {
        // ── Task 8: load calibration for this run ─────────────────────────────────
        val calibration = app.fusionCalibrationStore.load()

        // Gather model version strings for the run manifest ("id:versionKey" pairs)
        val modelVersionCsv = app.modelManager.getInstalledModels()
            .filter { it.metadata.id in config.enabledSceneModelIds }
            .map { "${it.metadata.id}:${it.metadata.roomVersionKey}" }
            .sorted()
            .joinToString(",")

        val orchestrator = SceneEnsembleOrchestrator(
            config        = config,
            modelManager  = app.modelManager,
            db            = db,
            cooldownStore = app.ensembleModelCooldownStore,
            calibration   = calibration,
        )

        // Guard: zero enabled models → fail clearly, never silently fall back
        if (!orchestrator.hasEnabledRunners()) {
            Log.e(TAG, "Ensemble mode active but zero scene models are enabled. " +
                "Enable at least one scene model in Model Management to run ensemble suggestions.")
            return Result.failure(
                workDataOf("error" to "No scene models enabled for ensemble mode")
            )
        }

        // Use the active model's embeddings as the indexing anchor for now;
        // in future phases each model will have its own embedding batch.
        val activeModel = app.modelManager.getActiveModel(ModelCategory.SCENE_EMBEDDING)
        if (activeModel == null) {
            Log.i(TAG, "Ensemble: no active scene model for embedding lookup — skipping")
            return Result.success(workDataOf("skipped" to true))
        }

        val modelVersion = activeModel.metadata.roomVersionKey
        val totalAssets  = db.imageEmbeddingDao().countIndexed(modelVersion)
        Log.i(TAG, "Ensemble: processing $totalAssets assets (${config.enabledSceneModelIds.size} models enabled)")

        // ── Phase 4: runtime policy and concurrency control ───────────────────
        val policy      = EnsembleRuntimePolicy(app.thermalMonitor)
        val checkpoints = app.ensembleCheckpointStore
        val runType     = EnsembleRunType.SCENE_SCAN

        // Build stable identity strings for checkpoint validation.
        val currentMode     = config.pipelineMode.name
        val currentModelIds = config.enabledSceneModelIds.sorted().joinToString(",")

        // ── Checkpoint: restore or start fresh ───────────────────────────────
        val existing = checkpoints.load(runType)
        val (startedAt, resumeOffset, resumeProcessed, resumeSuggestions, resumeBatch) =
            if (existing != null && checkpoints.isValid(existing, currentMode, currentModelIds)) {
                Log.i(TAG, "Resuming scene ensemble from offset=${existing.offset}, " +
                    "processed=${existing.processedCount} (started=${existing.startedAt})")
                listOf(
                    existing.startedAt,
                    existing.offset,
                    existing.processedCount,
                    existing.suggestionsWritten,
                    existing.batchNumber,
                )
            } else {
                if (existing != null) {
                    Log.i(TAG, "Discarding stale checkpoint (mode or model set changed)")
                    checkpoints.clear(runType)
                }
                listOf(System.currentTimeMillis(), 0, 0, 0, 0)
            }

        var processed          = resumeProcessed as Int
        var suggestionsWritten = resumeSuggestions as Int
        var offset             = resumeOffset as Int
        var batchNumber        = resumeBatch as Int
        var lastProcessedId: String? = existing?.lastProcessedId

        // ── Task 9: create run manifest (in-progress until run completes) ────────
        val workerRunId = UUID.randomUUID().toString()
        app.ensembleRunManifestStore.save(EnsembleRunManifest(
            runId                     = workerRunId,
            pipelineMode              = currentMode,
            participatingModelIds     = currentModelIds,
            modelVersions             = modelVersionCsv,
            calibrationVersion        = calibration.version,
            reliabilityStatsTimestamp = System.currentTimeMillis(),
            runStartedAt              = startedAt as Long,
        ))

        // Task 10: tracks whether we passed the first policy check without being blocked
        var hasReportedSuccessfulStart = false

        while (coroutineContext.isActive) {

            // Evaluate policy before every batch — not only at job start.
            val decision = policy.evaluate(
                context      = applicationContext,
                config       = config,
                isForeground = isForeground,
            )
            when (decision) {
                is PolicyDecision.Stop -> {
                    Log.w(TAG, "Ensemble scan stopped by runtime policy: ${decision.reason} " +
                        "(completed $processed/$totalAssets — run will not be retried; checkpoint kept)")
                    // Keep the checkpoint so a future manual restart can see the pause reason,
                    // but mark it with the stop reason.
                    checkpoints.save(com.example.boxpandora.worker.EnsembleCheckpoint(
                        runType              = runType,
                        lastProcessedId      = lastProcessedId,
                        offset               = offset,
                        processedCount       = processed,
                        suggestionsWritten   = suggestionsWritten,
                        totalItems           = totalAssets,
                        startedAt            = startedAt as Long,
                        executionMode        = currentMode,
                        participatingModelIds = currentModelIds,
                        batchNumber          = batchNumber,
                        pauseReason          = decision.reason,
                    ))
                    app.ensembleBlockedStore.recordBlock(decision.reason)
                    return Result.success(
                        workDataOf(
                            "processed"          to processed,
                            "suggestionsWritten" to suggestionsWritten,
                            "stoppedByPolicy"    to decision.reason,
                        )
                    )
                }
                is PolicyDecision.Pause -> {
                    Log.i(TAG, "Ensemble scan paused by runtime policy: ${decision.reason} " +
                        "(completed $processed/$totalAssets — checkpoint saved; WorkManager will retry)")
                    checkpoints.save(com.example.boxpandora.worker.EnsembleCheckpoint(
                        runType              = runType,
                        lastProcessedId      = lastProcessedId,
                        offset               = offset,
                        processedCount       = processed,
                        suggestionsWritten   = suggestionsWritten,
                        totalItems           = totalAssets,
                        startedAt            = startedAt as Long,
                        executionMode        = currentMode,
                        participatingModelIds = currentModelIds,
                        batchNumber          = batchNumber,
                        pauseReason          = decision.reason,
                    ))
                    app.ensembleBlockedStore.recordBlock(decision.reason)
                    return Result.retry()
                }
                is PolicyDecision.Throttle -> {
                    Log.d(TAG, "Ensemble scan throttled: ${decision.reason} " +
                        "— pausing ${decision.batchPauseMs} ms before next batch")
                    delay(decision.batchPauseMs)
                }
                PolicyDecision.Proceed -> Unit
            }

            // Task 10: reset consecutive-block counter on first successful policy pass.
            if (!hasReportedSuccessfulStart) {
                app.ensembleBlockedStore.recordSuccessfulStart()
                hasReportedSuccessfulStart = true
            }

            // Adjust batch size to current thermal / charging / pipeline conditions.
            val isCharging = (applicationContext.getSystemService(Context.BATTERY_SERVICE)
                as android.os.BatteryManager).isCharging
            val memInfo = android.app.ActivityManager.MemoryInfo().also {
                (applicationContext.getSystemService(Context.ACTIVITY_SERVICE)
                    as ActivityManager).getMemoryInfo(it)
            }
            val limits = DynamicConcurrencyController.limitsFor(
                thermal       = app.thermalMonitor.currentLevel,
                isCharging    = isCharging,
                isForeground  = isForeground,
                hasFaceModels = config.isFaceEnsembleReady,
                isLowMemory   = memInfo.lowMemory,
            )

            val uris = db.imageEmbeddingDao().getIndexedAssetUris(modelVersion, limits.batchSize, offset)
            if (uris.isEmpty()) break

            for (uri in uris) {
                if (!coroutineContext.isActive) break

                val existingTagKeys = db.mediaTagDao().getMediaTagsForUri(uri)
                    .mapNotNull { mt -> db.tagDao().getById(mt.tagId)?.normalizedName }
                    .toSet()
                val rejectedTagKeys = db.tagRejectionDao().getForAsset(uri)
                    .map { it.tagKey }
                    .toSet()

                val fusedResults = orchestrator.processAsset(
                    assetId         = uri,
                    existingTagKeys = existingTagKeys,
                    rejectedTagKeys = rejectedTagKeys,
                )

                lastProcessedId     = uri
                suggestionsWritten += fusedResults.size
                processed++
            }

            offset      += limits.batchSize
            batchNumber += 1

            // Persist checkpoint after every committed batch.
            checkpoints.save(com.example.boxpandora.worker.EnsembleCheckpoint(
                runType              = runType,
                lastProcessedId      = lastProcessedId,
                offset               = offset,
                processedCount       = processed,
                suggestionsWritten   = suggestionsWritten,
                totalItems           = totalAssets,
                startedAt            = startedAt as Long,
                executionMode        = currentMode,
                participatingModelIds = currentModelIds,
                batchNumber          = batchNumber,
                pauseReason          = null,
            ))

            setProgress(workDataOf("processed" to processed, "total" to totalAssets))
        }

        // Run complete — remove checkpoint so the next trigger starts fresh.
        checkpoints.clear(runType)
        app.ensembleRunManifestStore.complete(
            runId              = workerRunId,
            completedAt        = System.currentTimeMillis(),
            assetsProcessed    = processed,
            suggestionsWritten = suggestionsWritten,
        )

        Log.i(TAG, "Ensemble complete — $processed assets, $suggestionsWritten fused suggestions")
        return Result.success(workDataOf("processed" to processed, "suggestionsWritten" to suggestionsWritten))
    }
}
