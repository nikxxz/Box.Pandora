package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.boxpandora.data.local.entity.DetectedFace
import com.example.boxpandora.data.local.entity.FaceEmbedding

@Dao
interface FaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFace(face: DetectedFace)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFaces(faces: List<DetectedFace>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbedding(embedding: FaceEmbedding)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbeddings(embeddings: List<FaceEmbedding>)

    @Query("SELECT * FROM detected_faces WHERE asset_id = :assetId")
    suspend fun getFacesForAsset(assetId: String): List<DetectedFace>

    @Query("SELECT * FROM face_embeddings WHERE face_id = :faceId")
    suspend fun getEmbeddingForFace(faceId: String): FaceEmbedding?

    @Query("DELETE FROM detected_faces WHERE asset_id = :assetId")
    suspend fun deleteForAsset(assetId: String)

    @Query("DELETE FROM detected_faces WHERE asset_id IN (:assetIds)")
    suspend fun deleteForAssets(assetIds: List<String>)

    /**
     * Returns URIs of still-image assets (JPEG, PNG, WEBP, etc.) not yet scanned by
     * [detectorVersion]. GIFs are excluded — face detection on animated GIFs requires
     * frame-sampling infrastructure gated by [AiFeatureFlags.FACE_DETECTION_ON_GIF].
     *
     * Uses face_scan_log so that zero-face images are skipped on subsequent runs.
     */
    /**
     * Returns URIs of still-image assets (JPEG, PNG, WEBP, etc.) not yet processed by
     * [detectorVersion] — or processed with [FaceScanLog.RESULT_FAILED], which leaves the
     * item eligible for retry.
     *
     * Items with [FaceScanLog.RESULT_FACES_FOUND] or [FaceScanLog.RESULT_NO_FACES_FOUND] are
     * excluded; they were successfully processed and do not need to be re-run.
     */
    @Query("""
        SELECT m.uri FROM media_index m
        WHERE m.media_type = 'image'
        AND LOWER(m.extension) != 'gif'
        AND NOT EXISTS (
            SELECT 1 FROM face_scan_log sl
            WHERE sl.asset_id = m.uri
              AND sl.detector_version = :detectorVersion
              AND sl.result_status != 'failed'
        )
        ORDER BY m.device_created_at DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getUnprocessedImageUris(
        detectorVersion: String,
        limit: Int,
        offset: Int
    ): List<String>

    /**
     * Count of still (non-GIF) images not yet processed (or processed with [FaceScanLog.RESULT_FAILED])
     * by [detectorVersion]. Drives the initial progress total in [FaceIndexWorker].
     */
    @Query("""
        SELECT COUNT(*) FROM media_index m
        WHERE m.media_type = 'image'
        AND LOWER(m.extension) != 'gif'
        AND NOT EXISTS (
            SELECT 1 FROM face_scan_log sl
            WHERE sl.asset_id = m.uri
              AND sl.detector_version = :detectorVersion
              AND sl.result_status != 'failed'
        )
    """)
    suspend fun countUnprocessed(detectorVersion: String): Int

    /**
     * Count of animated GIF assets not yet scanned by [detectorVersion] (or previously failed).
     * Used by [FaceIndexWorker] to report how many GIFs are pending when the
     * [AiFeatureFlags.FACE_DETECTION_ON_GIF] flag is off.
     */
    @Query("""
        SELECT COUNT(*) FROM media_index m
        WHERE m.media_type = 'image'
        AND LOWER(m.extension) = 'gif'
        AND NOT EXISTS (
            SELECT 1 FROM face_scan_log sl
            WHERE sl.asset_id = m.uri
              AND sl.detector_version = :detectorVersion
              AND sl.result_status != 'failed'
        )
    """)
    suspend fun countUnprocessedGifs(detectorVersion: String): Int

    /**
     * Count of video assets not yet scanned by [detectorVersion] (or previously failed).
     * Used by [FaceIndexWorker] to report how many videos are pending when the
     * [AiSettings.faceDetectionInVideos] experimental flag is off.
     */
    @Query("""
        SELECT COUNT(*) FROM media_index m
        WHERE m.media_type = 'video'
        AND NOT EXISTS (
            SELECT 1 FROM face_scan_log sl
            WHERE sl.asset_id = m.uri
              AND sl.detector_version = :detectorVersion
              AND sl.result_status != 'failed'
        )
    """)
    suspend fun countUnprocessedVideos(detectorVersion: String): Int

    // ── Album-scoped queries (for folder scan) ────────────────────────────────

    /** Count of still (non-GIF) images in [albumId] not yet processed by [detectorVersion]. */
    @Query("""
        SELECT COUNT(*) FROM media_index m
        WHERE m.media_type = 'image'
        AND LOWER(m.extension) != 'gif'
        AND m.album_id = :albumId
        AND NOT EXISTS (
            SELECT 1 FROM face_scan_log sl
            WHERE sl.asset_id = m.uri
              AND sl.detector_version = :detectorVersion
              AND sl.result_status != 'failed'
        )
    """)
    suspend fun countUnprocessedByAlbum(albumId: Long, detectorVersion: String): Int

    /** Unprocessed still image URIs in [albumId] for [detectorVersion], paginated. */
    @Query("""
        SELECT m.uri FROM media_index m
        WHERE m.media_type = 'image'
        AND LOWER(m.extension) != 'gif'
        AND m.album_id = :albumId
        AND NOT EXISTS (
            SELECT 1 FROM face_scan_log sl
            WHERE sl.asset_id = m.uri
              AND sl.detector_version = :detectorVersion
              AND sl.result_status != 'failed'
        )
        ORDER BY m.device_created_at DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getUnprocessedImageUrisByAlbum(
        albumId: Long, detectorVersion: String, limit: Int, offset: Int
    ): List<String>

    /** Count of GIFs in [albumId] not yet processed by [detectorVersion]. */
    @Query("""
        SELECT COUNT(*) FROM media_index m
        WHERE m.media_type = 'image'
        AND LOWER(m.extension) = 'gif'
        AND m.album_id = :albumId
        AND NOT EXISTS (
            SELECT 1 FROM face_scan_log sl
            WHERE sl.asset_id = m.uri
              AND sl.detector_version = :detectorVersion
              AND sl.result_status != 'failed'
        )
    """)
    suspend fun countUnprocessedGifsByAlbum(albumId: Long, detectorVersion: String): Int

    /** Count of videos in [albumId] not yet processed by [detectorVersion]. */
    @Query("""
        SELECT COUNT(*) FROM media_index m
        WHERE m.media_type = 'video'
        AND m.album_id = :albumId
        AND NOT EXISTS (
            SELECT 1 FROM face_scan_log sl
            WHERE sl.asset_id = m.uri
              AND sl.detector_version = :detectorVersion
              AND sl.result_status != 'failed'
        )
    """)
    suspend fun countUnprocessedVideosByAlbum(albumId: Long, detectorVersion: String): Int

    /** Faces for an asset detected with a specific detector version. */
    @Query("SELECT * FROM detected_faces WHERE asset_id = :assetId AND detector_model_version = :detectorVersion")
    suspend fun getFacesForAssetAndDetector(assetId: String, detectorVersion: String): List<DetectedFace>

    /** Delete all detected faces. Used by the "Rebuild Face Index" action. */
    @Query("DELETE FROM detected_faces")
    suspend fun deleteAllFaces()

    /** Delete all face embeddings. Used by the "Rebuild Face Index" action. */
    @Query("DELETE FROM face_embeddings")
    suspend fun deleteAllFaceEmbeddings()

    // ── Clustering queries ────────────────────────────────────────────────────

    /**
     * Loads all face IDs and embeddings for a given embedder version, sorted by quality score
     * descending (higher quality faces are processed first in the clustering algorithm).
     *
     * Only returns faces that also have a valid DetectedFace row (inner join).
     */
    @Query("""
        SELECT fe.face_id, fe.embedding, df.quality_score
        FROM face_embeddings fe
        INNER JOIN detected_faces df ON df.face_id = fe.face_id
        WHERE fe.model_version = :embedderVersion
        ORDER BY df.quality_score DESC
    """)
    suspend fun getAllEmbeddingsForClustering(embedderVersion: String): List<FaceEmbeddingRow>

    /** Thin projection used only during clustering (avoids loading all DetectedFace columns). */
    data class FaceEmbeddingRow(
        val face_id: String,
        val embedding: ByteArray,
        val quality_score: Double
    )

    // ── Person profile training queries ───────────────────────────────────────

    /**
     * Returns face embeddings that are high-trust person training samples.
     *
     * Conditions that must all be true for a row to appear:
     *  1. The asset has exactly one face embedding for [embedderVersion] — the single-face
     *     constraint guarantees this face belongs to the person labelled on the asset.
     *  2. The asset has exactly one person-category tag — single-label prevents ambiguous
     *     multi-person images from polluting a profile.
     */
    @Query("""
        SELECT fe.face_id, fe.embedding, t.id AS tag_id, t.name AS tag_name, t.normalized_name AS tag_key
        FROM face_embeddings fe
        INNER JOIN detected_faces df ON df.face_id = fe.face_id
        INNER JOIN media_tags mt ON mt.media_uri = df.asset_id
        INNER JOIN tags t ON t.id = mt.tag_id AND t.category = 'person'
        WHERE fe.model_version = :embedderVersion
        AND (
            SELECT COUNT(*) FROM face_embeddings fe2
            INNER JOIN detected_faces df2 ON df2.face_id = fe2.face_id
            WHERE df2.asset_id = df.asset_id AND fe2.model_version = :embedderVersion
        ) = 1
        AND (
            SELECT COUNT(*) FROM media_tags mt2
            INNER JOIN tags t2 ON t2.id = mt2.tag_id
            WHERE mt2.media_uri = df.asset_id AND t2.category = 'person'
        ) = 1
    """)
    suspend fun getSingleFaceSinglePersonTagSamples(embedderVersion: String): List<PersonTrainingSample>

    /**
     * Returns face embeddings where the user explicitly assigned the face to a confirmed cluster
     * that carries a person-category tag. User corrections are the highest-trust signal.
     *
     * Only clusters with [FaceCluster.confirmedByUser] = 1 and a non-null [FaceCluster.tagId]
     * linked to a person-category [Tag] are included.
     */
    @Query("""
        SELECT fe.face_id, fe.embedding, t.id AS tag_id, t.name AS tag_name, t.normalized_name AS tag_key
        FROM face_embeddings fe
        INNER JOIN detected_faces df ON df.face_id = fe.face_id
        INNER JOIN face_cluster_corrections fcc ON fcc.face_id = fe.face_id AND fcc.action = 'assign'
        INNER JOIN face_clusters fc ON fc.cluster_id = fcc.to_cluster_id
            AND fc.confirmed_by_user = 1
            AND fc.tag_id IS NOT NULL
        INNER JOIN tags t ON t.id = fc.tag_id AND t.category = 'person'
        WHERE fe.model_version = :embedderVersion
    """)
    suspend fun getCorrectionDerivedPersonSamples(embedderVersion: String): List<PersonTrainingSample>

    /** Thin projection for person profile training samples. */
    data class PersonTrainingSample(
        val face_id: String,
        val embedding: ByteArray,
        val tag_id: Long,
        val tag_name: String,
        val tag_key: String
    )
}
