/**
 * EmbeddingIndexer
 *
 * Batch-computes TFLite embeddings for all unindexed photos and stores
 * them in the `image_embeddings` table.
 *
 * Strategy:
 *   - Poll for unindexed assets in batches of BATCH_SIZE.
 *   - Skip assets already indexed for the current MODEL_VERSION.
 *   - Emit optional progress callbacks so UI can show a progress bar.
 *   - Respects a `cancelled` flag to allow early stop.
 *
 * Scheduling guidance (call from wherever your background job runs):
 *   EmbeddingIndexer.runBatch({ onProgress });
 *
 * Triggered by:
 *   — App foreground, after MediaIndexer completes a scan (you schedule this).
 *   — Explicitly from the debug screen.
 */

import { InteractionManager } from 'react-native';
import { embedImage, MODEL_VERSION } from './EmbeddingBridgeModule';
import { ImageEmbeddingService } from '../database/ImageEmbeddingService';
import { PrototypeBackfillService } from '../database/PrototypeBackfillService';
import { DatabaseService } from '../database/DatabaseService';

// ─── Constants ────────────────────────────────────────────────────────────────

const BATCH_SIZE = 30;

/** Delay between items in ms (throttle on slower devices). */
const INTER_ITEM_DELAY_MS = 50;

// ─── Global state ─────────────────────────────────────────────────────────────
let _cancelled = false;
/** Prevents overlapping runBatch / runToCompletion calls. */
let _running = false;
/** When false, all indexing calls are silently skipped. */
let _enabled = true;

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Index one batch of up to BATCH_SIZE unindexed images.
 *
 * @param {{
 *   batchSize?: number,
 *   onProgress?: (current: number, total: number, assetId: string) => void,
 *   onError?:    (assetId: string, err: Error) => void,
 * }} opts
 * @returns {Promise<{ indexed: number, skipped: number, errors: number, done: boolean }>}
 *   done = true when no more unindexed assets remain.
 */
export async function runBatch(opts = {}) {
  if (!_enabled) {
    return { indexed: 0, skipped: 0, errors: 0, done: true };
  }
  if (_running) {
    console.log('[EmbeddingIndexer] runBatch skipped — already running');
    return { indexed: 0, skipped: 0, errors: 0, done: false };
  }

  const { batchSize = BATCH_SIZE, onProgress = null, onError = null } = opts;
  _cancelled = false;
  _running = true;
  try {
    const result = await _runBatchImpl({ batchSize, onProgress, onError });
    // When this was the final batch, seed any missing tag prototypes from
    // historic tag-image pairs so existing custom tags become suggestion candidates.
    if (result.done) {
      _triggerBackfill();
    }
    return result;
  } finally {
    _running = false;
  }
}

async function _runBatchImpl({
  batchSize = BATCH_SIZE,
  onProgress = null,
  onError = null,
} = {}) {
  const assetIds = await ImageEmbeddingService.getUnindexedAssetIds(
    MODEL_VERSION,
    batchSize,
  );

  if (!assetIds.length) {
    return { indexed: 0, skipped: 0, errors: 0, done: true };
  }

  let indexed = 0;
  let skipped = 0;
  let errors = 0;

  for (let i = 0; i < assetIds.length; i++) {
    if (_cancelled) break;

    const assetId = assetIds[i];

    // Double-check (another job might have indexed it concurrently).
    const already = await ImageEmbeddingService.hasEmbedding(
      assetId,
      MODEL_VERSION,
    );
    if (already) {
      skipped++;
      continue;
    }

    try {
      const { embedding, dim, modelVersion } = await embedImage(assetId);
      await ImageEmbeddingService.saveEmbedding(
        assetId,
        modelVersion,
        embedding,
        dim,
      );
      indexed++;
      onProgress?.(i + 1, assetIds.length, assetId);
    } catch (err) {
      errors++;
      onError?.(assetId, err);
      console.warn(
        `[EmbeddingIndexer] Failed to embed ${assetId}:`,
        err?.message ?? err,
      );
    }

    // Small throttle to avoid saturating the JS thread.
    if (INTER_ITEM_DELAY_MS > 0 && i < assetIds.length - 1) {
      await _sleep(INTER_ITEM_DELAY_MS);
    }
  }

  // Check if there are more remaining after this batch.
  const remaining = await ImageEmbeddingService.getUnindexedAssetIds(
    MODEL_VERSION,
    1,
  );
  const done = remaining.length === 0;

  return { indexed, skipped, errors, done };
}

/**
 * Run batches until all assets are indexed or the job is cancelled.
 *
 * @param {{
 *   onBatchComplete?: (stats: { indexed, skipped, errors, batchNum }) => void,
 *   onProgress?:      (current, total, assetId) => void,
 *   onError?:         (assetId, err) => void,
 *   maxBatches?:      number   (default = Infinity — run to completion)
 * }} opts
 * @returns {Promise<{ totalIndexed: number, totalErrors: number }>}
 */
export async function runToCompletion(opts = {}) {
  if (!_enabled) {
    return { totalIndexed: 0, totalErrors: 0 };
  }
  if (_running) {
    console.log('[EmbeddingIndexer] runToCompletion skipped — already running');
    return { totalIndexed: 0, totalErrors: 0 };
  }

  const { onBatchComplete, onProgress, onError, maxBatches = Infinity } = opts;
  _cancelled = false;
  _running = true;
  try {
    return await _runToCompletionImpl({
      onBatchComplete,
      onProgress,
      onError,
      maxBatches,
    });
  } finally {
    _running = false;
  }
}

async function _runToCompletionImpl({
  onBatchComplete,
  onProgress,
  onError,
  maxBatches = Infinity,
} = {}) {
  let totalIndexed = 0;
  let totalErrors = 0;
  let batchNum = 0;

  while (!_cancelled && batchNum < maxBatches) {
    const result = await _runBatchImpl({ onProgress, onError });
    totalIndexed += result.indexed;
    totalErrors += result.errors;
    batchNum++;
    onBatchComplete?.({ ...result, batchNum });
    if (result.done) {
      // Seed missing tag prototypes once all embeddings are indexed.
      _triggerBackfill();
      break;
    }
    // Brief pause between batches.
    await _sleep(200);
  }

  return { totalIndexed, totalErrors };
}

/**
 * Index up to `n` most-recent unindexed images in recent-first order.
 * Used after a model download to quickly bring the newest photos up to date
 * without waiting for a full runToCompletion() pass.
 *
 * @param {number} n   — max total assets to index (default 500)
 * @param {{
 *   onBatchComplete?: (stats: { indexed, skipped, errors, batchNum }) => void,
 *   onProgress?:      (current, total, assetId) => void,
 *   onError?:         (assetId, err) => void,
 * }} opts
 * @returns {Promise<{ totalIndexed: number, totalErrors: number }>}
 */
export async function runRecentFirst(n = 500, opts = {}) {
  if (_running) {
    console.log('[EmbeddingIndexer] runRecentFirst skipped — already running');
    return { totalIndexed: 0, totalErrors: 0 };
  }

  const { onBatchComplete, onProgress, onError } = opts;
  _cancelled = false;
  _running = true;

  let totalIndexed = 0;
  let totalErrors = 0;
  let batchNum = 0;

  try {
    while (!_cancelled && totalIndexed + totalErrors < n) {
      const remaining = n - totalIndexed - totalErrors;
      const batchSize = Math.min(BATCH_SIZE, remaining);
      const result = await _runBatchImpl({ batchSize, onProgress, onError });
      totalIndexed += result.indexed;
      totalErrors += result.errors;
      batchNum++;
      onBatchComplete?.({ ...result, batchNum });
      if (result.done) break;
      await _sleep(200);
    }
  } finally {
    _running = false;
  }

  return { totalIndexed, totalErrors };
}

/**
 * Cancel any in-progress indexing run.
 */
export function cancel() {
  _cancelled = true;
}

/**
 * Return current indexing statistics.
 *
 * @returns {Promise<{ total: number, indexed: number, pending: number }>}
 */
export async function getIndexingStats() {
  const [totalRow] = await _queryTotalImages();
  const indexed = await ImageEmbeddingService.countIndexed(MODEL_VERSION);
  const total = totalRow ?? 0;
  return {
    total,
    indexed,
    pending: Math.max(0, total - indexed),
    running: _running,
  };
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Fire-and-forget backfill of tag prototypes from historic tag-image data.
 * Only runs for tags that don't yet have a prototype.
 */
function _triggerBackfill() {
  // Defer until UI is idle — backfill iterates every tag's embeddings which
  // generates many SQLite reads and must not compete with scroll frames.
  InteractionManager.runAfterInteractions(() => {
    PrototypeBackfillService.backfillAll(MODEL_VERSION).catch(err =>
      console.warn(
        '[EmbeddingIndexer] prototype backfill failed:',
        err?.message ?? err,
      ),
    );
  });
}

function _sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function _queryTotalImages() {
  try {
    const db = DatabaseService.getDb();
    const { rows } = await db.execute(
      `SELECT COUNT(*) AS n
       FROM   media_index m
       LEFT  JOIN albums a ON a.id = m.album_id
       WHERE  m.media_type = 'image'
         AND  m.hidden = 0
         AND  (a.hidden IS NULL OR a.hidden = 0)`,
    );
    return [rows[0]?.n ?? 0];
  } catch {
    return [0];
  }
}

/** Enable or disable scene embedding indexing at runtime. */
export function setSceneIndexingEnabled(enabled) {
  _enabled = Boolean(enabled);
  if (!_enabled && _running) {
    _cancelled = true; // abort any in-progress run
  }
}

export function isSceneIndexingEnabled() {
  return _enabled;
}

export const EmbeddingIndexer = {
  runBatch,
  runToCompletion,
  runRecentFirst,
  cancel,
  getIndexingStats,
  setSceneIndexingEnabled,
  isSceneIndexingEnabled,
  MODEL_VERSION,
};
