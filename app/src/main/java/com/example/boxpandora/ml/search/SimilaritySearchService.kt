package com.example.boxpandora.ml.search

import com.example.boxpandora.data.local.dao.ImageEmbeddingDao
import com.example.boxpandora.ml.engine.EmbeddingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SimilarResult(val assetUri: String, val similarity: Float)

/**
 * Finds visually similar images using stored scene embeddings.
 * Uses exact cosine similarity (dot product of L2-normalized vectors).
 * All computation is local and deterministic.
 */
class SimilaritySearchService(private val imageEmbeddingDao: ImageEmbeddingDao) {

    /**
     * Returns up to [topK] images most similar to [queryUri] for the given [modelVersion],
     * sorted by descending similarity. The query image itself is excluded.
     * Returns an empty list if the query image has no embedding.
     */
    suspend fun findSimilar(
        queryUri: String,
        modelVersion: String,
        topK: Int = 20
    ): List<SimilarResult> = withContext(Dispatchers.IO) {
        val queryRow = imageEmbeddingDao.getForAssetAndModel(queryUri, modelVersion)
            ?: return@withContext emptyList()

        val queryEmbedding = EmbeddingUtils.bytesToFloatArray(queryRow.embedding)
        val allEmbeddings = imageEmbeddingDao.getAllEmbeddings(modelVersion)

        allEmbeddings
            .filter { it.assetId != queryUri }
            .map { row ->
                val vec = EmbeddingUtils.bytesToFloatArray(row.embedding)
                SimilarResult(
                    assetUri = row.assetId,
                    similarity = EmbeddingUtils.cosineSimilarity(queryEmbedding, vec)
                )
            }
            .sortedByDescending { it.similarity }
            .take(topK)
    }
}
