package com.example.boxpandora.ml.policy

/**
 * Device thermal severity ladder used to gate ensemble workloads.
 *
 * Ordered from least to most severe. [EnsembleRuntimePolicy] maps platform thermal
 * status codes onto these four buckets so the rest of the system never needs to
 * import or interpret [android.os.PowerManager] constants directly.
 */
enum class ThermalLevel {

    /**
     * Device is at or below safe operating temperature.
     * Full ensemble concurrency and batch size are permitted.
     */
    NORMAL,

    /**
     * Temperature is noticeably elevated but not yet dangerous.
     * Reduce concurrent model count and batch size to allow the device to cool.
     */
    ELEVATED,

    /**
     * Device is hot. Background ensemble work is paused.
     * User-triggered foreground work is still allowed but at reduced throughput.
     */
    HOT,

    /**
     * Thermal situation is severe or the platform has declared an emergency.
     * All ensemble execution stops immediately regardless of foreground/background mode.
     */
    CRITICAL,
}
