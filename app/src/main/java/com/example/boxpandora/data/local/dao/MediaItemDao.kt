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

    // Legacy — kept for reference. Queries BOTH tables; any write to `albums`
    // re-fires this flow, causing thumbnail flashes while a sync runs.
    // Use getMediaByAlbumIdFlow instead.
    @Query("""
        SELECT media_index.* FROM media_index
        INNER JOIN albums ON media_index.album_id = albums.id
        WHERE albums.name = :albumName
        AND (media_index.hidden = 0 OR :showHidden = 1)
        ORDER BY media_index.device_created_at DESC
    """)
    fun getMediaByAlbumFlow(albumName: String, showHidden: Boolean): Flow<List<MediaItem>>

    // Queries ONLY media_index — never re-fires when the albums table is written.
    // Sync writes albums first then media; this flow only wakes on media_index
    // changes, so thumbnails are never invalidated by an album upsert.
    @Query("""
        SELECT * FROM media_index
        WHERE album_id = :albumId
        AND (hidden = 0 OR :showHidden = 1)
        ORDER BY device_created_at DESC, uri DESC
    """)
    fun getMediaByAlbumIdFlow(albumId: Long, showHidden: Boolean): Flow<List<MediaItem>>

    @Query("SELECT * FROM media_index WHERE album_id = :albumId")
    suspend fun getMediaByAlbum(albumId: Long): List<MediaItem>

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

    @Query("UPDATE media_index SET hidden = :hidden WHERE uri = :uri")
    suspend fun setHidden(uri: String, hidden: Int)

    // Only select rows with a truly unresolved thumbnail (NULL).
    // Rows with thumb_uri = '' were permanently marked as unresolvable and are
    // intentionally excluded to prevent infinite retry loops on broken/orphaned files.
    @Query("SELECT * FROM media_index WHERE thumb_uri IS NULL")
    suspend fun getItemsMissingThumbnails(): List<MediaItem>

    @Query("SELECT uri FROM media_index")
    suspend fun getAllUris(): List<String>

    // Lightweight fetch of only the user-editable fields needed to survive a re-sync.
    // Used in syncMediaStore() to avoid N individual getByUri() calls.
    @Query("SELECT uri, rating, favorite, hidden, notes FROM media_index")
    suspend fun getAllUserMetadata(): List<MediaUserMetadata>
}

/** Projection used only during sync to preserve user-editable fields in one query. */
data class MediaUserMetadata(
    val uri: String,
    val rating: Int,
    val favorite: Int,
    val hidden: Int,
    val notes: String?
)
