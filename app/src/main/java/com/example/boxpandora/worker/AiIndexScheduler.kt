package com.example.boxpandora.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.boxpandora.ml.config.AiSettings

/**
 * Work policy constants for [AiIndexScheduler] scheduling calls.
 *
 * Use [ExistingWorkPolicy.KEEP] for passive background scheduling (startup, sync hooks):
 * no-ops if the same work is already queued or running.
 *
 * Use [ExistingWorkPolicy.REPLACE] for user-triggered maintenance actions (repair, rebuild):
 * cancels any pending or running instance and enqueues a fresh one so the action takes
 * effect immediately rather than being silently ignored.
 */

private const val TAG = "AiIndexScheduler"

/**
 * WorkManager input data key that tells ensemble workers whether this run was started by the
 * user directly (foreground) or by background scheduling.
 *
 * Workers read this via `inputData.getBoolean(KEY_IS_FOREGROUND, false)` and pass the value
 * to [com.example.boxpandora.ml.policy.EnsembleRuntimePolicy.evaluate] so the policy can
 * apply the correct concurrency and thermal-response rules.
 */
const val KEY_IS_FOREGROUND = "is_foreground"

const val SCENE_INDEX_WORK_NAME    = "pandora_scene_index"
const val PROTOTYPE_BUILD_WORK_NAME = "pandora_prototype_build"
const val TAG_SUGGESTION_WORK_NAME  = "pandora_tag_suggestion"

/** Unique work name for the sequential folder-scoped scene + suggestion chain. */
const val FOLDER_SCAN_WORK_NAME    = "pandora_folder_scan"

/**
 * WorkManager tags added to each step of the folder-scan chain so they can be observed
 * individually via [WorkManager.getWorkInfosByTagLiveData]. These are separate from the
 * chain's unique work name, which only exposes the currently-active step.
 */
const val FOLDER_SCAN_SCENE_TAG          = "pandora_folder_scan:scene"
const val FOLDER_SCAN_FACE_INDEX_TAG     = "pandora_folder_scan:face_index"
const val FOLDER_SCAN_FACE_CLUSTER_TAG   = "pandora_folder_scan:face_cluster"
const val FOLDER_SCAN_PROTOTYPE_TAG      = "pandora_folder_scan:prototype"
const val FOLDER_SCAN_SUGGESTION_TAG     = "pandora_folder_scan:suggestion"
const val FOLDER_SCAN_PERSON_PROFILE_TAG  = "pandora_folder_scan:person_profile"
const val FOLDER_SCAN_PERSON_SUGGEST_TAG  = "pandora_folder_scan:person_suggest"
const val FOLDER_SCAN_RELIABILITY_TAG     = "pandora_folder_scan:reliability"
const val FOLDER_SCAN_FUSED_IDENTITY_TAG  = "pandora_folder_scan:fused_identity"

/** WorkManager input key for restricting a worker to a single album. */
const val KEY_ALBUM_ID             = "album_id"
const val FACE_INDEX_WORK_NAME        = "pandora_face_index"
const val FACE_CLUSTER_WORK_NAME      = "pandora_face_cluster"
const val PERSON_PROFILE_WORK_NAME    = "pandora_person_profile"
const val PERSON_SUGGESTION_WORK_NAME = "pandora_person_suggestion"
const val FUSED_IDENTITY_REBUILD_WORK_NAME  = "pandora_fused_identity_rebuild"
const val RELIABILITY_RECALCULATE_WORK_NAME = "pandora_reliability_recalculate"

/**
 * Unique work name for all sequential maintenance chains (repair, rebuild, rescan).
 * Using a single name means starting any maintenance action cancels a previously-queued one,
 * which is the correct behaviour — only one maintenance chain should run at a time.
 */
const val MAINTENANCE_CHAIN_WORK_NAME = "pandora_maintenance_chain"

/** Tags for individual steps inside a maintenance chain, for per-step progress tracking. */
const val MAINT_SCENE_TAG           = "pandora_maint:scene"
const val MAINT_FACE_INDEX_TAG      = "pandora_maint:face_index"
const val MAINT_FACE_CLUSTER_TAG    = "pandora_maint:face_cluster"
const val MAINT_PROTOTYPE_TAG       = "pandora_maint:prototype"
const val MAINT_SUGGESTION_TAG      = "pandora_maint:suggestion"
const val MAINT_PERSON_PROFILE_TAG  = "pandora_maint:person_profile"
const val MAINT_PERSON_SUGGEST_TAG  = "pandora_maint:person_suggest"
const val MAINT_RELIABILITY_TAG     = "pandora_maint:reliability"
const val MAINT_FUSED_IDENTITY_TAG  = "pandora_maint:fused_identity"

/**
 * Orchestration layer between the media sync pipeline and AI indexing workers.
 *
 * SyncWorker is never modified — instead, call [scheduleSceneIndexIfEnabled] at
 * appropriate trigger points (startup, explicit user action, or post-sync hooks
 * added in a future phase).
 *
 * All scheduling is idempotent (ExistingWorkPolicy.KEEP): if scene indexing is
 * already queued or running, the call is a no-op.
 */
object AiIndexScheduler {

    // ── Constraints ───────────────────────────────────────────────────────────

    /**
     * Standard constraints applied to heavy AI processing jobs.
     *
     * Always required:
     *   - Battery not low
     *   - Storage not low
     *
     * When [forceRun] is **false** (background-scheduled jobs), also requires charging so
     * that long-running rebuilds happen opportunistically while plugged in.
     * When [forceRun] is **true** (user-triggered), charging is NOT required — the user
     * wants the job to start as soon as possible.
     *
     * Network is NOT required for local processing workers.
     */
    private fun buildHeavyJobConstraints(forceRun: Boolean = false): Constraints =
        Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .apply { if (!forceRun) setRequiresCharging(true) }
            .build()

    /**
     * Constraints for ensemble background jobs.
     *
     * Applies charging requirement from [AiSettings.ensembleOnlyWhileCharging] when
     * [forceRun] is false (background scheduling). Phase 1 does not implement a full
     * thermal monitor — this is the lightweight guard hook described in the spec.
     *
     * Note: [AiSettings.pauseEnsembleOnBatterySaver] is enforced at scheduling call-sites
     * by the caller checking the system battery saver state before invoking the scheduler.
     * Battery saver detection is a runtime system check, not a WorkManager constraint.
     */
    private fun buildEnsembleJobConstraints(
        forceRun: Boolean = false,
        ensembleOnlyWhileCharging: Boolean = true,
    ): Constraints =
        Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .apply {
                if (!forceRun && ensembleOnlyWhileCharging) setRequiresCharging(true)
            }
            .build()

    /**
     * Constraints for model download workers.
     * Applies [NetworkType.UNMETERED] when [wifiOnlyDownloads] is true.
     */
    private fun buildDownloadConstraints(wifiOnlyDownloads: Boolean): Constraints =
        Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .setRequiredNetworkType(
                if (wifiOnlyDownloads) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()

    /**
     * Enqueues [SceneIndexWorker] if [settings] allows it.
     *
     * Conditions checked:
     *  - [AiSettings.sceneTaggingEnabled] must be true
     *  - [AiSettings.backgroundIndexingEnabled] must be true (unless [forceRun] is set)
     *
     * @param forceRun    when true, bypasses the backgroundIndexingEnabled check and drops
     *                    the charging constraint. Use for explicit user-triggered actions.
     * @param workPolicy  [ExistingWorkPolicy.KEEP] (default) for passive scheduling that
     *                    no-ops if already queued; [ExistingWorkPolicy.REPLACE] for repair
     *                    and rebuild actions where the user expects the job to restart.
     */
    fun scheduleSceneIndexIfEnabled(
        context: Context,
        settings: AiSettings,
        forceRun: Boolean = false,
        workPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    ) {
        if (!settings.sceneTaggingEnabled) {
            Log.d(TAG, "Scene tagging disabled — skipping schedule")
            return
        }
        if (!forceRun && !settings.backgroundIndexingEnabled) {
            Log.d(TAG, "Background indexing disabled — skipping auto-schedule")
            return
        }

        val request = OneTimeWorkRequestBuilder<SceneIndexWorker>()
            .setConstraints(buildHeavyJobConstraints(forceRun))
            .setInputData(workDataOf(KEY_IS_FOREGROUND to forceRun))
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(SCENE_INDEX_WORK_NAME, workPolicy, request)

        Log.i(TAG, "SceneIndexWorker enqueued (forceRun=$forceRun, policy=$workPolicy)")
    }

    /**
     * Enqueues [PrototypeBuildWorker] if scene tagging is enabled.
     * Typically called after [scheduleSceneIndexIfEnabled] or after the user tags images manually.
     *
     * @param workPolicy  See [scheduleSceneIndexIfEnabled] — same KEEP/REPLACE semantics.
     */
    fun schedulePrototypeBuildIfEnabled(
        context: Context,
        settings: AiSettings,
        forceRun: Boolean = false,
        workPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    ) {
        if (!settings.sceneTaggingEnabled) return
        if (!forceRun && !settings.backgroundIndexingEnabled) return

        val request = OneTimeWorkRequestBuilder<PrototypeBuildWorker>()
            .setConstraints(buildHeavyJobConstraints(forceRun))
            .setInputData(workDataOf(KEY_IS_FOREGROUND to forceRun))
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(PROTOTYPE_BUILD_WORK_NAME, workPolicy, request)

        Log.i(TAG, "PrototypeBuildWorker enqueued (forceRun=$forceRun, policy=$workPolicy)")
    }

    /**
     * Enqueues [TagSuggestionWorker] if scene tagging is enabled.
     * Typically called after prototypes have been (re)built.
     *
     * @param workPolicy  See [scheduleSceneIndexIfEnabled] — same KEEP/REPLACE semantics.
     */
    fun scheduleTagSuggestionsIfEnabled(
        context: Context,
        settings: AiSettings,
        forceRun: Boolean = false,
        workPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    ) {
        if (!settings.sceneTaggingEnabled) return
        if (!forceRun && !settings.backgroundIndexingEnabled) return

        // Use ensemble constraints when ensemble mode is active so the charging guard is applied
        val constraints = if (settings.pipelineMode == com.example.boxpandora.ml.config.AiPipelineMode.ENSEMBLE_ALL_ENABLED) {
            buildEnsembleJobConstraints(forceRun, settings.ensembleOnlyWhileCharging)
        } else {
            buildHeavyJobConstraints(forceRun)
        }

        val request = OneTimeWorkRequestBuilder<TagSuggestionWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(KEY_IS_FOREGROUND to forceRun))
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(TAG_SUGGESTION_WORK_NAME, workPolicy, request)

        Log.i(TAG, "TagSuggestionWorker enqueued (forceRun=$forceRun, policy=$workPolicy, " +
            "mode=${settings.pipelineMode})")
    }

    /**
     * Convenience: schedules the full AI pipeline in order — scene index → prototypes → suggestions.
     * Each step runs independently (not chained); the [workPolicy] is forwarded to all three.
     *
     * @param workPolicy  Use KEEP for passive scheduling; REPLACE for maintenance actions.
     */
    fun scheduleFullPipelineIfEnabled(
        context: Context,
        settings: AiSettings,
        forceRun: Boolean = false,
        workPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    ) {
        scheduleSceneIndexIfEnabled(context, settings, forceRun, workPolicy)
        schedulePrototypeBuildIfEnabled(context, settings, forceRun, workPolicy)
        scheduleTagSuggestionsIfEnabled(context, settings, forceRun, workPolicy)
    }

    /**
     * Enqueues [FaceIndexWorker] if [settings] allows it.
     *
     * Conditions checked:
     *  - [AiSettings.faceProcessingEnabled] must be true
     *  - [AiSettings.backgroundIndexingEnabled] must be true (unless [forceRun] is set)
     *
     * Both the face detector and face embedder models must be installed separately from
     * ModelManifest. FaceIndexWorker itself returns SUCCESS early if either model is missing,
     * so no-op scheduling is safe even before models are in place.
     */
    fun scheduleFaceIndexIfEnabled(
        context: Context,
        settings: AiSettings,
        forceRun: Boolean = false,
        workPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    ) {
        if (!settings.faceProcessingEnabled) {
            Log.d(TAG, "Face processing disabled — skipping schedule")
            return
        }
        if (!forceRun && !settings.backgroundIndexingEnabled) {
            Log.d(TAG, "Background indexing disabled — skipping face index auto-schedule")
            return
        }

        val request = OneTimeWorkRequestBuilder<FaceIndexWorker>()
            .setConstraints(buildHeavyJobConstraints(forceRun))
            .setInputData(workDataOf(KEY_IS_FOREGROUND to forceRun))
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(FACE_INDEX_WORK_NAME, workPolicy, request)

        Log.i(TAG, "FaceIndexWorker enqueued (forceRun=$forceRun, policy=$workPolicy)")
    }

    /**
     * Enqueues [FaceClusterWorker] if [settings] allows face processing.
     * Typically called after [scheduleFaceIndexIfEnabled] has run, or on an explicit
     * "Rebuild People" user action.
     */
    fun scheduleFaceClusterIfEnabled(
        context: Context,
        settings: AiSettings,
        forceRun: Boolean = false,
        workPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    ) {
        if (!settings.faceProcessingEnabled) {
            Log.d(TAG, "Face processing disabled — skipping cluster schedule")
            return
        }
        if (!forceRun && !settings.backgroundIndexingEnabled) {
            Log.d(TAG, "Background indexing disabled — skipping face cluster auto-schedule")
            return
        }

        val request = OneTimeWorkRequestBuilder<FaceClusterWorker>()
            .setConstraints(buildHeavyJobConstraints(forceRun))
            .setInputData(workDataOf(KEY_IS_FOREGROUND to forceRun))
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(FACE_CLUSTER_WORK_NAME, workPolicy, request)

        Log.i(TAG, "FaceClusterWorker enqueued (forceRun=$forceRun, policy=$workPolicy)")
    }

    /**
     * Enqueues [PersonProfileWorker] if face processing is enabled.
     *
     * [PersonProfileWorker] rebuilds per-person [FaceCluster] prototypes from manual person
     * tags and user-confirmed corrections. Must run after [FaceIndexWorker] (so face embeddings
     * exist) and before [schedulePersonSuggestionsIfEnabled] (so confirmed clusters include
     * person-tag-derived ones).
     */
    fun schedulePersonProfileIfEnabled(
        context: Context,
        settings: AiSettings,
        forceRun: Boolean = false,
        workPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    ) {
        if (!settings.faceProcessingEnabled) {
            Log.d(TAG, "Face processing disabled — skipping person profile schedule")
            return
        }
        if (!forceRun && !settings.backgroundIndexingEnabled) {
            Log.d(TAG, "Background indexing disabled — skipping person profile auto-schedule")
            return
        }

        val request = OneTimeWorkRequestBuilder<PersonProfileWorker>()
            .setConstraints(buildHeavyJobConstraints(forceRun))
            .setInputData(workDataOf(KEY_IS_FOREGROUND to forceRun))
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(PERSON_PROFILE_WORK_NAME, workPolicy, request)

        Log.i(TAG, "PersonProfileWorker enqueued (forceRun=$forceRun, policy=$workPolicy)")
    }

    /**
     * Enqueues [PersonSuggestionWorker] if face processing is enabled.
     * Should run after both [FaceClusterWorker] and [PersonProfileWorker] have completed.
     */
    fun schedulePersonSuggestionsIfEnabled(
        context: Context,
        settings: AiSettings,
        forceRun: Boolean = false,
        workPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    ) {
        if (!settings.faceProcessingEnabled) {
            Log.d(TAG, "Face processing disabled — skipping person suggestion schedule")
            return
        }
        if (!forceRun && !settings.backgroundIndexingEnabled) {
            Log.d(TAG, "Background indexing disabled — skipping person suggestion auto-schedule")
            return
        }

        val request = OneTimeWorkRequestBuilder<PersonSuggestionWorker>()
            .setConstraints(buildHeavyJobConstraints(forceRun))
            .setInputData(workDataOf(KEY_IS_FOREGROUND to forceRun))
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(PERSON_SUGGESTION_WORK_NAME, workPolicy, request)

        Log.i(TAG, "PersonSuggestionWorker enqueued (forceRun=$forceRun, policy=$workPolicy)")
    }

    /**
     * Schedules the full face pipeline:
     *   face index → face clustering → person profiles → person suggestions.
     *
     * All four steps use KEEP policy and run independently (not chained). Safe to call on
     * startup or from "Rebuild Face Index" in Settings.
     */
    /**
     * Schedules the full face pipeline:
     *   face index → face clustering → person profiles → person suggestions.
     *
     * All four steps run independently (not chained); the [workPolicy] is forwarded to all.
     *
     * @param workPolicy  Use KEEP for passive scheduling; REPLACE for maintenance actions.
     */
    fun scheduleFacePipelineIfEnabled(
        context: Context,
        settings: AiSettings,
        forceRun: Boolean = false,
        workPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    ) {
        scheduleFaceIndexIfEnabled(context, settings, forceRun, workPolicy)
        scheduleFaceClusterIfEnabled(context, settings, forceRun, workPolicy)
        schedulePersonProfileIfEnabled(context, settings, forceRun, workPolicy)
        schedulePersonSuggestionsIfEnabled(context, settings, forceRun, workPolicy)
    }

    /**
     * Cancels any pending or running scene index work.
     * Used when the user disables scene tagging mid-run.
     */
    fun cancelSceneIndex(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SCENE_INDEX_WORK_NAME)
        Log.i(TAG, "SceneIndexWorker cancelled")
    }

    /**
     * Enqueues [RebuildFusedIdentityWorker] to re-fuse identity suggestions from stored evidence.
     *
     * Always uses [ExistingWorkPolicy.REPLACE] so that a stale run is cancelled and a fresh one
     * starts immediately. Does not check `backgroundIndexingEnabled` — the user explicitly
     * triggered this action.
     *
     * Requires face processing to be enabled in [settings]; no-ops otherwise.
     */
    fun scheduleRebuildFusedIdentity(
        context: Context,
        settings: AiSettings,
    ) {
        if (!settings.faceProcessingEnabled) {
            Log.d(TAG, "Face processing disabled — skipping fused identity rebuild")
            return
        }

        val request = OneTimeWorkRequestBuilder<RebuildFusedIdentityWorker>()
            .setConstraints(buildHeavyJobConstraints(forceRun = true))
            .setInputData(workDataOf(KEY_IS_FOREGROUND to true))
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(FUSED_IDENTITY_REBUILD_WORK_NAME, ExistingWorkPolicy.REPLACE, request)

        Log.i(TAG, "RebuildFusedIdentityWorker enqueued")
    }

    /**
     * Enqueues [RecalculateReliabilityWorker] to recompute all derived reliability weights.
     *
     * Always uses [ExistingWorkPolicy.REPLACE]. Lightweight enough to run without charging
     * constraints — it reads and writes the [model_reliability_stats] table only.
     */
    fun scheduleReliabilityRecalculation(context: Context) {
        val request = OneTimeWorkRequestBuilder<RecalculateReliabilityWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(RELIABILITY_RECALCULATE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)

        Log.i(TAG, "RecalculateReliabilityWorker enqueued")
    }

    /**
     * Enqueues a full sequential folder-scoped AI scan for [albumId]:
     *
     *   1. [SceneIndexWorker]             — scene embeddings for unindexed folder assets
     *   2. [FaceIndexWorker]              — face detection for folder assets (no-op if face processing off)
     *   3. [FaceClusterWorker]            — re-clusters all faces to absorb new detections (global)
     *   4. [PrototypeBuildWorker]         — rebuilds tag prototypes from all user-tagged images (global)
     *   5. [TagSuggestionWorker]          — scores folder assets against updated prototypes
     *   6. [PersonProfileWorker]          — rebuilds person profiles from confirmed cluster data (global)
     *   7. [PersonSuggestionWorker]       — regenerates person match suggestions (global)
     *   8. [RecalculateReliabilityWorker] — recomputes reliability weights from feedback history (global)
     *   9. [RebuildFusedIdentityWorker]   — re-fuses identity suggestions from updated evidence (global)
     *
     * Workers run one after another; each step starts only after its predecessor succeeds.
     * Always uses [ExistingWorkPolicy.REPLACE] so tapping the button again restarts the chain.
     * Skips already-processed assets at every step — no data is deleted before re-running.
     * Face workers return [Result.success] early when face processing is disabled, so the
     * chain continues through tag suggestions regardless.
     */
    fun scheduleFolderScanChained(
        context: Context,
        settings: AiSettings,
        albumId: Long,
    ) {
        if (!settings.sceneTaggingEnabled) {
            Log.d(TAG, "Scene tagging disabled — skipping folder scan for albumId=$albumId")
            return
        }

        val constraints = buildHeavyJobConstraints(forceRun = true)
        val folderInput  = workDataOf(KEY_IS_FOREGROUND to true, KEY_ALBUM_ID to albumId)
        val globalInput  = workDataOf(KEY_IS_FOREGROUND to true)

        val sceneRequest = OneTimeWorkRequestBuilder<SceneIndexWorker>()
            .setConstraints(constraints).addTag(FOLDER_SCAN_SCENE_TAG)
            .setInputData(folderInput).build()

        val faceIndexRequest = OneTimeWorkRequestBuilder<FaceIndexWorker>()
            .setConstraints(constraints).addTag(FOLDER_SCAN_FACE_INDEX_TAG)
            .setInputData(folderInput).build()

        val faceClusterRequest = OneTimeWorkRequestBuilder<FaceClusterWorker>()
            .setConstraints(constraints).addTag(FOLDER_SCAN_FACE_CLUSTER_TAG)
            .setInputData(globalInput).build()

        val prototypeRequest = OneTimeWorkRequestBuilder<PrototypeBuildWorker>()
            .setConstraints(constraints).addTag(FOLDER_SCAN_PROTOTYPE_TAG)
            .setInputData(globalInput).build()

        val suggestionRequest = OneTimeWorkRequestBuilder<TagSuggestionWorker>()
            .setConstraints(constraints).addTag(FOLDER_SCAN_SUGGESTION_TAG)
            .setInputData(folderInput).build()

        val personProfileRequest = OneTimeWorkRequestBuilder<PersonProfileWorker>()
            .setConstraints(constraints).addTag(FOLDER_SCAN_PERSON_PROFILE_TAG)
            .setInputData(globalInput).build()

        val personSuggestRequest = OneTimeWorkRequestBuilder<PersonSuggestionWorker>()
            .setConstraints(constraints).addTag(FOLDER_SCAN_PERSON_SUGGEST_TAG)
            .setInputData(globalInput).build()

        val reliabilityRequest = OneTimeWorkRequestBuilder<RecalculateReliabilityWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag(FOLDER_SCAN_RELIABILITY_TAG).build()

        val fusedIdentityRequest = OneTimeWorkRequestBuilder<RebuildFusedIdentityWorker>()
            .setConstraints(constraints).addTag(FOLDER_SCAN_FUSED_IDENTITY_TAG)
            .setInputData(globalInput).build()

        WorkManager.getInstance(context)
            .beginUniqueWork(FOLDER_SCAN_WORK_NAME, ExistingWorkPolicy.REPLACE, sceneRequest)
            .then(faceIndexRequest)
            .then(faceClusterRequest)
            .then(prototypeRequest)
            .then(suggestionRequest)
            .then(personProfileRequest)
            .then(personSuggestRequest)
            .then(reliabilityRequest)
            .then(fusedIdentityRequest)
            .enqueue()

        Log.i(TAG, "Full folder scan chain enqueued for albumId=$albumId (9 steps)")
    }

    // ── Sequential maintenance chains ─────────────────────────────────────────

    /**
     * Full 9-step sequential maintenance chain:
     *   scene → face_index → face_cluster → prototype → suggestion →
     *   person_profile → person_suggest → reliability → fused_identity
     *
     * Each step starts only after its predecessor succeeds. Workers return [Result.success]
     * early when their feature is disabled so the chain always runs to completion.
     *
     * Uses [MAINTENANCE_CHAIN_WORK_NAME] as the unique work name, so starting any maintenance
     * action cancels a previously-queued chain automatically.
     *
     * @param workPolicy  [ExistingWorkPolicy.REPLACE] for user-triggered maintenance (default).
     *                    [ExistingWorkPolicy.KEEP] for passive background scheduling.
     */
    fun scheduleMaintenanceChain(
        context: Context,
        settings: AiSettings,
        workPolicy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        if (!settings.sceneTaggingEnabled) {
            Log.d(TAG, "Scene tagging disabled — skipping maintenance chain")
            return
        }

        val constraints    = buildHeavyJobConstraints(forceRun = true)
        val foregroundData = workDataOf(KEY_IS_FOREGROUND to true)

        val sceneReq = OneTimeWorkRequestBuilder<SceneIndexWorker>()
            .setConstraints(constraints).addTag(MAINT_SCENE_TAG)
            .setInputData(foregroundData).build()
        val faceIndexReq = OneTimeWorkRequestBuilder<FaceIndexWorker>()
            .setConstraints(constraints).addTag(MAINT_FACE_INDEX_TAG)
            .setInputData(foregroundData).build()
        val faceClusterReq = OneTimeWorkRequestBuilder<FaceClusterWorker>()
            .setConstraints(constraints).addTag(MAINT_FACE_CLUSTER_TAG)
            .setInputData(foregroundData).build()
        val prototypeReq = OneTimeWorkRequestBuilder<PrototypeBuildWorker>()
            .setConstraints(constraints).addTag(MAINT_PROTOTYPE_TAG)
            .setInputData(foregroundData).build()
        val suggestionReq = OneTimeWorkRequestBuilder<TagSuggestionWorker>()
            .setConstraints(constraints).addTag(MAINT_SUGGESTION_TAG)
            .setInputData(foregroundData).build()
        val personProfileReq = OneTimeWorkRequestBuilder<PersonProfileWorker>()
            .setConstraints(constraints).addTag(MAINT_PERSON_PROFILE_TAG)
            .setInputData(foregroundData).build()
        val personSuggestReq = OneTimeWorkRequestBuilder<PersonSuggestionWorker>()
            .setConstraints(constraints).addTag(MAINT_PERSON_SUGGEST_TAG)
            .setInputData(foregroundData).build()
        val reliabilityReq = OneTimeWorkRequestBuilder<RecalculateReliabilityWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag(MAINT_RELIABILITY_TAG).build()
        val fusedIdentityReq = OneTimeWorkRequestBuilder<RebuildFusedIdentityWorker>()
            .setConstraints(constraints).addTag(MAINT_FUSED_IDENTITY_TAG)
            .setInputData(foregroundData).build()

        WorkManager.getInstance(context)
            .beginUniqueWork(MAINTENANCE_CHAIN_WORK_NAME, workPolicy, sceneReq)
            .then(faceIndexReq)
            .then(faceClusterReq)
            .then(prototypeReq)
            .then(suggestionReq)
            .then(personProfileReq)
            .then(personSuggestReq)
            .then(reliabilityReq)
            .then(fusedIdentityReq)
            .enqueue()

        Log.i(TAG, "Maintenance chain enqueued (9 steps, policy=$workPolicy)")
    }

    /**
     * Scene rebuild chain: scene → prototype → suggestion → reliability
     * Used when scene embeddings are cleared and rebuilt from scratch.
     */
    fun scheduleSceneRebuildChain(context: Context, settings: AiSettings) {
        if (!settings.sceneTaggingEnabled) return

        val constraints    = buildHeavyJobConstraints(forceRun = true)
        val foregroundData = workDataOf(KEY_IS_FOREGROUND to true)

        val sceneReq = OneTimeWorkRequestBuilder<SceneIndexWorker>()
            .setConstraints(constraints).addTag(MAINT_SCENE_TAG)
            .setInputData(foregroundData).build()
        val prototypeReq = OneTimeWorkRequestBuilder<PrototypeBuildWorker>()
            .setConstraints(constraints).addTag(MAINT_PROTOTYPE_TAG)
            .setInputData(foregroundData).build()
        val suggestionReq = OneTimeWorkRequestBuilder<TagSuggestionWorker>()
            .setConstraints(constraints).addTag(MAINT_SUGGESTION_TAG)
            .setInputData(foregroundData).build()
        val reliabilityReq = OneTimeWorkRequestBuilder<RecalculateReliabilityWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag(MAINT_RELIABILITY_TAG).build()

        WorkManager.getInstance(context)
            .beginUniqueWork(MAINTENANCE_CHAIN_WORK_NAME, ExistingWorkPolicy.REPLACE, sceneReq)
            .then(prototypeReq)
            .then(suggestionReq)
            .then(reliabilityReq)
            .enqueue()

        Log.i(TAG, "Scene rebuild chain enqueued (4 steps)")
    }

    /**
     * Prototype rebuild chain: prototype → suggestion → reliability
     * Used when tag prototypes are cleared and rebuilt from confirmed tags.
     */
    fun schedulePrototypeRebuildChain(context: Context, settings: AiSettings) {
        if (!settings.sceneTaggingEnabled) return

        val constraints    = buildHeavyJobConstraints(forceRun = true)
        val foregroundData = workDataOf(KEY_IS_FOREGROUND to true)

        val prototypeReq = OneTimeWorkRequestBuilder<PrototypeBuildWorker>()
            .setConstraints(constraints).addTag(MAINT_PROTOTYPE_TAG)
            .setInputData(foregroundData).build()
        val suggestionReq = OneTimeWorkRequestBuilder<TagSuggestionWorker>()
            .setConstraints(constraints).addTag(MAINT_SUGGESTION_TAG)
            .setInputData(foregroundData).build()
        val reliabilityReq = OneTimeWorkRequestBuilder<RecalculateReliabilityWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag(MAINT_RELIABILITY_TAG).build()

        WorkManager.getInstance(context)
            .beginUniqueWork(MAINTENANCE_CHAIN_WORK_NAME, ExistingWorkPolicy.REPLACE, prototypeReq)
            .then(suggestionReq)
            .then(reliabilityReq)
            .enqueue()

        Log.i(TAG, "Prototype rebuild chain enqueued (3 steps)")
    }

    /**
     * Suggestion rebuild chain: suggestion → reliability
     * Used when tag suggestions are cleared and need to be regenerated against current prototypes.
     */
    fun scheduleSuggestionRebuildChain(context: Context, settings: AiSettings) {
        if (!settings.sceneTaggingEnabled) return

        val constraints    = buildHeavyJobConstraints(forceRun = true)
        val foregroundData = workDataOf(KEY_IS_FOREGROUND to true)

        val suggestionReq = OneTimeWorkRequestBuilder<TagSuggestionWorker>()
            .setConstraints(constraints).addTag(MAINT_SUGGESTION_TAG)
            .setInputData(foregroundData).build()
        val reliabilityReq = OneTimeWorkRequestBuilder<RecalculateReliabilityWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag(MAINT_RELIABILITY_TAG).build()

        WorkManager.getInstance(context)
            .beginUniqueWork(MAINTENANCE_CHAIN_WORK_NAME, ExistingWorkPolicy.REPLACE, suggestionReq)
            .then(reliabilityReq)
            .enqueue()

        Log.i(TAG, "Suggestion rebuild chain enqueued (2 steps)")
    }

    /**
     * Face rebuild chain: face_index → face_cluster → person_profile → person_suggest → fused_identity
     * Used when face data is fully cleared and rebuilt from scratch.
     */
    fun scheduleFaceRebuildChain(context: Context, settings: AiSettings) {
        if (!settings.faceProcessingEnabled) return

        val constraints    = buildHeavyJobConstraints(forceRun = true)
        val foregroundData = workDataOf(KEY_IS_FOREGROUND to true)

        val faceIndexReq = OneTimeWorkRequestBuilder<FaceIndexWorker>()
            .setConstraints(constraints).addTag(MAINT_FACE_INDEX_TAG)
            .setInputData(foregroundData).build()
        val faceClusterReq = OneTimeWorkRequestBuilder<FaceClusterWorker>()
            .setConstraints(constraints).addTag(MAINT_FACE_CLUSTER_TAG)
            .setInputData(foregroundData).build()
        val personProfileReq = OneTimeWorkRequestBuilder<PersonProfileWorker>()
            .setConstraints(constraints).addTag(MAINT_PERSON_PROFILE_TAG)
            .setInputData(foregroundData).build()
        val personSuggestReq = OneTimeWorkRequestBuilder<PersonSuggestionWorker>()
            .setConstraints(constraints).addTag(MAINT_PERSON_SUGGEST_TAG)
            .setInputData(foregroundData).build()
        val fusedIdentityReq = OneTimeWorkRequestBuilder<RebuildFusedIdentityWorker>()
            .setConstraints(constraints).addTag(MAINT_FUSED_IDENTITY_TAG)
            .setInputData(foregroundData).build()

        WorkManager.getInstance(context)
            .beginUniqueWork(MAINTENANCE_CHAIN_WORK_NAME, ExistingWorkPolicy.REPLACE, faceIndexReq)
            .then(faceClusterReq)
            .then(personProfileReq)
            .then(personSuggestReq)
            .then(fusedIdentityReq)
            .enqueue()

        Log.i(TAG, "Face rebuild chain enqueued (5 steps)")
    }

    /**
     * Face cluster chain: face_cluster → person_profile → person_suggest → fused_identity
     * Used when clusters are cleared but existing face embeddings are preserved.
     */
    fun scheduleFaceClusterChain(context: Context, settings: AiSettings) {
        if (!settings.faceProcessingEnabled) return

        val constraints    = buildHeavyJobConstraints(forceRun = true)
        val foregroundData = workDataOf(KEY_IS_FOREGROUND to true)

        val faceClusterReq = OneTimeWorkRequestBuilder<FaceClusterWorker>()
            .setConstraints(constraints).addTag(MAINT_FACE_CLUSTER_TAG)
            .setInputData(foregroundData).build()
        val personProfileReq = OneTimeWorkRequestBuilder<PersonProfileWorker>()
            .setConstraints(constraints).addTag(MAINT_PERSON_PROFILE_TAG)
            .setInputData(foregroundData).build()
        val personSuggestReq = OneTimeWorkRequestBuilder<PersonSuggestionWorker>()
            .setConstraints(constraints).addTag(MAINT_PERSON_SUGGEST_TAG)
            .setInputData(foregroundData).build()
        val fusedIdentityReq = OneTimeWorkRequestBuilder<RebuildFusedIdentityWorker>()
            .setConstraints(constraints).addTag(MAINT_FUSED_IDENTITY_TAG)
            .setInputData(foregroundData).build()

        WorkManager.getInstance(context)
            .beginUniqueWork(MAINTENANCE_CHAIN_WORK_NAME, ExistingWorkPolicy.REPLACE, faceClusterReq)
            .then(personProfileReq)
            .then(personSuggestReq)
            .then(fusedIdentityReq)
            .enqueue()

        Log.i(TAG, "Face cluster chain enqueued (4 steps)")
    }

    /**
     * People rebuild chain: person_profile → person_suggest → fused_identity
     * Used when person profiles and suggestions need to be regenerated from existing clusters.
     */
    fun schedulePeopleRebuildChain(context: Context, settings: AiSettings) {
        if (!settings.faceProcessingEnabled) return

        val constraints    = buildHeavyJobConstraints(forceRun = true)
        val foregroundData = workDataOf(KEY_IS_FOREGROUND to true)

        val personProfileReq = OneTimeWorkRequestBuilder<PersonProfileWorker>()
            .setConstraints(constraints).addTag(MAINT_PERSON_PROFILE_TAG)
            .setInputData(foregroundData).build()
        val personSuggestReq = OneTimeWorkRequestBuilder<PersonSuggestionWorker>()
            .setConstraints(constraints).addTag(MAINT_PERSON_SUGGEST_TAG)
            .setInputData(foregroundData).build()
        val fusedIdentityReq = OneTimeWorkRequestBuilder<RebuildFusedIdentityWorker>()
            .setConstraints(constraints).addTag(MAINT_FUSED_IDENTITY_TAG)
            .setInputData(foregroundData).build()

        WorkManager.getInstance(context)
            .beginUniqueWork(MAINTENANCE_CHAIN_WORK_NAME, ExistingWorkPolicy.REPLACE, personProfileReq)
            .then(personSuggestReq)
            .then(fusedIdentityReq)
            .enqueue()

        Log.i(TAG, "People rebuild chain enqueued (3 steps)")
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SCENE_INDEX_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(PROTOTYPE_BUILD_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(TAG_SUGGESTION_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(FACE_INDEX_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(FACE_CLUSTER_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(PERSON_PROFILE_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(PERSON_SUGGESTION_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(FOLDER_SCAN_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(MAINTENANCE_CHAIN_WORK_NAME)
        Log.i(TAG, "All AI workers cancelled")
    }
}
