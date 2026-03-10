package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.boxpandora.data.local.entity.TagSuggestion

@Dao
interface TagSuggestionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(suggestions: List<TagSuggestion>)

    @Query("SELECT * FROM tag_suggestions WHERE asset_id = :assetId")
    suspend fun getForAsset(assetId: String): List<TagSuggestion>

    @Query("DELETE FROM tag_suggestions WHERE asset_id = :assetId")
    suspend fun deleteForAsset(assetId: String)

    @Query("DELETE FROM tag_suggestions WHERE asset_id IN (:assetIds)")
    suspend fun deleteForAssets(assetIds: List<String>)
}
