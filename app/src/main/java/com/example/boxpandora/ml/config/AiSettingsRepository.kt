package com.example.boxpandora.ml.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
        val SCENE_TAGGING_ENABLED           = booleanPreferencesKey("scene_tagging_enabled")
        val FACE_PROCESSING_ENABLED         = booleanPreferencesKey("face_processing_enabled")
        val BACKGROUND_INDEXING_ENABLED     = booleanPreferencesKey("background_indexing_enabled")
        val CONFIDENCE_THRESHOLD            = floatPreferencesKey("confidence_threshold")
        val AUTO_INDEX_ON_SYNC              = booleanPreferencesKey("auto_index_on_sync")
        val WIFI_ONLY_DOWNLOADS             = booleanPreferencesKey("wifi_only_downloads")
        val FACE_DETECTION_IN_VIDEOS        = booleanPreferencesKey("face_detection_in_videos")
        // Ensemble
        val PIPELINE_MODE                    = stringPreferencesKey("pipeline_mode")
        val DISABLED_ENSEMBLE_MODEL_IDS      = stringSetPreferencesKey("disabled_ensemble_model_ids")
        val ENSEMBLE_ONLY_WHILE_CHARGING     = booleanPreferencesKey("ensemble_only_while_charging")
        val PAUSE_ENSEMBLE_ON_BATTERY_SAVER  = booleanPreferencesKey("pause_ensemble_on_battery_saver")
        // Face ensemble model participation
        val DISABLED_ENSEMBLE_DETECTOR_IDS   = stringSetPreferencesKey("disabled_ensemble_detector_ids")
        val DISABLED_ENSEMBLE_RECOGNIZER_IDS = stringSetPreferencesKey("disabled_ensemble_recognizer_ids")
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    val settings: Flow<AiSettings> = dataStore.data.map { prefs ->
        AiSettings(
            sceneTaggingEnabled           = prefs[Keys.SCENE_TAGGING_ENABLED]           ?: AiSettings.DEFAULT.sceneTaggingEnabled,
            faceProcessingEnabled         = prefs[Keys.FACE_PROCESSING_ENABLED]         ?: AiSettings.DEFAULT.faceProcessingEnabled,
            backgroundIndexingEnabled     = prefs[Keys.BACKGROUND_INDEXING_ENABLED]     ?: AiSettings.DEFAULT.backgroundIndexingEnabled,
            confidenceThreshold           = prefs[Keys.CONFIDENCE_THRESHOLD]            ?: AiSettings.DEFAULT.confidenceThreshold,
            autoIndexOnSync               = prefs[Keys.AUTO_INDEX_ON_SYNC]               ?: AiSettings.DEFAULT.autoIndexOnSync,
            wifiOnlyDownloads             = prefs[Keys.WIFI_ONLY_DOWNLOADS]              ?: AiSettings.DEFAULT.wifiOnlyDownloads,
            faceDetectionInVideos         = prefs[Keys.FACE_DETECTION_IN_VIDEOS]        ?: AiSettings.DEFAULT.faceDetectionInVideos,
            pipelineMode                  = AiPipelineMode.fromString(prefs[Keys.PIPELINE_MODE] ?: AiPipelineMode.SINGLE_ACTIVE.name),
            disabledEnsembleModelIds      = prefs[Keys.DISABLED_ENSEMBLE_MODEL_IDS]      ?: emptySet(),
            ensembleOnlyWhileCharging     = prefs[Keys.ENSEMBLE_ONLY_WHILE_CHARGING]     ?: AiSettings.DEFAULT.ensembleOnlyWhileCharging,
            pauseEnsembleOnBatterySaver   = prefs[Keys.PAUSE_ENSEMBLE_ON_BATTERY_SAVER]  ?: AiSettings.DEFAULT.pauseEnsembleOnBatterySaver,
            disabledEnsembleDetectorIds   = prefs[Keys.DISABLED_ENSEMBLE_DETECTOR_IDS]   ?: emptySet(),
            disabledEnsembleRecognizerIds = prefs[Keys.DISABLED_ENSEMBLE_RECOGNIZER_IDS] ?: emptySet(),
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

    // ── Ensemble ──────────────────────────────────────────────────────────────

    suspend fun setPipelineMode(mode: AiPipelineMode) {
        dataStore.edit { it[Keys.PIPELINE_MODE] = mode.name }
    }

    /**
     * Marks [modelId] as disabled for ensemble execution.
     * Newly installed models are enabled by default (not in the disabled set).
     */
    suspend fun disableEnsembleModel(modelId: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.DISABLED_ENSEMBLE_MODEL_IDS] ?: emptySet()
            prefs[Keys.DISABLED_ENSEMBLE_MODEL_IDS] = current + modelId
        }
    }

    /** Re-enables [modelId] for ensemble execution. */
    suspend fun enableEnsembleModel(modelId: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.DISABLED_ENSEMBLE_MODEL_IDS] ?: emptySet()
            prefs[Keys.DISABLED_ENSEMBLE_MODEL_IDS] = current - modelId
        }
    }

    suspend fun setEnsembleOnlyWhileCharging(enabled: Boolean) {
        dataStore.edit { it[Keys.ENSEMBLE_ONLY_WHILE_CHARGING] = enabled }
    }

    suspend fun setPauseEnsembleOnBatterySaver(enabled: Boolean) {
        dataStore.edit { it[Keys.PAUSE_ENSEMBLE_ON_BATTERY_SAVER] = enabled }
    }

    // ── Face ensemble model participation ─────────────────────────────────────

    suspend fun disableEnsembleDetector(modelId: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.DISABLED_ENSEMBLE_DETECTOR_IDS] ?: emptySet()
            prefs[Keys.DISABLED_ENSEMBLE_DETECTOR_IDS] = current + modelId
        }
    }

    suspend fun enableEnsembleDetector(modelId: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.DISABLED_ENSEMBLE_DETECTOR_IDS] ?: emptySet()
            prefs[Keys.DISABLED_ENSEMBLE_DETECTOR_IDS] = current - modelId
        }
    }

    suspend fun disableEnsembleRecognizer(modelId: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.DISABLED_ENSEMBLE_RECOGNIZER_IDS] ?: emptySet()
            prefs[Keys.DISABLED_ENSEMBLE_RECOGNIZER_IDS] = current + modelId
        }
    }

    suspend fun enableEnsembleRecognizer(modelId: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.DISABLED_ENSEMBLE_RECOGNIZER_IDS] ?: emptySet()
            prefs[Keys.DISABLED_ENSEMBLE_RECOGNIZER_IDS] = current - modelId
        }
    }
}
