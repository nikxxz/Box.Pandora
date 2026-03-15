package com.example.boxpandora.ml.ensemble

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

private const val TAG = "EnsembleRunManifestStore"
private const val PREFS_NAME = "pandora_ensemble_run_manifests"
private const val KEY_LATEST_RUN_ID = "latest_run_id"
private const val KEY_RUN_IDS_LIST  = "run_ids"

/** Maximum number of manifests retained in the rolling history. */
private const val MAX_STORED_MANIFESTS = 5

private fun key(runId: String, field: String) = "${runId}_$field"

/**
 * SharedPreferences-backed store for [EnsembleRunManifest] records.
 *
 * Retains the last [MAX_STORED_MANIFESTS] manifests (rolling window — oldest are evicted on
 * [save] when the limit is exceeded). Provides [isCompatible] for callers that want to verify
 * whether cached fused outputs were produced under the current pipeline configuration.
 */
class EnsembleRunManifestStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Persists [manifest] and marks it as the latest run. Evicts entries beyond
     * [MAX_STORED_MANIFESTS] from the rolling history.
     */
    fun save(manifest: EnsembleRunManifest) {
        val runId = manifest.runId
        val edit  = prefs.edit()

        edit.putString(key(runId, "pipeline_mode"),       manifest.pipelineMode)
        edit.putString(key(runId, "model_ids"),           manifest.participatingModelIds)
        edit.putString(key(runId, "model_versions"),      manifest.modelVersions)
        edit.putString(key(runId, "calibration_version"), manifest.calibrationVersion)
        edit.putLong  (key(runId, "reliability_ts"),      manifest.reliabilityStatsTimestamp)
        edit.putLong  (key(runId, "started_at"),          manifest.runStartedAt)
        edit.putLong  (key(runId, "completed_at"),        manifest.runCompletedAt)
        edit.putInt   (key(runId, "assets_processed"),    manifest.assetsProcessed)
        edit.putInt   (key(runId, "suggestions_written"), manifest.suggestionsWritten)

        // Update rolling index
        val ids = loadIdList().toMutableList()
        if (runId !in ids) {
            ids.add(0, runId)
            if (ids.size > MAX_STORED_MANIFESTS) {
                val evicted = ids.drop(MAX_STORED_MANIFESTS)
                evicted.forEach { old -> evictFields(edit, old) }
                ids.subList(MAX_STORED_MANIFESTS, ids.size).clear()
            }
        }
        edit.putString(KEY_RUN_IDS_LIST, ids.joinToString(","))
        edit.putString(KEY_LATEST_RUN_ID, runId)
        edit.apply()
    }

    /**
     * Stamps completion metadata onto an in-progress manifest. No-op if [runId] is unknown.
     */
    fun complete(
        runId: String,
        completedAt: Long,
        assetsProcessed: Int,
        suggestionsWritten: Int,
    ) {
        val existing = load(runId) ?: run {
            Log.w(TAG, "complete() called for unknown run $runId — ignoring")
            return
        }
        save(
            existing.copy(
                runCompletedAt     = completedAt,
                assetsProcessed    = assetsProcessed,
                suggestionsWritten = suggestionsWritten,
            )
        )
        Log.d(TAG, "Run $runId completed: $assetsProcessed assets, $suggestionsWritten suggestions")
    }

    /** Returns the most recently saved manifest, or null if none exists. */
    fun getLatest(): EnsembleRunManifest? {
        val id = prefs.getString(KEY_LATEST_RUN_ID, null) ?: return null
        return load(id)
    }

    /** Returns all stored manifests, newest first. */
    fun getAll(): List<EnsembleRunManifest> = loadIdList().mapNotNull { load(it) }

    /**
     * Returns true when [manifest] was produced under the same pipeline mode, model set, and
     * calibration version as the supplied current-state arguments.
     *
     * Callers (e.g. the repository layer) can use this to avoid surfacing results from a
     * prior run that predates a significant configuration change.
     */
    fun isCompatible(
        manifest: EnsembleRunManifest,
        currentPipelineMode: String,
        currentModelIds: String,
        currentCalibrationVersion: String,
    ): Boolean =
        manifest.pipelineMode == currentPipelineMode &&
        manifest.participatingModelIds == currentModelIds &&
        manifest.calibrationVersion == currentCalibrationVersion

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun load(runId: String): EnsembleRunManifest? {
        val mode = prefs.getString(key(runId, "pipeline_mode"), null) ?: return null
        return EnsembleRunManifest(
            runId                     = runId,
            pipelineMode              = mode,
            participatingModelIds     = prefs.getString(key(runId, "model_ids"), "") ?: "",
            modelVersions             = prefs.getString(key(runId, "model_versions"), "") ?: "",
            calibrationVersion        = prefs.getString(key(runId, "calibration_version"), "default") ?: "default",
            reliabilityStatsTimestamp = prefs.getLong(key(runId, "reliability_ts"), 0L),
            runStartedAt              = prefs.getLong(key(runId, "started_at"), 0L),
            runCompletedAt            = prefs.getLong(key(runId, "completed_at"), 0L),
            assetsProcessed           = prefs.getInt(key(runId, "assets_processed"), 0),
            suggestionsWritten        = prefs.getInt(key(runId, "suggestions_written"), 0),
        )
    }

    private fun loadIdList(): List<String> {
        val raw = prefs.getString(KEY_RUN_IDS_LIST, null) ?: return emptyList()
        return raw.split(",").filter { it.isNotBlank() }
    }

    private fun evictFields(edit: SharedPreferences.Editor, runId: String) {
        listOf(
            "pipeline_mode", "model_ids", "model_versions", "calibration_version",
            "reliability_ts", "started_at", "completed_at", "assets_processed", "suggestions_written"
        ).forEach { field -> edit.remove(key(runId, field)) }
    }
}
