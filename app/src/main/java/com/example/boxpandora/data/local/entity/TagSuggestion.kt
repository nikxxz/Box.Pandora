package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tag_suggestions",
    foreignKeys = [
        ForeignKey(
            entity = MediaItem::class,
            parentColumns = ["uri"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("asset_id")
    ]
)
data class TagSuggestion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "tag_key") val tagKey: String,
    @ColumnInfo(name = "score") val score: Double,
    @ColumnInfo(name = "source") val source: String = "generic_vocab",
    @ColumnInfo(name = "model_version") val modelVersion: String = "0",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
