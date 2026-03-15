package com.example.boxpandora.ml.policy

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

private const val TAG = "EnsembleBlockedStore"
private const val PREFS_NAME           = "pandora_ensemble_blocked"
private const val KEY_CONSECUTIVE      = "consecutive_blocks"
private const val KEY_LAST_REASON      = "last_block_reason"
private const val KEY_LAST_BLOCK_AT    = "last_block_at"

/** Consecutive blocks required before [isSuggestingFallback] becomes true. */
internal const val ENSEMBLE_BLOCKED_THRESHOLD = 3

/**
 * Tracks consecutive policy blocks (Pause or Stop decisions) across ensemble worker runs.
 *
 * When the block count reaches [ENSEMBLE_BLOCKED_THRESHOLD] without an intervening
 * successful run, [isSuggestingFallback] becomes true. The Model Management UI uses this
 * to surface a subtle suggestion to switch to single-active mode — it never switches
 * automatically.
 *
 * The counter resets to zero whenever an ensemble run makes meaningful progress (first
 * successful policy check passed).
 */
class EnsembleBlockedStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Number of consecutive policy blocks without an intervening successful pass. */
    val consecutiveBlockCount: Int
        get() = prefs.getInt(KEY_CONSECUTIVE, 0)

    /** Human-readable reason from the most recent block event, or null if none. */
    val lastBlockReason: String?
        get() = prefs.getString(KEY_LAST_REASON, null)

    /** True when consecutive blocks have reached the threshold for a fallback UI hint. */
    val isSuggestingFallback: Boolean
        get() = consecutiveBlockCount >= ENSEMBLE_BLOCKED_THRESHOLD

    /**
     * Records one policy block event.
     *
     * Call when the ensemble worker returns [Result.retry] (Pause) or returns
     * [Result.success] with a [stoppedByPolicy] output key (Stop).
     */
    fun recordBlock(reason: String) {
        val count = consecutiveBlockCount + 1
        prefs.edit()
            .putInt(KEY_CONSECUTIVE, count)
            .putString(KEY_LAST_REASON, reason)
            .putLong(KEY_LAST_BLOCK_AT, System.currentTimeMillis())
            .apply()
        Log.d(TAG, "Ensemble block recorded: $reason (consecutive=$count)")
    }

    /**
     * Resets the consecutive block counter.
     *
     * Call once the ensemble run has made it past the first policy check without
     * being blocked — this signals that conditions are currently permitting work.
     */
    fun recordSuccessfulStart() {
        if (consecutiveBlockCount > 0) {
            prefs.edit().putInt(KEY_CONSECUTIVE, 0).apply()
            Log.d(TAG, "Ensemble block counter reset (run started successfully)")
        }
    }
}
