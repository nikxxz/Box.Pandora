/**
 * FaceIndexer
 *
 * Batch-processes undetected images to:
 *   1. Detect faces (ML Kit — always available via Play Services)
 *   2. Embed detected faces (MobileFaceNet — skipped if model not installed)
 *   3. Cluster each new face embedding into an existing cluster or create a new one
 *
 * Only runs when the device is idle (called from requestIdleCallback).
 * Has the same _running / cancel guard pattern as EmbeddingIndexer.
 *
 * Usage:
 *   FaceIndexer.runFaceBatch({ onProgress, onError })
 *   FaceIndexer.cancel()
 *   FaceIndexer.getStats()
 */

import { InteractionManager } from 'react-native';
import { FaceBridgeModule, FACE_MODEL_VERSION } from './FaceBridgeModule';
import { FaceService } from '../database/FaceService';
import { FaceEmbeddingService } from '../database/FaceEmbeddingService';
import { FaceClusterService } from '../database/FaceClusterService';
import { FaceClusterBackfillService } from '../database/FaceClusterBackfillService';

// ─── Constants ────────────────────────────────────────────────────────────────

const BATCH_SIZE = 20;
const INTER_ITEM_DELAY_MS = 80;
/** Minimum quality score to attempt embedding (skip very blurry/tiny faces). */
const MIN_QUALITY_TO_EMBED = 0.3;

// ─── Global state ─────────────────────────────────────────────────────────────

let _cancelled = false;
let _running = false;
/** When false, all face indexing calls are silently skipped. */
let _enabled = true;

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Detect and embed faces for one batch of unprocessed images.
 *
 * @param {{
 *   batchSize?:  number,
 *   onProgress?: (current: number, total: number, assetId: string) => void,
 *   onError?:    (assetId: string, err: Error) => void,
 * }} opts
 * @returns {Promise<{ detected: number, embedded: number, errors: number, done: boolean }>}
 */
export async function runFaceBatch(opts = {}) {
  if (!_enabled) {
    return { detected: 0, embedded: 0, errors: 0, done: true };
  }
  if (_running) {
    console.log('[FaceIndexer] runFaceBatch skipped — already running');
    return { detected: 0, embedded: 0, errors: 0, done: false };
  }

  const { batchSize = BATCH_SIZE, onProgress = null, onError = null } = opts;
  _cancelled = false;
  _running = true;

  try {
    const result = await _runBatchImpl({ batchSize, onProgress, onError });
    // When the final batch is done, retroactively bind face clusters to any
    // existing people-category tags in the database.
    if (result.done) {
      _triggerFaceBackfill();
    }
    return result;
  } finally {
    _running = false;
  }
}

/**
 * Run face batches until all images are processed or cancelled.
 *
 * @param {{
 *   onBatchComplete?: (stats: { detected, embedded, errors, batchNum }) => void,
 *   onProgress?:      (current, total, assetId) => void,
 *   onError?:         (assetId, err) => void,
 * }} opts
 * @returns {Promise<{ totalDetected: number, totalEmbedded: number, totalErrors: number }>}
 */
export async function runToCompletion(opts = {}) {
  if (!_enabled) {
    return { totalDetected: 0, totalEmbedded: 0, totalErrors: 0 };
  }
  if (_running) {
    console.log('[FaceIndexer] runToCompletion skipped — already running');
    return { totalDetected: 0, totalEmbedded: 0, totalErrors: 0 };
  }

  const { onBatchComplete, onProgress, onError } = opts;
  _cancelled = false;
  _running = true;

  let totalDetected = 0;
  let totalEmbedded = 0;
  let totalErrors = 0;
  let batchNum = 0;

  try {
    while (!_cancelled) {
      const result = await _runBatchImpl({ onProgress, onError });
      totalDetected += result.detected;
      totalEmbedded += result.embedded;
      totalErrors += result.errors;
      batchNum++;
      onBatchComplete?.({ ...result, batchNum });
      if (result.done) {
        // Retroactively link face clusters to existing people-category tags.
        _triggerFaceBackfill();
        break;
      }
      await _sleep(300);
    }
  } finally {
    _running = false;
  }

  return { totalDetected, totalEmbedded, totalErrors };
}

/**
 * Cancel any in-progress run.
 */
export function cancel() {
  _cancelled = true;
}

/**
 * Whether face indexing is currently running.
 */
export function isRunning() {
  return _running;
}

/**
 * Whether the face detection native module is available.
 * Detection always works (ML Kit Play Services).
 * Embedding requires the MobileFaceNet model download.
 */
export function isFaceDetectionAvailable() {
  return FaceBridgeModule.isNativeAvailable();
}

/**
 * Return current face indexing statistics.
 *
 * @returns {Promise<{ undetected: number, faceStats: object, running: boolean }>}
 */
export async function getStats() {
  const [undetected, faceStats] = await Promise.all([
    FaceService.countUndetected(),
    FaceService.getFaceStats(),
  ]);
  return { undetected, ...faceStats, running: _running };
}

/** Enable or disable face indexing at runtime. */
export function setFaceIndexingEnabled(enabled) {
  _enabled = Boolean(enabled);
  if (!_enabled && _running) {
    _cancelled = true; // abort any in-progress run
  }
}

export function isFaceIndexingEnabled() {
  return _enabled;
}

export const FaceIndexer = {
  runFaceBatch,
  runToCompletion,
  cancel,
  isRunning,
  isFaceDetectionAvailable,
  getStats,
  setFaceIndexingEnabled,
  isFaceIndexingEnabled,
};

// ─── Implementation ───────────────────────────────────────────────────────────

async function _runBatchImpl({ batchSize, onProgress, onError }) {
  const assetIds = await FaceService.getUndetectedAssetIds(batchSize);

  if (!assetIds.length) {
    return { detected: 0, embedded: 0, errors: 0, done: true };
  }

  let detected = 0;
  let embedded = 0;
  let errors = 0;

  for (let i = 0; i < assetIds.length; i++) {
    if (_cancelled) break;

    const assetId = assetIds[i];
    try {
      // ── Step 1: Detect faces ────────────────────────────────────────────
      const faces = await FaceBridgeModule.detectFaces(assetId);

      // Always save detection result (even empty = no faces) so this asset
      // is not re-processed on the next batch.
      if (faces.length > 0) {
        await FaceService.saveFaces(assetId, faces);
        detected += faces.length;

        // ── Step 2: Embed each quality face ────────────────────────────────
        for (const face of faces) {
          if (_cancelled) break;
          if (face.qualityScore < MIN_QUALITY_TO_EMBED) continue;

          await _embedAndClusterFace(assetId, face);
          embedded++;
        }
      } else {
        // No faces — mark as processed with an empty record so we skip it.
        // We do this by inserting a sentinel row; simpler: saveFaces with []
        // already handles this via the DELETE before insert pattern.
        // The getUndetectedAssetIds query uses NOT EXISTS on detected_faces,
        // so we need at least one row. Skip: the DELETE+INSERT of zero rows
        // means this asset keeps appearing. Insert a sentinel instead.
        await _markAsProcessed(assetId);
      }

      onProgress?.(i + 1, assetIds.length, assetId);
    } catch (err) {
      errors++;
      onError?.(assetId, err);
      console.warn(
        `[FaceIndexer] Failed to process ${assetId}:`,
        err?.message ?? err,
      );
      // Mark as processed even on error to avoid infinite retry.
      await _markAsProcessed(assetId).catch(() => {});
    }

    if (INTER_ITEM_DELAY_MS > 0 && i < assetIds.length - 1) {
      await _sleep(INTER_ITEM_DELAY_MS);
    }
  }

  // Check if more remain.
  const remaining = await FaceService.getUndetectedAssetIds(1);
  const done = remaining.length === 0;

  return { detected, embedded, errors, done };
}

async function _embedAndClusterFace(assetId, face) {
  // Skip if already embedded (concurrent guard).
  const alreadyEmbedded = await FaceEmbeddingService.hasEmbedding(
    face.faceId,
    FACE_MODEL_VERSION,
  );
  if (alreadyEmbedded) return;

  const { embedding, modelAvailable } = await FaceBridgeModule.embedFace(
    assetId,
    {
      leftNorm: face.leftNorm,
      topNorm: face.topNorm,
      rightNorm: face.rightNorm,
      bottomNorm: face.bottomNorm,
    },
  );

  if (!modelAvailable || !embedding || embedding.every(v => v === 0)) {
    // Model not installed yet — skip embedding/clustering but keep detection result.
    return;
  }

  // Save embedding.
  await FaceEmbeddingService.saveEmbedding(
    face.faceId,
    FACE_MODEL_VERSION,
    embedding,
    embedding.length,
  );

  // Find or create cluster.
  const clusterId = await FaceClusterService.findNearestCluster(embedding);
  if (clusterId) {
    await FaceService.assignCluster(face.faceId, clusterId);
    await FaceClusterService.updateClusterCentroid(clusterId, embedding);
  } else {
    const newClusterId = await FaceClusterService.createCluster(embedding);
    await FaceService.assignCluster(face.faceId, newClusterId);
  }
}

/**
 * Insert a zero-quality sentinel face row so the asset is excluded from
 * future getUndetectedAssetIds() queries (no faces found).
 */
async function _markAsProcessed(assetId) {
  await FaceService.saveFaces(assetId, [
    {
      faceIndex: -1, // sentinel: -1 = "no faces found"
      leftNorm: 0,
      topNorm: 0,
      rightNorm: 0,
      bottomNorm: 0,
      widthPx: 0,
      heightPx: 0,
      yaw: 0,
      pitch: 0,
      roll: 0,
      qualityScore: 0,
    },
  ]);
}

/**
 * Fire-and-forget backfill of face cluster → people-tag bindings using
 * existing media_tags history.  Deferred until the UI is idle so it doesn't
 * compete with scroll / navigation frames.
 */
function _triggerFaceBackfill() {
  InteractionManager.runAfterInteractions(() => {
    FaceClusterBackfillService.backfillAll().catch(err =>
      console.warn(
        '[FaceIndexer] face cluster backfill failed:',
        err?.message ?? err,
      ),
    );
  });
}

function _sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}
