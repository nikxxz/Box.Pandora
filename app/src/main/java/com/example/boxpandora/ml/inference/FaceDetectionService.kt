package com.example.boxpandora.ml.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.boxpandora.ml.config.AiFeatureFlags
import com.example.boxpandora.ml.detection.DetectionResult
import com.example.boxpandora.ml.detection.DetectorConfig
import com.example.boxpandora.ml.detection.FaceAlignmentHelper
import com.example.boxpandora.ml.detection.FaceDetectionEngine
import com.example.boxpandora.ml.detection.MediaType
import com.example.boxpandora.ml.detection.ScrfdDetectionEngine
import com.example.boxpandora.ml.detection.YunetDetectionEngine
import com.example.boxpandora.ml.manager.ModelManager
import com.example.boxpandora.ml.model.InstalledModel
import com.example.boxpandora.ml.model.ModelCategory
import com.example.boxpandora.ml.runtime.ModelRuntimeFactory
import java.io.Closeable

private const val TAG = "FaceDetectionService"

/**
 * Availability state of the face detection pipeline.
 *
 * Returned by [FaceDetectionService.state] — callers use this instead of catching exceptions.
 */
sealed class FaceDetectorState {
    /** Engine loaded and ready. [modelVersionKey] is the Room key written to DB rows. */
    data class Ready(val modelVersionKey: String) : FaceDetectorState()

    /** No face_detection model is installed or activated. */
    object NoModelInstalled : FaceDetectorState()

    /** The installed model's format is not executable on this build. The model remains
     *  installed and visible in the UI, but inference will not run. */
    data class UnsupportedFormat(val modelId: String, val format: String) : FaceDetectorState()

    /** The engine failed to load the model binary (corrupt file, shape mismatch, etc.). */
    data class LoadFailed(val reason: String, val cause: Throwable? = null) : FaceDetectorState()
}

/**
 * Orchestrates face detection by:
 *   1. Resolving the active [ModelCategory.FACE_DETECTION] model via [ModelManager].
 *   2. Routing to the correct [FaceDetectionEngine] based on model metadata.
 *   3. Gating media types through [AiFeatureFlags.isFaceDetectionAllowed].
 *
 * Engine routing (by model id prefix, not hardcoded format):
 *   • model id starts with "scrfd" → [ScrfdDetectionEngine]
 *   • any other model id           → [YunetDetectionEngine]
 *
 * **This service does not throw in its constructor.** Check [state] after construction.
 * [detect] returns an empty list when the state is not [FaceDetectorState.Ready] or when
 * [AiFeatureFlags.isFaceDetectionAllowed] blocks the given [MediaType].
 *
 * Lifecycle: create once per worker invocation; call [close] in a finally block.
 * Not thread-safe.
 *
 * @param config  Detector thresholds; defaults to [DetectorConfig.DEFAULT].
 */
class FaceDetectionService(
    context: Context,
    modelManager: ModelManager,
    config: DetectorConfig = DetectorConfig.DEFAULT
) : Closeable {

    val state: FaceDetectorState
    private val engine: FaceDetectionEngine?

    /** Non-null only when [state] is [FaceDetectorState.Ready]. */
    val modelVersionKey: String?

    init {
        val installed = modelManager.getActiveModel(ModelCategory.FACE_DETECTION)
        var resolvedEngine: FaceDetectionEngine? = null
        val resolvedState: FaceDetectorState

        when {
            installed == null -> {
                Log.i(TAG, "No active face_detection model")
                resolvedState = FaceDetectorState.NoModelInstalled
            }
            !ModelRuntimeFactory.isFormatSupported(installed.metadata.format) -> {
                Log.w(TAG, "Format '${installed.metadata.format}' not supported — ${installed.metadata.id} installed but not executable")
                resolvedState = FaceDetectorState.UnsupportedFormat(
                    installed.metadata.id, installed.metadata.format
                )
            }
            else -> {
                val result = runCatching { buildEngine(installed, config) }
                if (result.isSuccess) {
                    resolvedEngine = result.getOrThrow()
                    resolvedState  = FaceDetectorState.Ready(installed.metadata.roomVersionKey)
                    Log.i(TAG, "Engine ready [${installed.metadata.format}/${installed.metadata.id}]")
                } else {
                    val ex = result.exceptionOrNull()
                    Log.e(TAG, "Engine load failed for ${installed.metadata.id}: ${ex?.message}", ex)
                    resolvedState = FaceDetectorState.LoadFailed(ex?.message ?: "Unknown error", ex)
                }
            }
        }

        engine          = resolvedEngine
        state           = resolvedState
        modelVersionKey = (state as? FaceDetectorState.Ready)?.modelVersionKey
    }

    /** True when [state] is [FaceDetectorState.Ready]. */
    val isAvailable: Boolean get() = state is FaceDetectorState.Ready

    /**
     * Runs face detection on [bitmap] for the given [mediaType].
     *
     * Returns an empty list when:
     *   • [state] is not [FaceDetectorState.Ready]
     *   • [AiFeatureFlags.isFaceDetectionAllowed] returns false for [mediaType]
     *
     * The [bitmap] is not recycled by this call.
     */
    fun detect(bitmap: Bitmap, mediaType: MediaType): List<DetectionResult> {
        if (!AiFeatureFlags.isFaceDetectionAllowed(mediaType)) {
            Log.d(TAG, "Face detection blocked for media type: $mediaType")
            return emptyList()
        }
        return engine?.detect(bitmap) ?: emptyList()
    }

    /**
     * Produces a 112×112 ArcFace-aligned face crop for [detection].
     * Delegates to the active engine; falls back to [FaceAlignmentHelper] if no engine is loaded.
     */
    fun alignFace(sourceBitmap: Bitmap, detection: DetectionResult): Bitmap =
        engine?.alignFace(sourceBitmap, detection)
            ?: FaceAlignmentHelper.align(sourceBitmap, detection)

    override fun close() {
        engine?.close()
        Log.d(TAG, "closed [state=$state]")
    }

    // ── Engine routing ────────────────────────────────────────────────────────

    private fun buildEngine(installed: InstalledModel, config: DetectorConfig): FaceDetectionEngine {
        val runtime = ModelRuntimeFactory.create(installed.metadata, installed.file)
        return when {
            installed.metadata.id.startsWith("scrfd", ignoreCase = true) ->
                ScrfdDetectionEngine(runtime, installed.metadata, config)
            else ->
                YunetDetectionEngine(runtime, installed.metadata, config)
        }
    }
}

/** Thrown by inference callers for legacy compatibility; no longer thrown from the constructor. */
class FaceDetectionException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
