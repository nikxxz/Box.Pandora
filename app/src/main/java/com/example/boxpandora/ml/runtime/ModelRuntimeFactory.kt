package com.example.boxpandora.ml.runtime

import com.example.boxpandora.ml.model.ModelMetadata
import java.io.File

/**
 * Creates the correct [ModelRuntime] for a given [ModelMetadata] + on-disk [File].
 *
 * Runtime selection is driven entirely by [ModelMetadata.format]:
 *   "tflite" → [TfliteRuntime]
 *   "onnx"   → [OnnxRuntime] (NCHW input shape derived from metadata)
 *
 * Throws [UnsupportedOperationException] for unrecognised formats — callers should guard
 * with [isFormatSupported] before calling [create] if the format is user-controlled.
 */
object ModelRuntimeFactory {

    /**
     * Returns true when this build can execute models with [format].
     * ONNX support requires the onnxruntime-android AAR on the classpath.
     */
    fun isFormatSupported(format: String): Boolean = when (format.lowercase()) {
        "tflite" -> true
        "onnx"   -> isOnnxAvailable()
        else     -> false
    }

    /**
     * Creates and returns a [ModelRuntime] for [meta] pointing at the installed [file].
     *
     * For ONNX models the input shape is derived from [ModelMetadata.inputWidth] /
     * [ModelMetadata.inputHeight] assuming standard NCHW layout: [1, 3, H, W].
     *
     * @throws UnsupportedOperationException if the format is not supported.
     * @throws RuntimeException if the runtime fails to load the model file.
     */
    fun create(meta: ModelMetadata, file: File): ModelRuntime = when (meta.format.lowercase()) {
        "tflite" -> TfliteRuntime(file)
        "onnx"   -> {
            // ONNX models in this app use NCHW: [batch, channels, height, width]
            val shape = longArrayOf(1L, 3L, meta.inputHeight.toLong(), meta.inputWidth.toLong())
            OnnxRuntime(file, shape)
        }
        else -> throw UnsupportedOperationException(
            "Unsupported model format '${meta.format}' for model '${meta.id}'"
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun isOnnxAvailable(): Boolean = try {
        Class.forName("ai.onnxruntime.OrtEnvironment")
        true
    } catch (_: ClassNotFoundException) {
        false
    }
}
