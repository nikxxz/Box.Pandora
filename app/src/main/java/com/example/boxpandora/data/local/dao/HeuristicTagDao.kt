package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.boxpandora.data.local.entity.HeuristicTag

@Dao
interface HeuristicTagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<HeuristicTag>)

    @Query("SELECT * FROM heuristic_tags WHERE asset_id = :assetId")
    suspend fun getForAsset(assetId: String): List<HeuristicTag>

    @Query("DELETE FROM heuristic_tags WHERE asset_id = :assetId")
    suspend fun deleteForAsset(assetId: String)

    @Query("DELETE FROM heuristic_tags WHERE asset_id IN (:assetIds)")
    suspend fun deleteForAssets(assetIds: List<String>)
}
