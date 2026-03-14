package com.example.boxpandora.ml.model

import android.content.res.AssetManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "ManifestParser"

/** Asset path for the canonical JSON manifest. */
internal const val MANIFEST_ASSET_PATH = "models/model_manifest.json"

/**
 * Parses [MANIFEST_ASSET_PATH] from the APK assets into a [ModelManifest].
 *
 * Field schema (all keys camelCase):
 *
 * | Field        | Type    | Required |
 * |--------------|---------|----------|
 * | id           | String  | yes      |
 * | displayName  | String  | yes      |
 * | category     | String  | yes      |
 * | version      | String  | yes      |
 * | format       | String  | no (def: "tflite") |
 * | source       | String  | yes — "bundled_asset", "remote_download", or "local_file" |
 * | assetPath    | String  | when source = bundled_asset |
 * | url          | String  | when source = remote_download |
 * | sha256       | String  | no (blank = skip check) |
 * | sizeBytes    | Long    | no (def: 0) |
 * | inputWidth   | Int     | no (def: 224) |
 * | inputHeight  | Int     | no (def: 224) |
 * | outputDim    | Int     | no (def: 0)   |
 * | isDefault         | Boolean | no (def: false) |
 * | isOptional        | Boolean | no (def: false) |
 * | isEnabledByUser   | Boolean | no (def: true)  |
 *
 * Malformed individual entries are skipped with a warning; they do not abort the parse.
 */
object ManifestParser {

    /**
     * Reads and parses the manifest from [assets].
     *
     * @throws Exception if the file is missing or the top-level JSON is invalid.
     *   Callers should handle this and fall back to the hardcoded manifest.
     */
    fun parse(assets: AssetManager): ModelManifest {
        val raw = assets.open(MANIFEST_ASSET_PATH).bufferedReader().use { it.readText() }
        val root = JSONObject(raw)
        val array: JSONArray = root.getJSONArray("models")
        val models = mutableListOf<ModelMetadata>()

        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            // Skip comment-only entries (no "id" key)
            if (!entry.has("id")) continue
            try {
                models.add(parseEntry(entry))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed manifest entry at index $i: ${e.message}")
            }
        }

        Log.d(TAG, "Parsed ${models.size} model(s) from $MANIFEST_ASSET_PATH")
        return ModelManifest(models)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun parseEntry(e: JSONObject): ModelMetadata {
        val id          = e.getString("id")
        val displayName = e.getString("displayName")
        val category    = parseCategory(e.getString("category"), id)
        val version     = e.getString("version")
        val format      = e.optString("format", "tflite")
        val source      = parseSource(e, id)
        val sha256      = e.optString("sha256", "")
        val sizeBytes   = e.optLong("sizeBytes", 0L)
        val inputWidth  = e.optInt("inputWidth", 224)
        val inputHeight = e.optInt("inputHeight", 224)
        val outputDim   = e.optInt("outputDim", 0)
        val isDefault        = e.optBoolean("isDefault", false)
        val isOptional       = e.optBoolean("isOptional", false)
        val isEnabledByUser  = e.optBoolean("isEnabledByUser", true)

        return ModelMetadata(
            id              = id,
            displayName     = displayName,
            category        = category,
            version         = version,
            format          = format,
            source          = source,
            sha256          = sha256,
            sizeBytes       = sizeBytes,
            inputWidth      = inputWidth,
            inputHeight     = inputHeight,
            outputDim       = outputDim,
            isDefault       = isDefault,
            isOptional      = isOptional,
            isEnabledByUser = isEnabledByUser
        )
    }

    private fun parseCategory(raw: String, entryId: String): ModelCategory =
        ModelCategory.values().firstOrNull { it.id.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown category \"$raw\" in entry \"$entryId\"")

    private fun parseSource(e: JSONObject, entryId: String): ModelSource {
        return when (val type = e.getString("source")) {
            "bundled_asset" -> {
                val path = e.optString("assetPath", "").ifBlank {
                    throw IllegalArgumentException(
                        "Entry \"$entryId\" has source=bundled_asset but assetPath is empty"
                    )
                }
                ModelSource.BundledAsset(path)
            }
            "remote_download" -> ModelSource.RemoteDownload(e.optString("url", ""))
            "local_file" -> {
                val path = e.optString("assetPath", "").ifBlank {
                    throw IllegalArgumentException(
                        "Entry \"$entryId\" has source=local_file but assetPath is empty"
                    )
                }
                ModelSource.LocalFile(File(path))
            }
            else -> throw IllegalArgumentException(
                "Unknown source type \"$type\" in entry \"$entryId\""
            )
        }
    }
}
