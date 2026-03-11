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

    @Query("UPDATE OR IGNORE media_tags SET tag_id = :targetTagId WHERE tag_id = :sourceTagId")
    suspend fun transferTags(sourceTagId: Long, targetTagId: Long)

    @Query("DELETE FROM media_tags WHERE tag_id = :tagId")
    suspend fun deleteByTagId(tagId: Long)

    @Query("SELECT COUNT(*) FROM media_tags WHERE tag_id = :tagId")
    suspend fun getUsageCount(tagId: Long): Int

    @Query("""
        SELECT m.uri FROM media_index m
        INNER JOIN media_tags mt ON m.uri = mt.media_uri
        WHERE mt.tag_id = :tagId
        ORDER BY m.device_created_at DESC
        LIMIT 1
    """)
    suspend fun getCoverMediaUri(tagId: Long): String?

    @Query("""
        SELECT mt.media_uri AS mediaUri, t.name AS tagName, t.normalized_name AS normalizedName
        FROM media_tags mt
        INNER JOIN tags t ON mt.tag_id = t.id
        WHERE mt.media_uri IN (:uris)
    """)
    suspend fun getTagNamesForUris(uris: List<String>): List<MediaUriTagName>
}

data class MediaUriTagName(
    val mediaUri: String,
    val tagName: String,
    val normalizedName: String
)
