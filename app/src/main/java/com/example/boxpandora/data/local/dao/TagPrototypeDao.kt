package com.example.boxpandora.data.local.dao

import androidx.room.*
import com.example.boxpandora.data.local.entity.TagPrototype

@Dao
interface TagPrototypeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(prototype: TagPrototype)

    @Query("SELECT * FROM tag_prototypes WHERE tag_key = :tagKey")
    suspend fun getByKey(tagKey: String): TagPrototype?

    @Query("SELECT * FROM tag_prototypes")
    suspend fun getAll(): List<TagPrototype>

    @Query("DELETE FROM tag_prototypes WHERE tag_key = :tagKey")
    suspend fun deleteByKey(tagKey: String)

    @Query("DELETE FROM tag_prototypes")
    suspend fun clearAll()
}
