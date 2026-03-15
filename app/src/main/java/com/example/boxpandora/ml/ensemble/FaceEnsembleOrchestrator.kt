package com.example.boxpandora.ml.ensemble

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.boxpandora.data.local.AppDatabase
import com.example.boxpandora.data.local.entity.FusedFace
import com.example.boxpandora.data.local.entity.FusedIdentitySuggestion
import com.example.boxpandora.data.local.entity.IdentityInferenceEvidence
import com.example.boxpandora.data.local.entity.ModelReliabilityStats
import com.example.boxpandora.ml.config.AiPipelineConfig
import com.example.boxpandora.ml.config.FusionThresholdConfig
import com.example.boxpandora.ml.detection.DetectorConfig
import com.example.boxpandora.ml.detection.FaceDetectionEngine
import com.example.boxpandora.ml.detection.MediaType
import com.example.boxpandora.ml.detection.ScrfdDetectionEngine
import com.example.boxpandora.ml.detection.YunetDetectionEngine
import com.example.boxpandora.ml.engine.EmbeddingUtils
import com.example.boxpandora.ml.manager.ModelManager
import com.example.boxpandora.ml.model.InstalledModel
import com.example.boxpandora.ml.model.ModelCategory
import com.example.boxpandora.ml.runtime.ModelRuntimeFactory
import java.io.Closeable
import java.util.UUID

private const val TAG = "FaceEnsembleOrchestrator"

/**
 * Minimum cosine similarity for a per-recognizer cluster candidate to be collected as
 * [IdentityEvidenceRecord]. Candidates below this are silently dropped before fusion.
 * Keeps the evidence list small and prevents low-signal matches from inflating fused scores.
 */
private const val IDENTITY_PRE_FILTER_THRESHOLD = 0.50f

/**
 * Orchestrates multi-detector face detection and multi-recognizer identity matching in
 * [AiPipelineMode.ENSEMBLE_ALL_ENABLED] mode.
 *
 * Per-asset pipeline:
 *  1. Detect faces with every enabled face detector → collect [FaceDetectionEvidenceRecord]s.
 *  2. Fuse detections with [FaceDetectionFusionEngine] (IoU-based box merging).
 *  3. For each fused face:
 *     a. Align the face crop from the anchor detector.
 *     b. Embed with every enabled face recognizer.
 *     c. Score each embedding against confirmed person-cluster centroids built with the
 *        same recognizer version → collect [IdentityEvidenceRecord]s.
 *     d. Fuse identity evidence with [IdentityFusionEngine].
 *  4. Persist [FusedFace], [IdentityInferenceEvidence], and [FusedIdentitySuggestion] rows.
 *
 * Guards:
 *  - [hasEnabledDetectors] must be true before calling [processAsset]; if false the worker
 *    should return [Result.failure].
 *  - Recognizer identity matching is silently skipped for assets where no confirmed clusters
 *    exist for a recognizer's version — no error, just zero identity evidence from that model.
 *
 * Lifecycle: create one instance per worker run; call [close] in a finally block.
 * Not thread-safe.
 *
 * @param config        Assembled pipeline config from worker startup.
 * @param modelManager  Used to resolve enabled installed models.
 * @param db            Room database for cluster centroid loading and result persistence.
 * @param context       Application context (not stored after init).
 */
class FaceEnsembleOrchestrator(
    private val config: AiPipelineConfig,
    private val modelManager: ModelManager,
    private val db: AppDatabase,
    context: Context,
) : Closeable {

    private val detectionFusionEngine = FaceDetectionFusionEngine()
    private val identityFusionEngine  = IdentityFusionEngine()
    private val reliabilityService    by lazy { ReliabilityUpdateService(db) }
    val pipelineRunId: String         = UUID.randomUUID().toString()

    // ── Build enabled detector engines ───────────────────────────────────────

    private val detectorEngines: Map<String, Pair<FaceDetectionEngine, InstalledModel>> =
        modelManager.getInstalledModels()
            .filter { it.metadata.category == ModelCategory.FACE_DETECTION
                   && it.metadata.id in config.enabledFaceDetectorIds }
            .mapNotNull { installed ->
                runCatching {
                    val runtime = ModelRuntimeFactory.create(installed.metadata, installed.file)
                    val engine: FaceDetectionEngine = when {
                        installed.metadata.id.startsWith("scrfd", ignoreCase = true) ->
                            ScrfdDetectionEngine(runtime, installed.metadata, DetectorConfig.DEFAULT)
                        else ->
                            YunetDetectionEngine(runtime, installed.metadata, DetectorConfig.DEFAULT)
                    }
                    installed.metadata.id to Pair(engine, installed)
                }.onFailure { e ->
                    Log.e(TAG, "Failed to load detector '${installed.metadata.id}' — skipping: ${e.message}")
                }.getOrNull()
            }
            .associate { it }

    // ── Build enabled recognizer embedders ───────────────────────────────────

    private val embedderRunners: List<EnsembleFaceEmbedder> =
        modelManager.getInstalledModels()
            .filter { it.metadata.category == ModelCategory.FACE_EMBEDDING
                   && it.metadata.id in config.enabledFaceRecognizerIds }
            .mapNotNull { installed ->
                runCatching { EnsembleFaceEmbedder(installed) }
                    .onFailure { e ->
                        Log.e(TAG, "Failed to load recognizer '${installed.metadata.id}' — skipping: ${e.message}")
                    }
                    .getOrNull()
            }

    init {
        Log.i(TAG, "Initialized — ${detectorEngines.size} detector(s): " +
            "[${detectorEngines.keys.joinToString()}], " +
            "${embedderRunners.size} recognizer(s): [${embedderRunners.joinToString { it.modelId }}]")
    }

    /** True when at least one face detector engine was successfully built. */
    fun hasEnabledDetectors(): Boolean = detectorEngines.isNotEmpty()

    /**
     * Composite detector-version key for face_scan_log.
     * Changes when the set of enabled detectors changes, triggering re-detection.
     */
    val compositeDetectorVersion: String =
        config.enabledFaceDetectorIds.sorted().joinToString(",")

    // ── Lazy caches (loaded once per orchestrator run) ────────────────────────

    /** Maps recognizer manifest ID → derived reliability weight for identity fusion. */
    private var identityWeightCache: Map<String, Float>? = null

    private suspend fun getIdentityWeights(): Map<String, Float> {
        identityWeightCache?.let { return it }
        return db.modelReliabilityStatsDao().getAllIdentityWeights()
            .associate { it.modelId to it.derivedWeight }
            .also { identityWeightCache = it }
    }

    // ── Cluster centroid cache (loaded lazily on first processAsset call) ─────

    /** Per-recognizer-version list of (clusterId, tagKey, tagName, tagId, centroid). */
    private var clusterCache: Map<String, List<ClusterRef>>? = null

    private data class ClusterRef(
        val clusterId: String,
        val tagKey: String,
        val tagName: String,
        val tagId: Long,
        val centroid: FloatArray,
    )

    private suspend fun getClusterCache(): Map<String, List<ClusterRef>> {
        clusterCache?.let { return it }

        val confirmedClusters = db.faceClusterDao().getConfirmedClusters()
            .filter { it.centroidBlob != null && it.n > 0 && it.tagId != null }

        val cache = mutableMapOf<String, MutableList<ClusterRef>>()
        for (cluster in confirmedClusters) {
            val tag = db.tagDao().getById(cluster.tagId!!) ?: continue
            val centroid = EmbeddingUtils.bytesToFloatArray(cluster.centroidBlob!!)
            val ref = ClusterRef(
                clusterId = cluster.clusterId,
                tagKey    = tag.normalizedName,
                tagName   = tag.name,
                tagId     = tag.id,
                centroid  = centroid,
            )
            cache.getOrPut(cluster.embedderVersion) { mutableListOf() }.add(ref)
        }

        val total = cache.values.sumOf { it.size }
        Log.d(TAG, "Cluster cache: $total confirmed clusters across ${cache.size} recognizer version(s)")
        return cache.also { clusterCache = it }
    }

    // ── Asset processing ──────────────────────────────────────────────────────

    /**
     * Runs the full ensemble face pipeline for a single asset.
     *
     * @param assetId     URI of the source media item.
     * @param bitmap      Decoded bitmap for [assetId]; not recycled by this call.
     * @return Pair(fused faces detected, identity suggestions written).
     */
    suspend fun processAsset(assetId: String, bitmap: Bitmap): Pair<Int, Int> {
        // ── Step 1: detect with all enabled detectors ─────────────────────────
        val evidencePairs = mutableListOf<Pair<FaceDetectionEvidenceRecord, com.example.boxpandora.ml.detection.DetectionResult>>()
        for ((_, pair) in detectorEngines) {
            val (engine, installed) = pair
            runCatching { engine.detect(bitmap) }
                .onSuccess { detections ->
                    for (det in detections) {
                        evidencePairs.add(
                            FaceDetectionEvidenceRecord(
                                assetId         = assetId,
                                detectorId      = installed.metadata.id,
                                detectorVersion = installed.metadata.roomVersionKey,
                                leftNorm        = det.leftNorm,
                                topNorm         = det.topNorm,
                                rightNorm       = det.rightNorm,
                                bottomNorm      = det.bottomNorm,
                                confidence      = det.score,
                                landmarks       = det.landmarks,
                            ) to det
                        )
                    }
                }
                .onFailure { e ->
                    Log.w(TAG, "Detection failed for ${installed.metadata.id} on $assetId: ${e.message}")
                }
        }

        // ── Step 2: fuse detections ───────────────────────────────────────────
        val fusedFaces = detectionFusionEngine.fuse(assetId, evidencePairs)
        if (fusedFaces.isEmpty()) {
            // Write empty result (clean up any stale rows from previous runs)
            db.fusedFaceDao().deleteForAsset(assetId)
            db.identityInferenceEvidenceDao().deleteForAsset(assetId)
            db.fusedIdentitySuggestionDao().deleteForAsset(assetId)
            return Pair(0, 0)
        }

        // ── Step 3: align, embed, match identities ────────────────────────────
        val clusters         = getClusterCache()
        val identityWeights  = getIdentityWeights()
        val fusedFaceEntities   = mutableListOf<FusedFace>()
        val identityEvidenceAll = mutableListOf<IdentityInferenceEvidence>()
        val identitySuggestions = mutableListOf<FusedIdentitySuggestion>()
        val now = System.currentTimeMillis()

        for (fused in fusedFaces) {
            val anchorEnginePair = detectorEngines[fused.anchorDetectorId]
            if (anchorEnginePair == null) {
                Log.w(TAG, "Anchor detector ${fused.anchorDetectorId} not found — skipping face ${fused.fusedFaceId}")
                continue
            }

            // Align face crop using the anchor detector
            val alignedCrop = runCatching {
                anchorEnginePair.first.alignFace(bitmap, fused.anchorDetectionResult)
            }.getOrElse { e ->
                Log.w(TAG, "Alignment failed for ${fused.fusedFaceId}: ${e.message}")
                null
            } ?: continue

            // Build FusedFace entity
            fusedFaceEntities.add(
                FusedFace(
                    fusedFaceId             = fused.fusedFaceId,
                    assetId                 = assetId,
                    faceIndex               = fused.faceIndex,
                    leftNorm                = fused.leftNorm.toDouble(),
                    topNorm                 = fused.topNorm.toDouble(),
                    rightNorm               = fused.rightNorm.toDouble(),
                    bottomNorm              = fused.bottomNorm.toDouble(),
                    fusedConfidence         = fused.fusedConfidence.toDouble(),
                    strongestConfidence     = fused.strongestConfidence.toDouble(),
                    contributingDetectorIds = fused.contributingDetectorIds.joinToString(","),
                    anchorDetectorId        = fused.anchorDetectorId,
                    anchorDetectorVersion   = fused.anchorDetectorVersion,
                    pipelineRunId           = pipelineRunId,
                    createdAt               = now,
                )
            )

            // Embed and match identities
            val faceIdentityEvidence = mutableListOf<IdentityEvidenceRecord>()
            try {
                for (embedder in embedderRunners) {
                    val embedResult = runCatching { embedder.embed(alignedCrop) }
                        .getOrElse { e ->
                            Log.w(TAG, "Embedding failed [${embedder.modelId}] for ${fused.fusedFaceId}: ${e.message}")
                            reliabilityService.applyModelPenalty(
                                modelId          = embedder.modelId,
                                pipelineCategory = ModelReliabilityStats.PIPELINE_IDENTITY,
                                penalty          = FusionThresholdConfig.INFERENCE_FAILURE_PENALTY,
                                reason           = "inference_failure",
                            )
                            null
                        } ?: continue

                    val clusterRefs = clusters[embedder.modelVersionKey] ?: continue

                    for (ref in clusterRefs) {
                        val sim = EmbeddingUtils.cosineSimilarity(embedResult.embedding, ref.centroid)
                        if (sim < IDENTITY_PRE_FILTER_THRESHOLD) continue
                        faceIdentityEvidence.add(
                            IdentityEvidenceRecord(
                                fusedFaceId      = fused.fusedFaceId,
                                recognizerId     = embedder.modelId,
                                recognizerVersion = embedder.modelVersionKey,
                                clusterId        = ref.clusterId,
                                tagKey           = ref.tagKey,
                                tagName          = ref.tagName,
                                tagId            = ref.tagId,
                                similarityScore  = sim,
                            )
                        )
                    }
                }
            } finally {
                alignedCrop.recycle()
            }

            // Fuse identity evidence for this face
            val fusedIdentities = identityFusionEngine.fuse(
                fusedFaceId          = fused.fusedFaceId,
                assetId              = assetId,
                evidence             = faceIdentityEvidence,
                confidenceThreshold  = config.confidenceThreshold,
                reliabilityWeights   = identityWeights,
            )

            // Convert to DB entities
            identityEvidenceAll.addAll(faceIdentityEvidence.map { ev ->
                IdentityInferenceEvidence(
                    assetId           = assetId,
                    fusedFaceId       = ev.fusedFaceId,
                    clusterId         = ev.clusterId,
                    tagKey            = ev.tagKey,
                    tagName           = ev.tagName,
                    tagId             = ev.tagId,
                    recognizerId      = ev.recognizerId,
                    recognizerVersion = ev.recognizerVersion,
                    similarityScore   = ev.similarityScore,
                    pipelineRunId     = pipelineRunId,
                    inferredAt        = now,
                )
            })

            identitySuggestions.addAll(fusedIdentities.map { fi ->
                FusedIdentitySuggestion(
                    assetId                     = assetId,
                    fusedFaceId                 = fi.fusedFaceId,
                    clusterId                   = fi.clusterId,
                    tagKey                      = fi.tagKey,
                    tagName                     = fi.tagName,
                    tagId                       = fi.tagId,
                    fusedScore                  = fi.fusedScore,
                    contributingRecognizerIds   = fi.contributingRecognizerIds.joinToString(","),
                    contributingRecognizerCount = fi.contributingRecognizerIds.size,
                    strongestScore              = fi.strongestScore,
                    isAmbiguous                 = fi.isAmbiguous,
                    agreementLevelOrdinal       = fi.agreementLevel.ordinal,
                    status                      = FusedIdentitySuggestion.STATUS_PENDING,
                    pipelineRunId               = pipelineRunId,
                    createdAt                   = now,
                )
            })
        }

        // ── Step 4: persist ───────────────────────────────────────────────────
        db.fusedFaceDao().deleteForAsset(assetId)
        db.fusedFaceDao().insertAll(fusedFaceEntities)

        db.identityInferenceEvidenceDao().deleteForAsset(assetId)
        if (identityEvidenceAll.isNotEmpty()) {
            db.identityInferenceEvidenceDao().insertAll(identityEvidenceAll)
        }

        db.fusedIdentitySuggestionDao().deleteForAsset(assetId)
        if (identitySuggestions.isNotEmpty()) {
            db.fusedIdentitySuggestionDao().insertAll(identitySuggestions)
        }

        Log.d(TAG, "processAsset($assetId): ${fusedFaceEntities.size} fused faces, " +
            "${identitySuggestions.size} identity suggestions")

        return Pair(fusedFaceEntities.size, identitySuggestions.size)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun close() {
        detectorEngines.values.forEach { (engine, _) ->
            runCatching { engine.close() }
        }
        embedderRunners.forEach { runCatching { it.close() } }
        Log.d(TAG, "Closed — $pipelineRunId")
    }
}
