package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.boxpandora.data.local.entity.Tag
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY usage_count DESC")
    fun getAllTagsFlow(): Flow<List<Tag>>

    @Query("SELECT * FROM tags WHERE category = :category ORDER BY usage_count DESC")
    fun getTagsByCategoryFlow(category: String): Flow<List<Tag>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: Tag): Long

    @Update
    suspend fun update(tag: Tag)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM tags WHERE normalized_name = :normalizedName")
    suspend fun getByNormalizedName(normalizedName: String): Tag?

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getById(id: Long): Tag?

    @Query("SELECT * FROM tags WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Tag?>

    @Query("UPDATE tags SET usage_count = usage_count + :delta WHERE id = :id")
    suspend fun updateUsageCount(id: Long, delta: Int)

    @Query("UPDATE tags SET name = :newName, normalized_name = :newNormalized, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateName(id: Long, newName: String, newNormalized: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE tags SET description = :description, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateDescription(id: Long, description: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE tags SET category = :category, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateCategory(id: Long, category: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM tags WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Tag>

    /** One-shot suspend version of getAllTagsFlow() used by TagPrototypeEngine. */
    @Query("SELECT * FROM tags ORDER BY usage_count DESC")
    suspend fun getAll(): List<Tag>

    // ── Library health queries ────────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun countAll(): Int

    /**
     * Tags whose stored [Tag.usageCount] doesn't match the real count of rows
     * in media_tags.  Caused by missed increments/decrements during move/rename
     * operations or interrupted transactions.
     */
    @Query("""
        SELECT * FROM tags
        WHERE usage_count != (
            SELECT COUNT(*) FROM media_tags WHERE tag_id = tags.id
        )
        ORDER BY name ASC
    """)
    suspend fun getTagsWithCountMismatch(): List<Tag>

    /** Tags that have zero actual associations in media_tags (safe to delete). */
    @Query("""
        SELECT * FROM tags
        WHERE NOT EXISTS (SELECT 1 FROM media_tags WHERE tag_id = tags.id)
        ORDER BY name ASC
    """)
    suspend fun getTagsWithNoAssociations(): List<Tag>

    /**
     * Groups of tags sharing the same [Tag.normalizedName].  Each row is one
     * duplicate group; the returned count > 1 means that many tags share the key.
     */
    @Query("""
        SELECT normalized_name, COUNT(*) AS cnt
        FROM tags
        GROUP BY normalized_name
        HAVING COUNT(*) > 1
    """)
    suspend fun getDuplicateNormalizedNameGroups(): List<DuplicateNameRow>

    /** All tags that belong to any duplicate-normalized-name group. */
    @Query("""
        SELECT * FROM tags
        WHERE normalized_name IN (
            SELECT normalized_name FROM tags
            GROUP BY normalized_name HAVING COUNT(*) > 1
        )
        ORDER BY normalized_name ASC, usage_count DESC
    """)
    suspend fun getTagsInDuplicateGroups(): List<Tag>

    /**
     * Single-statement recalculation of every tag's [Tag.usageCount] from the
     * live media_tags table.  Runs as one UPDATE so it's fast even for large
     * libraries.
     */
    @Query("UPDATE tags SET usage_count = (SELECT COUNT(*) FROM media_tags WHERE tag_id = tags.id)")
    suspend fun recalculateAllUsageCounts()

    /** Delete all tags that have no associations in media_tags. */
    @Query("""
        DELETE FROM tags
        WHERE NOT EXISTS (SELECT 1 FROM media_tags WHERE tag_id = tags.id)
    """)
    suspend fun deleteTagsWithNoAssociations()

    data class DuplicateNameRow(
        val normalized_name: String,
        val cnt: Int
    )
}
