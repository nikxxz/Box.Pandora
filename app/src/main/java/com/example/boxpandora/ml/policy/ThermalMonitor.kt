package com.example.boxpandora.ml.policy

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "ThermalMonitor"

/**
 * Wraps the Android thermal status API (API 29+) and translates platform codes into
 * [ThermalLevel] values consumed by [EnsembleRuntimePolicy].
 *
 * On devices running API < 29 (minimum SDK 24), the platform provides no thermal status
 * API; [currentLevel] always returns [ThermalLevel.NORMAL]. This is deliberately
 * non-pessimistic — the WorkManager [Constraints.setRequiresBatteryNotLow] constraint
 * already blocks jobs on overheated devices at the OS level. This monitor supplements
 * that coarse guard with continuous in-flight re-evaluation for long ensemble scans.
 *
 * Lifecycle: create one instance in [PandoraApp]; pass it to workers via [PandoraApp].
 * Call [close] in [Application.onTerminate] to release the registered platform listener.
 *
 * Thread-safety: [currentLevel] is safe to call from any thread.
 */
class ThermalMonitor(context: Context) : Closeable {

    private val powerManager: PowerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    /**
     * Stores the raw platform [PowerManager.THERMAL_STATUS_*] integer (API 29+).
     * Initialized to 0 (THERMAL_STATUS_NONE = nominal) so the first call before any
     * listener event returns NORMAL rather than an unknown sentinel.
     */
    private val rawStatus = AtomicInteger(0)

    /**
     * Stored as [Any?] so the class compiles cleanly against minSdk 24;
     * only set on API 29+ via [registerListener].
     */
    private var thermalListenerRef: Any? = null

    init {
        if (Build.VERSION.SDK_INT >= 29) {
            registerListener()
        }
    }

    @Suppress("unused")  // called only from API-guarded init block
    private fun registerListener() {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            // Seed from the current thermal status so the first call to currentLevel()
            // is accurate even before the listener fires its first event.
            rawStatus.set(powerManager.currentThermalStatus)

            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                val prev = rawStatus.getAndSet(status)
                if (prev != status) {
                    Log.i(TAG, "Thermal status: ${statusName(prev)} → ${statusName(status)}")
                }
            }
            powerManager.addThermalStatusListener(listener)
            thermalListenerRef = listener
            Log.d(TAG, "Thermal listener registered (initial=${statusName(rawStatus.get())})")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register thermal listener — will report NORMAL always", e)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * The most recently observed thermal level. Returns [ThermalLevel.NORMAL] on devices
     * that do not support the thermal status API (API < 29).
     *
     * Safe to call from any thread without locking.
     */
    val currentLevel: ThermalLevel
        get() = if (Build.VERSION.SDK_INT >= 29) {
            fromPlatformStatus(rawStatus.get())
        } else {
            ThermalLevel.NORMAL
        }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    override fun close() {
        if (Build.VERSION.SDK_INT < 29) return
        val listener = thermalListenerRef as? PowerManager.OnThermalStatusChangedListener
            ?: return
        try {
            powerManager.removeThermalStatusListener(listener)
            Log.d(TAG, "Thermal listener removed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove thermal listener", e)
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun fromPlatformStatus(status: Int): ThermalLevel {
        if (Build.VERSION.SDK_INT < 29) return ThermalLevel.NORMAL
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT     -> ThermalLevel.NORMAL
            PowerManager.THERMAL_STATUS_MODERATE  -> ThermalLevel.ELEVATED
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL  -> ThermalLevel.HOT
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN  -> ThermalLevel.CRITICAL
            else                                   -> ThermalLevel.NORMAL
        }
    }

    private fun statusName(status: Int): String {
        if (Build.VERSION.SDK_INT < 29) return "N/A"
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE      -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT     -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE  -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE    -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL  -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN  -> "SHUTDOWN"
            else                                   -> "UNKNOWN($status)"
        }
    }
}
