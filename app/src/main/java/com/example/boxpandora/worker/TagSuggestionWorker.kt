package com.example.boxpandora.worker

import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import android.content.Context
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.TagSuggestion
import com.example.boxpandora.ml.config.AiSettings
import com.example.boxpandora.ml.engine.EmbeddingUtils
import com.example.boxpandora.ml.engine.TagSuggestionEngine
import com.example.boxpandora.ml.model.ModelCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val TAG = "TagSuggestionWorker"
private const val BATCH_SIZE = 16

/**
 * Scores all indexed media against tag prototypes and writes [TagSuggestion] rows.
 *
 * Design rules:
 *  - Scores images, GIFs, and videos — any asset with a scene embedding is eligible.
 *  - Never overwrites or removes user-applied tags.
 *  - Never writes suggestions for tags already applied to the asset (rejection memory).
 *  - Never writes suggestions for (asset, tag) pairs the user has rejected (rejection memory).
 *  - Existing suggestions for an asset+model are deleted before new ones are inserted
 *    so the review queue always reflects the current prototype state.
 *  - Applies the second-best margin rule via [TagSuggestionEngine] — ambiguous top
 *    suggestions are penalised or suppressed before writing.
 *  - Idempotent: safe to run multiple times.
 */
class TagSuggestionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PandoraApp
        val db = app.database

        val activeModel = app.modelManager.getActiveModel(ModelCategory.SCENE_EMBEDDING)
        if (activeModel == null) {
            Log.i(TAG, "No active scene_embedding model — skipping suggestion scoring")
            return@withContext Result.success(workDataOf("skipped" to true))
        }

        val modelVersion = activeModel.metadata.roomVersionKey
        val settings = app.aiSettingsRepository.settings.first()
        val threshold = settings.confidenceThreshold

        val engine = TagSuggestionEngine(
            tagPrototypeDao = db.tagPrototypeDao(),
            tagDao = db.tagDao(),
            tagCooccurrenceDao = db.tagCooccurrenceDao()
        )

        val prototypes = engine.loadPrototypes()
        if (prototypes.isEmpty()) {
            Log.i(TAG, "No prototypes available — run PrototypeBuildWorker first")
            return@withContext Result.success(workDataOf("skipped" to true))
        }

        val totalAssets = db.imageEmbeddingDao().countIndexed(modelVersion)
        Log.i(TAG, "Scoring $totalAssets indexed assets against ${prototypes.size} prototypes (threshold=$threshold)")

        var processed = 0
        var suggestionsWritten = 0
        var offset = 0

        while (isActive) {
            val uris = db.imageEmbeddingDao().getIndexedAssetUris(modelVersion, BATCH_SIZE, offset)
            if (uris.isEmpty()) break

            for (uri in uris) {
                if (!isActive) break

                val embeddingRow = db.imageEmbeddingDao().getForAssetAndModel(uri, modelVersion)
                    ?: continue
                val assetEmbedding = EmbeddingUtils.bytesToFloatArray(embeddingRow.embedding)

                // Build exclusion sets
                val existingTagKeys = db.mediaTagDao().getMediaTagsForUri(uri)
                    .mapNotNull { mt -> db.tagDao().getById(mt.tagId)?.normalizedName }
                    .toSet()
                val rejectedTagKeys = db.tagRejectionDao().getForAsset(uri)
                    .map { it.tagKey }
                    .toSet()

                // Score
                val scores = engine.score(
                    assetEmbedding = assetEmbedding,
                    prototypes = prototypes,
                    existingTagKeys = existingTagKeys,
                    rejectedTagKeys = rejectedTagKeys,
                    confidenceThreshold = threshold
                )

                // Replace old suggestions for this asset+model with fresh ones
                db.tagSuggestionDao().deleteForAssetAndModel(uri, modelVersion)

                if (scores.isNotEmpty()) {
                    db.tagSuggestionDao().insertAll(
                        scores.map { s ->
                            TagSuggestion(
                                assetId = uri,
                                tagKey = s.tagKey,
                                score = s.finalConfidence.toDouble(),
                                source = s.source,
                                modelVersion = modelVersion
                            )
                        }
                    )
                    suggestionsWritten += scores.size
                }

                processed++
            }

            setProgress(workDataOf("processed" to processed, "total" to totalAssets))
            offset += BATCH_SIZE
        }

        Log.i(TAG, "Suggestion scoring complete — $processed assets, $suggestionsWritten suggestions written")
        Result.success(workDataOf("processed" to processed, "suggestionsWritten" to suggestionsWritten))
    }
}
