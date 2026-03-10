package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "albums")
data class Album(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "path") val path: String? = null,
    @ColumnInfo(name = "album_type") val albumType: String = "Album",
    @ColumnInfo(name = "hidden") val isHidden: Boolean = false,
    @ColumnInfo(name = "pinned") val isPinned: Boolean = false,
    @ColumnInfo(name = "cover_uri") val coverUri: String? = null,
    @ColumnInfo(name = "cover_file_path") val coverFilePath: String? = null, // direct file path for Coil
    @ColumnInfo(name = "photo_cover_uri") val photoCoverUri: String? = null,
    @ColumnInfo(name = "video_cover_uri") val videoCoverUri: String? = null,
    @ColumnInfo(name = "media_count") val mediaCount: Int = 0,
    @ColumnInfo(name = "photo_count") val photoCount: Int = 0,
    @ColumnInfo(name = "video_count") val videoCount: Int = 0,
    @ColumnInfo(name = "last_modified_at") val lastModifiedAt: Long? = null,
    @ColumnInfo(name = "last_scanned_at") val lastScannedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
