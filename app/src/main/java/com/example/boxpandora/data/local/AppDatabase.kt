package com.example.boxpandora.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun albumDao(): AlbumDao
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun tagDao(): TagDao
    abstract fun mediaTagDao(): MediaTagDao
    abstract fun tagAliasDao(): TagAliasDao
    abstract fun tagReviewQueueDao(): TagReviewQueueDao
    abstract fun tagChangeHistoryDao(): TagChangeHistoryDao
    abstract fun tagCooccurrenceDao(): TagCooccurrenceDao
    abstract fun tagPrototypeDao(): TagPrototypeDao
    abstract fun imageEmbeddingDao(): ImageEmbeddingDao
    abstract fun faceDao(): FaceDao
    abstract fun tagSuggestionDao(): TagSuggestionDao
    abstract fun heuristicTagDao(): HeuristicTagDao
    abstract fun tagRejectionDao(): TagRejectionDao
    abstract fun scanLogDao(): ScanLogDao
    abstract fun userPreferenceDao(): UserPreferenceDao

    companion object {
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // tag_rejections PK is (tag_key, asset_id) so WHERE asset_id=? was a full
                // table scan. Add a dedicated index so getForAsset() is O(log n).
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tag_rejections_asset_id " +
                    "ON tag_rejections (asset_id)"
                )
            }
        }
    }
}
