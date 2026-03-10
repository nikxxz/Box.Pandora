package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.boxpandora.data.local.entity.DetectedFace
import com.example.boxpandora.data.local.entity.FaceEmbedding

@Dao
interface FaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFace(face: DetectedFace)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFaces(faces: List<DetectedFace>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbedding(embedding: FaceEmbedding)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbeddings(embeddings: List<FaceEmbedding>)

    @Query("SELECT * FROM detected_faces WHERE asset_id = :assetId")
    suspend fun getFacesForAsset(assetId: String): List<DetectedFace>

    @Query("SELECT * FROM face_embeddings WHERE face_id = :faceId")
    suspend fun getEmbeddingForFace(faceId: String): FaceEmbedding?

    @Query("DELETE FROM detected_faces WHERE asset_id = :assetId")
    suspend fun deleteForAsset(assetId: String)

    @Query("DELETE FROM detected_faces WHERE asset_id IN (:assetIds)")
    suspend fun deleteForAssets(assetIds: List<String>)
}
