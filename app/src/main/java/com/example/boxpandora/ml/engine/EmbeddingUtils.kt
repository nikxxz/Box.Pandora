package com.example.boxpandora.ml.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shared helpers for converting between FloatArray and the ByteArray representation
 * stored in Room (little-endian, 4 bytes per float).
 *
 * These must stay in sync with the encoding in SceneIndexWorker.floatArrayToBytes().
 */
object EmbeddingUtils {

    fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.getFloat() }
    }

    fun floatArrayToBytes(floats: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buf.putFloat(it) }
        return buf.array()
    }

    /**
     * Cosine similarity between two L2-normalized vectors.
     * Since both are unit vectors this equals the dot product.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vector dimension mismatch: ${a.size} vs ${b.size}" }
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot.coerceIn(-1f, 1f)
    }

    /** L2-normalizes a vector in-place, returns the same array. */
    fun l2Normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = Math.sqrt(norm.toDouble()).toFloat()
        if (norm < 1e-8f) return v
        for (i in v.indices) v[i] /= norm
        return v
    }

    /**
     * Computes the normalized mean of [vectors].
     * Returns null when [vectors] is empty.
     */
    fun normalizedMean(vectors: List<FloatArray>): FloatArray? {
        if (vectors.isEmpty()) return null
        val dim = vectors[0].size
        val sum = FloatArray(dim)
        for (v in vectors) {
            require(v.size == dim) { "Inconsistent vector dimensions in normalizedMean" }
            for (i in 0 until dim) sum[i] += v[i]
        }
        for (i in 0 until dim) sum[i] /= vectors.size
        return l2Normalize(sum)
    }
}
