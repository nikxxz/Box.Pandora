package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.boxpandora.data.local.entity.Album
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums WHERE hidden = 0 ORDER BY last_modified_at DESC")
    fun getAllAlbumsFlow(): Flow<List<Album>>

    @Query("SELECT * FROM albums WHERE hidden = 1 ORDER BY last_modified_at DESC")
    fun getHiddenAlbumsFlow(): Flow<List<Album>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(albums: List<Album>)

    @Query("SELECT * FROM albums WHERE name = :name")
    suspend fun getByName(name: String): Album?

    @Query("UPDATE albums SET hidden = :hidden WHERE name = :name")
    suspend fun setHidden(name: String, hidden: Boolean)

    @Query("DELETE FROM albums WHERE name NOT IN (:names) AND path IS NULL")
    suspend fun deleteStaleAlbums(names: List<String>)
}
