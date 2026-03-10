/**
 * FaceClusterService
 *
 * Manages the `face_clusters` table — internal person clusters used to group
 * faces of the same individual across photos without exposing them directly.
 *
 * Each cluster:
 *   - Has a centroid (mean face embedding, L2-normalized; dim matches the loaded model)
 *   - Tracks n (number of faces averaged in, for online mean updates)
 *   - May be bound to a user-created people-category tag via tag_id
 *
 * Clustering strategy (online, incremental):
 *   - When a new face embedding arrives, find the nearest existing cluster
 *     with cosine_sim > CLUSTER_THRESHOLD.
 *   - If found: assign face to that cluster + update centroid (online mean).
 *   - If not found: create a new cluster seeded with this embedding.
 */

import { DatabaseService } from './DatabaseService';
import { decodeEmbedding, encodeEmbedding } from './ImageEmbeddingService';

// ─── Constants ────────────────────────────────────────────────────────────────

/** Minimum cosine similarity to assign a face to an existing cluster. */
export const CLUSTER_THRESHOLD = 0.65;

// ─── In-memory cluster cache ─────────────────────────────────────────────────
//
// Keeps all cluster objects in a Map<clusterId, cluster> so
// findNearestCluster / findSimilarClusters / getCluster don't reload and
// re-decode every centroid Float32Array from the DB on every call.
//
// Lifecycle:
//   Cold (null)    → first getAllClusters() call populates it.
//   Write ops      → targeted in-place mutation so we never do a full reload
//                    unless the cache was cold.
//   invalidate()   → drops the Map so the next read rebuilds from DB.
//                    Called by StandbyService on background to free RAM.

let _clusterCache = null; // null = cold; Map = warm

function _cacheSet(cluster) {
  if (_clusterCache) _clusterCache.set(cluster.clusterId, cluster);
}

function _cacheDelete(clusterId) {
  if (_clusterCache) _clusterCache.delete(clusterId);
}

/**
 * Release the in-memory cache.  Low RAM devices or StandbyService can call
 * this to free the Float32Array centroids without touching the DB.
 */
function invalidateClusterCache() {
  _clusterCache = null;
}

// ─── Math helpers ─────────────────────────────────────────────────────────────

function l2Normalize(v) {
  let norm = 0;
  for (let i = 0; i < v.length; i++) norm += v[i] * v[i];
  norm = Math.sqrt(norm);
  if (norm > 0) for (let i = 0; i < v.length; i++) v[i] /= norm;
  return v;
}

function cosineSim(a, b) {
  if (a.length !== b.length) return -1;
  let s = 0;
  for (let i = 0; i < a.length; i++) s += a[i] * b[i];
  return s; // both L2-normalized → dot product = cosine similarity
}

function onlineMean(oldCentroid, newEmbedding, n) {
  const dim = oldCentroid.length;
  const updated = new Float32Array(dim);
  for (let i = 0; i < dim; i++) {
    updated[i] = (n * oldCentroid[i] + newEmbedding[i]) / (n + 1);
  }
  return l2Normalize(updated);
}

function _uuid() {
  // Simple UUID-v4 from Math.random (good enough for internal IDs)
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

// ─── Create ───────────────────────────────────────────────────────────────────

/**
 * Create a new cluster seeded with an initial face embedding.
 *
 * @param {Float32Array} seedEmbedding  — L2-normalized embedding (128 or 512-dim)
 * @returns {Promise<string>} clusterId
 */
async function createCluster(seedEmbedding) {
  const db = DatabaseService.getDb();
  const clusterId = _uuid();
  const now = Date.now();
  const normalized = l2Normalize(Float32Array.from(seedEmbedding));
  await db.execute(
    `INSERT INTO face_clusters
       (cluster_id, centroid_blob, dim, n, tag_id, created_at, updated_at)
     VALUES (?, ?, ?, 1, NULL, ?, ?)`,
    [clusterId, encodeEmbedding(normalized), seedEmbedding.length, now, now],
  );
  // Add the new entry to the cache if it is warm — avoids a full reload.
  if (_clusterCache !== null) {
    _clusterCache.set(clusterId, {
      clusterId,
      centroid: normalized,
      dim: seedEmbedding.length,
      n: 1,
      tagId: null,
      createdAt: now,
      updatedAt: now,
    });
  }
  return clusterId;
}

// ─── Read ─────────────────────────────────────────────────────────────────────

/**
 * Fetch a single cluster by ID.
 *
 * @param {string} clusterId
 * @returns {Promise<{ clusterId, centroid: Float32Array, n, dim, tagId }|null>}
 */
async function getCluster(clusterId) {
  // Fast path: cache hit.
  if (_clusterCache !== null) return _clusterCache.get(clusterId) ?? null;

  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT * FROM face_clusters WHERE cluster_id = ?',
    [clusterId],
  );
  if (!rows.length) return null;
  return _normalise(rows[0]);
}

/**
 * Fetch all clusters.
 *
 * @returns {Promise<Array>}
 */
async function getAllClusters() {
  // Return cached data if available — avoids re-decoding all centroids.
  if (_clusterCache !== null) return Array.from(_clusterCache.values());

  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT * FROM face_clusters ORDER BY n DESC',
  );
  const clusters = rows.map(_normalise);
  // Populate cache for future look-ups.
  _clusterCache = new Map(clusters.map(c => [c.clusterId, c]));
  return clusters;
}

/**
 * Fetch clusters bound to a specific tag.
 *
 * @param {number} tagId
 * @returns {Promise<Array>}
 */
async function getClustersForTag(tagId) {
  // Serve from cache when warm — avoids a full table scan.
  if (_clusterCache !== null) {
    return Array.from(_clusterCache.values()).filter(c => c.tagId === tagId);
  }
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT * FROM face_clusters WHERE tag_id = ?',
    [tagId],
  );
  return rows.map(_normalise);
}

// ─── Nearest-cluster search ───────────────────────────────────────────────────

/**
 * Find the nearest cluster for a face embedding.
 * Loads all clusters, computes cosine similarity, returns the best match
 * above `threshold` or null if no cluster qualifies.
 *
 * Performance note: acceptable for up to ~10k clusters (all in memory as
 * 128 or 512-dim Float32Arrays). Beyond that, consider an approximate index.
 *
 * @param {Float32Array} embedding
 * @param {number} [threshold=CLUSTER_THRESHOLD]
 * @returns {Promise<string|null>} clusterId or null
 */
async function findNearestCluster(embedding, threshold = CLUSTER_THRESHOLD) {
  const clusters = await getAllClusters();
  if (!clusters.length) return null;

  let bestId = null;
  let bestScore = -1;

  for (const c of clusters) {
    if (!c.centroid || c.centroid.length !== embedding.length) continue;
    const score = cosineSim(embedding, c.centroid);
    if (score > bestScore) {
      bestScore = score;
      bestId = c.clusterId;
    }
  }

  return bestScore >= threshold ? bestId : null;
}

/**
 * Find similar clusters for a people tag binding preview (returns matched
 * cluster IDs + their scores above threshold).
 *
 * @param {Float32Array} queryEmbedding
 * @param {number} [threshold=CLUSTER_THRESHOLD]
 * @returns {Promise<Array<{ clusterId, score }>>}
 */
async function findSimilarClusters(
  queryEmbedding,
  threshold = CLUSTER_THRESHOLD,
) {
  const clusters = await getAllClusters();
  return clusters
    .filter(c => c.centroid && c.centroid.length === queryEmbedding.length)
    .map(c => ({
      clusterId: c.clusterId,
      score: cosineSim(queryEmbedding, c.centroid),
    }))
    .filter(r => r.score >= threshold)
    .sort((a, b) => b.score - a.score);
}

// ─── Update ───────────────────────────────────────────────────────────────────

/**
 * Apply online mean update to a cluster's centroid.
 *
 * @param {string}       clusterId
 * @param {Float32Array} newEmbedding  — the new face embedding to blend in
 */
async function updateClusterCentroid(clusterId, newEmbedding) {
  const cluster = await getCluster(clusterId);
  if (!cluster) return;

  const newCentroid = onlineMean(cluster.centroid, newEmbedding, cluster.n);
  const now = Date.now();
  const db = DatabaseService.getDb();
  await db.execute(
    `UPDATE face_clusters
     SET centroid_blob = ?, n = ?, updated_at = ?
     WHERE cluster_id = ?`,
    [encodeEmbedding(newCentroid), cluster.n + 1, now, clusterId],
  );
  // Targeted in-place cache update — no full reload needed.
  if (_clusterCache?.has(clusterId)) {
    const cached = _clusterCache.get(clusterId);
    cached.centroid = newCentroid;
    cached.n = cluster.n + 1;
    cached.updatedAt = now;
  }
}

/**
 * Bind a cluster to a user-created people-category tag.
 *
 * @param {string} clusterId
 * @param {number} tagId
 */
async function bindClusterToTag(clusterId, tagId) {
  const now = Date.now();
  const db = DatabaseService.getDb();
  await db.execute(
    'UPDATE face_clusters SET tag_id = ?, updated_at = ? WHERE cluster_id = ?',
    [tagId, now, clusterId],
  );
  // Update cache in-place.
  if (_clusterCache?.has(clusterId)) {
    const cached = _clusterCache.get(clusterId);
    cached.tagId = tagId;
    cached.updatedAt = now;
  }
}

/**
 * Unbind a cluster from its tag (tag deleted or re-assignment).
 *
 * @param {string} clusterId
 */
async function unbindCluster(clusterId) {
  const now = Date.now();
  const db = DatabaseService.getDb();
  await db.execute(
    'UPDATE face_clusters SET tag_id = NULL, updated_at = ? WHERE cluster_id = ?',
    [now, clusterId],
  );
  // Update cache in-place.
  if (_clusterCache?.has(clusterId)) {
    const cached = _clusterCache.get(clusterId);
    cached.tagId = null;
    cached.updatedAt = now;
  }
}

// ─── Stats ────────────────────────────────────────────────────────────────────

/**
 * @returns {Promise<{ totalClusters, boundClusters, unboundClusters }>}
 */
async function getClusterStats() {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    `SELECT
       COUNT(*) AS total,
       COUNT(CASE WHEN tag_id IS NOT NULL THEN 1 END) AS bound
     FROM face_clusters`,
  );
  const total = rows[0]?.total ?? 0;
  const bound = rows[0]?.bound ?? 0;
  return {
    totalClusters: total,
    boundClusters: bound,
    unboundClusters: total - bound,
  };
}

/**
 * Returns all clusters that are bound to a people-category tag,
 * joined with the tag name — ordered by face count descending.
 *
 * @returns {Promise<Array<{clusterId: string, tagId: number, name: string, n: number}>>}
 */
async function getNamedClusters() {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    `SELECT fc.cluster_id, fc.n, fc.tag_id, t.name
     FROM face_clusters fc
     JOIN tags t ON t.id = fc.tag_id
     WHERE fc.tag_id IS NOT NULL
     ORDER BY fc.n DESC`,
  );
  return rows.map(r => ({
    clusterId: r.cluster_id,
    tagId: r.tag_id,
    name: r.name,
    n: r.n,
  }));
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function _normalise(r) {
  return {
    clusterId: r.cluster_id,
    centroid: r.centroid_blob ? decodeEmbedding(r.centroid_blob) : null,
    dim: r.dim,
    n: r.n,
    tagId: r.tag_id ?? null,
    createdAt: r.created_at,
    updatedAt: r.updated_at,
  };
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const FaceClusterService = {
  createCluster,
  getCluster,
  getAllClusters,
  getClustersForTag,
  findNearestCluster,
  findSimilarClusters,
  updateClusterCentroid,
  bindClusterToTag,
  unbindCluster,
  getClusterStats,
  getNamedClusters,
  invalidateClusterCache,
  CLUSTER_THRESHOLD,
};
