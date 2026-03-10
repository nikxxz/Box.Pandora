package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "media_tags",
    primaryKeys = ["media_uri", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItem::class,
            parentColumns = ["uri"],
            childColumns = ["media_uri"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("tag_id")
    ]
)
data class MediaTag(
    @ColumnInfo(name = "media_uri") val mediaUri: String,
    @ColumnInfo(name = "tag_id") val tagId: Long,
    @ColumnInfo(name = "tagged_at") val taggedAt: Long = System.currentTimeMillis()
)
