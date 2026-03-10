/**
 * TagPrototypeService
 *
 * Manages the `tag_prototypes` table:
 *   — reads and writes prototype blobs (base64 Float32)
 *   — implements the online mean update for self-learning custom tags
 *   — manages per-tag rejection counts for threshold tuning
 *
 * Schema (existing, v2):
 *   tag_prototypes (tag_key TEXT PK, prototype_blob BLOB, dim INT, n INT,
 *                   model_version TEXT, updated_at INT)
 *
 * Schema (v7):
 *   tag_rejections (tag_key TEXT, asset_id TEXT, created_at INT)
 *                  PRIMARY KEY (tag_key, asset_id)
 */

import { DatabaseService } from './DatabaseService';
import { decodeEmbedding, encodeEmbedding } from './ImageEmbeddingService';

// ─── Online mean constants ────────────────────────────────────────────────────

/** Minimum examples before a prototype is used for suggestions. */
export const MIN_EXAMPLES = 3;

// ─── L2 normalization (pure JS) ───────────────────────────────────────────────

function l2Normalize(v) {
  let norm = 0;
  for (let i = 0; i < v.length; i++) norm += v[i] * v[i];
  norm = Math.sqrt(norm);
  if (norm > 0) for (let i = 0; i < v.length; i++) v[i] /= norm;
  return v;
}

/**
 * Online mean update:
 *   P_new_raw = (n * P + E) / (n + 1)
 *   P_new = normalize(P_new_raw)
 *
 * @param {Float32Array} oldPrototype  — L2-normalized existing prototype
 * @param {Float32Array} newEmbedding  — L2-normalized new embedding
 * @param {number}       n             — existing example count
 * @returns {Float32Array}             — new L2-normalized prototype
 */
export function updatePrototypeMean(oldPrototype, newEmbedding, n) {
  if (oldPrototype.length !== newEmbedding.length) {
    // Dimension mismatch (model change) — seed with just the new embedding.
    return l2Normalize(Float32Array.from(newEmbedding));
  }
  const dim = oldPrototype.length;
  const updated = new Float32Array(dim);
  for (let i = 0; i < dim; i++) {
    updated[i] = (n * oldPrototype[i] + newEmbedding[i]) / (n + 1);
  }
  return l2Normalize(updated);
}

// ─── DB read ──────────────────────────────────────────────────────────────────

/**
 * Fetch the prototype for a single tag key.
 * Returns null if not found.
 *
 * @param {string} tagKey
 * @returns {Promise<{ prototype: Float32Array, n: number, dim: number, modelVersion: string }|null>}
 */
async function getPrototype(tagKey) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT * FROM tag_prototypes WHERE tag_key = ?',
    [tagKey],
  );
  if (!rows.length) return null;
  const row = rows[0];
  return {
    prototype: decodeEmbedding(row.prototype_blob),
    n: row.n,
    dim: row.dim,
    modelVersion: row.model_version,
  };
}

/**
 * Fetch all prototypes that have at least `minExamples` examples for a given
 * model version.
 *
 * @param {string} modelVersion
 * @param {number} [minExamples=MIN_EXAMPLES]
 * @returns {Promise<Array<{ tagKey: string, prototype: Float32Array, n: number, dim: number }>>}
 */
async function getAllPrototypes(modelVersion, minExamples = MIN_EXAMPLES) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    `SELECT tag_key, prototype_blob, n, dim
     FROM   tag_prototypes
     WHERE  model_version = ? AND n >= ?`,
    [modelVersion, minExamples],
  );
  return rows.map(r => ({
    tagKey: r.tag_key,
    prototype: decodeEmbedding(r.prototype_blob),
    n: r.n,
    dim: r.dim,
  }));
}

// ─── DB write ─────────────────────────────────────────────────────────────────

/**
 * Apply the online mean update for tagKey given a new example embedding.
 *
 * If no prototype exists yet for (tagKey, modelVersion), seeds with the
 * new embedding as prototype (n = 1).
 *
 * @param {string}     tagKey
 * @param {Float32Array} newEmbedding   — L2-normalized embedding from native
 * @param {string}     modelVersion
 */
async function updatePrototype(tagKey, newEmbedding, modelVersion) {
  const db = DatabaseService.getDb();
  const existing = await getPrototype(tagKey);

  let newProto;
  let newN;

  if (!existing || existing.modelVersion !== modelVersion || existing.n === 0) {
    // First example: seed directly.
    newProto = l2Normalize(Float32Array.from(newEmbedding));
    newN = 1;
  } else {
    newProto = updatePrototypeMean(
      existing.prototype,
      newEmbedding,
      existing.n,
    );
    newN = existing.n + 1;
  }

  await db.execute(
    `INSERT OR REPLACE INTO tag_prototypes
       (tag_key, prototype_blob, dim, n, model_version, updated_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
    [
      tagKey,
      encodeEmbedding(newProto),
      newProto.length,
      newN,
      modelVersion,
      Date.now(),
    ],
  );
}

/**
 * Delete the prototype for a tag (e.g. when the tag is deleted).
 *
 * @param {string} tagKey
 */
async function deletePrototype(tagKey) {
  const db = DatabaseService.getDb();
  await db.execute('DELETE FROM tag_prototypes WHERE tag_key = ?', [tagKey]);
}

// ─── Rejection tracking ───────────────────────────────────────────────────────

/**
 * Record that the user dismissed a suggested tag for an asset.
 *
 * @param {string} tagKey
 * @param {string} assetId
 */
async function recordRejection(tagKey, assetId) {
  const db = DatabaseService.getDb();
  await db.execute(
    `INSERT OR IGNORE INTO tag_rejections (tag_key, asset_id, created_at)
     VALUES (?, ?, ?)`,
    [tagKey, assetId, Date.now()],
  );
}

/**
 * Get the rejection count for a tag (how many times it has been dismissed).
 *
 * @param {string} tagKey
 * @returns {Promise<number>}
 */
async function getRejectionCount(tagKey) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT COUNT(*) AS n FROM tag_rejections WHERE tag_key = ?',
    [tagKey],
  );
  return rows[0]?.n ?? 0;
}

/**
 * Get rejection counts for all tags as a map: tagKey → count.
 *
 * @returns {Promise<Record<string, number>>}
 */
async function getAllRejectionCounts() {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT tag_key, COUNT(*) AS n FROM tag_rejections GROUP BY tag_key',
  );
  const map = {};
  for (const row of rows) map[row.tag_key] = row.n;
  return map;
}

/**
 * Remove the rejection record for a specific (tagKey, assetId) pair.
 * Called when the user explicitly applies a tag to an asset, signalling
 * that the previous dismissal was not a lasting preference.
 *
 * @param {string} tagKey
 * @param {string} assetId
 */
async function clearRejection(tagKey, assetId) {
  const db = DatabaseService.getDb();
  await db.execute(
    'DELETE FROM tag_rejections WHERE tag_key = ? AND asset_id = ?',
    [tagKey, assetId],
  );
}

export const TagPrototypeService = {
  getPrototype,
  getAllPrototypes,
  updatePrototype,
  deletePrototype,
  recordRejection,
  clearRejection,
  getRejectionCount,
  getAllRejectionCounts,
  // Exported math helpers for tests
  updatePrototypeMean,
  l2Normalize,
  MIN_EXAMPLES,
};
