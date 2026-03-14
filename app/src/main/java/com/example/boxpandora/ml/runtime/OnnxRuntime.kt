package com.example.boxpandora.ml.runtime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer

private const val TAG = "OnnxRuntime"

/**
 * [ModelRuntime] backed by ONNX Runtime for Android.
 *
 * [inputShape] must match the model's expected input (e.g. [1, 3, 320, 320] for NCHW).
 * Caller provides the shape explicitly because ONNX models may have dynamic dimensions (-1)
 * that cannot be resolved until the actual buffer size is known.
 *
 * Outputs are returned as flat [FloatArray]s in model-definition order.
 * Not thread-safe — create one instance per worker invocation.
 */
class OnnxRuntime(file: File, private val inputShape: LongArray) : ModelRuntime {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val session: OrtSession = try {
        env.createSession(file.absolutePath, OrtSession.SessionOptions())
    } catch (e: Exception) {
        throw RuntimeException("ONNX model load failed: ${file.path}", e)
    }

    private val inputName: String  = session.inputNames.first()

    /** Output names in model-definition order (LinkedHashMap preserves insertion order). */
    private val outputNames: List<String> = session.outputInfo.keys.toList()

    override fun getOutputShape(index: Int): IntArray {
        val name = outputNames.getOrNull(index) ?: return IntArray(0)
        val info = session.outputInfo[name]?.info
        return if (info is TensorInfo) info.shape.map { it.toInt() }.toIntArray()
        else IntArray(0)
    }

    override fun run(input: ByteBuffer): List<FloatArray> {
        val floatBuf: FloatBuffer = input.asFloatBuffer()
        val tensor = OnnxTensor.createTensor(env, floatBuf, inputShape)
        val result = session.run(mapOf(inputName to tensor))
        return try {
            outputNames.map { name ->
                val value = result.get(name).orElse(null)?.value
                    ?: return@map FloatArray(0)
                flattenValue(value)
            }
        } finally {
            result.close()
            tensor.close()
        }
    }

    override fun close() {
        session.close()
        Log.d(TAG, "closed")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun flattenValue(value: Any): FloatArray = when (value) {
        is FloatArray       -> value
        is Array<*>         -> buildList<Float> { flattenAny(value, this) }.toFloatArray()
        else                -> FloatArray(0)
    }

    private fun flattenAny(arr: Array<*>, acc: MutableList<Float>) {
        for (item in arr) when (item) {
            is FloatArray -> item.forEach { acc.add(it) }
            is Array<*>   -> flattenAny(item, acc)
            is Float      -> acc.add(item)
        }
    }
}

/** Convenience — extracts a [FloatArray] from a [List] result safely. */
fun List<FloatArray>.outputAt(index: Int): FloatArray = getOrElse(index) { FloatArray(0) }
