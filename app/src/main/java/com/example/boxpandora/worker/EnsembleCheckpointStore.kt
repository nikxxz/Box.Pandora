package com.example.boxpandora.worker

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

private const val TAG        = "EnsembleCheckpointStore"
private const val PREFS_NAME = "pandora_ensemble_checkpoints"

/**
 * SharedPreferences-backed store for [EnsembleCheckpoint] state.
 *
 * Each [EnsembleRunType] has its own namespace so multiple checkpoint types can coexist.
 * Checkpoints are lightweight (< 1 KB per type) and can survive process death, WorkManager
 * retries, and app restarts.
 *
 * ### Resume guard
 * Workers must call [isValid] after loading a checkpoint to confirm that the participating
 * model set and execution mode are still consistent with the current configuration.
 * An invalid checkpoint must be cleared before starting a fresh run.
 *
 * ### Thread safety
 * All writes use `apply()` (fire-and-forget). Reads are safe from any thread.
 * Not safe for concurrent writes from multiple threads to the same [EnsembleRunType].
 */
class EnsembleCheckpointStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Key helpers ───────────────────────────────────────────────────────────

    private fun k(type: EnsembleRunType, field: String) = "${type.key}_$field"

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Persists [checkpoint] atomically.
     *
     * Call this at every batch boundary inside ensemble workers — after the batch has been
     * committed to the DB but before the in-memory counters advance to the next batch.
     */
    fun save(checkpoint: EnsembleCheckpoint) {
        prefs.edit()
            .putString( k(checkpoint.runType, "last_id"),        checkpoint.lastProcessedId ?: "")
            .putInt(    k(checkpoint.runType, "offset"),         checkpoint.offset)
            .putInt(    k(checkpoint.runType, "processed"),      checkpoint.processedCount)
            .putInt(    k(checkpoint.runType, "suggestions"),    checkpoint.suggestionsWritten)
            .putInt(    k(checkpoint.runType, "total"),          checkpoint.totalItems)
            .putLong(   k(checkpoint.runType, "started_at"),     checkpoint.startedAt)
            .putString( k(checkpoint.runType, "mode"),           checkpoint.executionMode)
            .putString( k(checkpoint.runType, "models"),         checkpoint.participatingModelIds)
            .putInt(    k(checkpoint.runType, "batch"),          checkpoint.batchNumber)
            .putString( k(checkpoint.runType, "pause_reason"),   checkpoint.pauseReason ?: "")
            .apply()
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Returns the last saved checkpoint for [runType], or null if none exists.
     *
     * A null return means the run type has no checkpoint and should start from the beginning.
     */
    fun load(runType: EnsembleRunType): EnsembleCheckpoint? {
        val startedAt = prefs.getLong(k(runType, "started_at"), 0L)
        if (startedAt == 0L) return null     // no checkpoint written yet

        return EnsembleCheckpoint(
            runType              = runType,
            lastProcessedId      = prefs.getString(k(runType, "last_id"), "")
                                       ?.takeIf { it.isNotEmpty() },
            offset               = prefs.getInt(k(runType, "offset"), 0),
            processedCount       = prefs.getInt(k(runType, "processed"), 0),
            suggestionsWritten   = prefs.getInt(k(runType, "suggestions"), 0),
            totalItems           = prefs.getInt(k(runType, "total"), 0),
            startedAt            = startedAt,
            executionMode        = prefs.getString(k(runType, "mode"), "")      ?: "",
            participatingModelIds= prefs.getString(k(runType, "models"), "")    ?: "",
            batchNumber          = prefs.getInt(k(runType, "batch"), 0),
            pauseReason          = prefs.getString(k(runType, "pause_reason"), "")
                                       ?.takeIf { it.isNotEmpty() },
        )
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * Returns true when [checkpoint] is still valid for the current run configuration.
     *
     * A checkpoint is **invalid** when:
     *  - [currentExecutionMode] differs from [EnsembleCheckpoint.executionMode]
     *  - [currentSortedModelIds] differs from [EnsembleCheckpoint.participatingModelIds]
     *
     * Workers must treat an invalid checkpoint as non-existent and call [clear] before
     * starting from offset 0. This prevents stale state from being replayed after the
     * model set or fusion logic has changed.
     *
     * @param checkpoint            The checkpoint loaded from [load].
     * @param currentExecutionMode  The pipeline mode string for the current run.
     * @param currentSortedModelIds Sorted comma-separated model IDs for the current run —
     *                              must use the same sort order as when [save] was called.
     */
    fun isValid(
        checkpoint: EnsembleCheckpoint,
        currentExecutionMode: String,
        currentSortedModelIds: String,
    ): Boolean {
        if (checkpoint.executionMode != currentExecutionMode) {
            Log.i(TAG, "[${checkpoint.runType.key}] checkpoint invalid: " +
                "mode changed ${checkpoint.executionMode} → $currentExecutionMode")
            return false
        }
        if (checkpoint.participatingModelIds != currentSortedModelIds) {
            Log.i(TAG, "[${checkpoint.runType.key}] checkpoint invalid: " +
                "model set changed '${checkpoint.participatingModelIds}' → '$currentSortedModelIds'")
            return false
        }
        return true
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Removes all persisted keys for [runType].
     *
     * Call this when a run completes successfully or when a stale checkpoint is detected.
     */
    fun clear(runType: EnsembleRunType) {
        prefs.edit()
            .remove(k(runType, "last_id"))
            .remove(k(runType, "offset"))
            .remove(k(runType, "processed"))
            .remove(k(runType, "suggestions"))
            .remove(k(runType, "total"))
            .remove(k(runType, "started_at"))
            .remove(k(runType, "mode"))
            .remove(k(runType, "models"))
            .remove(k(runType, "batch"))
            .remove(k(runType, "pause_reason"))
            .apply()
        Log.d(TAG, "[${runType.key}] checkpoint cleared")
    }

    /** Clears checkpoints for all run types. */
    fun clearAll() {
        EnsembleRunType.entries.forEach { clear(it) }
        Log.d(TAG, "All ensemble checkpoints cleared")
    }
}
