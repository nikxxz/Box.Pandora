package com.example.boxpandora.ui.settings.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.ml.manager.ModelInstallResult
import com.example.boxpandora.ml.manager.ModelManager
import com.example.boxpandora.ml.model.ModelCategory
import com.example.boxpandora.ml.model.ModelMetadata
import com.example.boxpandora.ml.model.ModelSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ── UI model ─────────────────────────────────────────────────────────────────

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
 * @param meta          Manifest metadata (id, category, version, format, source, etc.).
 * @param status        Current lifecycle state of this model on the device.
 * @param isDownloadable True when [meta.source] is [ModelSource.RemoteDownload] with a
 *                      non-blank URL — so the "Download" action should be offered.
 * @param downloadProgress 0.0..1.0 while [status] == [ModelStatus.DOWNLOADING]; null otherwise.
 * @param failureReason  Human-readable reason populated when [status] is [ModelStatus.INVALID]
 *                      or [ModelStatus.FAILED].
 */
data class ModelUiState(
    val meta: ModelMetadata,
    val status: ModelStatus,
    val isDownloadable: Boolean,
    val downloadProgress: Float? = null,
    val failureReason: String? = null,
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
) : ViewModel() {

    private val _models = MutableStateFlow(computeModelStates())
    val models: StateFlow<List<ModelUiState>> = _models.asStateFlow()

    /** IDs of models currently being downloaded — prevents double-tap. */
    private val downloadingIds = mutableSetOf<String>()

    // ── Refresh ───────────────────────────────────────────────────────────────

    /** Re-reads [ModelManager] state and rebuilds the UI list. */
    fun refresh() {
        _models.value = computeModelStates()
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    /** Makes [meta] the active model for its category. */
    fun activate(meta: ModelMetadata) {
        modelManager.activate(meta)
        refresh()
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
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun patchEntry(id: String, transform: (ModelUiState) -> ModelUiState) {
        _models.update { list -> list.map { if (it.meta.id == id) transform(it) else it } }
    }

    private fun computeModelStates(): List<ModelUiState> {
        val installed = modelManager.getInstalledModels()
        return modelManager.manifest.models.map { meta ->
            val found = installed.firstOrNull {
                it.metadata.id == meta.id && it.metadata.version == meta.version
            }
            val isDownloadable = meta.source is ModelSource.RemoteDownload &&
                (meta.source as ModelSource.RemoteDownload).url.isNotBlank()
            val isSuspended = modelManager.hasTooManyFailures(meta.category) && found?.isActive == true
            val status = when {
                isSuspended      -> ModelStatus.FAILED
                found == null    -> ModelStatus.NOT_INSTALLED
                found.isActive   -> ModelStatus.ACTIVE
                else             -> ModelStatus.INSTALLED
            }
            ModelUiState(meta = meta, status = status, isDownloadable = isDownloadable)
        }
    }
}

// ── Factory ──────────────────────────────────────────────────────────────────

class ModelManagerViewModelFactory(
    private val modelManager: ModelManager,
    private val appContext: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ModelManagerViewModel(modelManager, appContext) as T
}
