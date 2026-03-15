package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.boxpandora.data.local.entity.FusedFace

@Dao
interface FusedFaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(faces: List<FusedFace>)

    @Query("SELECT * FROM fused_faces WHERE asset_id = :assetId ORDER BY face_index ASC")
    suspend fun getForAsset(assetId: String): List<FusedFace>

    @Query("SELECT * FROM fused_faces WHERE fused_face_id = :fusedFaceId")
    suspend fun getById(fusedFaceId: String): FusedFace?

    @Query("DELETE FROM fused_faces WHERE asset_id = :assetId")
    suspend fun deleteForAsset(assetId: String)

    @Query("DELETE FROM fused_faces")
    suspend fun deleteAll()

    /** Count of distinct assets that have at least one fused face row. */
    @Query("SELECT COUNT(DISTINCT asset_id) FROM fused_faces")
    suspend fun countDistinctAssets(): Int
}
