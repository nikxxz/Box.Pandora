package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tags",
    indices = [
        Index("usage_count"),
        Index(value = ["normalized_name"], unique = true)
    ]
)
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String, // Display name (e.g., "Dogs")
    @ColumnInfo(name = "normalized_name") val normalizedName: String, // Lookup key (e.g., "dogs")
    @ColumnInfo(name = "color") val color: String = "#888888",
    @ColumnInfo(name = "icon") val icon: String? = null,
    @ColumnInfo(name = "category") val category: String = "misc",
    @ColumnInfo(name = "usage_count") val usageCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
    @ColumnInfo(name = "last_reviewed_at") val lastReviewedAt: Long? = null
)
