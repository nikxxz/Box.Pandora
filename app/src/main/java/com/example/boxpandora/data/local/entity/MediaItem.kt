package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_index",
    foreignKeys = [
        ForeignKey(
            entity = Album::class,
            parentColumns = ["id"],
            childColumns = ["album_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("album_id"),
        Index("media_type"),
        Index("favorite"),
        Index("hidden"),
        Index("device_created_at"),
        Index(value = ["album_id", "device_created_at"])
    ]
)
data class MediaItem(
    @PrimaryKey val uri: String,
    @ColumnInfo(name = "filename") val filename: String,
    @ColumnInfo(name = "album_id") val albumId: Long?,
    @ColumnInfo(name = "file_size") val fileSize: Long = 0,
    @ColumnInfo(name = "width") val width: Int = 0,
    @ColumnInfo(name = "height") val height: Int = 0,
    @ColumnInfo(name = "duration") val duration: Double?,
    @ColumnInfo(name = "extension") val extension: String = "",
    @ColumnInfo(name = "media_type") val mediaType: String = "image",
    @ColumnInfo(name = "device_created_at") val deviceCreatedAt: Long?,
    @ColumnInfo(name = "device_modified_at") val deviceModifiedAt: Long? = null,
    @ColumnInfo(name = "indexed_at") val indexedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "scanned_at") val scannedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "rating") val rating: Int = 0,
    @ColumnInfo(name = "favorite") val isFavorite: Int = 0,
    @ColumnInfo(name = "hidden") val isHidden: Int = 0,
    @ColumnInfo(name = "notes") val notes: String? = null,
    @ColumnInfo(name = "thumb_uri") val thumbUri: String? = null,
    // Actual filesystem path (e.g. /storage/emulated/0/DCIM/video.mp4).
    // Used by Coil to load thumbnails directly from the file, bypassing
    // ContentResolver — same approach as Simple Gallery.
    @ColumnInfo(name = "file_path") val filePath: String? = null,

    @Ignore val albumName: String? = null
) {
    // Secondary constructor for Room (excludes @Ignore field albumName)
    constructor(
        uri: String,
        filename: String,
        albumId: Long?,
        fileSize: Long,
        width: Int,
        height: Int,
        duration: Double?,
        extension: String,
        mediaType: String,
        deviceCreatedAt: Long?,
        deviceModifiedAt: Long?,
        indexedAt: Long,
        scannedAt: Long,
        rating: Int,
        isFavorite: Int,
        isHidden: Int,
        notes: String?,
        thumbUri: String?,
        filePath: String?
    ) : this(
        uri, filename, albumId, fileSize, width, height, duration, extension, mediaType,
        deviceCreatedAt, deviceModifiedAt, indexedAt, scannedAt, rating, isFavorite, isHidden,
        notes, thumbUri, filePath, null
    )
}
