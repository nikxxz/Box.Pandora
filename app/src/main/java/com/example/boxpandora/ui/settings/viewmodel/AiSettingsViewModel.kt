package com.example.boxpandora.ui.settings.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.AppDatabase
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.ml.config.AiSettings
import com.example.boxpandora.ml.config.AiSettingsRepository
import com.example.boxpandora.ml.manager.ModelManager
import com.example.boxpandora.ml.model.ModelCategory
import androidx.work.ExistingWorkPolicy
import com.example.boxpandora.worker.AiIndexScheduler
import com.example.boxpandora.worker.IndexingRunStats
import com.example.boxpandora.worker.IndexingStatsStore
import androidx.lifecycle.asFlow
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.boxpandora.worker.FACE_CLUSTER_WORK_NAME
import com.example.boxpandora.worker.FACE_INDEX_WORK_NAME
import com.example.boxpandora.worker.PERSON_PROFILE_WORK_NAME
import com.example.boxpandora.worker.PERSON_SUGGESTION_WORK_NAME
import com.example.boxpandora.worker.PROTOTYPE_BUILD_WORK_NAME
import com.example.boxpandora.worker.SCENE_INDEX_WORK_NAME
import com.example.boxpandora.worker.TAG_SUGGESTION_WORK_NAME
import com.example.boxpandora.worker.FOLDER_SCAN_WORK_NAME
import com.example.boxpandora.worker.FOLDER_SCAN_SCENE_TAG
import com.example.boxpandora.worker.FOLDER_SCAN_FACE_INDEX_TAG
import com.example.boxpandora.worker.FOLDER_SCAN_FACE_CLUSTER_TAG
import com.example.boxpandora.worker.FOLDER_SCAN_PROTOTYPE_TAG
import com.example.boxpandora.worker.FOLDER_SCAN_SUGGESTION_TAG
import com.example.boxpandora.worker.FOLDER_SCAN_PERSON_PROFILE_TAG
import com.example.boxpandora.worker.FOLDER_SCAN_PERSON_SUGGEST_TAG
import com.example.boxpandora.worker.FOLDER_SCAN_RELIABILITY_TAG
import com.example.boxpandora.worker.FOLDER_SCAN_FUSED_IDENTITY_TAG
import com.example.boxpandora.worker.FUSED_IDENTITY_REBUILD_WORK_NAME
import com.example.boxpandora.worker.RELIABILITY_RECALCULATE_WORK_NAME
import com.example.boxpandora.worker.MAINTENANCE_CHAIN_WORK_NAME
import com.example.boxpandora.worker.MAINT_SCENE_TAG
import com.example.boxpandora.worker.MAINT_FACE_INDEX_TAG
import com.example.boxpandora.worker.MAINT_FACE_CLUSTER_TAG
import com.example.boxpandora.worker.MAINT_PROTOTYPE_TAG
import com.example.boxpandora.worker.MAINT_SUGGESTION_TAG
import com.example.boxpandora.worker.MAINT_PERSON_PROFILE_TAG
import com.example.boxpandora.worker.MAINT_PERSON_SUGGEST_TAG
import com.example.boxpandora.worker.MAINT_RELIABILITY_TAG
import com.example.boxpandora.worker.MAINT_FUSED_IDENTITY_TAG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AiSettingsViewModel(
    private val repository: AiSettingsRepository,
    private val database: AppDatabase,
    private val statsStore: IndexingStatsStore,
    private val modelManager: ModelManager,
    private val appContext: Context
) : ViewModel() {

    val settings: StateFlow<AiSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AiSettings.DEFAULT
    )

    /** All albums available for folder-scoped AI scan selection. */
    val allAlbums: StateFlow<List<Album>> = database.albumDao().getAllAlbumsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Worker display names ─────────────────────────────────────────────────────

    private val WORKER_DISPLAY_NAMES = mapOf(
        SCENE_INDEX_WORK_NAME              to "Scene embeddings",
        PROTOTYPE_BUILD_WORK_NAME          to "Tag prototypes",
        TAG_SUGGESTION_WORK_NAME           to "Tag suggestions",
        FACE_INDEX_WORK_NAME               to "Face detection",
        FACE_CLUSTER_WORK_NAME             to "Face clustering",
        PERSON_PROFILE_WORK_NAME           to "Person profiles",
        PERSON_SUGGESTION_WORK_NAME        to "People matching",
        FUSED_IDENTITY_REBUILD_WORK_NAME   to "Identity re-fusion",
        RELIABILITY_RECALCULATE_WORK_NAME  to "Reliability weights",
        FOLDER_SCAN_WORK_NAME              to "Folder scan",
        // Maintenance chain step display names (shared across all maintenance chains)
        MAINT_SCENE_TAG           to "Scene embeddings",
        MAINT_FACE_INDEX_TAG      to "Face detection",
        MAINT_FACE_CLUSTER_TAG    to "Face clustering",
        MAINT_PROTOTYPE_TAG       to "Tag prototypes",
        MAINT_SUGGESTION_TAG      to "Tag suggestions",
        MAINT_PERSON_PROFILE_TAG  to "Person profiles",
        MAINT_PERSON_SUGGEST_TAG  to "People matching",
        MAINT_RELIABILITY_TAG     to "Reliability weights",
        MAINT_FUSED_IDENTITY_TAG  to "Identity re-fusion",
        // Per-step display names for the folder scan chain (tracked by tag)
        FOLDER_SCAN_SCENE_TAG          to "1/9 · Scene embeddings",
        FOLDER_SCAN_FACE_INDEX_TAG     to "2/9 · Face detection",
        FOLDER_SCAN_FACE_CLUSTER_TAG   to "3/9 · Face clustering",
        FOLDER_SCAN_PROTOTYPE_TAG      to "4/9 · Tag prototypes",
        FOLDER_SCAN_SUGGESTION_TAG     to "5/9 · Tag suggestions",
        FOLDER_SCAN_PERSON_PROFILE_TAG to "6/9 · Person profiles",
        FOLDER_SCAN_PERSON_SUGGEST_TAG to "7/9 · People matching",
        FOLDER_SCAN_RELIABILITY_TAG    to "8/9 · Reliability weights",
        FOLDER_SCAN_FUSED_IDENTITY_TAG to "9/9 · Identity re-fusion",
    )

    // ── Settings toggles ──────────────────────────────────────────────────────

    fun setSceneTaggingEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setSceneTaggingEnabled(enabled) }
    }

    fun setFaceProcessingEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setFaceProcessingEnabled(enabled) }
    }

    fun setBackgroundIndexingEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setBackgroundIndexingEnabled(enabled) }
    }

    fun setAutoIndexOnSync(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoIndexOnSync(enabled) }
    }

    fun setConfidenceThreshold(value: Float) {
        viewModelScope.launch { repository.setConfidenceThreshold(value) }
    }

    fun setWifiOnlyDownloads(enabled: Boolean) {
        viewModelScope.launch { repository.setWifiOnlyDownloads(enabled) }
    }

    fun setFaceDetectionInVideos(enabled: Boolean) {
        viewModelScope.launch { repository.setFaceDetectionInVideos(enabled) }
    }

    fun setEnsembleOnlyWhileCharging(enabled: Boolean) {
        viewModelScope.launch { repository.setEnsembleOnlyWhileCharging(enabled) }
    }

    fun setPauseEnsembleOnBatterySaver(enabled: Boolean) {
        viewModelScope.launch { repository.setPauseEnsembleOnBatterySaver(enabled) }
    }

    // ── Rebuild state ─────────────────────────────────────────────────────────

    private val _rebuildState = MutableStateFlow<RebuildState>(RebuildState.Idle)
    val rebuildState: StateFlow<RebuildState> = _rebuildState.asStateFlow()

    // ── Maintenance progress tracking ─────────────────────────────────────────

    /**
     * @param workNames     Unique work names observed via [WorkManager.getWorkInfosForUniqueWorkLiveData].
     * @param taggedNames   WorkManager tags observed via [WorkManager.getWorkInfosByTagLiveData];
     *                      used for chained workers where each step needs individual tracking.
     */
    private data class TrackedAction(
        val label: String,
        val workNames: List<String> = emptyList(),
        val taggedNames: List<String> = emptyList()
    )
    private val _trackedAction = MutableStateFlow<TrackedAction?>(null)

    val maintenanceProgress: StateFlow<MaintenanceProgressState> = _trackedAction
        .flatMapLatest { action ->
            if (action == null) return@flatMapLatest flowOf(MaintenanceProgressState.Idle)

            fun progressFor(info: WorkInfo?, name: String): WorkerProgress {
                val prog = info?.progress
                val processed = if (prog != null) when {
                    prog.getInt("indexed", -1) != -1         -> prog.getInt("indexed", 0)
                    prog.getInt("processed", -1) != -1       -> prog.getInt("processed", 0)
                    prog.getInt("imagesProcessed", -1) != -1 -> prog.getInt("imagesProcessed", 0)
                    else -> 0
                } else 0
                return WorkerProgress(
                    workName    = name,
                    displayName = WORKER_DISPLAY_NAMES[name] ?: name,
                    state       = info?.state ?: WorkInfo.State.ENQUEUED,
                    processed   = processed,
                    total       = prog?.getInt("total", 0) ?: 0
                )
            }

            val uniqueFlows = action.workNames.map { name ->
                WorkManager.getInstance(appContext)
                    .getWorkInfosForUniqueWorkLiveData(name)
                    .asFlow()
                    .map { infos -> progressFor(infos.firstOrNull(), name) }
            }
            val tagFlows = action.taggedNames.map { tag ->
                WorkManager.getInstance(appContext)
                    .getWorkInfosByTagLiveData(tag)
                    .asFlow()
                    .map { infos -> progressFor(infos.firstOrNull(), tag) }
            }
            val allFlows = uniqueFlows + tagFlows
            if (allFlows.isEmpty()) return@flatMapLatest flowOf(MaintenanceProgressState.Idle)
            combine(allFlows) { array ->
                MaintenanceProgressState.Active(action.label, array.toList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MaintenanceProgressState.Idle)

    fun dismissMaintenanceProgress() { _trackedAction.value = null }

    fun cancelMaintenanceWork() {
        val action = _trackedAction.value ?: return
        val wm = WorkManager.getInstance(appContext)
        action.workNames.forEach { wm.cancelUniqueWork(it) }
        action.taggedNames.forEach { wm.cancelAllWorkByTag(it) }
        _trackedAction.value = null
    }

    private fun startTracking(label: String, vararg workNames: String) {
        _trackedAction.value = TrackedAction(label = label, workNames = workNames.toList())
    }

    // ── Indexing stats ────────────────────────────────────────────────────────

    private val _indexingStats = MutableStateFlow(statsStore.getAllStats())
    val indexingStats: StateFlow<List<IndexingRunStats>> = _indexingStats.asStateFlow()

    fun refreshStats() {
        _indexingStats.value = statsStore.getAllStats()
    }

    // ── Model health ──────────────────────────────────────────────────────────

    private val _suspendedModels = MutableStateFlow(computeSuspendedModels())
    val suspendedModels: StateFlow<List<ModelCategory>> = _suspendedModels.asStateFlow()

    fun clearModelSuspension(category: ModelCategory) {
        modelManager.clearModelFailures(category)
        refreshModelHealth()
    }

    fun refreshModelHealth() {
        _suspendedModels.value = computeSuspendedModels()
    }

    private fun computeSuspendedModels(): List<ModelCategory> =
        ModelCategory.values().filter { modelManager.hasTooManyFailures(it) }

    // ── Maintenance actions ───────────────────────────────────────────────────

    /**
     * Schedules all workers without clearing any existing data (KEEP policy).
     * Workers are idempotent and will skip assets already processed for the active model version.
     * Use this to index new media added since the last run.
     */
    fun scanNewMedia() {
        _trackedAction.value = TrackedAction(
            label = "Scan New Media",
            taggedNames = listOf(
                MAINT_SCENE_TAG, MAINT_FACE_INDEX_TAG, MAINT_FACE_CLUSTER_TAG,
                MAINT_PROTOTYPE_TAG, MAINT_SUGGESTION_TAG, MAINT_PERSON_PROFILE_TAG,
                MAINT_PERSON_SUGGEST_TAG, MAINT_RELIABILITY_TAG, MAINT_FUSED_IDENTITY_TAG
            )
        )
        viewModelScope.launch {
            // KEEP: no-op if a maintenance chain is already running.
            AiIndexScheduler.scheduleMaintenanceChain(appContext, settings.value,
                workPolicy = ExistingWorkPolicy.KEEP)
        }
    }

    /**
     * Repairs gaps without discarding valid results:
     *  - Deletes ONLY scan log entries with result_status = 'failed' so those assets are
     *    retried on the next run. Successfully-scanned rows (faces_found / no_faces_found)
     *    are preserved — they will not be needlessly reprocessed.
     *  - Uses REPLACE work policy so any currently-queued worker is cancelled and restarted,
     *    ensuring the repair actually runs even if a previous job stalled.
     *  - Does NOT touch scene embeddings or tag data; workers skip assets already indexed
     *    for the active model version automatically.
     */
    fun repairStaleAiData() {
        _trackedAction.value = TrackedAction(
            label = "Repair Stale AI Data",
            taggedNames = listOf(
                MAINT_SCENE_TAG, MAINT_FACE_INDEX_TAG, MAINT_FACE_CLUSTER_TAG,
                MAINT_PROTOTYPE_TAG, MAINT_SUGGESTION_TAG, MAINT_PERSON_PROFILE_TAG,
                MAINT_PERSON_SUGGEST_TAG, MAINT_RELIABILITY_TAG, MAINT_FUSED_IDENTITY_TAG
            )
        )
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("repair")
            // Remove failed scan-log rows so FaceIndexWorker retries those assets.
            database.faceClusterDao().deleteFailedScanLogs()
            AiIndexScheduler.scheduleMaintenanceChain(appContext, settings.value,
                workPolicy = ExistingWorkPolicy.REPLACE)
            _rebuildState.value = RebuildState.Idle
        }
    }

    // ── Individual rebuild actions ────────────────────────────────────────────

    /**
     * Clears all scene embeddings from the database, then schedules [SceneIndexWorker].
     * On the next run the worker will re-embed the entire library from scratch.
     * Uses REPLACE policy so any existing run is cancelled and restarted immediately.
     */
    fun rebuildSceneEmbeddings() {
        _trackedAction.value = TrackedAction(
            label = "Rebuild Scene Embeddings",
            taggedNames = listOf(MAINT_SCENE_TAG, MAINT_PROTOTYPE_TAG, MAINT_SUGGESTION_TAG, MAINT_RELIABILITY_TAG)
        )
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("scene_embeddings")
            database.imageEmbeddingDao().deleteAll()
            AiIndexScheduler.scheduleSceneRebuildChain(appContext, settings.value)
            _rebuildState.value = RebuildState.Idle
        }
    }

    /**
     * Clears all tag prototypes and schedules [PrototypeBuildWorker].
     * Prototypes will be rebuilt from current user-tagged images.
     * Uses REPLACE policy so any existing run is cancelled and restarted immediately.
     */
    fun rebuildTagPrototypes() {
        _trackedAction.value = TrackedAction(
            label = "Rebuild Tag Prototypes",
            taggedNames = listOf(MAINT_PROTOTYPE_TAG, MAINT_SUGGESTION_TAG, MAINT_RELIABILITY_TAG)
        )
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("tag_prototypes")
            database.tagPrototypeDao().clearAll()
            AiIndexScheduler.schedulePrototypeRebuildChain(appContext, settings.value)
            _rebuildState.value = RebuildState.Idle
        }
    }

    /**
     * Clears all AI-generated tag suggestions and schedules [TagSuggestionWorker].
     * Suggestions will be rescored against current prototypes.
     * Uses REPLACE policy so any existing run is cancelled and restarted immediately.
     */
    fun rebuildTagSuggestions() {
        _trackedAction.value = TrackedAction(
            label = "Rebuild Tag Suggestions",
            taggedNames = listOf(MAINT_SUGGESTION_TAG, MAINT_RELIABILITY_TAG)
        )
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("tag_suggestions")
            database.tagSuggestionDao().deleteAll()
            database.fusedSceneSuggestionDao().deleteAll()
            database.modelInferenceEvidenceDao().deleteAll()
            AiIndexScheduler.scheduleSuggestionRebuildChain(appContext, settings.value)
            _rebuildState.value = RebuildState.Idle
        }
    }

    /**
     * Full face detection and embedding rebuild:
     *  - Clears all face scan logs so every asset is re-scanned from scratch.
     *  - Clears detected faces and face embeddings.
     *  - Clears face→cluster assignments (faces being deleted makes them stale).
     *  - Schedules [FaceIndexWorker] to re-detect and re-embed all faces, followed by
     *    [FaceClusterWorker] to re-cluster from the new embeddings.
     *
     * Face cluster metadata (names, confirmed status, tag links) is preserved so user work
     * is not lost; centroids are rebuilt from the fresh embeddings by [FaceClusterWorker].
     *
     * Uses REPLACE policy so any currently-running face worker is cancelled immediately.
     */
    fun rebuildFaceIndex() {
        _trackedAction.value = TrackedAction(
            label = "Re-scan Faces",
            taggedNames = listOf(
                MAINT_FACE_INDEX_TAG, MAINT_FACE_CLUSTER_TAG,
                MAINT_PERSON_PROFILE_TAG, MAINT_PERSON_SUGGEST_TAG, MAINT_FUSED_IDENTITY_TAG
            )
        )
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("face_index")
            with(database) {
                faceClusterDao().deleteAllFaceScanLogs()
                faceClusterDao().clearAllClusterAssignments() // stale after faces deleted
                faceDao().deleteAllFaces()                    // CASCADE deletes face_embeddings via FK
                faceDao().deleteAllFaceEmbeddings()           // explicit for safety
                fusedFaceDao().deleteAll()
                identityInferenceEvidenceDao().deleteAll()
                fusedIdentitySuggestionDao().deleteAll()
            }
            AiIndexScheduler.scheduleFaceRebuildChain(appContext, settings.value)
            _rebuildState.value = RebuildState.Idle
        }
    }

    /**
     * Rebuilds face clusters from existing embeddings:
     *  - Clears face→cluster assignments.
     *  - Deletes all cluster rows (centroids become invalid when the full assignment set changes).
     *  - Deletes all person suggestions (they reference cluster IDs that are being dropped).
     *  - Schedules [FaceClusterWorker] → [PersonProfileWorker] → [PersonSuggestionWorker]
     *    to rebuild the entire downstream chain.
     *
     * Existing face embeddings are preserved; only the clustering layer is reset.
     * Uses REPLACE policy so any existing run is cancelled and restarted immediately.
     */
    fun rebuildFaceClusters() {
        _trackedAction.value = TrackedAction(
            label = "Rebuild Face Clusters",
            taggedNames = listOf(
                MAINT_FACE_CLUSTER_TAG, MAINT_PERSON_PROFILE_TAG,
                MAINT_PERSON_SUGGEST_TAG, MAINT_FUSED_IDENTITY_TAG
            )
        )
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("face_clusters")
            with(database.faceClusterDao()) {
                clearAllClusterAssignments()
                deleteAllPersonSuggestions() // reference cluster IDs being dropped
                deleteAllClusters()
            }
            AiIndexScheduler.scheduleFaceClusterChain(appContext, settings.value)
            _rebuildState.value = RebuildState.Idle
        }
    }

    /**
     * Recomputes person profiles and regenerates match suggestions without clearing clusters.
     * [PersonProfileWorker] rebuilds centroids from current confirmed training data;
     * [PersonSuggestionWorker] then regenerates suggestions against the updated profiles.
     * Uses REPLACE policy so any stalled run is cancelled and restarted immediately.
     */
    fun rebuildPeopleMatching() {
        _trackedAction.value = TrackedAction(
            label = "Rebuild People Matching",
            taggedNames = listOf(MAINT_PERSON_PROFILE_TAG, MAINT_PERSON_SUGGEST_TAG, MAINT_FUSED_IDENTITY_TAG)
        )
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("people_matching")
            database.faceClusterDao().deleteAllPersonSuggestions()
            AiIndexScheduler.schedulePeopleRebuildChain(appContext, settings.value)
            _rebuildState.value = RebuildState.Idle
        }
    }

    /**
     * Clears ALL AI-generated data, then schedules the full pipeline from scratch.
     * Use after a major model upgrade or when the database is in an inconsistent state.
     * Uses REPLACE policy so all currently-running workers are cancelled immediately.
     *
     * User-applied tags, tag rejections, and manual cluster confirmations (name, tag links)
     * are NOT deleted.
     */
    fun fullAiRescan() {
        _trackedAction.value = TrackedAction(
            label = "Full AI Rescan",
            taggedNames = listOf(
                MAINT_SCENE_TAG, MAINT_FACE_INDEX_TAG, MAINT_FACE_CLUSTER_TAG,
                MAINT_PROTOTYPE_TAG, MAINT_SUGGESTION_TAG, MAINT_PERSON_PROFILE_TAG,
                MAINT_PERSON_SUGGEST_TAG, MAINT_RELIABILITY_TAG, MAINT_FUSED_IDENTITY_TAG
            )
        )
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("full_rescan")
            with(database) {
                imageEmbeddingDao().deleteAll()
                tagPrototypeDao().clearAll()
                tagSuggestionDao().deleteAll()
                fusedSceneSuggestionDao().deleteAll()
                modelInferenceEvidenceDao().deleteAll()
                with(faceClusterDao()) {
                    deleteAllFaceScanLogs()
                    clearAllClusterAssignments()
                    deleteAllPersonSuggestions()
                    deleteAllClusters()
                }
                faceDao().deleteAllFaces()
                faceDao().deleteAllFaceEmbeddings()
                fusedFaceDao().deleteAll()
                identityInferenceEvidenceDao().deleteAll()
                fusedIdentitySuggestionDao().deleteAll()
                modelReliabilityStatsDao().deleteAll()
            }
            AiIndexScheduler.scheduleMaintenanceChain(appContext, settings.value,
                workPolicy = ExistingWorkPolicy.REPLACE)
            _rebuildState.value = RebuildState.Idle
        }
    }

    /**
     * Enqueues a sequential folder-scoped scan for [albumId]:
     * scene embeddings → prototypes → tag suggestions, each step starting only after the
     * previous one succeeds.  Already-processed assets are skipped at every step so this
     * is safe to run on a folder that was partially or fully scanned before.
     */
    fun folderAiScan(albumId: Long) {
        // Track each chained step individually so the progress dialog shows all 3 workers.
        _trackedAction.value = TrackedAction(
            label       = "Folder AI Scan",
            taggedNames = listOf(
                FOLDER_SCAN_SCENE_TAG,
                FOLDER_SCAN_FACE_INDEX_TAG,
                FOLDER_SCAN_FACE_CLUSTER_TAG,
                FOLDER_SCAN_PROTOTYPE_TAG,
                FOLDER_SCAN_SUGGESTION_TAG,
                FOLDER_SCAN_PERSON_PROFILE_TAG,
                FOLDER_SCAN_PERSON_SUGGEST_TAG,
                FOLDER_SCAN_RELIABILITY_TAG,
                FOLDER_SCAN_FUSED_IDENTITY_TAG
            )
        )
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("folder_scan")
            AiIndexScheduler.scheduleFolderScanChained(appContext, settings.value, albumId)
            _rebuildState.value = RebuildState.Idle
        }
    }

    /**
     * Deletes ALL AI-generated data from the database without scheduling any workers:
     *   - scene embeddings
     *   - tag prototypes and suggestions
     *   - detected faces, face embeddings, face scan logs
     *   - face clusters, cluster assignments, and person suggestions
     *
     * User-applied tags, tag rejections, and manual cluster confirmations are NOT deleted.
     */
    fun clearAllAiData() {
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("clear_all")
            with(database) {
                imageEmbeddingDao().deleteAll()
                tagPrototypeDao().clearAll()
                tagSuggestionDao().deleteAll()
                fusedSceneSuggestionDao().deleteAll()
                modelInferenceEvidenceDao().deleteAll()
                with(faceClusterDao()) {
                    deleteAllFaceScanLogs()
                    clearAllClusterAssignments()
                    deleteAllPersonSuggestions()
                    deleteAllClusters()
                }
                faceDao().deleteAllFaces()
                faceDao().deleteAllFaceEmbeddings()
                fusedFaceDao().deleteAll()
                identityInferenceEvidenceDao().deleteAll()
                fusedIdentitySuggestionDao().deleteAll()
                // Phase 3: reliability stats represent AI-derived feedback — clear with all AI data
                modelReliabilityStatsDao().deleteAll()
            }
            _rebuildState.value = RebuildState.Idle
        }
    }

    // ── Ensemble maintenance actions ──────────────────────────────────────────

    /**
     * Clears fused scene suggestions and raw scene evidence, then reschedules
     * [TagSuggestionWorker]. Workers re-fuse from current embeddings using the latest
     * thresholds and reliability weights.
     *
     * Preserves: scene embeddings, tag prototypes, single-model tag suggestions,
     * face data, cluster metadata, and all reliability stats.
     */
    fun rebuildFusedTagSuggestions() {
        _trackedAction.value = TrackedAction(
            label = "Rebuild Fused Tag Suggestions",
            taggedNames = listOf(MAINT_SUGGESTION_TAG, MAINT_RELIABILITY_TAG)
        )
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("fused_tag_suggestions")
            with(database) {
                fusedSceneSuggestionDao().deleteAll()
                modelInferenceEvidenceDao().deleteAll()
            }
            AiIndexScheduler.scheduleSuggestionRebuildChain(appContext, settings.value)
            _rebuildState.value = RebuildState.Idle
        }
    }

    /**
     * Schedules [RebuildFusedIdentityWorker] to re-fuse identity suggestions from stored
     * [identity_inference_evidence] rows, applying current thresholds and reliability weights.
     *
     * Preserves: face scan log, detected faces, face embeddings, face clusters, reliability stats.
     * Uses REPLACE policy so any stalled run is cancelled immediately.
     */
    fun rebuildFusedIdentitySuggestions() {
        startTracking("Rebuild Fused Identity", FUSED_IDENTITY_REBUILD_WORK_NAME)
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("fused_identity_suggestions")
            AiIndexScheduler.scheduleRebuildFusedIdentity(appContext, settings.value)
            _rebuildState.value = RebuildState.Idle
        }
    }

    /**
     * Schedules [RecalculateReliabilityWorker] to recompute all derived reliability weights
     * from stored accept/reject counts using the current Bayesian formula.
     *
     * Does NOT alter accept/reject event counts or any user feedback.
     * Use this after a formula change or to repair diverged weights.
     */
    fun recalculateModelReliability() {
        startTracking("Recalculate Reliability", RELIABILITY_RECALCULATE_WORK_NAME)
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("model_reliability")
            AiIndexScheduler.scheduleReliabilityRecalculation(appContext)
            _rebuildState.value = RebuildState.Idle
        }
    }

    /**
     * Deletes all raw ensemble evidence ([model_inference_evidence] and
     * [identity_inference_evidence]). These rows will be regenerated on the next
     * indexing run.
     *
     * Preserves: fused suggestions, reliability stats, embeddings, faces, clusters.
     */
    fun clearEnsembleEvidenceCache() {
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("ensemble_evidence_cache")
            with(database) {
                modelInferenceEvidenceDao().deleteAll()
                identityInferenceEvidenceDao().deleteAll()
            }
            _rebuildState.value = RebuildState.Idle
        }
    }

    // ── Indexing stats (non-reactive snapshot) ────────────────────────────────

    fun getIndexingStats(): List<IndexingRunStats> = statsStore.getAllStats()

    // ── Legacy convenience (used by scheduleFullPipeline action rows) ─────────

    /** Schedules all pipeline stages without clearing. Use [scanNewMedia] for the same effect. */
    fun scheduleFullPipeline() {
        viewModelScope.launch {
            val s = settings.value
            AiIndexScheduler.scheduleFullPipelineIfEnabled(appContext, s, forceRun = true,
                workPolicy = ExistingWorkPolicy.KEEP)
            AiIndexScheduler.scheduleFacePipelineIfEnabled(appContext, s, forceRun = true,
                workPolicy = ExistingWorkPolicy.KEEP)
        }
    }
}

/** Live progress snapshot for a single background maintenance worker. */
data class WorkerProgress(
    val workName: String,
    val displayName: String,
    val state: WorkInfo.State,
    val processed: Int = 0,
    val total: Int = 0
)

/** UI-facing state for the maintenance progress dialog. */
sealed class MaintenanceProgressState {
    object Idle : MaintenanceProgressState()
    data class Active(
        val actionLabel: String,
        val workers: List<WorkerProgress>
    ) : MaintenanceProgressState()
}

/** Represents the current state of a rebuild / clear operation triggered from the UI. */
sealed class RebuildState {
    object Idle : RebuildState()
    data class Queued(val pipeline: String) : RebuildState()
}

class AiSettingsViewModelFactory(
    private val repository: AiSettingsRepository,
    private val database: AppDatabase,
    private val statsStore: IndexingStatsStore,
    private val modelManager: ModelManager,
    private val appContext: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AiSettingsViewModel(repository, database, statsStore, modelManager, appContext) as T
}
