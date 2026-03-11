package com.example.boxpandora.data.local.dao

import androidx.room.*
import com.example.boxpandora.data.local.entity.TagChangeHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface TagChangeHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: TagChangeHistory): Long

    @Query("SELECT * FROM tag_change_history WHERE tag_id = :tagId ORDER BY changed_at DESC")
    fun getHistoryForTagFlow(tagId: Long): Flow<List<TagChangeHistory>>

    @Query("SELECT * FROM tag_change_history ORDER BY changed_at DESC LIMIT :limit")
    fun getRecentHistoryFlow(limit: Int = 100): Flow<List<TagChangeHistory>>
}
