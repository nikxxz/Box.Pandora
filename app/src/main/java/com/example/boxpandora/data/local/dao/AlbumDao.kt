package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.boxpandora.data.local.entity.Album
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums WHERE (hidden = 0 OR :showHidden = 1) ORDER BY last_modified_at DESC")
    fun getAlbumsFlow(showHidden: Boolean): Flow<List<Album>>

    @Query("SELECT * FROM albums WHERE hidden = 0 ORDER BY last_modified_at DESC")
    fun getAllAlbumsFlow(): Flow<List<Album>>

    @Query("SELECT * FROM albums WHERE hidden = 1 ORDER BY last_modified_at DESC")
    fun getHiddenAlbumsFlow(): Flow<List<Album>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(albums: List<Album>): List<Long>

    @Update
    suspend fun updateAll(albums: List<Album>)

    @Transaction
    suspend fun upsertAll(albums: List<Album>) {
        val insertResults = insertIgnore(albums)
        val updateList = mutableListOf<Album>()
        for (i in insertResults.indices) {
            if (insertResults[i] == -1L) {
                // Before updating, preserve pinned status
                val existing = getById(albums[i].id)
                updateList.add(if (existing != null) albums[i].copy(isPinned = existing.isPinned) else albums[i])
            }
        }
        if (updateList.isNotEmpty()) {
            updateAll(updateList)
        }
    }

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getById(id: Long): Album?

    @Query("SELECT * FROM albums WHERE name = :name")
    suspend fun getByName(name: String): Album?

    @Query("UPDATE albums SET hidden = :hidden WHERE name = :name")
    suspend fun setHidden(name: String, hidden: Boolean)

    @Query("UPDATE albums SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("DELETE FROM albums")
    suspend fun clearAll()

    @Query("DELETE FROM albums WHERE id NOT IN (:ids)")
    suspend fun deleteStaleAlbums(ids: List<Long>)
}
