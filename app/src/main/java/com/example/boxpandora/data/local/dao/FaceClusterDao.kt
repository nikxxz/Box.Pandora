package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.boxpandora.data.local.entity.FaceCluster
import com.example.boxpandora.data.local.entity.FaceClusterCorrection
import com.example.boxpandora.data.local.entity.FaceScanLog
import com.example.boxpandora.data.local.entity.PersonSuggestion
import kotlinx.coroutines.flow.Flow

@Dao
interface FaceClusterDao {

    // ── FaceCluster ───────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCluster(cluster: FaceCluster)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClusters(clusters: List<FaceCluster>)

    @Update
    suspend fun updateCluster(cluster: FaceCluster)

    @Query("SELECT * FROM face_clusters WHERE cluster_id = :clusterId")
    suspend fun getCluster(clusterId: String): FaceCluster?

    @Query("SELECT * FROM face_clusters WHERE is_hidden = 0 ORDER BY n DESC, updated_at DESC")
    fun observeVisibleClusters(): Flow<List<FaceCluster>>

    @Query("SELECT * FROM face_clusters ORDER BY n DESC, updated_at DESC")
    suspend fun getAllClusters(): List<FaceCluster>

    @Query("SELECT * FROM face_clusters WHERE confirmed_by_user = 1 AND is_hidden = 0")
    suspend fun getConfirmedClusters(): List<FaceCluster>

    /** Reset face count to 0 on all clusters before a full clustering rebuild. */
    @Query("UPDATE face_clusters SET n = 0, updated_at = :now")
    suspend fun resetAllCounts(now: Long = System.currentTimeMillis())

    /** Update centroid and face count after re-assigning faces to this cluster. */
    @Query("""
        UPDATE face_clusters
        SET centroid_blob = :centroid, dim = :dim, n = :n, updated_at = :now
        WHERE cluster_id = :clusterId
    """)
    suspend fun updateCentroid(
        clusterId: String,
        centroid: ByteArray,
        dim: Int,
        n: Int,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE face_clusters SET name = :name, confirmed_by_user = 1, updated_at = :now WHERE cluster_id = :clusterId")
    suspend fun nameAndConfirmCluster(
        clusterId: String,
        name: String,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE face_clusters SET is_hidden = :hidden, updated_at = :now WHERE cluster_id = :clusterId")
    suspend fun setHidden(
        clusterId: String,
        hidden: Boolean,
        now: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM face_clusters WHERE cluster_id = :clusterId")
    suspend fun deleteCluster(clusterId: String)

    /** Remove clusters that have zero faces and were never confirmed by the user. */
    @Query("DELETE FROM face_clusters WHERE n = 0 AND confirmed_by_user = 0")
    suspend fun pruneOrphanClusters()

    // ── cluster_id assignment on detected_faces ───────────────────────────────

    @Query("UPDATE detected_faces SET cluster_id = :clusterId WHERE face_id = :faceId")
    suspend fun assignFaceToCluster(faceId: String, clusterId: String)

    @Query("UPDATE detected_faces SET cluster_id = :clusterId WHERE face_id IN (:faceIds)")
    suspend fun assignFacesToCluster(faceIds: List<String>, clusterId: String)

    /** Clear all cluster assignments before a full rebuild. */
    @Query("UPDATE detected_faces SET cluster_id = NULL")
    suspend fun clearAllClusterAssignments()

    /** First face (by quality desc) in a cluster — used as the grid thumbnail. */
    @Query("""
        SELECT df.asset_id FROM detected_faces df
        WHERE df.cluster_id = :clusterId
        ORDER BY df.quality_score DESC
        LIMIT 1
    """)
    suspend fun getRepresentativeAssetId(clusterId: String): String?

    @Query("SELECT * FROM detected_faces WHERE cluster_id = :clusterId ORDER BY quality_score DESC")
    suspend fun getFacesInCluster(clusterId: String): List<com.example.boxpandora.data.local.entity.DetectedFace>

    // ── FaceClusterCorrection ─────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCorrection(correction: FaceClusterCorrection)

    /** All corrections keyed by faceId for O(1) lookup during clustering. */
    @Query("SELECT * FROM face_cluster_corrections")
    suspend fun getAllCorrections(): List<FaceClusterCorrection>

    @Query("SELECT * FROM face_cluster_corrections WHERE face_id = :faceId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestCorrectionForFace(faceId: String): FaceClusterCorrection?

    @Query("DELETE FROM face_cluster_corrections WHERE face_id = :faceId")
    suspend fun deleteCorrectionForFace(faceId: String)

    /** Delete all face clusters. Used by the "Rebuild Face Clusters" action. */
    @Query("DELETE FROM face_clusters")
    suspend fun deleteAllClusters()

    /** Delete all face scan log entries. Forces FaceIndexWorker to re-scan all images on next run. */
    @Query("DELETE FROM face_scan_log")
    suspend fun deleteAllFaceScanLogs()

    /**
     * Deletes only scan log entries whose result was a processing failure.
     * Items that were successfully scanned (faces_found / no_faces_found) are left in place
     * so they are not unnecessarily re-processed.
     *
     * Used by the "Repair Stale AI Data" action to force retry of previously-failed assets
     * without discarding results for successfully-completed ones.
     */
    @Query("DELETE FROM face_scan_log WHERE result_status = 'failed'")
    suspend fun deleteFailedScanLogs()

    /**
     * Returns the cluster linked to [tagId], or null if none exists.
     * Used by [PersonProfileEngine] to find the existing cluster for a person tag so its
     * non-centroid metadata (is_hidden, created_at) can be preserved across profile rebuilds.
     */
    @Query("SELECT * FROM face_clusters WHERE tag_id = :tagId LIMIT 1")
    suspend fun getClusterForTag(tagId: Long): FaceCluster?

    // ── PersonSuggestion ──────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSuggestion(suggestion: PersonSuggestion)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSuggestions(suggestions: List<PersonSuggestion>)

    @Query("SELECT * FROM person_suggestions WHERE status = 'pending' ORDER BY similarity DESC")
    fun observePendingSuggestions(): Flow<List<PersonSuggestion>>

    @Query("SELECT * FROM person_suggestions WHERE cluster_id = :clusterId AND status = 'pending' ORDER BY similarity DESC")
    suspend fun getPendingSuggestionsForCluster(clusterId: String): List<PersonSuggestion>

    @Query("UPDATE person_suggestions SET status = :status WHERE id = :id")
    suspend fun updateSuggestionStatus(id: Long, status: String)

    @Query("DELETE FROM person_suggestions WHERE face_id = :faceId")
    suspend fun deleteSuggestionsForFace(faceId: String)

    @Query("DELETE FROM person_suggestions WHERE cluster_id = :clusterId")
    suspend fun deleteSuggestionsForCluster(clusterId: String)

    /** Delete all person suggestion rows. Used when clusters are fully rebuilt. */
    @Query("DELETE FROM person_suggestions")
    suspend fun deleteAllPersonSuggestions()

    // ── FaceScanLog ───────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScanLog(log: FaceScanLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScanLogs(logs: List<FaceScanLog>)

    @Query("SELECT COUNT(*) FROM face_scan_log WHERE asset_id = :assetId AND detector_version = :detectorVersion")
    suspend fun isScanned(assetId: String, detectorVersion: String): Int
}
