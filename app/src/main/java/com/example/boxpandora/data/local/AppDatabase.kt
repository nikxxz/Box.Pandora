package com.example.boxpandora.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.boxpandora.data.local.dao.*
import com.example.boxpandora.data.local.entity.*
import com.example.boxpandora.data.local.util.Converters

@Database(
    entities = [
        Album::class,
        MediaItem::class,
        Tag::class,
        MediaTag::class,
        ScanLog::class,
        UserPreference::class,
        TagSuggestion::class,
        TagPrototype::class,
        ImageEmbedding::class,
        HeuristicTag::class,
        TagRejection::class,
        DetectedFace::class,
        FaceEmbedding::class,
        FaceCluster::class,
        TagCooccurrence::class,
        TagAlias::class,
        TagReviewQueue::class,
        TagChangeHistory::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun albumDao(): AlbumDao
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun tagDao(): TagDao
    abstract fun scanLogDao(): ScanLogDao
    abstract fun userPreferenceDao(): UserPreferenceDao
}
