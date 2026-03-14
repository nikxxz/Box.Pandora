package com.example.boxpandora.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.boxpandora.data.local.dao.*
import com.example.boxpandora.data.local.entity.*
import com.example.boxpandora.data.local.entity.FaceClusterCorrection
import com.example.boxpandora.data.local.entity.PersonSuggestion
import com.example.boxpandora.data.local.entity.FaceScanLog
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
        FaceClusterCorrection::class,
        PersonSuggestion::class,
        FaceScanLog::class,
        TagCooccurrence::class,
        TagAlias::class,
        TagReviewQueue::class,
        TagChangeHistory::class
    ],
    version = 11,
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
    abstract fun faceClusterDao(): FaceClusterDao
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

        /**
         * Schema v7 — Face pipeline provenance.
         *
         * Adds `detector_model_version` to `detected_faces` so FaceIndexWorker can:
         *   1. Skip assets already processed with the current detector version.
         *   2. Identify stale rows when the detector model is upgraded.
         *
         * Default '' means "produced before v7 / detector version unknown". Since no face
         * processing has run before this migration, all existing rows (if any) are effectively
         * invalid and can be cleared by re-running FaceIndexWorker.
         *
         * Known Phase 4 limitation: images where zero faces are detected have no rows in this
         * table, so they are re-processed on every worker run. A face_scan_log table (Phase 5)
         * will track per-asset scan completion independently of face count.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE detected_faces " +
                    "ADD COLUMN detector_model_version TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * Schema v8 — Phase 5: face clustering, scan log, person suggestions.
         *
         * Changes:
         *  1. detected_faces — add cluster_id (FK into face_clusters); add index.
         *  2. face_clusters  — add name, confirmed_by_user, is_hidden columns.
         *  3. New table face_cluster_corrections — reversible user edits to clustering.
         *  4. New table person_suggestions — unconfirmed face→cluster matches for review.
         *  5. New table face_scan_log — per-asset scan sentinel (closes Phase 4 zero-face gap).
         *
         * face_scan_log replaces the detected_faces-based "was this scanned?" check in
         * FaceDao.getUnprocessedImageUris so that zero-face images are no longer re-processed.
         */
        /**
         * Schema v9 — Scene embedding provenance.
         *
         * Adds two columns to image_embeddings:
         *   media_type  — the type of the source asset ("image", "gif", "video").
         *   scan_method — optional frame-sampling descriptor; NULL for still images.
         *
         * Existing rows default to media_type = 'image' and scan_method = NULL, which is
         * correct since only image-type assets were indexed before v9.
         */
        /**
         * Schema v10 — Face embedding provenance.
         *
         * Adds model_id to face_embeddings so each row records both the manifest identifier
         * and the Room version key of the ArcFace model that produced it.
         *
         * Existing rows default to '' (unknown). Since no face embeddings were written before
         * v10 in a production install, this default has no practical effect.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE face_embeddings ADD COLUMN model_id TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * Schema v11 — Face scan-log result status.
         *
         * Adds `result_status` to `face_scan_log` so the pipeline can distinguish between:
         *   - "faces_found"     — detection ran; at least one face was stored.
         *   - "no_faces_found"  — detection ran; zero faces found (image truly has no faces).
         *   - "failed"          — processing threw an exception; item stays eligible for retry.
         *   - "skipped"         — reserved for future GIF/video gating.
         *
         * Existing rows (written before v11 with no status) default to "no_faces_found", which
         * is the safe choice: those rows were only written for successfully completed scans,
         * and most had zero faces detected (images with faces were tracked via detected_faces).
         *
         * [FaceDao.getUnprocessedImageUris] is updated to use result_status so that
         * "failed" items are always retried and "faces_found"/"no_faces_found" items are not.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE face_scan_log " +
                    "ADD COLUMN result_status TEXT NOT NULL DEFAULT 'no_faces_found'"
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE image_embeddings ADD COLUMN media_type TEXT NOT NULL DEFAULT 'image'"
                )
                database.execSQL(
                    "ALTER TABLE image_embeddings ADD COLUMN scan_method TEXT"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. detected_faces — cluster_id column + index
                database.execSQL(
                    "ALTER TABLE detected_faces ADD COLUMN cluster_id TEXT"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_detected_faces_cluster_id " +
                    "ON detected_faces (cluster_id)"
                )

                // 2. face_clusters — new metadata columns
                database.execSQL(
                    "ALTER TABLE face_clusters ADD COLUMN name TEXT"
                )
                database.execSQL(
                    "ALTER TABLE face_clusters ADD COLUMN confirmed_by_user INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE face_clusters ADD COLUMN is_hidden INTEGER NOT NULL DEFAULT 0"
                )

                // 3. face_cluster_corrections
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS face_cluster_corrections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        face_id TEXT NOT NULL,
                        action TEXT NOT NULL,
                        to_cluster_id TEXT,
                        from_cluster_id TEXT,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY (face_id) REFERENCES detected_faces(face_id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_face_cluster_corrections_face_id " +
                    "ON face_cluster_corrections (face_id)"
                )

                // 4. person_suggestions
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS person_suggestions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        face_id TEXT NOT NULL,
                        cluster_id TEXT NOT NULL,
                        similarity REAL NOT NULL,
                        status TEXT NOT NULL DEFAULT 'pending',
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY (face_id) REFERENCES detected_faces(face_id) ON DELETE CASCADE,
                        FOREIGN KEY (cluster_id) REFERENCES face_clusters(cluster_id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_person_suggestions_face_id " +
                    "ON person_suggestions (face_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_person_suggestions_cluster_id " +
                    "ON person_suggestions (cluster_id)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_person_suggestions_face_cluster " +
                    "ON person_suggestions (face_id, cluster_id)"
                )

                // 5. face_scan_log
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS face_scan_log (
                        asset_id TEXT NOT NULL,
                        detector_version TEXT NOT NULL,
                        scanned_at INTEGER NOT NULL,
                        PRIMARY KEY (asset_id, detector_version),
                        FOREIGN KEY (asset_id) REFERENCES media_index(uri) ON DELETE CASCADE
                    )
                """.trimIndent())
            }
        }
    }
}
