package com.example.boxpandora.ml.search

import com.example.boxpandora.data.local.dao.FaceDao
import com.example.boxpandora.data.local.dao.FaceDao.AssetFaceEmbeddingRow
import com.example.boxpandora.data.local.dao.ImageEmbeddingDao
import com.example.boxpandora.data.local.entity.ImageEmbedding
import com.example.boxpandora.ml.engine.EmbeddingUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimilaritySearchServiceTest {

    @Test
    fun computeNormalizedTagBoost_fullOverlap_returnsMaxBoost() {
        val boost = computeNormalizedTagBoost(setOf("cat", "dog"), setOf("dog", "cat"))
        assertEquals(0.05f, boost, 1e-6f)
    }

    @Test
    fun computeNormalizedTagBoost_partialOverlap_scalesLinearly() {
        val boost = computeNormalizedTagBoost(setOf("cat", "dog", "bird"), setOf("dog", "rabbit"))
        assertEquals(0.05f / 3f, boost, 1e-6f)
    }

    @Test
    fun computeBestFaceSimilarity_returnsMaxPairwiseSimilarity() {
        val queryFaces = listOf(
            EmbeddingUtils.l2Normalize(floatArrayOf(1f, 0f)),
            EmbeddingUtils.l2Normalize(floatArrayOf(0f, 1f))
        )
        val candidateFaces = listOf(
            EmbeddingUtils.l2Normalize(floatArrayOf(0.7f, 0.7f)),
            EmbeddingUtils.l2Normalize(floatArrayOf(1f, 0f))
        )

        val best = computeBestFaceSimilarity(queryFaces, candidateFaces)
        assertEquals(1f, best, 1e-6f)
    }

    @Test
    fun findSimilarCombined_sceneOnlyFallback_whenFaceDaoMissing() = runBlocking {
        val service = SimilaritySearchService(
            imageEmbeddingDao = FakeImageEmbeddingDao(
                embeddings = listOf(
                    imageEmbedding("query", "scene-v1", floatArrayOf(1f, 0f)),
                    imageEmbedding("a1", "scene-v1", floatArrayOf(0.8f, 0.6f)),
                    imageEmbedding("a2", "scene-v1", floatArrayOf(0.6f, 0.8f))
                )
            ),
            faceDao = null,
            tagRepository = null
        )

        val results = service.findSimilarCombined(
            queryUri = "query",
            sceneModelVersion = "scene-v1",
            faceEmbedderVersion = "face-v1",
            topK = 2
        )

        assertEquals(listOf("a1", "a2"), results.map { it.assetUri })
        assertNull(results[0].faceScore)
        assertEquals(0f, results[0].identityBoost, 0f)
    }

    @Test
    fun findSimilarCombined_noQueryFacesFallback() = runBlocking {
        val service = SimilaritySearchService(
            imageEmbeddingDao = FakeImageEmbeddingDao(
                embeddings = listOf(
                    imageEmbedding("query", "scene-v1", floatArrayOf(1f, 0f)),
                    imageEmbedding("a1", "scene-v1", floatArrayOf(0.8f, 0.6f)),
                    imageEmbedding("a2", "scene-v1", floatArrayOf(0.6f, 0.8f))
                )
            ),
            faceDao = FakeFaceDao(queryFaceRows = emptyList(), candidateFaceRows = emptyList()),
            tagRepository = null
        )

        val results = service.findSimilarCombined(
            queryUri = "query",
            sceneModelVersion = "scene-v1",
            faceEmbedderVersion = "face-v1",
            topK = 2
        )

        assertEquals(listOf("a1", "a2"), results.map { it.assetUri })
        assertNull(results[0].faceScore)
        assertEquals(0f, results[0].identityBoost, 0f)
    }

    @Test
    fun findSimilarCombined_appliesIdentityBoost_and_sortsByRank() = runBlocking {
        val service = SimilaritySearchService(
            imageEmbeddingDao = FakeImageEmbeddingDao(
                embeddings = listOf(
                    imageEmbedding("query", "scene-v1", floatArrayOf(1f, 0f)),
                    imageEmbedding("assetA", "scene-v1", floatArrayOf(0.8f, 0.6f)),
                    imageEmbedding("assetB", "scene-v1", floatArrayOf(0.6f, 0.8f))
                )
            ),
            faceDao = FakeFaceDao(
                queryFaceRows = listOf(
                    assetFaceEmbeddingRow("query", "face-q", "cluster-1", floatArrayOf(1f, 0f))
                ),
                candidateFaceRows = listOf(
                    assetFaceEmbeddingRow("assetA", "face-a", "cluster-1", floatArrayOf(1f, 0f)),
                    assetFaceEmbeddingRow("assetB", "face-b", "cluster-2", floatArrayOf(1f, 0f))
                )
            ),
            tagRepository = null
        )

        val results = service.findSimilarCombined(
            queryUri = "query",
            sceneModelVersion = "scene-v1",
            faceEmbedderVersion = "face-v1",
            topK = 2
        )

        assertEquals(listOf("assetA", "assetB"), results.map { it.assetUri })
        assertEquals(0.08f, results.first().identityBoost, 1e-6f)
        assertEquals(1f, results.first().faceScore ?: 0f, 1e-6f)
    }

    private fun imageEmbedding(assetId: String, modelVersion: String, vector: FloatArray) =
        ImageEmbedding(
            assetId = assetId,
            modelVersion = modelVersion,
            dim = vector.size,
            embedding = EmbeddingUtils.floatArrayToBytes(EmbeddingUtils.l2Normalize(vector))
        )

    private fun assetFaceEmbeddingRow(
        assetId: String,
        faceId: String,
        clusterId: String?,
        vector: FloatArray
    ) = AssetFaceEmbeddingRow(
        assetId = assetId,
        faceId = faceId,
        clusterId = clusterId,
        qualityScore = 1.0,
        embedding = EmbeddingUtils.floatArrayToBytes(EmbeddingUtils.l2Normalize(vector))
    )

    private class FakeImageEmbeddingDao(
        private val embeddings: List<ImageEmbedding>
    ) : ImageEmbeddingDao {
        override suspend fun getForAssetAndModel(assetId: String, modelVersion: String) =
            embeddings.singleOrNull { it.assetId == assetId && it.modelVersion == modelVersion }

        override suspend fun getAllEmbeddings(modelVersion: String) =
            embeddings.filter { it.modelVersion == modelVersion }

        override suspend fun insert(embedding: ImageEmbedding) = throw UnsupportedOperationException()
        override suspend fun insertAll(embeddings: List<ImageEmbedding>) = throw UnsupportedOperationException()
        override suspend fun getForAsset(assetId: String) = throw UnsupportedOperationException()
        override suspend fun deleteForAsset(assetId: String) = throw UnsupportedOperationException()
        override suspend fun deleteForAssets(assetIds: List<String>) = throw UnsupportedOperationException()
        override suspend fun getUnindexedImageUris(modelVersion: String, limit: Int, offset: Int) = throw UnsupportedOperationException()
        override suspend fun countUnindexed(modelVersion: String) = throw UnsupportedOperationException()
        override suspend fun getUnindexedGifUris(modelVersion: String, limit: Int, offset: Int) = throw UnsupportedOperationException()
        override suspend fun countUnindexedGifs(modelVersion: String) = throw UnsupportedOperationException()
        override suspend fun getUnindexedVideoUris(modelVersion: String, limit: Int, offset: Int) = throw UnsupportedOperationException()
        override suspend fun countUnindexedVideos(modelVersion: String) = throw UnsupportedOperationException()
        override suspend fun getIndexedAssetUris(modelVersion: String, limit: Int, offset: Int) = throw UnsupportedOperationException()
        override suspend fun countIndexed(modelVersion: String) = throw UnsupportedOperationException()
        override suspend fun deleteAll() = throw UnsupportedOperationException()
        override suspend fun getUnindexedImageUrisByAlbum(albumId: Long, modelVersion: String, limit: Int, offset: Int) = throw UnsupportedOperationException()
        override suspend fun countUnindexedByAlbum(albumId: Long, modelVersion: String) = throw UnsupportedOperationException()
        override suspend fun countUnindexedGifsByAlbum(albumId: Long, modelVersion: String) = throw UnsupportedOperationException()
        override suspend fun getUnindexedGifUrisByAlbum(albumId: Long, modelVersion: String, limit: Int, offset: Int) = throw UnsupportedOperationException()
        override suspend fun countUnindexedVideosByAlbum(albumId: Long, modelVersion: String) = throw UnsupportedOperationException()
        override suspend fun getUnindexedVideoUrisByAlbum(albumId: Long, modelVersion: String, limit: Int, offset: Int) = throw UnsupportedOperationException()
        override suspend fun countIndexedWithoutSuggestionsByAlbum(albumId: Long, modelVersion: String) = throw UnsupportedOperationException()
        override suspend fun getIndexedAssetUrisWithoutSuggestionsByAlbum(albumId: Long, modelVersion: String, limit: Int, offset: Int) = throw UnsupportedOperationException()
    }

    private class FakeFaceDao(
        private val queryFaceRows: List<AssetFaceEmbeddingRow>,
        private val candidateFaceRows: List<AssetFaceEmbeddingRow>
    ) : FaceDao {
        override suspend fun getFaceEmbeddingsForAsset(assetId: String, embedderVersion: String) =
            if (assetId == "query") queryFaceRows else emptyList()

        override suspend fun getFaceEmbeddingsForAssets(assetIds: List<String>, embedderVersion: String) =
            candidateFaceRows.filter { it.assetId in assetIds }

        override suspend fun insertFace(face: com.example.boxpandora.data.local.entity.DetectedFace) = throw UnsupportedOperationException()
        override suspend fun insertFaces(faces: List<com.example.boxpandora.data.local.entity.DetectedFace>) = throw UnsupportedOperationException()
        override suspend fun insertEmbedding(embedding: com.example.boxpandora.data.local.entity.FaceEmbedding) = throw UnsupportedOperationException()
        override suspend fun insertEmbeddings(embeddings: List<com.example.boxpandora.data.local.entity.FaceEmbedding>) = throw UnsupportedOperationException()
        override suspend fun getFacesForAsset(assetId: String) = throw UnsupportedOperationException()
        override suspend fun getEmbeddingForFace(faceId: String) = throw UnsupportedOperationException()
        override suspend fun deleteForAsset(assetId: String) = throw UnsupportedOperationException()
        override suspend fun deleteForAssets(assetIds: List<String>) = throw UnsupportedOperationException()
        override suspend fun getUnprocessedImageUris(detectorVersion: String, limit: Int, offset: Int) = throw UnsupportedOperationException()
        override suspend fun countUnprocessed(detectorVersion: String) = throw UnsupportedOperationException()
        override suspend fun countUnprocessedGifs(detectorVersion: String) = throw UnsupportedOperationException()
        override suspend fun countUnprocessedVideos(detectorVersion: String) = throw UnsupportedOperationException()
        override suspend fun getFacesForAssetAndDetector(assetId: String, detectorVersion: String) = throw UnsupportedOperationException()
        override suspend fun deleteAllFaces() = throw UnsupportedOperationException()
        override suspend fun getUnprocessedImageUrisByAlbum(albumId: Long, detectorVersion: String, limit: Int, offset: Int) = throw UnsupportedOperationException()
        override suspend fun countUnprocessedByAlbum(albumId: Long, detectorVersion: String) = throw UnsupportedOperationException()
        override suspend fun countUnprocessedGifsByAlbum(albumId: Long, detectorVersion: String) = throw UnsupportedOperationException()
        override suspend fun countUnprocessedVideosByAlbum(albumId: Long, detectorVersion: String) = throw UnsupportedOperationException()
        override suspend fun getAllEmbeddingsForClustering(embedderVersion: String) = throw UnsupportedOperationException()
        override suspend fun deleteAllFaceEmbeddings() = throw UnsupportedOperationException()
        override suspend fun getSingleFaceSinglePersonTagSamples(embedderVersion: String) = throw UnsupportedOperationException()
        override suspend fun getCorrectionDerivedPersonSamples(embedderVersion: String) = throw UnsupportedOperationException()
    }
}
