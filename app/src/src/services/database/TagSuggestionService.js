/**
 * TagSuggestionService
 *
 * Persists and retrieves cached tag suggestions for a media asset.
 *
 * Current usage: ML Kit Image Labeling suggestions (source = 'mlkit').
 * Schema lives in src/services/database/schema.js (tag_suggestions table).
 */

import { DatabaseService } from './DatabaseService';

const SOURCE_MLKIT = 'mlkit';
const MODEL_VERSION_MLKIT = 'mlkit';

/**
 * Read cached ML Kit suggestions for an asset.
 *
 * @param {string} assetId
 * @param {{ maxAgeMs?: number }} opts
 * @returns {Promise<Array<{ text: string, confidence: number, createdAt: number }>>}
 */
async function getMlkitSuggestionsForAsset(assetId, opts = {}) {
  const { maxAgeMs } = opts;
  const db = DatabaseService.getDb();
  const params = [assetId, SOURCE_MLKIT];

  let sql = `
    SELECT tag_key, score, created_at
    FROM tag_suggestions
    WHERE asset_id = ? AND source = ?
  `;

  if (typeof maxAgeMs === 'number' && maxAgeMs > 0) {
    sql += ' AND created_at >= ?';
    params.push(Date.now() - maxAgeMs);
  }

  sql += ' ORDER BY score DESC';

  const { rows } = await db.execute(sql, params);
  return rows.map(r => ({
    text: r.tag_key,
    confidence: r.score,
    createdAt: r.created_at,
  }));
}

/**
 * Replace cached ML Kit suggestions for an asset.
 *
 * @param {string} assetId
 * @param {Array<{ text: string, confidence: number }>} labels
 */
async function saveMlkitSuggestionsForAsset(assetId, labels = []) {
  const db = DatabaseService.getDb();
  const now = Date.now();

  const batch = [
    [
      'DELETE FROM tag_suggestions WHERE asset_id = ? AND source = ?',
      [assetId, SOURCE_MLKIT],
    ],
  ];

  for (const l of labels) {
    const text = (l?.text ?? '').trim();
    const confidence = Number(l?.confidence ?? 0);
    if (!text) continue;
    if (!Number.isFinite(confidence)) continue;

    batch.push([
      `INSERT INTO tag_suggestions
         (asset_id, tag_key, score, source, model_version, created_at)
       VALUES (?, ?, ?, ?, ?, ?)`,
      [assetId, text, confidence, SOURCE_MLKIT, MODEL_VERSION_MLKIT, now],
    ]);
  }

  await db.executeBatch(batch);
}

export const TagSuggestionService = {
  getMlkitSuggestionsForAsset,
  saveMlkitSuggestionsForAsset,
};
