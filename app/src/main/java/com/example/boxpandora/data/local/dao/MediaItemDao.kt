package com.example.boxpandora.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.boxpandora.data.local.entity.MediaItem
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaItemDao {
    @Query("SELECT * FROM media_index WHERE (hidden = 0 OR :showHidden = 1) ORDER BY device_created_at DESC")
    fun getAllMediaPaged(showHidden: Boolean): PagingSource<Int, MediaItem>

    @Query("SELECT * FROM media_index WHERE album_id = :albumId ORDER BY device_created_at DESC")
    fun getMediaByAlbumPaged(albumId: Long): PagingSource<Int, MediaItem>

    @Query("""
        SELECT media_index.* FROM media_index
        INNER JOIN albums ON media_index.album_id = albums.id
        WHERE albums.name = :albumName
        AND (media_index.hidden = 0 OR :showHidden = 1)
        ORDER BY media_index.device_created_at DESC
    """)
    fun getMediaByAlbumFlow(albumName: String, showHidden: Boolean): Flow<List<MediaItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediaItem>)

    @Query("DELETE FROM media_index WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    @Query("DELETE FROM media_index")
    suspend fun clearAll()

    @Query("SELECT * FROM media_index WHERE uri = :uri")
    suspend fun getByUri(uri: String): MediaItem?

    @Update
    suspend fun update(item: MediaItem)

    // Only select rows with a truly unresolved thumbnail (NULL).
    // Rows with thumb_uri = '' were permanently marked as unresolvable and are
    // intentionally excluded to prevent infinite retry loops on broken/orphaned files.
    @Query("SELECT * FROM media_index WHERE thumb_uri IS NULL")
    suspend fun getItemsMissingThumbnails(): List<MediaItem>

    @Query("SELECT uri FROM media_index")
    suspend fun getAllUris(): List<String>
}
