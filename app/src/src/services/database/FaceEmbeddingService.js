/**
 * FaceEmbeddingService
 *
 * Stores and retrieves face identity embeddings (128-dim MobileFaceNet or 512-dim ArcFace).
 * Mirrors the API of ImageEmbeddingService but targets the `face_embeddings`
 * table and works with face_id keys instead of asset_id keys.
 *
 * The base64 Float32 codec is re-exported from ImageEmbeddingService so both
 * tables share the same wire format.
 */

import { DatabaseService } from './DatabaseService';
import { decodeEmbedding, encodeEmbedding } from './ImageEmbeddingService';

// ─── Write ────────────────────────────────────────────────────────────────────

/**
 * Persist a face embedding.
 *
 * @param {string}     faceId        — detected_faces PK
 * @param {string}     modelVersion  — e.g. 'mobilefacenet_v1'
 * @param {Float32Array} embedding   — L2-normalized 128-dim vector
 * @param {number}     dim
 */
async function saveEmbedding(faceId, modelVersion, embedding, dim) {
  const db = DatabaseService.getDb();
  await db.execute(
    `INSERT OR REPLACE INTO face_embeddings
       (face_id, model_version, dim, embedding, created_at)
     VALUES (?, ?, ?, ?, ?)`,
    [faceId, modelVersion, dim, encodeEmbedding(embedding), Date.now()],
  );
}

// ─── Read ─────────────────────────────────────────────────────────────────────

/**
 * Retrieve a face embedding as a Float32Array, or null if not found.
 *
 * @param {string} faceId
 * @param {string} modelVersion
 * @returns {Promise<Float32Array|null>}
 */
async function getEmbedding(faceId, modelVersion) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT embedding FROM face_embeddings WHERE face_id = ? AND model_version = ?',
    [faceId, modelVersion],
  );
  if (!rows.length || !rows[0].embedding) return null;
  return decodeEmbedding(rows[0].embedding);
}

/**
 * Check whether a face embedding already exists.
 *
 * @param {string} faceId
 * @param {string} modelVersion
 * @returns {Promise<boolean>}
 */
async function hasEmbedding(faceId, modelVersion) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT 1 FROM face_embeddings WHERE face_id = ? AND model_version = ? LIMIT 1',
    [faceId, modelVersion],
  );
  return rows.length > 0;
}

/**
 * Return face IDs that have a detected_faces row but no embedding yet for
 * the given model version.
 *
 * @param {string} modelVersion
 * @param {number} [limit=50]
 * @returns {Promise<string[]>}
 */
async function getUnembeddedFaceIds(modelVersion, limit = 50) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    `SELECT df.face_id
     FROM   detected_faces df
     WHERE  NOT EXISTS (
              SELECT 1 FROM face_embeddings fe
              WHERE  fe.face_id = df.face_id
                AND  fe.model_version = ?
            )
     ORDER  BY df.created_at DESC
     LIMIT  ?`,
    [modelVersion, limit],
  );
  return rows.map(r => r.face_id);
}

/**
 * Count embedded faces for a model version.
 *
 * @param {string} modelVersion
 * @returns {Promise<number>}
 */
async function countEmbedded(modelVersion) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT COUNT(*) AS n FROM face_embeddings WHERE model_version = ?',
    [modelVersion],
  );
  return rows[0]?.n ?? 0;
}

/**
 * Delete all face embeddings for a model version (used on model upgrade).
 *
 * @param {string} modelVersion
 */
async function deleteEmbeddingsByModel(modelVersion) {
  const db = DatabaseService.getDb();
  await db.execute('DELETE FROM face_embeddings WHERE model_version = ?', [
    modelVersion,
  ]);
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const FaceEmbeddingService = {
  saveEmbedding,
  getEmbedding,
  hasEmbedding,
  getUnembeddedFaceIds,
  countEmbedded,
  deleteEmbeddingsByModel,
};
