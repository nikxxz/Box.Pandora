package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.boxpandora.data.local.entity.ImageEmbedding

@Dao
interface ImageEmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(embedding: ImageEmbedding)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(embeddings: List<ImageEmbedding>)

    @Query("SELECT * FROM image_embeddings WHERE asset_id = :assetId")
    suspend fun getForAsset(assetId: String): List<ImageEmbedding>

    @Query("DELETE FROM image_embeddings WHERE asset_id = :assetId")
    suspend fun deleteForAsset(assetId: String)

    @Query("DELETE FROM image_embeddings WHERE asset_id IN (:assetIds)")
    suspend fun deleteForAssets(assetIds: List<String>)

    // ── Plain images (non-GIF) ────────────────────────────────────────────────

    /**
     * Returns URIs of plain image assets (JPEG, PNG, WEBP, etc.) not yet embedded for
     * [modelVersion]. GIFs are excluded — they are processed by [getUnindexedGifUris].
     */
    @Query("""
        SELECT m.uri FROM media_index m
        WHERE m.media_type = 'image'
        AND LOWER(m.extension) != 'gif'
        AND NOT EXISTS (
            SELECT 1 FROM image_embeddings ie
            WHERE ie.asset_id = m.uri AND ie.model_version = :modelVersion
        )
        ORDER BY m.device_created_at DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getUnindexedImageUris(
        modelVersion: String,
        limit: Int,
        offset: Int
    ): List<String>

    /** Count of plain (non-GIF) image assets not yet indexed for [modelVersion]. */
    @Query("""
        SELECT COUNT(*) FROM media_index m
        WHERE m.media_type = 'image'
        AND LOWER(m.extension) != 'gif'
        AND NOT EXISTS (
            SELECT 1 FROM image_embeddings ie
            WHERE ie.asset_id = m.uri AND ie.model_version = :modelVersion
        )
    """)
    suspend fun countUnindexed(modelVersion: String): Int

    // ── GIFs ─────────────────────────────────────────────────────────────────

    /**
     * Returns URIs of GIF assets not yet embedded for [modelVersion].
     * GIFs are identified by extension since [media_type] = 'image' for all still-like media.
     */
    @Query("""
        SELECT m.uri FROM media_index m
        WHERE m.media_type = 'image'
        AND LOWER(m.extension) = 'gif'
        AND NOT EXISTS (
            SELECT 1 FROM image_embeddings ie
            WHERE ie.asset_id = m.uri AND ie.model_version = :modelVersion
        )
        ORDER BY m.device_created_at DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getUnindexedGifUris(
        modelVersion: String,
        limit: Int,
        offset: Int
    ): List<String>

    /** Count of GIF assets not yet indexed for [modelVersion]. */
    @Query("""
        SELECT COUNT(*) FROM media_index m
        WHERE m.media_type = 'image'
        AND LOWER(m.extension) = 'gif'
        AND NOT EXISTS (
            SELECT 1 FROM image_embeddings ie
            WHERE ie.asset_id = m.uri AND ie.model_version = :modelVersion
        )
    """)
    suspend fun countUnindexedGifs(modelVersion: String): Int

    // ── Videos ────────────────────────────────────────────────────────────────

    /** Returns URIs of video assets not yet embedded for [modelVersion]. */
    @Query("""
        SELECT m.uri FROM media_index m
        WHERE m.media_type = 'video'
        AND NOT EXISTS (
            SELECT 1 FROM image_embeddings ie
            WHERE ie.asset_id = m.uri AND ie.model_version = :modelVersion
        )
        ORDER BY m.device_created_at DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getUnindexedVideoUris(
        modelVersion: String,
        limit: Int,
        offset: Int
    ): List<String>

    /** Count of video assets not yet indexed for [modelVersion]. */
    @Query("""
        SELECT COUNT(*) FROM media_index m
        WHERE m.media_type = 'video'
        AND NOT EXISTS (
            SELECT 1 FROM image_embeddings ie
            WHERE ie.asset_id = m.uri AND ie.model_version = :modelVersion
        )
    """)
    suspend fun countUnindexedVideos(modelVersion: String): Int

    // ── Shared lookups ────────────────────────────────────────────────────────

    /** Single-asset lookup for a specific model version — used by debug tools and similarity search. */
    @Query("SELECT * FROM image_embeddings WHERE asset_id = :assetId AND model_version = :modelVersion LIMIT 1")
    suspend fun getForAssetAndModel(assetId: String, modelVersion: String): ImageEmbedding?

    /** All indexed asset URIs for a model version, paginated. Used by TagSuggestionWorker. */
    @Query("SELECT DISTINCT asset_id FROM image_embeddings WHERE model_version = :modelVersion ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getIndexedAssetUris(modelVersion: String, limit: Int, offset: Int): List<String>

    /** Count of distinct assets indexed under [modelVersion]. */
    @Query("SELECT COUNT(DISTINCT asset_id) FROM image_embeddings WHERE model_version = :modelVersion")
    suspend fun countIndexed(modelVersion: String): Int

    /** All embeddings for a model version — used by SimilaritySearchService for in-memory cosine search. */
    @Query("SELECT * FROM image_embeddings WHERE model_version = :modelVersion")
    suspend fun getAllEmbeddings(modelVersion: String): List<ImageEmbedding>

    /** Delete all scene embeddings. Used by the "Rebuild Scene Embeddings" action to force a full re-index. */
    @Query("DELETE FROM image_embeddings")
    suspend fun deleteAll()
}
