package com.example.boxpandora.worker

import android.content.Context
import android.content.SharedPreferences

private const val PREFS_NAME = "pandora_indexing_stats"

/**
 * Lightweight SharedPreferences-backed store for AI indexing run metrics.
 *
 * Records per-pipeline statistics after each worker run:
 *   - indexed media count (items successfully processed)
 *   - skipped media count (already indexed or filtered out)
 *   - inference failures (items that threw exceptions)
 *   - average processing time per indexed item (ms)
 *   - total worker run count
 *   - worker cancellation count
 *
 * Pipeline names are keyed by [PIPELINE_*] constants.
 * All writes are fire-and-forget (apply()).
 */
class IndexingStatsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Keys ──────────────────────────────────────────────────────────────────

    private fun key(pipeline: String, field: String) = "${pipeline}_$field"

    // ── Write ─────────────────────────────────────────────────────────────────

    fun recordRun(
        pipeline: String,
        indexedCount: Int,
        skippedCount: Int,
        inferenceFailures: Int,
        avgProcessingTimeMs: Long,
        wasCancelled: Boolean
    ) {
        val prevRunCount    = prefs.getInt(key(pipeline, "run_count"),    0)
        val prevCancelCount = prefs.getInt(key(pipeline, "cancel_count"), 0)
        prefs.edit()
            .putInt(key(pipeline,  "indexed"),      indexedCount)
            .putInt(key(pipeline,  "skipped"),      skippedCount)
            .putInt(key(pipeline,  "failures"),     inferenceFailures)
            .putLong(key(pipeline, "avg_ms"),       avgProcessingTimeMs)
            .putInt(key(pipeline,  "run_count"),    prevRunCount + 1)
            .putInt(key(pipeline,  "cancel_count"), prevCancelCount + if (wasCancelled) 1 else 0)
            .putLong(key(pipeline, "last_run_at"),  System.currentTimeMillis())
            .apply()
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    fun getStats(pipeline: String): IndexingRunStats = IndexingRunStats(
        pipeline            = pipeline,
        indexedCount        = prefs.getInt(key(pipeline,  "indexed"),      0),
        skippedCount        = prefs.getInt(key(pipeline,  "skipped"),      0),
        inferenceFailures   = prefs.getInt(key(pipeline,  "failures"),     0),
        avgProcessingTimeMs = prefs.getLong(key(pipeline, "avg_ms"),       0L),
        totalRunCount       = prefs.getInt(key(pipeline,  "run_count"),    0),
        cancellationCount   = prefs.getInt(key(pipeline,  "cancel_count"), 0),
        lastRunAt           = prefs.getLong(key(pipeline, "last_run_at"),  0L)
    )

    fun getAllStats(): List<IndexingRunStats> = ALL_PIPELINES.map { getStats(it) }

    companion object {
        const val PIPELINE_SCENE      = "scene"
        const val PIPELINE_FACE       = "face"
        const val PIPELINE_PROTOTYPE  = "prototype"
        const val PIPELINE_SUGGESTION = "suggestion"
        const val PIPELINE_CLUSTER    = "cluster"

        val ALL_PIPELINES = listOf(
            PIPELINE_SCENE, PIPELINE_FACE, PIPELINE_PROTOTYPE,
            PIPELINE_SUGGESTION, PIPELINE_CLUSTER
        )
    }
}

data class IndexingRunStats(
    val pipeline: String,
    val indexedCount: Int,
    val skippedCount: Int,
    val inferenceFailures: Int,
    val avgProcessingTimeMs: Long,
    val totalRunCount: Int,
    val cancellationCount: Int,
    val lastRunAt: Long
)
