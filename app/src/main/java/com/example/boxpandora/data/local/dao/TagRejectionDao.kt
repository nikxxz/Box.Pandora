package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.boxpandora.data.local.entity.TagRejection

@Dao
interface TagRejectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rejections: List<TagRejection>)

    @Query("SELECT * FROM tag_rejections WHERE asset_id = :assetId")
    suspend fun getForAsset(assetId: String): List<TagRejection>

    @Query("DELETE FROM tag_rejections WHERE asset_id = :assetId")
    suspend fun deleteForAsset(assetId: String)

    @Query("DELETE FROM tag_rejections WHERE asset_id IN (:assetIds)")
    suspend fun deleteForAssets(assetIds: List<String>)
}
