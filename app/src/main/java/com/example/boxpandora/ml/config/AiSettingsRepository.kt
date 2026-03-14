package com.example.boxpandora.ml.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.aiSettingsDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "ai_settings")

/**
 * DataStore-backed persistence for [AiSettings].
 *
 * Exposes a [Flow] of the current settings snapshot and individual suspend
 * setters for each field. All reads and writes go through the DataStore
 * preferences file — no SharedPreferences involvement.
 *
 * Usage: obtain a single instance from [PandoraApp]; do not create multiple instances
 * per process (DataStore enforces single-process write access per file).
 */
class AiSettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.aiSettingsDataStore

    // ── Keys ─────────────────────────────────────────────────────────────────

    private object Keys {
        val SCENE_TAGGING_ENABLED      = booleanPreferencesKey("scene_tagging_enabled")
        val FACE_PROCESSING_ENABLED    = booleanPreferencesKey("face_processing_enabled")
        val BACKGROUND_INDEXING_ENABLED = booleanPreferencesKey("background_indexing_enabled")
        val CONFIDENCE_THRESHOLD       = floatPreferencesKey("confidence_threshold")
        val AUTO_INDEX_ON_SYNC         = booleanPreferencesKey("auto_index_on_sync")
        val WIFI_ONLY_DOWNLOADS        = booleanPreferencesKey("wifi_only_downloads")
        val FACE_DETECTION_IN_VIDEOS   = booleanPreferencesKey("face_detection_in_videos")
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    val settings: Flow<AiSettings> = dataStore.data.map { prefs ->
        AiSettings(
            sceneTaggingEnabled       = prefs[Keys.SCENE_TAGGING_ENABLED]       ?: AiSettings.DEFAULT.sceneTaggingEnabled,
            faceProcessingEnabled     = prefs[Keys.FACE_PROCESSING_ENABLED]     ?: AiSettings.DEFAULT.faceProcessingEnabled,
            backgroundIndexingEnabled = prefs[Keys.BACKGROUND_INDEXING_ENABLED] ?: AiSettings.DEFAULT.backgroundIndexingEnabled,
            confidenceThreshold       = prefs[Keys.CONFIDENCE_THRESHOLD]        ?: AiSettings.DEFAULT.confidenceThreshold,
            autoIndexOnSync           = prefs[Keys.AUTO_INDEX_ON_SYNC]           ?: AiSettings.DEFAULT.autoIndexOnSync,
            wifiOnlyDownloads         = prefs[Keys.WIFI_ONLY_DOWNLOADS]          ?: AiSettings.DEFAULT.wifiOnlyDownloads,
            faceDetectionInVideos     = prefs[Keys.FACE_DETECTION_IN_VIDEOS]    ?: AiSettings.DEFAULT.faceDetectionInVideos
        )
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    suspend fun setSceneTaggingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SCENE_TAGGING_ENABLED] = enabled }
    }

    suspend fun setFaceProcessingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.FACE_PROCESSING_ENABLED] = enabled }
    }

    suspend fun setBackgroundIndexingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BACKGROUND_INDEXING_ENABLED] = enabled }
    }

    suspend fun setConfidenceThreshold(value: Float) {
        dataStore.edit { it[Keys.CONFIDENCE_THRESHOLD] = value.coerceIn(0f, 1f) }
    }

    suspend fun setAutoIndexOnSync(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_INDEX_ON_SYNC] = enabled }
    }

    suspend fun setWifiOnlyDownloads(enabled: Boolean) {
        dataStore.edit { it[Keys.WIFI_ONLY_DOWNLOADS] = enabled }
    }

    suspend fun setFaceDetectionInVideos(enabled: Boolean) {
        dataStore.edit { it[Keys.FACE_DETECTION_IN_VIDEOS] = enabled }
    }
}
