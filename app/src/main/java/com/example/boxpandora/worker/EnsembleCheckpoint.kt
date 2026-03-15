package com.example.boxpandora.worker

/**
 * Identifies which type of long-running ensemble operation produced a checkpoint.
 *
 * Used by [EnsembleCheckpointStore] to namespace each checkpoint so multiple run types
 * can coexist without key collisions.
 */
enum class EnsembleRunType(val key: String) {
    SCENE_SCAN("scene_scan"),
    FACE_SCAN("face_scan"),
    FUSED_REBUILD("fused_rebuild"),
    RELIABILITY_RECOMPUTE("reliability_recompute"),
}

/**
 * Snapshot of a resumable ensemble run captured at each batch boundary.
 *
 * All fields are written atomically via [EnsembleCheckpointStore.save] and read back
 * via [EnsembleCheckpointStore.load]. Workers load this at startup; if the participating
 * model set or execution mode has changed since the checkpoint was written, the worker
 * must call [EnsembleCheckpointStore.clear] and start from scratch.
 *
 * @param runType            The category of work being checkpointed.
 * @param lastProcessedId    The URI/ID of the last media item successfully processed,
 *                           or null if no items have been processed yet in this run.
 * @param offset             The DB cursor offset that should be used for the next batch.
 * @param processedCount     Number of items already processed in this run.
 * @param suggestionsWritten Number of suggestions written so far.
 * @param totalItems         Total item count at the time the run started (used for
 *                           progress reporting; may be stale if media was added/removed).
 * @param startedAt          Epoch ms when this run was first started (not resumed).
 * @param executionMode      The [com.example.boxpandora.ml.config.AiPipelineMode] name
 *                           at checkpoint time — used to detect stale state.
 * @param participatingModelIds  Sorted comma-separated model IDs that were participating
 *                           when the checkpoint was written. If this set differs on
 *                           resume the checkpoint is invalid.
 * @param batchNumber        How many DB batches have been fetched so far.
 * @param pauseReason        Non-null when the run was paused by the policy (e.g. "thermal_hot").
 */
data class EnsembleCheckpoint(
    val runType: EnsembleRunType,
    val lastProcessedId: String?,
    val offset: Int,
    val processedCount: Int,
    val suggestionsWritten: Int,
    val totalItems: Int,
    val startedAt: Long,
    val executionMode: String,
    val participatingModelIds: String,
    val batchNumber: Int,
    val pauseReason: String?,
)
