/**
 * ImageEmbeddingService
 *
 * Persists and retrieves TFLite image embeddings from the `image_embeddings` table.
 *
 * Embeddings are stored as base64-encoded little-endian Float32 bytes.
 * Decoding to Float32Array is done in JS so the DB layer stays thin.
 *
 * Schema (v7):
 *   image_embeddings (asset_id, model_version, dim, embedding BLOB, created_at)
 *   PRIMARY KEY (asset_id, model_version)
 */

import { DatabaseService } from './DatabaseService';

// ─── Codec helpers ────────────────────────────────────────────────────────────

/**
 * Decode a base64 string (little-endian IEEE-754 Float32 bytes) into a Float32Array.
 * Uses Buffer on Node (tests) and atob on device.
 */
export function decodeEmbedding(base64) {
  try {
    let binaryStr;
    if (typeof atob !== 'undefined') {
      binaryStr = atob(base64);
    } else {
      // Node.js (Jest) fallback
      binaryStr = Buffer.from(base64, 'base64').toString('binary');
    }
    const bytes = new Uint8Array(binaryStr.length);
    for (let i = 0; i < binaryStr.length; i++) {
      bytes[i] = binaryStr.charCodeAt(i);
    }
    return new Float32Array(bytes.buffer);
  } catch {
    return new Float32Array(0);
  }
}

/**
 * Encode a Float32Array as a base64 string (little-endian IEEE-754 bytes).
 * Mirrors the native Kotlin encodeFloat32 helper exactly.
 */
export function encodeEmbedding(embedding) {
  const bytes = new Uint8Array(
    embedding.buffer,
    embedding.byteOffset,
    embedding.byteLength,
  );
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  if (typeof btoa !== 'undefined') return btoa(binary);
  // Node.js (Jest) fallback
  return Buffer.from(binary, 'binary').toString('base64');
}

// ─── DB operations ────────────────────────────────────────────────────────────

/**
 * Check whether an embedding already exists for (assetId, modelVersion).
 *
 * @param {string} assetId
 * @param {string} modelVersion
 * @returns {Promise<boolean>}
 */
async function hasEmbedding(assetId, modelVersion) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT 1 FROM image_embeddings WHERE asset_id = ? AND model_version = ? LIMIT 1',
    [assetId, modelVersion],
  );
  return rows.length > 0;
}

/**
 * Retrieve a stored embedding as a Float32Array.
 * Returns null if none exists for the given (assetId, modelVersion).
 *
 * @param {string} assetId
 * @param {string} modelVersion
 * @returns {Promise<Float32Array|null>}
 */
async function getEmbedding(assetId, modelVersion) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT embedding, dim FROM image_embeddings WHERE asset_id = ? AND model_version = ?',
    [assetId, modelVersion],
  );
  if (!rows.length || !rows[0]?.embedding) return null;
  return decodeEmbedding(rows[0].embedding);
}

/**
 * Upsert an embedding blob for an asset.
 *
 * @param {string}     assetId
 * @param {string}     modelVersion
 * @param {Float32Array|string} embedding  — Float32Array or pre-encoded base64 string
 * @param {number}     dim
 */
async function saveEmbedding(assetId, modelVersion, embedding, dim) {
  const db = DatabaseService.getDb();
  const base64 =
    typeof embedding === 'string' ? embedding : encodeEmbedding(embedding);
  await db.execute(
    `INSERT OR REPLACE INTO image_embeddings
       (asset_id, model_version, dim, embedding, created_at)
     VALUES (?, ?, ?, ?, ?)`,
    [assetId, modelVersion, dim, base64, Date.now()],
  );
}

/**
 * Delete all embeddings for a specific model version.
 * Useful when clearing stale embeddings after a model upgrade.
 *
 * @param {string} modelVersion
 */
async function deleteEmbeddingsByModel(modelVersion) {
  const db = DatabaseService.getDb();
  await db.execute('DELETE FROM image_embeddings WHERE model_version = ?', [
    modelVersion,
  ]);
}

/**
 * Count indexed assets for a model version.
 *
 * @param {string} modelVersion
 * @returns {Promise<number>}
 */
async function countIndexed(modelVersion) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT COUNT(*) AS n FROM image_embeddings WHERE model_version = ?',
    [modelVersion],
  );
  return rows[0]?.n ?? 0;
}

/**
 * Fetch all asset_ids (media URIs) that do NOT yet have an embedding for modelVersion.
 * Optionally limit to a batch of `limit` rows.
 *
 * @param {string}  modelVersion
 * @param {number}  [limit=50]
 * @returns {Promise<string[]>}
 */
async function getUnindexedAssetIds(modelVersion, limit = 50) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    `SELECT m.uri
     FROM   media_index m
     LEFT  JOIN albums a ON a.id = m.album_id
     WHERE  m.media_type = 'image'
       AND  m.hidden = 0
       AND  (a.hidden IS NULL OR a.hidden = 0)
       AND  NOT EXISTS (
         SELECT 1 FROM image_embeddings ie
         WHERE  ie.asset_id = m.uri
           AND  ie.model_version = ?
       )
     ORDER BY m.device_created_at DESC
     LIMIT ?`,
    [modelVersion, limit],
  );
  return rows.map(r => r.uri);
}

export const ImageEmbeddingService = {
  hasEmbedding,
  getEmbedding,
  saveEmbedding,
  deleteEmbeddingsByModel,
  countIndexed,
  getUnindexedAssetIds,
  decodeEmbedding,
  encodeEmbedding,
};
