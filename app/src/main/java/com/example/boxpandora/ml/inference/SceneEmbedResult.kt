package com.example.boxpandora.ml.inference

/**
 * Result of a single scene embedding inference call.
 *
 * Bundles the L2-normalised embedding with the provenance metadata required
 * for writing [com.example.boxpandora.data.local.entity.ImageEmbedding] rows.
 *
 * @param embedding    L2-normalised float vector; length = [SceneEmbeddingService.outputDim].
 * @param modelId      Identifier of the model that produced the embedding (from manifest).
 * @param modelVersion Room-safe version key written to the [ImageEmbedding.modelVersion] column.
 * @param mediaType    "image", "gif", or "video" — the type of the source asset.
 * @param scanMethod   Optional description of the frame-sampling strategy used, e.g.
 *                     "uniform_5" or "keyframe". Null for plain images.
 */
data class SceneEmbedResult(
    val embedding: FloatArray,
    val modelId: String,
    val modelVersion: String,
    val mediaType: String,
    val scanMethod: String?
) {
    // FloatArray does not implement structural equals; provide consistent behaviour.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SceneEmbedResult) return false
        return modelId      == other.modelId &&
               modelVersion == other.modelVersion &&
               mediaType    == other.mediaType &&
               scanMethod   == other.scanMethod &&
               embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var result = embedding.contentHashCode()
        result = 31 * result + modelId.hashCode()
        result = 31 * result + modelVersion.hashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + (scanMethod?.hashCode() ?: 0)
        return result
    }
}
