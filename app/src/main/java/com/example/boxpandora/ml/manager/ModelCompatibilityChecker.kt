package com.example.boxpandora.ml.manager

import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.boxpandora.ml.model.ModelMetadata
import com.example.boxpandora.ml.runtime.ModelRuntimeFactory
import org.tensorflow.lite.Interpreter
import java.io.File

private const val TAG = "ModelCompatibilityCheck"

/**
 * Validates a model binary for correctness and compatibility before it is activated.
 *
 * Checks run in order of cheapness (fast-fail):
 *   1. Extension matches [ModelMetadata.format].
 *   2. File size matches [ModelMetadata.sizeBytes] when non-zero.
 *   3. Format is supported by the current build ([ModelRuntimeFactory.isFormatSupported]).
 *      Unsupported formats skip the init test but remain installable and visible in
 *      ModelManager — the model is simply not executable until a supporting runtime exists.
 *   4a. TFLite: one-shot interpreter init to catch corrupt flatbuffers.
 *   4b. ONNX: one-shot session init to catch corrupt/incompatible graphs.
 *
 * SHA-256 verification is handled upstream by [ModelIntegrityVerifier].
 */
internal object ModelCompatibilityChecker {

    /**
     * Runs all applicable checks against [file] using [meta] as the reference.
     *
     * @return `null` when every check passes; a non-null human-readable reason on the
     *   first failure.
     */
    fun check(meta: ModelMetadata, file: File): String? {
        // 1. Extension must match declared format
        val expectedExt = ".${meta.format.lowercase()}"
        if (!file.name.endsWith(expectedExt, ignoreCase = true)) {
            return "extension mismatch: file=${file.name}, expected=$expectedExt"
        }

        // 2. File size (only when manifest provides a non-zero value)
        if (meta.sizeBytes > 0) {
            val actual = file.length()
            if (actual != meta.sizeBytes) {
                return "size mismatch: actual=$actual bytes, expected=${meta.sizeBytes} bytes"
            }
        }

        // 3. Format support gate — unsupported format: skip init check, allow install
        if (!ModelRuntimeFactory.isFormatSupported(meta.format)) {
            Log.w(TAG, "Format '${meta.format}' not supported on this build — skipping init check for ${meta.id}")
            return null
        }

        // 4a. TFLite init check
        if (meta.format.equals("tflite", ignoreCase = true)) {
            tryTfliteInit(file)?.let { return "TFLite interpreter init failed: ${it.message}" }
        }

        // 4b. ONNX init check
        if (meta.format.equals("onnx", ignoreCase = true)) {
            tryOnnxInit(file)?.let { return "ONNX session init failed: ${it.message}" }
        }

        return null
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun tryTfliteInit(file: File): Throwable? {
        val options = Interpreter.Options().apply { numThreads = 1 }
        return try {
            Interpreter(file, options).close()
            Log.d(TAG, "TFLite init OK: ${file.name}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "TFLite init failed for ${file.name}: ${e.message}")
            e
        }
    }

    private fun tryOnnxInit(file: File): Throwable? = try {
        val env = OrtEnvironment.getEnvironment()
        env.createSession(file.absolutePath, OrtSession.SessionOptions()).close()
        Log.d(TAG, "ONNX init OK: ${file.name}")
        null
    } catch (e: Exception) {
        Log.w(TAG, "ONNX init failed for ${file.name}: ${e.message}")
        e
    }
}
