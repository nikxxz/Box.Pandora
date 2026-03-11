package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.boxpandora.data.local.entity.TagAlias

@Dao
interface TagAliasDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alias: TagAlias)

    @Query("SELECT * FROM tag_aliases WHERE alias = :alias LIMIT 1")
    suspend fun getByAlias(alias: String): TagAlias?

    @Query("SELECT * FROM tag_aliases WHERE tag_id = :tagId")
    suspend fun getByTagId(tagId: Long): List<TagAlias>

    @Query("DELETE FROM tag_aliases WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM tag_aliases WHERE tag_id = :tagId")
    suspend fun deleteByTagId(tagId: Long)
}
