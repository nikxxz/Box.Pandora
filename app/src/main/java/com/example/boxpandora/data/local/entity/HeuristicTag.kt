package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "heuristic_tags",
    primaryKeys = ["asset_id", "tag_key"],
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
data class HeuristicTag(
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "tag_key") val tagKey: String,
    @ColumnInfo(name = "score") val score: Double,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
