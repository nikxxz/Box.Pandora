package com.example.boxpandora.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.boxpandora.data.local.dao.*
import com.example.boxpandora.data.local.entity.*
import com.example.boxpandora.data.local.entity.FaceClusterCorrection
import com.example.boxpandora.data.local.entity.FusedSceneSuggestion
import com.example.boxpandora.data.local.entity.ModelInferenceEvidence
import com.example.boxpandora.data.local.entity.PersonSuggestion
import com.example.boxpandora.data.local.entity.FaceScanLog
import com.example.boxpandora.data.local.entity.FusedFace
import com.example.boxpandora.data.local.entity.IdentityInferenceEvidence
import com.example.boxpandora.data.local.entity.FusedIdentitySuggestion
import com.example.boxpandora.data.local.entity.ModelReliabilityStats
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
        TagChangeHistory::class,
        // Ensemble pipeline (Phase 1) — scene models
        ModelInferenceEvidence::class,
        FusedSceneSuggestion::class,
        // Ensemble pipeline (Phase 2) — face models
        FusedFace::class,
        IdentityInferenceEvidence::class,
        FusedIdentitySuggestion::class,
        // Ensemble pipeline (Phase 3) — per-model reliability stats
        ModelReliabilityStats::class,
    ],
    version = 16,
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
    // Ensemble pipeline (Phase 1) — scene models
    abstract fun modelInferenceEvidenceDao(): ModelInferenceEvidenceDao
    abstract fun fusedSceneSuggestionDao(): FusedSceneSuggestionDao
    // Ensemble pipeline (Phase 2) — face models
    abstract fun fusedFaceDao(): FusedFaceDao
    abstract fun identityInferenceEvidenceDao(): IdentityInferenceEvidenceDao
    abstract fun fusedIdentitySuggestionDao(): FusedIdentitySuggestionDao
    // Ensemble pipeline (Phase 3) — per-model reliability stats
    abstract fun modelReliabilityStatsDao(): ModelReliabilityStatsDao

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

        /**
         * Schema v12 — Face cluster embedder provenance.
         *
         * Adds `embedder_version` to `face_clusters` so [PersonSuggestionWorker] can detect
         * stale centroids after a face embedding model upgrade.
         *
         * Default '' means "produced before v12 / embedder version unknown". Such rows are
         * skipped by [PersonSuggestionWorker] until [FaceClusterWorker] rebuilds them with
         * the current model version stamped in.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE face_clusters " +
                    "ADD COLUMN embedder_version TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * Schema v13 — Ensemble pipeline tables (Phase 1).
         *
         * Adds two new tables:
         *   1. model_inference_evidence — raw per-model scene tag evidence produced during
         *      ensemble runs. Internal pipeline record; not exposed to the UI directly.
         *   2. fused_scene_suggestions — final fused suggestion rows produced by
         *      SuggestionFusionEngine. The repository layer serves these to the UI when
         *      AiPipelineMode.ENSEMBLE_ALL_ENABLED is active.
         *
         * Existing tag_suggestions rows and all single-active pipeline data are unchanged.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // model_inference_evidence
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS model_inference_evidence (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        asset_id TEXT NOT NULL,
                        canonical_tag_key TEXT NOT NULL,
                        raw_score REAL NOT NULL,
                        model_id TEXT NOT NULL,
                        model_version TEXT NOT NULL,
                        score_type TEXT NOT NULL DEFAULT 'cosine_prototype',
                        pipeline_run_id TEXT NOT NULL,
                        inferred_at INTEGER NOT NULL,
                        FOREIGN KEY (asset_id) REFERENCES media_index(uri) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_model_inference_evidence_asset_id " +
                    "ON model_inference_evidence (asset_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_model_inference_evidence_pipeline_run_id " +
                    "ON model_inference_evidence (pipeline_run_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_model_inference_evidence_asset_model_tag " +
                    "ON model_inference_evidence (asset_id, model_id, canonical_tag_key)"
                )

                // fused_scene_suggestions
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS fused_scene_suggestions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        asset_id TEXT NOT NULL,
                        canonical_tag_key TEXT NOT NULL,
                        fused_score REAL NOT NULL,
                        contributing_model_ids TEXT NOT NULL,
                        contributing_model_count INTEGER NOT NULL,
                        strongest_score REAL NOT NULL,
                        status TEXT NOT NULL DEFAULT 'pending',
                        pipeline_run_id TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY (asset_id) REFERENCES media_index(uri) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_fused_scene_suggestions_asset_id " +
                    "ON fused_scene_suggestions (asset_id)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_fused_scene_suggestions_asset_tag " +
                    "ON fused_scene_suggestions (asset_id, canonical_tag_key)"
                )
            }
        }

        /**
         * Schema v14 — Ensemble face pipeline tables (Phase 2).
         *
         * Adds three new tables:
         *   1. fused_faces — merged face bounding boxes from multi-detector ensemble runs.
         *      Populated by [FaceEnsembleOrchestrator]; only written when
         *      AiPipelineMode.ENSEMBLE_ALL_ENABLED is active.
         *   2. identity_inference_evidence — raw per-recognizer identity similarity scores
         *      for each fused face. Internal record; not exposed to the UI directly.
         *   3. fused_identity_suggestions — final fused identity (person) suggestions for
         *      fused faces, surfaced to the UI in ensemble mode for user review.
         *
         * Existing detected_faces, face_embeddings, person_suggestions, and all single-active
         * pipeline data are unchanged.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // fused_faces
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS fused_faces (
                        fused_face_id TEXT PRIMARY KEY NOT NULL,
                        asset_id TEXT NOT NULL,
                        face_index INTEGER NOT NULL,
                        left_norm REAL NOT NULL,
                        top_norm REAL NOT NULL,
                        right_norm REAL NOT NULL,
                        bottom_norm REAL NOT NULL,
                        fused_confidence REAL NOT NULL,
                        strongest_confidence REAL NOT NULL,
                        contributing_detector_ids TEXT NOT NULL,
                        anchor_detector_id TEXT NOT NULL,
                        anchor_detector_version TEXT NOT NULL,
                        pipeline_run_id TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY (asset_id) REFERENCES media_index(uri) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_fused_faces_asset_id " +
                    "ON fused_faces (asset_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_fused_faces_asset_run " +
                    "ON fused_faces (asset_id, pipeline_run_id)"
                )

                // identity_inference_evidence
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS identity_inference_evidence (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        asset_id TEXT NOT NULL,
                        fused_face_id TEXT NOT NULL,
                        cluster_id TEXT NOT NULL,
                        tag_key TEXT NOT NULL,
                        tag_name TEXT NOT NULL,
                        tag_id INTEGER NOT NULL,
                        recognizer_id TEXT NOT NULL,
                        recognizer_version TEXT NOT NULL,
                        similarity_score REAL NOT NULL,
                        pipeline_run_id TEXT NOT NULL,
                        inferred_at INTEGER NOT NULL,
                        FOREIGN KEY (asset_id) REFERENCES media_index(uri) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_identity_evidence_asset_id " +
                    "ON identity_inference_evidence (asset_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_identity_evidence_fused_face_id " +
                    "ON identity_inference_evidence (fused_face_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_identity_evidence_face_recognizer_cluster " +
                    "ON identity_inference_evidence (fused_face_id, recognizer_id, cluster_id)"
                )

                // fused_identity_suggestions
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS fused_identity_suggestions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        asset_id TEXT NOT NULL,
                        fused_face_id TEXT NOT NULL,
                        cluster_id TEXT NOT NULL,
                        tag_key TEXT NOT NULL,
                        tag_name TEXT NOT NULL,
                        tag_id INTEGER NOT NULL,
                        fused_score REAL NOT NULL,
                        contributing_recognizer_ids TEXT NOT NULL,
                        contributing_recognizer_count INTEGER NOT NULL,
                        strongest_score REAL NOT NULL,
                        is_ambiguous INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL DEFAULT 'pending',
                        pipeline_run_id TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY (asset_id) REFERENCES media_index(uri) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_fused_identity_suggestions_asset_id " +
                    "ON fused_identity_suggestions (asset_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_fused_identity_suggestions_fused_face_id " +
                    "ON fused_identity_suggestions (fused_face_id)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_fused_identity_suggestions_face_cluster " +
                    "ON fused_identity_suggestions (fused_face_id, cluster_id)"
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

        /**
         * Schema v15 — Ensemble Phase 3: category-aware fusion + per-model reliability.
         *
         * Changes:
         *  1. fused_scene_suggestions — add tag_category (tag content category, default "misc")
         *     and is_ambiguous (1/0 flag, default 0).
         *  2. New table model_reliability_stats — persists per-model accept/reject feedback
         *     counts and a Bayesian-smoothed derived weight used during ensemble fusion.
         *     Keyed on (model_id, pipeline_category, tag_category); model_version is
         *     best-effort provenance only.
         *
         * Existing fused_scene_suggestions rows are valid; new columns default to safe values.
         * model_reliability_stats starts empty; weights default to 1.0 until feedback accrues.
         */
        /**
         * Schema v16 — Richer agreement level stored on fused suggestions.
         *
         * Adds [agreement_level] (INTEGER, ordinal of [AgreementLevel] enum) to both
         * fused suggestion tables. Computed at fusion time from model count, score spread,
         * ambiguity, and consensus ratio — a richer signal than model count alone.
         *
         * DEFAULT 0 = [AgreementLevel.LOW]. Existing rows are conservative: they will show
         * "1 model" agreement until they are regenerated by a rebuild action.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE fused_scene_suggestions " +
                    "ADD COLUMN agreement_level INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE fused_identity_suggestions " +
                    "ADD COLUMN agreement_level INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. Extend fused_scene_suggestions
                database.execSQL(
                    "ALTER TABLE fused_scene_suggestions " +
                    "ADD COLUMN tag_category TEXT NOT NULL DEFAULT 'misc'"
                )
                database.execSQL(
                    "ALTER TABLE fused_scene_suggestions " +
                    "ADD COLUMN is_ambiguous INTEGER NOT NULL DEFAULT 0"
                )

                // 2. model_reliability_stats
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS model_reliability_stats (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        model_id TEXT NOT NULL,
                        model_version TEXT NOT NULL DEFAULT '',
                        pipeline_category TEXT NOT NULL,
                        tag_category TEXT NOT NULL,
                        accepted_count INTEGER NOT NULL DEFAULT 0,
                        rejected_count INTEGER NOT NULL DEFAULT 0,
                        surfaced_count INTEGER NOT NULL DEFAULT 0,
                        last_updated_at INTEGER NOT NULL,
                        derived_weight REAL NOT NULL DEFAULT 1.0,
                        is_cooling_down INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS
                    index_model_reliability_stats_model_pipeline_tag
                    ON model_reliability_stats (model_id, pipeline_category, tag_category)
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_model_reliability_stats_model_id " +
                    "ON model_reliability_stats (model_id)"
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
