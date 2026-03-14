package com.example.boxpandora.ml.engine

import android.util.Log
import com.example.boxpandora.data.local.dao.ImageEmbeddingDao
import com.example.boxpandora.data.local.dao.MediaTagDao
import com.example.boxpandora.data.local.dao.TagDao
import com.example.boxpandora.data.local.dao.TagPrototypeDao
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.data.local.entity.TagPrototype

private const val TAG = "TagPrototypeEngine"

/** Minimum number of tagged+indexed images required before building a prototype for a tag. */
private const val MIN_SAMPLES = 2

/**
 * Builds per-tag prototype vectors from user-confirmed tag data.
 *
 * Strategy: normalized mean of all L2-normalized scene embeddings for images tagged with a given
 * tag. Only images that also have a scene embedding for [modelVersion] are considered.
 *
 * The result is stored in the tag_prototypes table. Existing prototypes are replaced on rebuild.
 *
 * This engine requires no bundled seed data — it learns entirely from the user's own library.
 * Tags with fewer than [MIN_SAMPLES] valid images are skipped.
 */
class TagPrototypeEngine(
    private val tagDao: TagDao,
    private val mediaTagDao: MediaTagDao,
    private val imageEmbeddingDao: ImageEmbeddingDao,
    private val tagPrototypeDao: TagPrototypeDao
) {

    /**
     * Rebuilds prototypes for all tags in the database.
     * Returns the number of prototypes written.
     */
    suspend fun rebuildAllPrototypes(modelVersion: String): Int {
        val allTags = tagDao.getAll()
        Log.i(TAG, "rebuildAllPrototypes: ${allTags.size} tags to evaluate")
        var written = 0
        for (tag in allTags) {
            if (buildPrototypeForTag(tag, modelVersion)) written++
        }
        Log.i(TAG, "rebuildAllPrototypes done: $written prototypes written")
        return written
    }

    /**
     * Rebuilds the prototype for a single tag.
     * Returns true if a prototype was written, false if skipped (not enough samples).
     */
    suspend fun buildPrototypeForTag(tag: Tag, modelVersion: String): Boolean {
        val taggedUris = mediaTagDao.getTaggedUrisWithEmbedding(tag.id, modelVersion)

        if (taggedUris.size < MIN_SAMPLES) {
            Log.d(TAG, "Skipping '${tag.normalizedName}': ${taggedUris.size} samples < $MIN_SAMPLES")
            return false
        }

        val vectors = taggedUris.mapNotNull { uri ->
            val row = imageEmbeddingDao.getForAssetAndModel(uri, modelVersion)
            row?.let { EmbeddingUtils.bytesToFloatArray(it.embedding) }
        }

        if (vectors.size < MIN_SAMPLES) {
            Log.d(TAG, "Skipping '${tag.normalizedName}': only ${vectors.size} embeddings loaded")
            return false
        }

        val prototype = EmbeddingUtils.normalizedMean(vectors) ?: return false

        tagPrototypeDao.insert(
            TagPrototype(
                tagKey = tag.normalizedName,
                prototypeBlob = EmbeddingUtils.floatArrayToBytes(prototype),
                dim = prototype.size,
                n = vectors.size,
                modelVersion = modelVersion,
                updatedAt = System.currentTimeMillis()
            )
        )

        Log.d(TAG, "Prototype written for '${tag.normalizedName}': ${vectors.size} samples, dim=${prototype.size}")
        return true
    }
}

