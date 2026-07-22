package com.example.boxpandora.ml.search

import com.example.boxpandora.data.local.dao.FaceDao
import com.example.boxpandora.data.local.dao.ImageEmbeddingDao
import com.example.boxpandora.data.repository.TagRepository
import com.example.boxpandora.ml.engine.EmbeddingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DEFAULT_CANDIDATE_POOL = 60
private const val TAG_BOOST_MAX = 0.05f

data class SimilarResult(
    val assetUri: String,
    val similarity: Float,
    val sceneScore: Float,
    val faceScore: Float? = null,
    val identityBoost: Float = 0f,
    val tagBoost: Float = 0f
)

/**
 * Finds visually similar images using stored scene embeddings.
 * Uses exact cosine similarity (dot product of L2-normalized vectors).
 * All computation is local and deterministic.
 */
class SimilaritySearchService(
    private val imageEmbeddingDao: ImageEmbeddingDao,
    private val faceDao: FaceDao? = null,
    private val tagRepository: TagRepository? = null
) {

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
                val sceneScore = EmbeddingUtils.cosineSimilarity(queryEmbedding, vec)
                SimilarResult(
                    assetUri = row.assetId,
                    similarity = sceneScore,
                    sceneScore = sceneScore
                )
            }
            .sortedByDescending { it.similarity }
            .take(topK)
    }

    /**
     * Combined scene-first similarity search with optional face embedding reranking.
     * Falls back to the baseline scene-only search when face data is unavailable.
     */
    suspend fun findSimilarCombined(
        queryUri: String,
        sceneModelVersion: String,
        faceEmbedderVersion: String?,
        topK: Int = 20,
        candidatePool: Int = maxOf(topK * 5, 100),
        sceneWeight: Float = 0.75f,
        faceWeight: Float = 0.20f,
        identityBoostValue: Float = 0.08f
    ): List<SimilarResult> = withContext(Dispatchers.IO) {
        val baseline = findSimilar(queryUri, sceneModelVersion, candidatePool)
        if (baseline.isEmpty() || faceEmbedderVersion.isNullOrEmpty() || faceDao == null) {
            return@withContext baseline.take(topK)
        }

        val queryFaceRows = faceDao.getFaceEmbeddingsForAsset(queryUri, faceEmbedderVersion)
        if (queryFaceRows.isEmpty()) {
            return@withContext baseline.take(topK)
        }

        val queryFaces = queryFaceRows.map { row ->
            QueryFace(
                faceId = row.faceId,
                clusterId = row.clusterId,
                embedding = EmbeddingUtils.bytesToFloatArray(row.embedding)
            )
        }
        val queryClusterIds = queryFaceRows.mapNotNull { it.clusterId }.toSet()

        val tagBoosts = computeTagBoosts(queryUri, baseline.map { it.assetUri })

        val assetIds = baseline.map { it.assetUri }
        val candidateFaceRows = faceDao.getFaceEmbeddingsForAssets(assetIds, faceEmbedderVersion)
        if (candidateFaceRows.isEmpty()) {
            return@withContext baseline.take(topK)
        }

        val candidateByAsset = candidateFaceRows.groupBy { it.assetId }
            .mapValues { entry ->
                entry.value.map { row ->
                    CandidateFace(
                        faceId = row.faceId,
                        clusterId = row.clusterId,
                        embedding = EmbeddingUtils.bytesToFloatArray(row.embedding)
                    )
                }
            }

        baseline
            .map { result ->
                val candidateFaces = candidateByAsset[result.assetUri].orEmpty()
                if (candidateFaces.isEmpty()) {
                    result
                } else {
                    val faceScore = computeBestFaceSimilarity(queryFaces, candidateFaces)
                    val clusterMatchBoost = if (
                        queryClusterIds.isNotEmpty() &&
                        candidateFaces.any { it.clusterId != null && it.clusterId in queryClusterIds }
                    ) {
                        identityBoostValue
                    } else {
                        0f
                    }
                    val tagBoost = tagBoosts[result.assetUri] ?: 0f
                    val rankScore = (
                        result.sceneScore * sceneWeight +
                        faceScore * faceWeight +
                        clusterMatchBoost +
                        tagBoost
                    ).coerceIn(0f, 1f)

                    SimilarResult(
                        assetUri = result.assetUri,
                        similarity = rankScore,
                        sceneScore = result.sceneScore,
                        faceScore = faceScore,
                        identityBoost = clusterMatchBoost,
                        tagBoost = tagBoost
                    )
                }
            }
            .sortedByDescending { it.similarity }
            .take(topK)
    }

    private suspend fun computeTagBoosts(queryUri: String, candidateUris: List<String>): Map<String, Float> {
        val repository = tagRepository ?: return emptyMap()
        val allUris = listOf(queryUri) + candidateUris
        val uriTagRows = repository.getTagNamesForUris(allUris)
        val tagsByUri = uriTagRows.groupBy({ it.mediaUri }) { it.normalizedName }
        val queryTags = tagsByUri[queryUri].orEmpty().toSet()
        if (queryTags.isEmpty()) return emptyMap()

        return candidateUris.associateWith { candidateUri ->
            val candidateTags = tagsByUri[candidateUri].orEmpty().toSet()
            computeNormalizedTagBoost(queryTags, candidateTags)
        }
    }

    private fun computeBestFaceSimilarity(
        queryFaces: List<QueryFace>,
        candidateFaces: List<CandidateFace>
    ): Float {
        var best = 0f
        for (queryFace in queryFaces) {
            for (candidateFace in candidateFaces) {
                val sim = EmbeddingUtils.cosineSimilarity(queryFace.embedding, candidateFace.embedding)
                if (sim > best) best = sim
            }
        }
        return best
    }

    private data class QueryFace(
        val faceId: String,
        val clusterId: String?,
        val embedding: FloatArray
    )

    private data class CandidateFace(
        val faceId: String,
        val clusterId: String?,
        val embedding: FloatArray
    )
}

internal fun computeNormalizedTagBoost(queryTags: Set<String>, candidateTags: Set<String>): Float {
    if (queryTags.isEmpty() || candidateTags.isEmpty()) return 0f
    val overlapCount = queryTags.intersect(candidateTags).size.toFloat()
    return (overlapCount / queryTags.size.coerceAtLeast(1) * TAG_BOOST_MAX).coerceAtMost(TAG_BOOST_MAX)
}

internal fun computeBestFaceSimilarity(
    queryFaces: List<FloatArray>,
    candidateFaces: List<FloatArray>
): Float {
    var best = 0f
    for (queryFace in queryFaces) {
        for (candidateFace in candidateFaces) {
            val sim = EmbeddingUtils.cosineSimilarity(queryFace, candidateFace)
            if (sim > best) best = sim
        }
    }
    return best
}
