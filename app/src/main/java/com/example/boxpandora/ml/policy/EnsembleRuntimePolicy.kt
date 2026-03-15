package com.example.boxpandora.ml.policy

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import com.example.boxpandora.ml.config.AiPipelineConfig

private const val TAG = "EnsembleRuntimePolicy"

// ── Policy decision types ─────────────────────────────────────────────────────

/**
 * Outcome returned by [EnsembleRuntimePolicy.evaluate] for each batch boundary check.
 *
 * Workers must act on the decision before processing the next batch of assets.
 */
sealed class PolicyDecision {

    /** Continue immediately — no throttle or action required. */
    object Proceed : PolicyDecision()

    /**
     * Thermal or resource pressure is elevated but not severe.
     * The worker should sleep [batchPauseMs] before continuing.
     */
    data class Throttle(val batchPauseMs: Long, val reason: String) : PolicyDecision()

    /**
     * Conditions currently prohibit background ensemble work (e.g. battery saver, HOT
     * thermal state for a background run). The worker should stop and return
     * [androidx.work.ListenableWorker.Result.retry] so WorkManager can re-enqueue it
     * when conditions improve.
     */
    data class Pause(val reason: String) : PolicyDecision()

    /**
     * A hard stop — thermal CRITICAL or a safety limit that must not be bypassed even
     * for foreground-triggered runs. The worker should stop and return
     * [androidx.work.ListenableWorker.Result.success] so it is not retried automatically.
     * The orchestrator should log why the run stopped.
     */
    data class Stop(val reason: String) : PolicyDecision()
}

// ── Policy ────────────────────────────────────────────────────────────────────

/**
 * Central runtime gate for ensemble execution. All workers and orchestrators must call
 * [evaluate] at every batch boundary — not only at job startup.
 *
 * This class is the single place where the answer to "should we keep going?" is computed.
 * Workers must not reimplement any of this logic inline.
 *
 * ### Decision table
 *
 * | Thermal   | Background                     | Foreground                       |
 * |-----------|--------------------------------|----------------------------------|
 * | CRITICAL  | **Stop** (hard stop)           | **Stop** (hard stop)             |
 * | HOT       | **Pause** (retry later)        | **Throttle** (2 s batch pause)   |
 * | ELEVATED  | **Throttle** (500 ms pause)    | **Proceed**                      |
 * | NORMAL    | Proceed unless battery rules   | **Proceed**                      |
 *
 * Additional background-only guards:
 * - Battery saver active when [AiPipelineConfig.pauseEnsembleOnBatterySaver] is true → **Pause**
 * - Battery low (as reported by [BatteryManager.BATTERY_STATUS_*]) → **Pause**
 * - Not charging when [AiPipelineConfig.ensembleOnlyWhileCharging] is true → **Pause**
 *
 * Foreground runs bypass the battery/charging checks (user explicitly triggered) but
 * still respect all thermal hard stops.
 *
 * @param thermalMonitor Live thermal level source. Share the single [ThermalMonitor]
 *                       instance from [PandoraApp] — do not create per-worker instances.
 */
class EnsembleRuntimePolicy(
    private val thermalMonitor: ThermalMonitor,
) {

    /**
     * Evaluates current device conditions and returns the appropriate [PolicyDecision].
     *
     * Call once per batch boundary inside every ensemble worker loop. The call is cheap
     * (an atomic read plus two system service checks); it is safe to call frequently.
     *
     * @param context       Required to query [PowerManager] and [BatteryManager] state.
     * @param config        Pipeline configuration supplying battery/charging guard settings.
     * @param isForeground  True when this run was triggered explicitly by the user (from the
     *                      UI). Foreground runs bypass soft battery guards but still obey
     *                      thermal hard stops.
     */
    fun evaluate(
        context: Context,
        config: AiPipelineConfig,
        isForeground: Boolean,
    ): PolicyDecision {
        val thermal = thermalMonitor.currentLevel

        // ── Hard stops: block regardless of foreground/background ─────────────
        if (thermal == ThermalLevel.CRITICAL) {
            Log.e(TAG, "CRITICAL thermal state — stopping ensemble immediately")
            return PolicyDecision.Stop(reason = "thermal_critical")
        }

        // ── HOT: foreground is throttled, background is paused ────────────────
        if (thermal == ThermalLevel.HOT) {
            return if (isForeground) {
                Log.w(TAG, "HOT thermal: throttling foreground ensemble (2 s batch pause)")
                PolicyDecision.Throttle(batchPauseMs = 2_000L, reason = "thermal_hot")
            } else {
                Log.w(TAG, "HOT thermal: pausing background ensemble until device cools")
                PolicyDecision.Pause(reason = "thermal_hot_background")
            }
        }

        // ── ELEVATED: background throttled, foreground unaffected ─────────────
        if (thermal == ThermalLevel.ELEVATED && !isForeground) {
            Log.d(TAG, "ELEVATED thermal: throttling background ensemble (500 ms batch pause)")
            return PolicyDecision.Throttle(batchPauseMs = 500L, reason = "thermal_elevated")
        }

        // ── Background-only battery and charging guards ───────────────────────
        if (!isForeground) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

            if (config.pauseEnsembleOnBatterySaver && powerManager.isPowerSaveMode) {
                Log.i(TAG, "Battery saver active — pausing background ensemble")
                return PolicyDecision.Pause(reason = "battery_saver")
            }

            val batteryLow = !batteryManager.isCharging &&
                batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) < 15
            if (batteryLow) {
                Log.i(TAG, "Battery critically low — pausing background ensemble")
                return PolicyDecision.Pause(reason = "battery_low")
            }

            if (config.ensembleOnlyWhileCharging && !batteryManager.isCharging) {
                Log.d(TAG, "Not charging and ensembleOnlyWhileCharging=true — pausing background ensemble")
                return PolicyDecision.Pause(reason = "not_charging")
            }
        }

        return PolicyDecision.Proceed
    }
}
