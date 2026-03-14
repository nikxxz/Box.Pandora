package com.example.boxpandora.ml.runtime

import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "TfliteRuntime"

/**
 * [ModelRuntime] backed by a TensorFlow Lite [Interpreter].
 *
 * Supports 1-D, 2-D, and 3-D output tensors; higher-rank tensors are flattened.
 * Not thread-safe — create one instance per worker invocation.
 */
class TfliteRuntime(file: File) : ModelRuntime {

    private val interpreter: Interpreter = try {
        Interpreter(file, Interpreter.Options().apply { numThreads = 2 })
    } catch (e: Exception) {
        throw RuntimeException("TFLite model load failed: ${file.path}", e)
    }

    override fun getOutputShape(index: Int): IntArray =
        interpreter.getOutputTensor(index).shape()

    override fun run(input: ByteBuffer): List<FloatArray> {
        val count = interpreter.outputTensorCount
        val shapes  = Array(count) { i -> getOutputShape(i) }
        val buffers = mutableMapOf<Int, Any>()
        for (i in 0 until count) buffers[i] = allocate(shapes[i])

        interpreter.runForMultipleInputsOutputs(arrayOf(input), buffers)

        return (0 until count).map { i -> flatten(buffers[i]!!, shapes[i]) }
    }

    override fun close() {
        interpreter.close()
        Log.d(TAG, "closed")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun allocate(shape: IntArray): Any = when (shape.size) {
        1    -> FloatArray(shape[0])
        2    -> Array(shape[0]) { FloatArray(shape[1]) }
        3    -> Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
        else -> FloatArray(shape.fold(1) { a, b -> a * b })
    }

    @Suppress("UNCHECKED_CAST")
    private fun flatten(obj: Any, shape: IntArray): FloatArray = when (shape.size) {
        1 -> obj as FloatArray
        2 -> {
            val arr = obj as Array<FloatArray>
            FloatArray(shape[0] * shape[1]).also { flat ->
                var k = 0; for (row in arr) for (v in row) flat[k++] = v
            }
        }
        3 -> {
            val arr = obj as Array<Array<FloatArray>>
            FloatArray(shape[0] * shape[1] * shape[2]).also { flat ->
                var k = 0; for (mat in arr) for (row in mat) for (v in row) flat[k++] = v
            }
        }
        else -> obj as? FloatArray ?: FloatArray(0)
    }
}
