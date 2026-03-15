package com.example.boxpandora.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.ml.ensemble.ReliabilityUpdateService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "RecalculateReliabilityWorker"

/**
 * Recomputes [ModelReliabilityStats.derivedWeight] for every record in
 * [model_reliability_stats] using the current Bayesian formula and stored accept/reject counts.
 *
 * **What it does:**
 *  - Calls [ReliabilityUpdateService.recalculateAllWeights], which re-applies the formula
 *    (`smoothedPrecision = (accepted + α) / (total + α + β)`) to every row and updates
 *    [derivedWeight] where it differs from the stored value.
 *
 * **What it preserves:** accept/reject event counts, user feedback, all tagging data.
 *
 * **Idempotency:** fully idempotent — running it twice produces the same result.
 *
 * Use this after a [com.example.boxpandora.ml.config.FusionThresholdConfig] constant change,
 * or as a repair action when weights appear to have diverged from the formula.
 */
class RecalculateReliabilityWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PandoraApp
        val db  = app.database

        val updated = ReliabilityUpdateService(db).recalculateAllWeights()

        Log.i(TAG, "Reliability recalculation complete — $updated record(s) updated")
        Result.success(workDataOf("recordsUpdated" to updated))
    }
}
