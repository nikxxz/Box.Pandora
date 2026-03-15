package com.example.boxpandora.ml.policy

/**
 * Per-batch concurrency limits derived from the current [ThermalLevel] and charging state.
 *
 * These values tell workers and orchestrators how aggressively to parallelize each stage
 * of the pipeline. All values are conservative by design — the ceiling is 1 for every
 * dimension when thermal pressure is elevated, so "enable all compatible models" never
 * fully saturates the CPU/GPU/NPU regardless of the user's setting.
 *
 * Field semantics:
 * @param batchSize                  Number of media items fetched from the DB per work batch.
 * @param sceneModelParallelism      Maximum number of scene models run concurrently for a
 *                                   single media item. 1 = sequential (current default).
 * @param faceDetectorParallelism    Maximum number of face detectors run concurrently per
 *                                   media item. 1 = sequential.
 * @param faceRecognizerParallelism  Maximum number of face recognizers run concurrently per
 *                                   fused face crop. 1 = sequential.
 */
data class ConcurrencyLimits(
    val batchSize: Int,
    val sceneModelParallelism: Int,
    val faceDetectorParallelism: Int,
    val faceRecognizerParallelism: Int,
)

/**
 * Produces [ConcurrencyLimits] appropriate for the current runtime environment.
 *
 * Workers must call [limitsFor] at the start of each batch so that limits can
 * tighten as the device heats up during a long scan, not only at job start.
 *
 * ### Base limits by thermal / charge state
 *
 * | Thermal   | Charging | batchSize | scene | detector | recognizer |
 * |-----------|----------|-----------|-------|----------|------------|
 * | NORMAL    | yes      |    16     |   2   |    2     |     2      |
 * | NORMAL    | no       |    12     |   1   |    1     |     1      |
 * | ELEVATED  | yes      |     8     |   1   |    1     |     1      |
 * | ELEVATED  | no       |     6     |   1   |    1     |     1      |
 * | HOT       | *        |     4     |   1   |    1     |     1      |
 * | CRITICAL  | *        |     1     |   1   |    1     |     1      |
 *
 * ### Batch-size modifiers (applied after thermal/charge base; parallelism is unchanged)
 *
 * Modifiers are multiplicative and applied in order:
 *
 * 1. **Background** (`!isForeground`): × 0.75 — background runs preserve responsiveness
 *    for other apps and don't need to race.
 * 2. **Face-heavy pipeline** (`hasFaceModels`): × 0.75 — face detection + recognition are
 *    significantly heavier per item than scene embedding alone.
 * 3. **Ideal foreground** (`isForeground && !hasFaceModels && NORMAL && isCharging`): × 1.25
 *    — scene-only, charging, foreground run can afford slightly larger batches.
 * 4. **Low memory** (`isLowMemory`): × 0.50, minimum 1 — applied last, always overrides.
 *
 * Note: batch size and model parallelism are intentionally kept separate. A small batch with
 * aggressive model parallelism can still saturate the NPU/GPU. The parallelism values in
 * [ConcurrencyLimits] are only influenced by thermal level and charging state.
 *
 * When thermal is HOT or CRITICAL the [EnsembleRuntimePolicy] should already be returning
 * [PolicyDecision.Pause] or [PolicyDecision.Stop]; the minimal values here are a backstop only.
 *
 * @param thermal       Current thermal level from [ThermalMonitor.currentLevel].
 * @param isCharging    True when the device has a charging source connected.
 * @param isForeground  True when the run was triggered explicitly by the user.
 * @param hasFaceModels True when the pipeline includes face detection or recognition models.
 * @param isLowMemory   True when [android.app.ActivityManager.MemoryInfo.lowMemory] is set.
 */
object DynamicConcurrencyController {

    fun limitsFor(
        thermal: ThermalLevel,
        isCharging: Boolean,
        isForeground: Boolean = false,
        hasFaceModels: Boolean = false,
        isLowMemory: Boolean = false,
    ): ConcurrencyLimits {
        val base = baseFor(thermal, isCharging)

        var batch = base.batchSize.toFloat()

        // 1. Background penalty — yield headroom to other apps
        if (!isForeground) batch *= 0.75f

        // 2. Face-model penalty — each item triggers heavier compute
        if (hasFaceModels) batch *= 0.75f

        // 3. Ideal foreground bonus — scene-only, normal thermal, charging
        if (isForeground && !hasFaceModels && thermal == ThermalLevel.NORMAL && isCharging) {
            batch *= 1.25f
        }

        // 4. Memory pressure override (applied last; always wins)
        if (isLowMemory) batch *= 0.50f

        return base.copy(batchSize = batch.toInt().coerceAtLeast(1))
    }

    // ── Private base table ────────────────────────────────────────────────────

    private fun baseFor(thermal: ThermalLevel, isCharging: Boolean): ConcurrencyLimits =
        when (thermal) {
            ThermalLevel.NORMAL -> if (isCharging) {
                ConcurrencyLimits(
                    batchSize                 = 16,
                    sceneModelParallelism     = 2,
                    faceDetectorParallelism   = 2,
                    faceRecognizerParallelism = 2,
                )
            } else {
                ConcurrencyLimits(
                    batchSize                 = 12,
                    sceneModelParallelism     = 1,
                    faceDetectorParallelism   = 1,
                    faceRecognizerParallelism = 1,
                )
            }

            ThermalLevel.ELEVATED -> if (isCharging) {
                ConcurrencyLimits(
                    batchSize                 = 8,
                    sceneModelParallelism     = 1,
                    faceDetectorParallelism   = 1,
                    faceRecognizerParallelism = 1,
                )
            } else {
                ConcurrencyLimits(
                    batchSize                 = 6,
                    sceneModelParallelism     = 1,
                    faceDetectorParallelism   = 1,
                    faceRecognizerParallelism = 1,
                )
            }

            ThermalLevel.HOT -> ConcurrencyLimits(
                batchSize                 = 4,
                sceneModelParallelism     = 1,
                faceDetectorParallelism   = 1,
                faceRecognizerParallelism = 1,
            )

            ThermalLevel.CRITICAL -> ConcurrencyLimits(
                batchSize                 = 1,
                sceneModelParallelism     = 1,
                faceDetectorParallelism   = 1,
                faceRecognizerParallelism = 1,
            )
        }
}
