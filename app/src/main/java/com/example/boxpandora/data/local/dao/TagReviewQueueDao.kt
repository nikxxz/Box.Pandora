package com.example.boxpandora.data.local.dao

import androidx.room.*
import com.example.boxpandora.data.local.entity.TagReviewQueue
import kotlinx.coroutines.flow.Flow

@Dao
interface TagReviewQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TagReviewQueue): Long

    @Update
    suspend fun update(item: TagReviewQueue)

    @Query("SELECT * FROM tag_review_queue WHERE status = :status ORDER BY created_at ASC")
    fun getByStatusFlow(status: String = "pending"): Flow<List<TagReviewQueue>>

    @Query("SELECT * FROM tag_review_queue WHERE id = :id")
    suspend fun getById(id: Long): TagReviewQueue?

    @Query("UPDATE tag_review_queue SET status = :status, reviewed_at = :reviewedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, reviewedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM tag_review_queue WHERE status = 'accepted' OR status = 'rejected'")
    suspend fun clearProcessed()
}
