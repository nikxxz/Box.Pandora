package com.example.boxpandora.ml.ensemble

/**
 * Lightweight provenance record for a single ensemble worker invocation.
 *
 * Instances are stored in [EnsembleRunManifestStore] keyed by [runId].
 * Fused-suggestion rows already carry a per-asset [pipelineRunId] — this manifest
 * groups an entire worker run and carries the runtime context that produced it.
 *
 * @param runId                    Stable UUID for this worker invocation.
 * @param pipelineMode             [AiPipelineMode.name] in effect when the run started.
 * @param participatingModelIds    Sorted CSV of manifest IDs of all models that ran.
 * @param modelVersions            Sorted CSV of "id:versionKey" pairs for each model.
 * @param calibrationVersion       [FusionCalibration.version] in effect at run start.
 * @param reliabilityStatsTimestamp Wall-clock ms when reliability weights were snapshot-loaded.
 * @param runStartedAt             Wall-clock ms at job start.
 * @param runCompletedAt           Wall-clock ms when the run finished; 0 = in-progress or stopped.
 * @param assetsProcessed          Total assets touched in this run.
 * @param suggestionsWritten       Total fused suggestion rows emitted.
 */
data class EnsembleRunManifest(
    val runId: String,
    val pipelineMode: String,
    val participatingModelIds: String,
    val modelVersions: String,
    val calibrationVersion: String,
    val reliabilityStatsTimestamp: Long,
    val runStartedAt: Long,
    val runCompletedAt: Long = 0L,
    val assetsProcessed: Int = 0,
    val suggestionsWritten: Int = 0,
)
