package com.example.boxpandora.ui.settings.viewmodel

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.AppDatabase
import com.example.boxpandora.data.local.entity.ModelReliabilityStats
import com.example.boxpandora.ml.config.AiPipelineMode
import com.example.boxpandora.ml.config.AiSettings
import com.example.boxpandora.ml.config.AiSettingsRepository
import com.example.boxpandora.ml.manager.ModelInstallResult
import com.example.boxpandora.ml.manager.ModelManager
import com.example.boxpandora.ml.model.ModelCategory
import com.example.boxpandora.ml.model.ModelMetadata
import com.example.boxpandora.ml.model.ModelSource
import com.example.boxpandora.ml.policy.EnsembleBlockedStore
import com.example.boxpandora.ml.policy.ThermalLevel
import com.example.boxpandora.ml.policy.ThermalMonitor
import com.example.boxpandora.ml.runtime.ModelRuntimeFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ── Ensemble status types ─────────────────────────────────────────────────────

/**
 * Expected resource load of the current ensemble configuration.
 * Derived from the total number of participating models across all categories.
 */
enum class EnsembleCostHint {
    /** 1 model total. */
    LOW,
    /** 2–3 models total. */
    MEDIUM,
    /** 4+ models total. */
    HIGH,
}

/**
 * What the runtime policy would return for a background ensemble run right now.
 * Computed from the live [ThermalLevel] and current power/battery state.
 */
enum class EnsemblePolicyState {
    /** Policy would proceed — all conditions are permissive. */
    NORMAL,
    /** Elevated thermal state; background scans are throttled with a batch pause. */
    THROTTLED,
    /** HOT thermal or charging/battery-saver guard active; background scans are paused. */
    PAUSED,
    /** Critical thermal; all ensemble scans (including foreground) are hard-stopped. */
    STOPPED,
}

/**
 * Runtime status of the ensemble pipeline, surfaced in the Model Management screen.
 *
 * @param enabledSceneCount      Number of scene models currently enabled for ensemble.
 * @param enabledDetectorCount   Number of face detectors currently enabled for ensemble.
 * @param enabledRecognizerCount Number of face recognizers currently enabled for ensemble.
 * @param costHint               Expected resource cost bracket for this configuration.
 * @param policyState            What the background policy would return right now.
 * @param consecutiveBlockCount  How many consecutive times background runs were blocked.
 * @param isSuggestingFallback   True when blocks have reached the hint threshold.
 */
data class EnsembleStatusSummary(
    val enabledSceneCount: Int,
    val enabledDetectorCount: Int,
    val enabledRecognizerCount: Int,
    val costHint: EnsembleCostHint,
    val policyState: EnsemblePolicyState,
    val consecutiveBlockCount: Int,
    val isSuggestingFallback: Boolean,
)

// ── UI model ─────────────────────────────────────────────────────────────────

/**
 * Aggregated per-model reliability snapshot surfaced in the diagnostics panel.
 *
 * One or more [records] for a single model ID (one per active pipeline×tag-category
 * combination). For face recognizers there is typically exactly one record; scene models
 * accumulate one record per tag category they have accept/reject feedback for.
 */
data class ModelReliabilitySummary(
    val records: List<ModelReliabilityStats>
) {
    /** Total accepted events across all category records. */
    val totalAccepted: Int get() = records.sumOf { it.acceptedCount }
    /** Total rejected events across all category records. */
    val totalRejected: Int get() = records.sumOf { it.rejectedCount }
    /** Acceptance ratio in 0..100 int percent, or null when no events exist. */
    val acceptancePct: Int? get() {
        val total = totalAccepted + totalRejected
        return if (total > 0) totalAccepted * 100 / total else null
    }
    /**
     * Mean derived weight across all records.
     * Suitable for a single-line summary; category-level weights are in [records].
     */
    val meanWeight: Float get() =
        if (records.isEmpty()) 1f else records.map { it.derivedWeight }.average().toFloat()
}

/**
 * Ensemble execution state for a scene model card.
 *
 * Only meaningful when [AiPipelineMode.ENSEMBLE_ALL_ENABLED] is active.
 * When [BYPASSED] the card shows a note that single-active selection is ignored.
 */
enum class EnsembleModelState {
    /** Not a scene embedding model — ensemble state is not applicable. */
    NOT_APPLICABLE,
    /** Scene model is enabled for ensemble execution. */
    ENABLED,
    /** Scene model is installed but the user has disabled it from ensemble execution. */
    DISABLED,
    /** Single-active mode is on — this field is irrelevant. */
    BYPASSED,
}

/**
 * Installation lifecycle state for a single model binary in the manifest.
 *
 * State transitions:
 *   NOT_INSTALLED → DOWNLOADING → ACTIVE / INVALID / FAILED
 *   INSTALLED → ACTIVE (via activate)
 *   ACTIVE / INSTALLED → NOT_INSTALLED (via delete)
 */
enum class ModelStatus {
    /** Binary is not present on device. */
    NOT_INSTALLED,
    /** HTTP download is in progress. */
    DOWNLOADING,
    /** Binary is on device but not the active choice for its category. */
    INSTALLED,
    /** Binary is on device and is the active model for its category. */
    ACTIVE,
    /** Passed SHA-256 but failed compatibility checks (e.g. corrupt TFLite flatbuffer). */
    INVALID,
    /** Reached the crash-loop failure threshold; inference suspended. */
    FAILED,
}

/**
 * UI representation of a single manifest entry, produced by [ModelManagerViewModel].
 *
 * @param meta                    Manifest metadata (id, category, version, format, source, etc.).
 * @param status                  Current lifecycle state of this model on the device.
 * @param isDownloadable          True when [meta.source] is [ModelSource.RemoteDownload] with a
 *                                non-blank URL — so the "Download" action should be offered.
 * @param downloadProgress        0.0..1.0 while [status] == [ModelStatus.DOWNLOADING]; null otherwise.
 * @param failureReason           Human-readable reason populated when [status] is [ModelStatus.INVALID]
 *                                or [ModelStatus.FAILED].
 * @param isChecksumInManifest    True when [ModelMetadata.sha256] is non-blank; integrity data present.
 * @param isSizeInManifest        True when [ModelMetadata.sizeBytes] > 0; expected size is known.
 * @param fileSizeMatchesManifest null=not installed or size unknown; true=on-disk size matches manifest;
 *                                false=mismatch (possible corruption or partial download).
 * @param isRuntimeSupported      Whether this build can execute models in [ModelMetadata.format].
 *                                TFLite is always supported; ONNX requires the ORT AAR.
 * @param failureCount            Current crash-loop inference failure count for this model's category.
 * @param ensembleState           Ensemble participation state; [EnsembleModelState.NOT_APPLICABLE]
 *                                for non-scene-embedding models.
 */
data class ModelUiState(
    val meta: ModelMetadata,
    val status: ModelStatus,
    val isDownloadable: Boolean,
    val downloadProgress: Float? = null,
    val failureReason: String? = null,
    // Diagnostics
    val isChecksumInManifest: Boolean = false,
    val isSizeInManifest: Boolean = false,
    val fileSizeMatchesManifest: Boolean? = null,
    val isRuntimeSupported: Boolean = true,
    val failureCount: Int = 0,
    // Ensemble
    val ensembleState: EnsembleModelState = EnsembleModelState.NOT_APPLICABLE,
    /** Aggregated reliability data from [model_reliability_stats]; null until the first DB refresh. */
    val reliabilitySummary: ModelReliabilitySummary? = null,
)

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * Manages the [ModelUiState] list for [ModelManagementScreen].
 *
 * All heavy work (download, delete) is dispatched to [viewModelScope]; the UI collects
 * [models] as a [StateFlow] and reacts to state changes automatically.
 *
 * Invariant: at most one download job per model id is running at any time.
 */
class ModelManagerViewModel(
    private val modelManager: ModelManager,
    private val appContext: Context,
    private val aiSettingsRepository: AiSettingsRepository,
    private val database: AppDatabase,
    private val thermalMonitor: ThermalMonitor,
    private val ensembleBlockedStore: EnsembleBlockedStore,
) : ViewModel() {

    /** Current AI settings — used by diagnostics sections and ensemble controls in the UI. */
    val settings: StateFlow<AiSettings> = aiSettingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AiSettings.DEFAULT
    )

    private val _models = MutableStateFlow(computeModelStates())
    val models: StateFlow<List<ModelUiState>> = _models.asStateFlow()

    private val _ensembleStatus = MutableStateFlow(computeEnsembleStatus())
    /** Live ensemble participation count, cost hint, and runtime policy state for the UI. */
    val ensembleStatus: StateFlow<EnsembleStatusSummary> = _ensembleStatus.asStateFlow()

    /** IDs of models currently being downloaded — prevents double-tap. */
    private val downloadingIds = mutableSetOf<String>()

    // ── Ensemble controls ─────────────────────────────────────────────────────

    /**
     * Switches between [AiPipelineMode.SINGLE_ACTIVE] and [AiPipelineMode.ENSEMBLE_ALL_ENABLED].
     *
     * The UI must show a confirmation dialog warning about resource impact *before* calling
     * this with [AiPipelineMode.ENSEMBLE_ALL_ENABLED]. This method does not show any dialog.
     */
    fun setPipelineMode(mode: AiPipelineMode) {
        viewModelScope.launch {
            aiSettingsRepository.setPipelineMode(mode)
            refresh()
        }
    }

    /**
     * Enables or disables [modelId] for ensemble execution.
     * Routes to the correct DataStore setter based on [category]:
     *  - [ModelCategory.SCENE_EMBEDDING] → scene ensemble disabled set
     *  - [ModelCategory.FACE_DETECTION]  → face detector disabled set
     *  - [ModelCategory.FACE_EMBEDDING]  → face recognizer disabled set
     */
    fun setModelEnabledForEnsemble(modelId: String, category: ModelCategory, enabled: Boolean) {
        viewModelScope.launch {
            when (category) {
                ModelCategory.SCENE_EMBEDDING -> {
                    if (enabled) aiSettingsRepository.enableEnsembleModel(modelId)
                    else         aiSettingsRepository.disableEnsembleModel(modelId)
                }
                ModelCategory.FACE_DETECTION -> {
                    if (enabled) aiSettingsRepository.enableEnsembleDetector(modelId)
                    else         aiSettingsRepository.disableEnsembleDetector(modelId)
                }
                ModelCategory.FACE_EMBEDDING -> {
                    if (enabled) aiSettingsRepository.enableEnsembleRecognizer(modelId)
                    else         aiSettingsRepository.disableEnsembleRecognizer(modelId)
                }
                else -> Unit  // other categories do not participate in ensemble
            }
            refresh()
        }
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    /**
     * Re-reads [ModelManager] state and reliability stats from the database,
     * then rebuilds the UI list. Safe to call from any context.
     */
    fun refresh() {
        viewModelScope.launch {
            val allScene    = database.modelReliabilityStatsDao().getAllSceneWeights()
            val allIdentity = database.modelReliabilityStatsDao().getAllIdentityWeights()
            val byModel     = (allScene + allIdentity).groupBy { it.modelId }
            _models.value         = computeModelStates(byModel)
            _ensembleStatus.value = computeEnsembleStatus()
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    /** Makes [meta] the active model for its category. */
    fun activate(meta: ModelMetadata) {
        viewModelScope.launch {
            modelManager.activate(meta)
            refresh()
        }
    }

    /** Deletes the binary and sidecar files for [meta] from device storage. */
    fun delete(meta: ModelMetadata) {
        viewModelScope.launch {
            modelManager.delete(meta)
            refresh()
        }
    }

    /**
     * Downloads [meta] via [ModelManager.installFromUrl], updating [downloadProgress] on each
     * progress callback and reflecting the final result in [status].
     *
     * Does nothing if a download for this model is already in flight.
     */
    fun download(meta: ModelMetadata) {
        if (meta.id in downloadingIds) return
        downloadingIds += meta.id

        // Optimistically show downloading state before the coroutine kicks off
        patchEntry(meta.id) { it.copy(status = ModelStatus.DOWNLOADING, downloadProgress = 0f, failureReason = null) }

        viewModelScope.launch {
            val result = modelManager.installFromUrl(meta) { downloaded, total ->
                val progress = if (total > 0) downloaded.toFloat() / total.toFloat() else null
                patchEntry(meta.id) { it.copy(downloadProgress = progress) }
            }

            downloadingIds -= meta.id

            when (result) {
                is ModelInstallResult.Success ->
                    patchEntry(meta.id) { it.copy(status = ModelStatus.ACTIVE, downloadProgress = null) }

                ModelInstallResult.AlreadyInstalled ->
                    refresh()

                ModelInstallResult.ChecksumMismatch ->
                    patchEntry(meta.id) {
                        it.copy(
                            status = ModelStatus.INVALID,
                            downloadProgress = null,
                            failureReason = "Checksum mismatch — file may be corrupt or tampered with"
                        )
                    }

                is ModelInstallResult.InvalidModel ->
                    patchEntry(meta.id) {
                        it.copy(
                            status = ModelStatus.INVALID,
                            downloadProgress = null,
                            failureReason = result.reason
                        )
                    }

                ModelInstallResult.SourceNotFound ->
                    patchEntry(meta.id) {
                        it.copy(
                            status = ModelStatus.NOT_INSTALLED,
                            downloadProgress = null,
                            failureReason = "Download URL is not configured in the manifest"
                        )
                    }

                is ModelInstallResult.Error ->
                    patchEntry(meta.id) {
                        it.copy(
                            status = ModelStatus.FAILED,
                            downloadProgress = null,
                            failureReason = result.cause.message ?: "Unknown error"
                        )
                    }

                is ModelInstallResult.ManifestIncomplete ->
                    patchEntry(meta.id) {
                        it.copy(
                            status = ModelStatus.NOT_INSTALLED,
                            downloadProgress = null,
                            failureReason = result.reason
                        )
                    }
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun patchEntry(id: String, transform: (ModelUiState) -> ModelUiState) {
        _models.update { list -> list.map { if (it.meta.id == id) transform(it) else it } }
    }

    private fun computeEnsembleStatus(): EnsembleStatusSummary {
        val cur = settings.value
        val pipelineMode        = cur.pipelineMode
        val disabledScene       = cur.disabledEnsembleModelIds
        val disabledDetectors   = cur.disabledEnsembleDetectorIds
        val disabledRecognizers = cur.disabledEnsembleRecognizerIds

        val installed = modelManager.getInstalledModels()

        val sceneCount      = installed.count {
            it.metadata.category == ModelCategory.SCENE_EMBEDDING
                && it.metadata.id !in disabledScene
        }
        val detectorCount   = installed.count {
            it.metadata.category == ModelCategory.FACE_DETECTION
                && it.metadata.id !in disabledDetectors
        }
        val recognizerCount = installed.count {
            it.metadata.category == ModelCategory.FACE_EMBEDDING
                && it.metadata.id !in disabledRecognizers
        }

        val total = sceneCount + detectorCount + recognizerCount
        val costHint = when {
            total <= 1 -> EnsembleCostHint.LOW
            total <= 3 -> EnsembleCostHint.MEDIUM
            else       -> EnsembleCostHint.HIGH
        }

        // Evaluate what the background policy returns right now based on thermal + power state.
        val thermal = thermalMonitor.currentLevel
        val powerManager   = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLow = !batteryManager.isCharging &&
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) < 15
        val policyState = when {
            thermal == ThermalLevel.CRITICAL -> EnsemblePolicyState.STOPPED
            thermal == ThermalLevel.HOT      -> EnsemblePolicyState.PAUSED
            thermal == ThermalLevel.ELEVATED -> EnsemblePolicyState.THROTTLED
            cur.pauseEnsembleOnBatterySaver && powerManager.isPowerSaveMode -> EnsemblePolicyState.PAUSED
            batteryLow                       -> EnsemblePolicyState.PAUSED
            cur.ensembleOnlyWhileCharging && !batteryManager.isCharging     -> EnsemblePolicyState.PAUSED
            else                             -> EnsemblePolicyState.NORMAL
        }

        return EnsembleStatusSummary(
            enabledSceneCount      = sceneCount,
            enabledDetectorCount   = detectorCount,
            enabledRecognizerCount = recognizerCount,
            costHint               = costHint,
            policyState            = policyState,
            consecutiveBlockCount  = ensembleBlockedStore.consecutiveBlockCount,
            isSuggestingFallback   = ensembleBlockedStore.isSuggestingFallback,
        )
    }

    private fun computeModelStates(
        reliabilityByModel: Map<String, List<ModelReliabilityStats>> = emptyMap()
    ): List<ModelUiState> {
        val installed            = modelManager.getInstalledModels()
        val curSettings          = settings.value
        val pipelineMode         = curSettings.pipelineMode
        val disabledModelIds     = curSettings.disabledEnsembleModelIds
        val disabledDetectorIds  = curSettings.disabledEnsembleDetectorIds
        val disabledRecognizerIds = curSettings.disabledEnsembleRecognizerIds

        return modelManager.manifest.models.map { meta ->
            val found = installed.firstOrNull {
                it.metadata.id == meta.id && it.metadata.version == meta.version
            }
            val isDownloadable = meta.source is ModelSource.RemoteDownload &&
                (meta.source as ModelSource.RemoteDownload).url.isNotBlank()
            val failureCount = modelManager.getModelFailureCount(meta.category)
            val isSuspended  = failureCount >= 3 && found?.isActive == true
            val status = when {
                isSuspended    -> ModelStatus.FAILED
                found == null  -> ModelStatus.NOT_INSTALLED
                found.isActive -> ModelStatus.ACTIVE
                else           -> ModelStatus.INSTALLED
            }
            // Diagnostics — cheap checks, no SHA-256 hashing
            val isChecksumInManifest    = meta.sha256.isNotBlank()
            val isSizeInManifest        = meta.sizeBytes > 0
            val fileSizeMatchesManifest = if (found != null && meta.sizeBytes > 0)
                found.file.length() == meta.sizeBytes
            else null
            val isRuntimeSupported = ModelRuntimeFactory.isFormatSupported(meta.format)

            // Ensemble state — meaningful for installed scene/face-detection/face-embedding models
            val ensembleState = when (meta.category) {
                ModelCategory.SCENE_EMBEDDING -> when {
                    found == null                        -> EnsembleModelState.NOT_APPLICABLE
                    pipelineMode == AiPipelineMode.SINGLE_ACTIVE -> EnsembleModelState.BYPASSED
                    meta.id in disabledModelIds          -> EnsembleModelState.DISABLED
                    else                                 -> EnsembleModelState.ENABLED
                }
                ModelCategory.FACE_DETECTION -> when {
                    found == null                        -> EnsembleModelState.NOT_APPLICABLE
                    pipelineMode == AiPipelineMode.SINGLE_ACTIVE -> EnsembleModelState.BYPASSED
                    meta.id in disabledDetectorIds       -> EnsembleModelState.DISABLED
                    else                                 -> EnsembleModelState.ENABLED
                }
                ModelCategory.FACE_EMBEDDING -> when {
                    found == null                        -> EnsembleModelState.NOT_APPLICABLE
                    pipelineMode == AiPipelineMode.SINGLE_ACTIVE -> EnsembleModelState.BYPASSED
                    meta.id in disabledRecognizerIds     -> EnsembleModelState.DISABLED
                    else                                 -> EnsembleModelState.ENABLED
                }
                else -> EnsembleModelState.NOT_APPLICABLE
            }

            ModelUiState(
                meta                    = meta,
                status                  = status,
                isDownloadable          = isDownloadable,
                isChecksumInManifest    = isChecksumInManifest,
                isSizeInManifest        = isSizeInManifest,
                fileSizeMatchesManifest = fileSizeMatchesManifest,
                isRuntimeSupported      = isRuntimeSupported,
                failureCount            = failureCount,
                ensembleState           = ensembleState,
                reliabilitySummary      = reliabilityByModel[meta.id]
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { ModelReliabilitySummary(it) },
            )
        }
    }
}

// ── Factory ──────────────────────────────────────────────────────────────────

class ModelManagerViewModelFactory(
    private val modelManager: ModelManager,
    private val appContext: Context,
    private val aiSettingsRepository: AiSettingsRepository,
    private val database: AppDatabase,
    private val thermalMonitor: ThermalMonitor,
    private val ensembleBlockedStore: EnsembleBlockedStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ModelManagerViewModel(
            modelManager, appContext, aiSettingsRepository, database,
            thermalMonitor, ensembleBlockedStore,
        ) as T
}
