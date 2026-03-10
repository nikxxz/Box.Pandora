package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "tag_cooccurrences",
    primaryKeys = ["tag_id_a", "tag_id_b"],
    foreignKeys = [
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tag_id_a"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tag_id_b"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("tag_id_a"),
        Index("tag_id_b")
    ]
)
data class TagCooccurrence(
    @ColumnInfo(name = "tag_id_a") val tagIdA: Long,
    @ColumnInfo(name = "tag_id_b") val tagIdB: Long,
    @ColumnInfo(name = "count") val count: Int = 1,
    @ColumnInfo(name = "last_seen") val lastSeen: Long = System.currentTimeMillis()
)
