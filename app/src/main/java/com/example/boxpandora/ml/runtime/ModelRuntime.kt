package com.example.boxpandora.ml.runtime

import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Common inference abstraction over TFLite ([TfliteRuntime]) and ONNX Runtime ([OnnxRuntime]).
 *
 * Usage:
 * ```kotlin
 * val runtime = ModelRuntimeFactory.create(meta, file)
 * val outputs = runtime.run(inputBuffer)   // List<FloatArray>, one per output tensor
 * val shape   = runtime.getOutputShape(0)  // IntArray
 * runtime.close()
 * ```
 *
 * [run] returns all output tensors as flat [FloatArray]s in model-definition order.
 *   index 0 = first output, index 1 = second, etc.
 * Output tensors are always flat — reshape using [getOutputShape] as needed.
 *
 * [input] must be a float32 [ByteBuffer] in native byte order, rewound to position 0.
 */
interface ModelRuntime : Closeable {

    /**
     * Shape of output tensor at positional [index].
     * Returns an empty array if shape cannot be determined before inference.
     */
    fun getOutputShape(index: Int): IntArray

    /**
     * Runs inference with [input] and returns every output tensor as a flat [FloatArray].
     * Order matches the model's output declaration order.
     */
    fun run(input: ByteBuffer): List<FloatArray>
}
