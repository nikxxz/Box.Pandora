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
    @Query("SELECT * FROM media_index WHERE (hidden = 0 OR :showHidden = 1) ORDER BY device_created_at DESC, uri DESC")
    fun getAllMediaPaged(showHidden: Boolean): PagingSource<Int, MediaItem>

    @Query("SELECT * FROM media_index WHERE album_id = :albumId AND (hidden = 0 OR :showHidden = 1) ORDER BY device_created_at DESC, uri DESC")
    fun getMediaByAlbumPaged(albumId: Long, showHidden: Boolean): PagingSource<Int, MediaItem>

    @Query("""
        SELECT * FROM media_index
        WHERE album_id = :albumId
        AND (hidden = 0 OR :showHidden = 1)
        ORDER BY device_created_at DESC, uri DESC
    """)
    fun getMediaByAlbumIdFlow(albumId: Long, showHidden: Boolean): Flow<List<MediaItem>>

    @Query("""
        SELECT m.* FROM media_index m
        INNER JOIN albums a ON m.album_id = a.id
        WHERE a.name = :albumName
        AND (m.hidden = 0 OR :showHidden = 1)
        ORDER BY m.device_created_at DESC, m.uri DESC
    """)
    fun getMediaByAlbumFlow(albumName: String, showHidden: Boolean): Flow<List<MediaItem>>

    @Query("SELECT * FROM media_index WHERE album_id = :albumId")
    suspend fun getMediaByAlbum(albumId: Long): List<MediaItem>

    @Query("""
        SELECT m.* FROM media_index m
        INNER JOIN media_tags mt ON m.uri = mt.media_uri
        WHERE mt.tag_id = :tagId
        AND (m.hidden = 0 OR :showHidden = 1)
        ORDER BY m.device_created_at DESC, m.uri DESC
    """)
    fun getMediaByTagFlow(tagId: Long, showHidden: Boolean): Flow<List<MediaItem>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<MediaItem>)

    @Update
    suspend fun updateAll(items: List<MediaItem>)

    @Query("DELETE FROM media_index WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    @Query("SELECT * FROM media_index WHERE uri = :uri")
    suspend fun getByUri(uri: String): MediaItem?

    @Update
    suspend fun update(item: MediaItem)

    @Query("UPDATE media_index SET hidden = :hidden WHERE uri = :uri")
    suspend fun setHidden(uri: String, hidden: Int)

    @Query("SELECT * FROM media_index WHERE thumb_uri IS NULL")
    suspend fun getItemsMissingThumbnails(): List<MediaItem>

    @Query("SELECT uri FROM media_index")
    suspend fun getAllUris(): List<String>

    @Query("SELECT * FROM media_index WHERE uri IN (:uris)")
    suspend fun getByUris(uris: List<String>): List<MediaItem>

    /**
     * Structural filter for search.
     * We use flexible string checks for 'all' to ensure the logic isn't broken by case mismatches.
     */
    @Query("""
        SELECT m.* FROM media_index m
        WHERE (m.hidden = 0 OR :showHidden = 1)
        AND (:albumId = -1 OR m.album_id = :albumId)
        AND (LOWER(:type) = 'all' OR m.media_type = LOWER(:type))
        AND (LOWER(:format) = 'all' OR LOWER(m.extension) = LOWER(:format) OR (LOWER(:format) = 'jpg' AND LOWER(m.extension) = 'jpeg'))
        AND (LOWER(:tagCategory) = 'all' OR EXISTS (
            SELECT 1 FROM media_tags mt
            INNER JOIN tags t ON mt.tag_id = t.id
            WHERE mt.media_uri = m.uri AND LOWER(t.category) = LOWER(:tagCategory)
        ))
        AND (
            :query = '' 
            OR m.filename LIKE '%' || :query || '%' 
            OR EXISTS (
                SELECT 1 FROM media_tags mt
                INNER JOIN tags t ON mt.tag_id = t.id
                WHERE mt.media_uri = m.uri AND (t.name LIKE '%' || :query || '%' OR t.normalized_name LIKE '%' || :query || '%')
            )
        )
        ORDER BY m.device_created_at DESC
        LIMIT 500
    """)
    suspend fun searchFiltered(
        showHidden: Boolean,
        albumId: Long,
        type: String,
        format: String,
        tagCategory: String,
        query: String
    ): List<MediaItem>

    @Query("SELECT uri, rating, favorite, hidden, notes FROM media_index")
    suspend fun getAllUserMetadata(): List<MediaUserMetadata>
}

data class MediaUserMetadata(
    val uri: String,
    val rating: Int,
    val favorite: Int,
    val hidden: Int,
    val notes: String?
)
