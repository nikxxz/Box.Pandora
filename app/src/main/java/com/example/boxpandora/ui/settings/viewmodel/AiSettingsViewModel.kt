package com.example.boxpandora.ui.settings.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.AppDatabase
import com.example.boxpandora.ml.config.AiSettings
import com.example.boxpandora.ml.config.AiSettingsRepository
import com.example.boxpandora.ml.manager.ModelManager
import com.example.boxpandora.ml.model.ModelCategory
import com.example.boxpandora.worker.AiIndexScheduler
import com.example.boxpandora.worker.IndexingRunStats
import com.example.boxpandora.worker.IndexingStatsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // ── Rebuild state ─────────────────────────────────────────────────────────

    private val _rebuildState = MutableStateFlow<RebuildState>(RebuildState.Idle)
    val rebuildState: StateFlow<RebuildState> = _rebuildState.asStateFlow()

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
     * Workers are idempotent and will pick up any assets not yet processed.
     * Use this to index new media added since the last run.
     */
    fun scanNewMedia() {
        viewModelScope.launch {
            val s = settings.value
            AiIndexScheduler.scheduleFullPipelineIfEnabled(appContext, s, forceRun = false)
            AiIndexScheduler.scheduleFacePipelineIfEnabled(appContext, s, forceRun = false)
        }
    }

    /**
     * Force-runs all pipeline workers without clearing existing data.
     * Workers will fill any gaps caused by partial failures, model-version mismatches,
     * or assets that were skipped on previous runs. Does not delete valid existing data.
     */
    fun repairStaleAiData() {
        viewModelScope.launch {
            val s = settings.value
            AiIndexScheduler.scheduleFullPipelineIfEnabled(appContext, s, forceRun = true)
            AiIndexScheduler.scheduleFacePipelineIfEnabled(appContext, s, forceRun = true)
        }
    }

    // ── Individual rebuild actions ────────────────────────────────────────────

    /**
     * Clears all scene embeddings from the database, then schedules [SceneIndexWorker].
     * On next run the worker will re-embed the entire library from scratch.
     */
    fun rebuildSceneEmbeddings() {
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("scene_embeddings")
            database.imageEmbeddingDao().deleteAll()
            val s = settings.value
            AiIndexScheduler.scheduleSceneIndexIfEnabled(appContext, s, forceRun = true)
            _rebuildState.value = RebuildState.Idle
        }
    }

    /**
     * Clears all tag prototypes and schedules [PrototypeBuildWorker].
     * Prototypes will be rebuilt from current user-tagged images.
     */
    fun rebuildTagPrototypes() {
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("tag_prototypes")
            database.tagPrototypeDao().clearAll()
            val s = settings.value
            AiIndexScheduler.schedulePrototypeBuildIfEnabled(appContext, s, forceRun = true)
            _rebuildState.value = RebuildState.Idle
        }
    }

    /**
     * Clears all AI-generated tag suggestions and schedules [TagSuggestionWorker].
     * Suggestions will be rescored against current prototypes.
     */
    fun rebuildTagSuggestions() {
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("tag_suggestions")
            database.tagSuggestionDao().deleteAll()
            val s = settings.value
            AiIndexScheduler.scheduleTagSuggestionsIfEnabled(appContext, s, forceRun = true)
            _rebuildState.value = RebuildState.Idle
        }
    }

    /**
     * Clears all detected faces, face embeddings, and scan logs, then schedules
     * [FaceIndexWorker]. The worker will re-detect and re-embed all faces from scratch.
     */
    fun rebuildFaceIndex() {
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("face_index")
            with(database) {
                faceClusterDao().deleteAllFaceScanLogs()
                faceDao().deleteAllFaces()          // CASCADE deletes face_embeddings via FK
                faceDao().deleteAllFaceEmbeddings() // also explicit for safety
            }
            val s = settings.value
            AiIndexScheduler.scheduleFaceIndexIfEnabled(appContext, s, forceRun = true)
            _rebuildState.value = RebuildState.Idle
        }
    }

    /**
     * Clears all face clusters and schedules [FaceClusterWorker].
     * Existing face embeddings are preserved; only cluster assignments are reset.
     */
    fun rebuildFaceClusters() {
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("face_clusters")
            with(database.faceClusterDao()) {
                clearAllClusterAssignments()
                deleteAllClusters()
            }
            val s = settings.value
            AiIndexScheduler.scheduleFaceClusterIfEnabled(appContext, s, forceRun = true)
            _rebuildState.value = RebuildState.Idle
        }
    }

    /**
     * Schedules [PersonProfileWorker] and [PersonSuggestionWorker] without clearing clusters.
     * PersonProfileWorker will recompute centroids from current confirmed training data;
     * PersonSuggestionWorker will then regenerate suggestions against the updated profiles.
     */
    fun rebuildPeopleMatching() {
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("people_matching")
            val s = settings.value
            AiIndexScheduler.schedulePersonProfileIfEnabled(appContext, s, forceRun = true)
            AiIndexScheduler.schedulePersonSuggestionsIfEnabled(appContext, s, forceRun = true)
            _rebuildState.value = RebuildState.Idle
        }
    }

    /**
     * Clears ALL AI-generated data, then schedules the full pipeline from scratch.
     * Equivalent to [clearAllAiData] followed by [scheduleFullPipeline].
     * Use after a major model upgrade or when the database is in an inconsistent state.
     */
    fun fullAiRescan() {
        viewModelScope.launch {
            _rebuildState.value = RebuildState.Queued("full_rescan")
            with(database) {
                imageEmbeddingDao().deleteAll()
                tagPrototypeDao().clearAll()
                tagSuggestionDao().deleteAll()
                faceClusterDao().deleteAllFaceScanLogs()
                faceClusterDao().clearAllClusterAssignments()
                faceClusterDao().deleteAllClusters()
                faceDao().deleteAllFaces()
                faceDao().deleteAllFaceEmbeddings()
            }
            val s = settings.value
            AiIndexScheduler.scheduleFullPipelineIfEnabled(appContext, s, forceRun = true)
            AiIndexScheduler.scheduleFacePipelineIfEnabled(appContext, s, forceRun = true)
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
                faceClusterDao().deleteAllFaceScanLogs()
                faceClusterDao().clearAllClusterAssignments()
                faceClusterDao().deleteAllClusters()
                faceDao().deleteAllFaces()
                faceDao().deleteAllFaceEmbeddings()
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
            AiIndexScheduler.scheduleFullPipelineIfEnabled(appContext, s, forceRun = true)
            AiIndexScheduler.scheduleFacePipelineIfEnabled(appContext, s, forceRun = true)
        }
    }
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
