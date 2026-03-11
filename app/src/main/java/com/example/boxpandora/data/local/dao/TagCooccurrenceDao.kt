package com.example.boxpandora.data.local.dao

import androidx.room.*
import com.example.boxpandora.data.local.entity.TagCooccurrence
import kotlinx.coroutines.flow.Flow

@Dao
interface TagCooccurrenceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(cooccurrence: TagCooccurrence): Long

    @Query("""
        UPDATE tag_cooccurrences 
        SET count = count + 1, last_seen = :timestamp 
        WHERE tag_id_a = :tagIdA AND tag_id_b = :tagIdB
    """)
    suspend fun incrementCount(tagIdA: Long, tagIdB: Long, timestamp: Long = System.currentTimeMillis())

    @Transaction
    suspend fun recordCooccurrence(tagId1: Long, tagId2: Long) {
        val (a, b) = if (tagId1 < tagId2) tagId1 to tagId2 else tagId2 to tagId1
        if (insert(TagCooccurrence(a, b)) == -1L) {
            incrementCount(a, b)
        }
    }

    @Query("""
        SELECT * FROM tag_cooccurrences 
        WHERE tag_id_a = :tagId OR tag_id_b = :tagId 
        ORDER BY count DESC 
        LIMIT :limit
    """)
    fun getRelatedTagsFlow(tagId: Long, limit: Int = 10): Flow<List<TagCooccurrence>>
}
