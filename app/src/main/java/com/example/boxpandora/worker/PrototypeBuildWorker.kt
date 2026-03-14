package com.example.boxpandora.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.ml.engine.TagPrototypeEngine
import com.example.boxpandora.ml.model.ModelCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "PrototypeBuildWorker"

/**
 * Rebuilds tag prototype vectors from confirmed user tags and scene embeddings.
 *
 * **Learning sources** — only confirmed data is used for prototype construction:
 *   - Tags manually applied by the user.
 *   - Suggestions that the user has explicitly accepted (appear in media_tags).
 *   Pending (unconfirmed) suggestions in tag_suggestions are never used.
 *
 * **Media type support** — prototypes are built from all media types that have a
 * scene embedding: plain images, GIFs, and videos. Any tagged asset with a matching
 * [image_embeddings] row for the active model version contributes to its tag's prototype.
 *
 * Runs only when the device has a charged battery (constraint set in AiIndexScheduler).
 * Safe to run repeatedly — prototypes are fully replaced on each run.
 *
 * Requires at least one scene embedding to exist; exits with success if the scene
 * embedding model is not yet active (no-op until SceneIndexWorker has run).
 */
class PrototypeBuildWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PandoraApp
        val db = app.database

        val activeModel = app.modelManager.getActiveModel(ModelCategory.SCENE_EMBEDDING)
        if (activeModel == null) {
            Log.i(TAG, "No active scene_embedding model — skipping prototype build")
            return@withContext Result.success(workDataOf("skipped" to true))
        }

        val modelVersion = activeModel.metadata.roomVersionKey
        val indexedCount = db.imageEmbeddingDao().countIndexed(modelVersion)
        if (indexedCount == 0) {
            Log.i(TAG, "No indexed images yet — skipping prototype build")
            return@withContext Result.success(workDataOf("skipped" to true))
        }

        Log.i(TAG, "Building prototypes from $indexedCount indexed images (model: $modelVersion)")

        val engine = TagPrototypeEngine(
            tagDao = db.tagDao(),
            mediaTagDao = db.mediaTagDao(),
            imageEmbeddingDao = db.imageEmbeddingDao(),
            tagPrototypeDao = db.tagPrototypeDao()
        )

        return@withContext try {
            val written = engine.rebuildAllPrototypes(modelVersion)
            Log.i(TAG, "Prototype build complete: $written prototypes written")
            Result.success(workDataOf("prototypesWritten" to written))
        } catch (e: Exception) {
            Log.e(TAG, "Prototype build failed", e)
            Result.retry()
        }
    }
}
