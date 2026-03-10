/**
 * HeuristicTagService
 *
 * Persists and retrieves cached heuristic detector results from the
 * `heuristic_tags` table.
 *
 * Schema (v7):
 *   heuristic_tags (asset_id, tag_key, score, created_at)
 *   PRIMARY KEY (asset_id, tag_key)
 */

import { DatabaseService } from './DatabaseService';

// How long a heuristic result stays fresh before we re-run (ms).
// 7 days — heuristic results are stable for unchanged photos.
const DEFAULT_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;

/**
 * Get all cached heuristic tags for an asset.
 *
 * @param {string} assetId
 * @param {{ maxAgeMs?: number }} opts
 * @returns {Promise<Array<{ tagKey: string, score: number, createdAt: number }>>}
 */
async function getHeuristicTagsForAsset(assetId, opts = {}) {
  const { maxAgeMs = DEFAULT_MAX_AGE_MS } = opts;
  const db = DatabaseService.getDb();

  const cutoff = maxAgeMs > 0 ? Date.now() - maxAgeMs : 0;
  const { rows } = await db.execute(
    `SELECT tag_key, score, created_at
     FROM   heuristic_tags
     WHERE  asset_id = ? AND created_at >= ?
     ORDER BY score DESC`,
    [assetId, cutoff],
  );
  return rows.map(r => ({
    tagKey: r.tag_key,
    score: r.score,
    createdAt: r.created_at,
  }));
}

/**
 * Upsert a single heuristic result for an asset.
 *
 * @param {string} assetId
 * @param {string} tagKey   — 'screenshot' | 'monochrome' | 'night' | 'document' | 'blurry'
 * @param {number} score    — 0..1
 */
async function saveHeuristicTag(assetId, tagKey, score) {
  const db = DatabaseService.getDb();
  await db.execute(
    `INSERT OR REPLACE INTO heuristic_tags (asset_id, tag_key, score, created_at)
     VALUES (?, ?, ?, ?)`,
    [assetId, tagKey, score, Date.now()],
  );
}

/**
 * Save multiple heuristic results for an asset in a single batch.
 *
 * @param {string} assetId
 * @param {Array<{ tagKey: string, score: number }>} results
 */
async function saveHeuristicTagsBatch(assetId, results = []) {
  if (!results.length) return;
  const db = DatabaseService.getDb();
  const now = Date.now();
  await db.executeBatch(
    results.map(r => [
      `INSERT OR REPLACE INTO heuristic_tags (asset_id, tag_key, score, created_at)
       VALUES (?, ?, ?, ?)`,
      [assetId, r.tagKey, r.score, now],
    ]),
  );
}

/**
 * Delete all heuristic results for an asset (e.g. when photo changes).
 *
 * @param {string} assetId
 */
async function deleteHeuristicTagsForAsset(assetId) {
  const db = DatabaseService.getDb();
  await db.execute('DELETE FROM heuristic_tags WHERE asset_id = ?', [assetId]);
}

export const HeuristicTagService = {
  getHeuristicTagsForAsset,
  saveHeuristicTag,
  saveHeuristicTagsBatch,
  deleteHeuristicTagsForAsset,
};
