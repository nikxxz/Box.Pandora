package com.example.boxpandora.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.ml.engine.PersonProfileEngine
import com.example.boxpandora.ml.model.ModelCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "PersonProfileWorker"

/**
 * Rebuilds per-person [FaceCluster] prototypes from confirmed training samples.
 *
 * **What this worker does:**
 *  1. Loads all high-trust person training samples via [PersonProfileEngine]:
 *     - Images with exactly one face AND exactly one person-category tag.
 *     - Faces explicitly assigned by the user to a confirmed person cluster.
 *  2. Computes a normalized-mean centroid per person tag from those samples.
 *  3. Writes one [FaceCluster] row per person tag (marked confirmedByUser = true).
 *
 * **What this worker does NOT do:**
 *  - It does not use unconfirmed automatic clustering results.
 *  - It does not learn from pending [PersonSuggestion] rows.
 *  - It does not overwrite non-centroid cluster metadata (is_hidden, created_at).
 *
 * **Run order:** Should run after [FaceIndexWorker] (so face embeddings exist) and before
 * [PersonSuggestionWorker] (so confirmed clusters include person-tag-derived ones).
 * Typically scheduled by [AiIndexScheduler.schedulePersonProfileIfEnabled].
 *
 * **Idempotency:** Safe to run multiple times. Each run fully rebuilds all person profiles.
 *
 * Requires [AiSettings.faceProcessingEnabled] = true and an active face embedding model.
 */
class PersonProfileWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PandoraApp

        val settings = app.aiSettingsRepository.settings.first()
        if (!settings.faceProcessingEnabled) {
            Log.i(TAG, "Face processing disabled — skipping person profile build")
            return@withContext Result.success(workDataOf("skipped" to true))
        }

        val activeEmbedder = app.modelManager.getActiveModel(ModelCategory.FACE_EMBEDDING)
        if (activeEmbedder == null) {
            Log.i(TAG, "No active face embedding model — skipping person profile build")
            return@withContext Result.success(workDataOf("skipped" to true))
        }

        val embedderVersion = activeEmbedder.metadata.roomVersionKey
        val db = app.database

        val engine = PersonProfileEngine(
            faceDao        = db.faceClusterDao(),
            taggedFaceDao  = db.faceDao(),
            tagDao         = db.tagDao()
        )

        return@withContext try {
            val written = engine.rebuildAll(embedderVersion)
            Log.i(TAG, "Person profile build complete: $written profiles written")
            Result.success(workDataOf("profilesWritten" to written))
        } catch (e: Exception) {
            Log.e(TAG, "Person profile build failed", e)
            Result.retry()
        }
    }
}
