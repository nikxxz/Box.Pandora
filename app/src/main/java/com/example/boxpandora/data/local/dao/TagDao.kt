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
}
