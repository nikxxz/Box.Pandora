package com.example.boxpandora.ml.config

/**
 * Assembled runtime configuration for the AI execution pipeline.
 *
 * This is the single object that workers and orchestrators read to understand
 * how to behave. It is derived from [AiSettings] (DataStore) and the caller's
 * knowledge of the currently installed models. Do not thread raw [AiSettings]
 * or scattered booleans through the pipeline — build one [AiPipelineConfig]
 * at worker startup and pass it down.
 *
 * @param pipelineMode               [AiPipelineMode.SINGLE_ACTIVE] or [ENSEMBLE_ALL_ENABLED].
 * @param enabledSceneModelIds       IDs of scene models eligible to run in ensemble mode.
 *                                   Ignored when [pipelineMode] is [SINGLE_ACTIVE].
 * @param activeSceneModelId         ID of the single active scene model; null when none is set.
 * @param enabledFaceDetectorIds     IDs of face detector models eligible to run in ensemble mode.
 *                                   Ignored when [pipelineMode] is [SINGLE_ACTIVE].
 * @param enabledFaceRecognizerIds   IDs of face recognizer models eligible to run in ensemble mode.
 *                                   Ignored when [pipelineMode] is [SINGLE_ACTIVE].
 * @param confidenceThreshold        Minimum fused / single-model score to surface a suggestion.
 * @param backgroundIndexingEnabled  Whether background (non-user-triggered) runs are permitted.
 * @param ensembleOnlyWhileCharging  When true, background ensemble runs require charging.
 * @param pauseEnsembleOnBatterySaver When true, background ensemble runs are skipped in
 *                                   battery-saver mode (enforced at scheduling time).
 *
 * Future safety guard fields can be added here without touching individual workers.
 */
data class AiPipelineConfig(
    val pipelineMode: AiPipelineMode,
    val enabledSceneModelIds: Set<String>,
    val activeSceneModelId: String?,
    val enabledFaceDetectorIds: Set<String>,
    val enabledFaceRecognizerIds: Set<String>,
    val confidenceThreshold: Float,
    val backgroundIndexingEnabled: Boolean,
    val ensembleOnlyWhileCharging: Boolean,
    val pauseEnsembleOnBatterySaver: Boolean,
) {
    /** True when ensemble mode is active and at least one scene model is eligible to run. */
    val isEnsembleReady: Boolean
        get() = pipelineMode == AiPipelineMode.ENSEMBLE_ALL_ENABLED && enabledSceneModelIds.isNotEmpty()

    /** True when ensemble mode is active and at least one face detector is eligible to run. */
    val isFaceEnsembleReady: Boolean
        get() = pipelineMode == AiPipelineMode.ENSEMBLE_ALL_ENABLED && enabledFaceDetectorIds.isNotEmpty()

    companion object {
        /**
         * Builds an [AiPipelineConfig] from persisted [AiSettings] and the sets of
         * currently installed model IDs for each relevant category.
         *
         * @param settings                 Current persisted AI settings snapshot.
         * @param installedSceneModelIds   IDs of all installed (not necessarily active) scene models.
         * @param activeSceneModelId       ID of the currently active scene model; null if none.
         * @param installedDetectorIds     IDs of all installed face detector models.
         * @param installedRecognizerIds   IDs of all installed face recognizer models.
         */
        fun from(
            settings: AiSettings,
            installedSceneModelIds: Set<String>,
            activeSceneModelId: String?,
            installedDetectorIds: Set<String> = emptySet(),
            installedRecognizerIds: Set<String> = emptySet(),
        ): AiPipelineConfig {
            return AiPipelineConfig(
                pipelineMode              = settings.pipelineMode,
                enabledSceneModelIds      = installedSceneModelIds - settings.disabledEnsembleModelIds,
                activeSceneModelId        = activeSceneModelId,
                enabledFaceDetectorIds    = installedDetectorIds - settings.disabledEnsembleDetectorIds,
                enabledFaceRecognizerIds  = installedRecognizerIds - settings.disabledEnsembleRecognizerIds,
                confidenceThreshold       = settings.confidenceThreshold,
                backgroundIndexingEnabled = settings.backgroundIndexingEnabled,
                ensembleOnlyWhileCharging = settings.ensembleOnlyWhileCharging,
                pauseEnsembleOnBatterySaver = settings.pauseEnsembleOnBatterySaver,
            )
        }
    }
}
