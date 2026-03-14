package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "tag_rejections",
    primaryKeys = ["tag_key", "asset_id"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItem::class,
            parentColumns = ["uri"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("tag_key"),
        Index("asset_id")   // Required: getForAsset() queries by asset_id, not tag_key
    ]
)
data class TagRejection(
    @ColumnInfo(name = "tag_key") val tagKey: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
