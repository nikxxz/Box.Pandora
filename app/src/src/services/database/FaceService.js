/**
 * FaceService
 *
 * CRUD for the `detected_faces` table.
 * Each row represents one face detected in a photo by ML Kit Face Detection.
 * Bounding-box coordinates are stored normalised (0.0–1.0) so they remain
 * valid regardless of the image resolution at which they were detected.
 *
 * face_id format: `${asset_id}_${face_index}`
 */

import { DatabaseService } from './DatabaseService';

// ─── Write ────────────────────────────────────────────────────────────────────

/**
 * Batch-insert detected faces for an asset (replaces any existing rows).
 *
 * @param {string} assetId
 * @param {Array<{
 *   faceIndex: number,
 *   leftNorm: number, topNorm: number, rightNorm: number, bottomNorm: number,
 *   widthPx: number, heightPx: number,
 *   yaw: number, pitch: number, roll: number,
 *   qualityScore: number,
 * }>} faces
 */
async function saveFaces(assetId, faces) {
  if (!faces.length) return;
  const db = DatabaseService.getDb();
  const now = Date.now();

  // Delete existing detections for this asset first (re-detection replaces all).
  await db.execute('DELETE FROM detected_faces WHERE asset_id = ?', [assetId]);

  await db.executeBatch(
    faces.map(f => [
      `INSERT INTO detected_faces
         (face_id, asset_id, face_index,
          left_norm, top_norm, right_norm, bottom_norm,
          width_px, height_px, yaw, pitch, roll,
          quality_score, cluster_id, created_at)
       VALUES (?,?,?, ?,?,?,?, ?,?,?,?,?, ?,NULL,?)`,
      [
        `${assetId}_${f.faceIndex}`,
        assetId,
        f.faceIndex,
        f.leftNorm,
        f.topNorm,
        f.rightNorm,
        f.bottomNorm,
        f.widthPx,
        f.heightPx,
        f.yaw,
        f.pitch,
        f.roll,
        f.qualityScore,
        now,
      ],
    ]),
  );
}

// ─── Read ─────────────────────────────────────────────────────────────────────

/**
 * Return all detected faces for an asset.
 *
 * @param {string} assetId
 * @returns {Promise<Array<{
 *   faceId: string, assetId: string, faceIndex: number,
 *   leftNorm: number, topNorm: number, rightNorm: number, bottomNorm: number,
 *   widthPx: number, heightPx: number,
 *   yaw: number, pitch: number, roll: number,
 *   qualityScore: number, clusterId: string|null,
 * }>>}
 */
async function getFacesForAsset(assetId) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT * FROM detected_faces WHERE asset_id = ? ORDER BY face_index',
    [assetId],
  );
  return rows.map(_normalise);
}

/**
 * Return all faces belonging to a cluster.
 *
 * @param {string} clusterId
 * @returns {Promise<Array>}
 */
async function getFacesForCluster(clusterId) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT * FROM detected_faces WHERE cluster_id = ? ORDER BY created_at DESC',
    [clusterId],
  );
  return rows.map(_normalise);
}

/**
 * Return asset IDs that have not had face detection run yet.
 * Excludes non-image assets and hidden/album-hidden items.
 *
 * @param {number} [limit=50]
 * @returns {Promise<string[]>}
 */
async function getUndetectedAssetIds(limit = 50) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    `SELECT m.uri
     FROM   media_index m
     LEFT   JOIN albums a ON a.id = m.album_id
     WHERE  m.media_type = 'image'
       AND  m.hidden = 0
       AND  (a.hidden IS NULL OR a.hidden = 0)
       AND  NOT EXISTS (
              SELECT 1 FROM detected_faces df WHERE df.asset_id = m.uri
            )
     ORDER  BY m.device_created_at DESC
     LIMIT  ?`,
    [limit],
  );
  return rows.map(r => r.uri);
}

/**
 * Count assets with no face detection yet.
 *
 * @returns {Promise<number>}
 */
async function countUndetected() {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    `SELECT COUNT(*) AS n
     FROM   media_index m
     LEFT   JOIN albums a ON a.id = m.album_id
     WHERE  m.media_type = 'image'
       AND  m.hidden = 0
       AND  (a.hidden IS NULL OR a.hidden = 0)
       AND  NOT EXISTS (
              SELECT 1 FROM detected_faces df WHERE df.asset_id = m.uri
            )`,
  );
  return rows[0]?.n ?? 0;
}

// ─── Cluster assignment ───────────────────────────────────────────────────────

/**
 * Assign a cluster to a face.
 *
 * @param {string} faceId
 * @param {string} clusterId
 */
async function assignCluster(faceId, clusterId) {
  const db = DatabaseService.getDb();
  await db.execute(
    'UPDATE detected_faces SET cluster_id = ? WHERE face_id = ?',
    [clusterId, faceId],
  );
}

// ─── Cleanup ──────────────────────────────────────────────────────────────────

/**
 * Delete all face rows for an asset (called before re-detection).
 *
 * @param {string} assetId
 */
async function deleteFacesForAsset(assetId) {
  const db = DatabaseService.getDb();
  await db.execute('DELETE FROM detected_faces WHERE asset_id = ?', [assetId]);
}

// ─── Stats ────────────────────────────────────────────────────────────────────

/**
 * @returns {Promise<{ totalFaces: number, clusteredFaces: number, unclustered: number }>}
 */
async function getFaceStats() {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    `SELECT
       COUNT(*)                                    AS total,
       COUNT(CASE WHEN cluster_id IS NOT NULL THEN 1 END) AS clustered
     FROM detected_faces`,
  );
  const total = rows[0]?.total ?? 0;
  const clustered = rows[0]?.clustered ?? 0;
  return { totalFaces: total, clusteredFaces: clustered, unclustered: total - clustered };
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function _normalise(r) {
  return {
    faceId: r.face_id,
    assetId: r.asset_id,
    faceIndex: r.face_index,
    leftNorm: r.left_norm,
    topNorm: r.top_norm,
    rightNorm: r.right_norm,
    bottomNorm: r.bottom_norm,
    widthPx: r.width_px,
    heightPx: r.height_px,
    yaw: r.yaw,
    pitch: r.pitch,
    roll: r.roll,
    qualityScore: r.quality_score,
    clusterId: r.cluster_id ?? null,
  };
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const FaceService = {
  saveFaces,
  getFacesForAsset,
  getFacesForCluster,
  getUndetectedAssetIds,
  countUndetected,
  assignCluster,
  deleteFacesForAsset,
  getFaceStats,
};
