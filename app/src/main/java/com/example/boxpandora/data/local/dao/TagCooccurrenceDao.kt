package com.example.boxpandora.data.local.dao

import androidx.room.*
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.data.local.entity.TagCooccurrence
import kotlinx.coroutines.flow.Flow

/**
 * Single-query result combining a Tag with its co-occurrence count relative to a pivot tag.
 * Used to replace the previous N+1 per-tag lookup in getRelatedTagsFlow.
 */
data class RelatedTagResult(
    @Embedded val tag: Tag,
    @ColumnInfo(name = "co_count") val count: Int
)

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

    /**
     * Single JOIN query returning related tags with their co-occurrence counts.
     * Replaces the N+1 pattern of fetching each tag individually after getting cooccurrences.
     */
    @Query("""
        SELECT t.*, tc.count AS co_count FROM tags t
        INNER JOIN tag_cooccurrences tc
            ON (tc.tag_id_a = t.id AND tc.tag_id_b = :tagId)
            OR (tc.tag_id_b = t.id AND tc.tag_id_a = :tagId)
        WHERE t.id != :tagId
        ORDER BY tc.count DESC
        LIMIT :limit
    """)
    fun getRelatedTagsWithCountFlow(tagId: Long, limit: Int = 8): Flow<List<RelatedTagResult>>

    /**
     * Suspend (non-Flow) version used by TagSuggestionEngine during scoring.
     * Returns normalized tag keys and co-occurrence counts for tags related to [tagId].
     */
    @Query("""
        SELECT t.normalized_name AS tagKey, tc.count AS coCount FROM tags t
        INNER JOIN tag_cooccurrences tc
            ON (tc.tag_id_a = t.id AND tc.tag_id_b = :tagId)
            OR (tc.tag_id_b = t.id AND tc.tag_id_a = :tagId)
        WHERE t.id != :tagId
        ORDER BY tc.count DESC
        LIMIT :limit
    """)
    suspend fun getRelatedTagKeys(tagId: Long, limit: Int = 8): List<RelatedTagKeyResult>
}

data class RelatedTagKeyResult(
    @ColumnInfo(name = "tagKey") val tagKey: String,
    @ColumnInfo(name = "coCount") val coCount: Int
)
