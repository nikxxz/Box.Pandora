package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.boxpandora.data.local.entity.MediaTag
import com.example.boxpandora.data.local.entity.Tag
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaTagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mediaTag: MediaTag)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(mediaTags: List<MediaTag>)

    @Query("DELETE FROM media_tags WHERE media_uri = :mediaUri AND tag_id = :tagId")
    suspend fun delete(mediaUri: String, tagId: Long)

    @Query("""
        SELECT tags.* FROM tags 
        INNER JOIN media_tags ON tags.id = media_tags.tag_id 
        WHERE media_tags.media_uri = :mediaUri
    """)
    fun getTagsForMedia(mediaUri: String): Flow<List<Tag>>

    @Query("SELECT * FROM media_tags WHERE media_uri = :mediaUri")
    suspend fun getMediaTagsForUri(mediaUri: String): List<MediaTag>

    @Query("DELETE FROM media_tags WHERE media_uri = :mediaUri")
    suspend fun clearTagsForMedia(mediaUri: String)

    @Query("DELETE FROM media_tags WHERE media_uri IN (:uris)")
    suspend fun clearTagsForUris(uris: List<String>)
}
