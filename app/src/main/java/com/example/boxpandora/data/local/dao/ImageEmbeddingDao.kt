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
}
