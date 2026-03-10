package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tag_aliases",
    foreignKeys = [
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("tag_id"),
        Index(value = ["tag_id", "alias"], unique = true)
    ]
)
data class TagAlias(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "tag_id") val tagId: Long,
    @ColumnInfo(name = "alias") val alias: String, // lowercase search term
    @ColumnInfo(name = "source") val source: String = "ai", // 'ai' | 'user'
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
