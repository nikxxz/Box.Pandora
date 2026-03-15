package com.example.boxpandora.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.FusedIdentitySuggestion
import com.example.boxpandora.ml.ensemble.IdentityEvidenceRecord
import com.example.boxpandora.ml.ensemble.IdentityFusionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "RebuildFusedIdentityWorker"

/**
 * Re-fuses fused identity suggestions directly from stored [identity_inference_evidence] rows,
 * applying current thresholds and reliability weights.
 *
 * **What it does:**
 *  1. Deletes all existing [fused_identity_suggestions] rows (status PENDING/REJECTED/ACCEPTED).
 *  2. Loads all [identity_inference_evidence] rows (raw per-recognizer scores).
 *  3. Loads current identity reliability weights from [model_reliability_stats].
 *  4. Runs [IdentityFusionEngine] per fused-face group and inserts the new suggestions.
 *
 * **What it preserves:** face scan log, detected faces, face embeddings, face clusters,
 * cluster names, person tag links, reliability stats, and all user-applied tags.
 *
 * **Idempotency:** safe to re-run. Step 1 ensures no stale rows remain before the
 * fresh fused results are written.
 *
 * This is the correct repair path when identity thresholds or reliability weights changed
 * but face embeddings are still valid — avoids an expensive full face re-detection pass.
 */
class RebuildFusedIdentityWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PandoraApp
        val db  = app.database

        val settings = app.aiSettingsRepository.settings.first()
        if (!settings.faceProcessingEnabled) {
            Log.i(TAG, "Face processing disabled — worker exiting early")
            return@withContext Result.success(workDataOf("skipped" to true))
        }

        db.fusedIdentitySuggestionDao().deleteAll()

        val allEvidence = db.identityInferenceEvidenceDao().getAll()
        if (allEvidence.isEmpty()) {
            Log.i(TAG, "No identity evidence found — nothing to fuse")
            return@withContext Result.success(workDataOf("suggestionsWritten" to 0))
        }

        val reliabilityWeights = db.modelReliabilityStatsDao().getAllIdentityWeights()
            .associate { it.modelId to it.derivedWeight }
        val threshold    = settings.confidenceThreshold
        val fusionEngine = IdentityFusionEngine()
        val now          = System.currentTimeMillis()
        val runId        = "refusion-$now"

        val newSuggestions = mutableListOf<FusedIdentitySuggestion>()
        allEvidence.groupBy { it.fusedFaceId }.forEach { (fusedFaceId, records) ->
            val assetId  = records.first().assetId
            val evidence = records.map { ev ->
                IdentityEvidenceRecord(
                    fusedFaceId       = ev.fusedFaceId,
                    recognizerId      = ev.recognizerId,
                    recognizerVersion = ev.recognizerVersion,
                    clusterId         = ev.clusterId,
                    tagKey            = ev.tagKey,
                    tagName           = ev.tagName,
                    tagId             = ev.tagId,
                    similarityScore   = ev.similarityScore,
                )
            }
            fusionEngine.fuse(
                fusedFaceId         = fusedFaceId,
                assetId             = assetId,
                evidence            = evidence,
                confidenceThreshold = threshold,
                reliabilityWeights  = reliabilityWeights,
            ).forEach { fi ->
                newSuggestions.add(
                    FusedIdentitySuggestion(
                        assetId                     = assetId,
                        fusedFaceId                 = fi.fusedFaceId,
                        clusterId                   = fi.clusterId,
                        tagKey                      = fi.tagKey,
                        tagName                     = fi.tagName,
                        tagId                       = fi.tagId,
                        fusedScore                  = fi.fusedScore,
                        contributingRecognizerIds   = fi.contributingRecognizerIds.joinToString(","),
                        contributingRecognizerCount = fi.contributingRecognizerIds.size,
                        strongestScore              = fi.strongestScore,
                        isAmbiguous                 = fi.isAmbiguous,
                        agreementLevelOrdinal       = fi.agreementLevel.ordinal,
                        status                      = FusedIdentitySuggestion.STATUS_PENDING,
                        pipelineRunId               = runId,
                        createdAt                   = now,
                    )
                )
            }

            setProgress(workDataOf("processed" to newSuggestions.size))
        }

        if (newSuggestions.isNotEmpty()) {
            db.fusedIdentitySuggestionDao().insertAll(newSuggestions)
        }

        Log.i(TAG, "Identity refusion complete — ${newSuggestions.size} suggestions written " +
            "(${allEvidence.groupBy { it.fusedFaceId }.size} face groups)")
        Result.success(workDataOf("suggestionsWritten" to newSuggestions.size))
    }
}
