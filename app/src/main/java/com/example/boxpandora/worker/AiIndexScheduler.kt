package com.example.boxpandora.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.boxpandora.ml.config.AiSettings

private const val TAG = "AiIndexScheduler"

const val SCENE_INDEX_WORK_NAME    = "pandora_scene_index"
const val PROTOTYPE_BUILD_WORK_NAME = "pandora_prototype_build"
const val TAG_SUGGESTION_WORK_NAME  = "pandora_tag_suggestion"
const val FACE_INDEX_WORK_NAME        = "pandora_face_index"
const val FACE_CLUSTER_WORK_NAME      = "pandora_face_cluster"
const val PERSON_PROFILE_WORK_NAME    = "pandora_person_profile"
const val PERSON_SUGGESTION_WORK_NAME = "pandora_person_suggestion"

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
     * @param forceRun  when true, bypasses the backgroundIndexingEnabled check.
     *                  Use for the explicit "Rebuild Search Index" user action.
     */
    fun scheduleSceneIndexIfEnabled(
        context: Context,
        settings: AiSettings,
        forceRun: Boolean = false
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
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(SCENE_INDEX_WORK_NAME, ExistingWorkPolicy.KEEP, request)

        Log.i(TAG, "SceneIndexWorker enqueued (forceRun=$forceRun)")
    }

    /**
     * Enqueues [PrototypeBuildWorker] if scene tagging is enabled.
     * Typically called after [scheduleSceneIndexIfEnabled] or after the user tags images manually.
     */
    fun schedulePrototypeBuildIfEnabled(
        context: Context,
        settings: AiSettings,
        forceRun: Boolean = false
    ) {
        if (!settings.sceneTaggingEnabled) return
        if (!forceRun && !settings.backgroundIndexingEnabled) return

        val request = OneTimeWorkRequestBuilder<PrototypeBuildWorker>()
            .setConstraints(buildHeavyJobConstraints(forceRun))
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(PROTOTYPE_BUILD_WORK_NAME, ExistingWorkPolicy.KEEP, request)

        Log.i(TAG, "PrototypeBuildWorker enqueued (forceRun=$forceRun)")
    }

    /**
     * Enqueues [TagSuggestionWorker] if scene tagging is enabled.
     * Typically called after prototypes have been (re)built.
     */
    fun scheduleTagSuggestionsIfEnabled(
        context: Context,
        settings: AiSettings,
        forceRun: Boolean = false
    ) {
        if (!settings.sceneTaggingEnabled) return
        if (!forceRun && !settings.backgroundIndexingEnabled) return

        val request = OneTimeWorkRequestBuilder<TagSuggestionWorker>()
            .setConstraints(buildHeavyJobConstraints(forceRun))
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(TAG_SUGGESTION_WORK_NAME, ExistingWorkPolicy.KEEP, request)

        Log.i(TAG, "TagSuggestionWorker enqueued (forceRun=$forceRun)")
    }

    /**
     * Convenience: schedules the full AI pipeline in order — scene index → prototypes → suggestions.
     * Each step uses KEEP policy and runs independently; they are not chained.
     * Safe to call on startup or from the "Rebuild" button.
     */
    fun scheduleFullPipelineIfEnabled(
        context: Context,
        settings: AiSettings,
        forceRun: Boolean = false
    ) {
        scheduleSceneIndexIfEnabled(context, settings, forceRun)
        schedulePrototypeBuildIfEnabled(context, settings, forceRun)
        scheduleTagSuggestionsIfEnabled(context, settings, forceRun)
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
        forceRun: Boolean = false
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
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(FACE_INDEX_WORK_NAME, ExistingWorkPolicy.KEEP, request)

        Log.i(TAG, "FaceIndexWorker enqueued (forceRun=$forceRun)")
    }

    /**
     * Enqueues [FaceClusterWorker] if [settings] allows face processing.
     * Typically called after [scheduleFaceIndexIfEnabled] has run, or on an explicit
     * "Rebuild People" user action.
     */
    fun scheduleFaceClusterIfEnabled(
        context: Context,
        settings: AiSettings,
        forceRun: Boolean = false
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
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(FACE_CLUSTER_WORK_NAME, ExistingWorkPolicy.KEEP, request)

        Log.i(TAG, "FaceClusterWorker enqueued (forceRun=$forceRun)")
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
        forceRun: Boolean = false
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
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(PERSON_PROFILE_WORK_NAME, ExistingWorkPolicy.KEEP, request)

        Log.i(TAG, "PersonProfileWorker enqueued (forceRun=$forceRun)")
    }

    /**
     * Enqueues [PersonSuggestionWorker] if face processing is enabled.
     * Should run after both [FaceClusterWorker] and [PersonProfileWorker] have completed.
     */
    fun schedulePersonSuggestionsIfEnabled(
        context: Context,
        settings: AiSettings,
        forceRun: Boolean = false
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
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(PERSON_SUGGESTION_WORK_NAME, ExistingWorkPolicy.KEEP, request)

        Log.i(TAG, "PersonSuggestionWorker enqueued (forceRun=$forceRun)")
    }

    /**
     * Schedules the full face pipeline:
     *   face index → face clustering → person profiles → person suggestions.
     *
     * All four steps use KEEP policy and run independently (not chained). Safe to call on
     * startup or from "Rebuild Face Index" in Settings.
     */
    fun scheduleFacePipelineIfEnabled(
        context: Context,
        settings: AiSettings,
        forceRun: Boolean = false
    ) {
        scheduleFaceIndexIfEnabled(context, settings, forceRun)
        scheduleFaceClusterIfEnabled(context, settings, forceRun)
        schedulePersonProfileIfEnabled(context, settings, forceRun)
        schedulePersonSuggestionsIfEnabled(context, settings, forceRun)
    }

    /**
     * Cancels any pending or running scene index work.
     * Used when the user disables scene tagging mid-run.
     */
    fun cancelSceneIndex(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SCENE_INDEX_WORK_NAME)
        Log.i(TAG, "SceneIndexWorker cancelled")
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SCENE_INDEX_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(PROTOTYPE_BUILD_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(TAG_SUGGESTION_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(FACE_INDEX_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(FACE_CLUSTER_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(PERSON_PROFILE_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(PERSON_SUGGESTION_WORK_NAME)
        Log.i(TAG, "All AI workers cancelled")
    }
}
