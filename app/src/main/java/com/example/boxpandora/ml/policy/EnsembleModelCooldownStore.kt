package com.example.boxpandora.ml.policy

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

private const val TAG = "EnsembleModelCooldownStore"
private const val PREFS_NAME = "pandora_ensemble_cooldowns"

/** Consecutive failures before a model enters cooldown. */
private const val COOLDOWN_THRESHOLD = 3

/** How long a cooled-down model is skipped (30 minutes). */
private const val COOLDOWN_DURATION_MS = 30L * 60L * 1_000L

private fun keyFailures(modelId: String)  = "cool_${modelId}_failures"
private fun keyLastFail(modelId: String)  = "cool_${modelId}_last_fail"
private fun keyUntil(modelId: String)     = "cool_${modelId}_until"

/**
 * Tracks per-model-ID consecutive inference failures during ensemble runs.
 *
 * Distinct from [com.example.boxpandora.ml.manager.ModelManager]'s category-level
 * crash-loop guard. This store operates at individual model granularity so one
 * misbehaving model does not block the rest of the ensemble.
 *
 * Thread safety: SharedPreferences commits are not atomic across threads; callers are
 * expected to invoke these methods from a single coroutine context (the worker coroutine).
 */
class EnsembleModelCooldownStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Records one inference failure for [modelId].
     *
     * @return `true` if this failure pushed the model into cooldown (i.e. consecutive
     *         failures reached [COOLDOWN_THRESHOLD]); `false` otherwise.
     */
    fun recordFailure(modelId: String): Boolean {
        val previous  = prefs.getInt(keyFailures(modelId), 0)
        val updated   = previous + 1
        val now       = System.currentTimeMillis()

        val edit = prefs.edit()
            .putInt(keyFailures(modelId), updated)
            .putLong(keyLastFail(modelId), now)

        val enteredCooldown = updated >= COOLDOWN_THRESHOLD
        if (enteredCooldown) {
            val until = now + COOLDOWN_DURATION_MS
            edit.putLong(keyUntil(modelId), until)
            Log.w(TAG, "Model $modelId entered cooldown after $updated consecutive failures " +
                "(until ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(until))})")
        } else {
            Log.d(TAG, "Model $modelId failure $updated/$COOLDOWN_THRESHOLD")
        }
        edit.apply()
        return enteredCooldown
    }

    /**
     * Returns `true` if [modelId] is currently in its cooldown window and should be
     * skipped for this batch.
     */
    fun isCooledDown(modelId: String): Boolean {
        val until = prefs.getLong(keyUntil(modelId), 0L)
        return System.currentTimeMillis() < until
    }

    /**
     * A human-readable reason string for diagnostics (e.g. shown in model-management UI),
     * or `null` if the model is not cooled down.
     */
    fun getCooldownReason(modelId: String): String? {
        val until = prefs.getLong(keyUntil(modelId), 0L)
        val now   = System.currentTimeMillis()
        if (now >= until) return null
        val remaining = ((until - now) / 60_000L).coerceAtLeast(1)
        val failures  = prefs.getInt(keyFailures(modelId), 0)
        return "Cooling down after $failures consecutive failures — resumes in ${remaining}m"
    }

    /**
     * Resets the failure counter and cooldown window for [modelId] after a successful
     * inference run. Call this whenever a runner produces evidence without throwing.
     */
    fun clearFailures(modelId: String) {
        if (prefs.getInt(keyFailures(modelId), 0) > 0) {
            prefs.edit()
                .remove(keyFailures(modelId))
                .remove(keyLastFail(modelId))
                .remove(keyUntil(modelId))
                .apply()
            Log.d(TAG, "Model $modelId failure counter cleared after successful run")
        }
    }

    /** Removes all cooldown state (e.g. on model set reset or full index rebuild). */
    fun clearAll() {
        prefs.edit().clear().apply()
        Log.d(TAG, "All model cooldown state cleared")
    }
}
