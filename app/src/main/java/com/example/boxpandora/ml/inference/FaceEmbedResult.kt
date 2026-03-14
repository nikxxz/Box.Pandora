package com.example.boxpandora.ml.inference

/**
 * Result of a single face embedding inference call.
 *
 * Bundles the L2-normalised embedding with the provenance metadata required
 * for writing [com.example.boxpandora.data.local.entity.FaceEmbedding] rows.
 *
 * @param embedding    L2-normalised float vector; length = [FaceEmbeddingService.outputDim].
 * @param modelId      Manifest identifier of the ArcFace model (e.g. "arcface_resnet100_fp16").
 * @param modelVersion Room-safe version key written to [FaceEmbedding.modelVersion].
 */
data class FaceEmbedResult(
    val embedding: FloatArray,
    val modelId: String,
    val modelVersion: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceEmbedResult) return false
        return modelId      == other.modelId &&
               modelVersion == other.modelVersion &&
               embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var result = embedding.contentHashCode()
        result = 31 * result + modelId.hashCode()
        result = 31 * result + modelVersion.hashCode()
        return result
    }
}
