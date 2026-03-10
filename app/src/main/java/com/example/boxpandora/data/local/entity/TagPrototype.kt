package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tag_prototypes")
data class TagPrototype(
    @PrimaryKey @ColumnInfo(name = "tag_key") val tagKey: String,
    @ColumnInfo(name = "prototype_blob") val prototypeBlob: ByteArray,
    @ColumnInfo(name = "dim") val dim: Int = 512,
    @ColumnInfo(name = "n") val n: Int = 0,
    @ColumnInfo(name = "model_version") val modelVersion: String = "0",
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
