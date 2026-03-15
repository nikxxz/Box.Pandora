package com.example.boxpandora.ml.config

/**
 * AI execution mode that controls how scene models are selected and run.
 *
 * [SINGLE_ACTIVE] – default. Only the user's chosen active scene model runs.
 *   All existing pipeline behaviour is preserved unchanged.
 *
 * [ENSEMBLE_ALL_ENABLED] – advanced override. All installed scene models that
 *   the user has not explicitly disabled are run for every asset. Their outputs
 *   are fused by [SuggestionFusionEngine] into one unified suggestion list that
 *   is stored in the [fused_scene_suggestions] table and surfaced via the
 *   repository layer without the caller needing to know the source.
 *
 * The stored value in DataStore is the enum name (e.g. "SINGLE_ACTIVE").
 * Always read from [AiSettings.pipelineMode]; do not infer the mode from
 * scattered UI toggle state.
 */
enum class AiPipelineMode {
    SINGLE_ACTIVE,
    ENSEMBLE_ALL_ENABLED;

    companion object {
        /** Safe deserialisation — falls back to [SINGLE_ACTIVE] for any unknown string. */
        fun fromString(value: String): AiPipelineMode =
            entries.firstOrNull { it.name == value } ?: SINGLE_ACTIVE
    }
}
