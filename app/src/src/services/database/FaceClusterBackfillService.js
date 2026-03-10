/**
 * FaceClusterBackfillService
 *
 * Retroactively binds face clusters to people-category tags using the
 * existing media_tags history in the database.
 *
 * Why this is needed:
 *   TaggingService._linkFacesToPeopleTag() only fires when the user applies a
 *   tag going forward (after the face pipeline was deployed). Any photos the
 *   user had already tagged with a person's name before that point will have
 *   their face clusters sitting unbound — the model never learns those faces.
 *
 * Algorithm (per people-category tag):
 *   1. Find all media items tagged with that tag via media_tags.
 *   2. For each media, load every detected face that has a face embedding.
 *   3. Per face:
 *      a. Already clustered + cluster already bound to ANOTHER tag → skip
 *         (respect the existing assignment; it may be from a newer action).
 *      b. Already clustered + cluster UNBOUND → bind the cluster to this tag.
 *      c. Already clustered + cluster bound to THIS tag → update centroid.
 *      d. No cluster yet but embedding exists → create cluster, bind to tag.
 *
 * Usage:
 *   await FaceClusterBackfillService.backfillAll({ onProgress, onError });
 *   await FaceClusterBackfillService.getBackfillStats();
 *   await FaceClusterBackfillService.needsBackfill();
 *   await FaceClusterBackfillService.findOrClaimClusterForTag(embedding, tagId);
 *   await FaceClusterBackfillService.mergeAnonymousClusters();
 *   await FaceClusterBackfillService.rebindUnassignedFaces();
 */

import { DatabaseService } from './DatabaseService';
import { FaceService } from './FaceService';
import { FaceEmbeddingService } from './FaceEmbeddingService';
import { FaceClusterService } from './FaceClusterService';
import { FACE_MODEL_VERSION } from '../ml/FaceBridgeModule';

// ─── Stats ────────────────────────────────────────────────────────────────────

/**
 * Returns a breakdown of how many people-category tags are already fully
 * linked vs still have unbound face clusters.
 *
 * @returns {Promise<{
 *   peopleTags: number,
 *   tagsBound: number,
 *   tagsUnbound: number,
 *   unboundClusters: number,
 *   facesWithEmbeddings: number,
 * }>}
 */
async function getBackfillStats() {
  const db = DatabaseService.getDb();

  const [
    { rows: peopleRows },
    { rows: unboundRows },
    { rows: embeddedFaceRows },
  ] = await Promise.all([
    db.execute("SELECT COUNT(*) AS n FROM tags WHERE category = 'people'"),
    db.execute('SELECT COUNT(*) AS n FROM face_clusters WHERE tag_id IS NULL'),
    db.execute(
      `SELECT COUNT(*) AS n
       FROM   face_embeddings
       WHERE  model_version = ?`,
      [FACE_MODEL_VERSION],
    ),
  ]);

  const peopleTags = peopleRows[0]?.n ?? 0;

  // Count people tags that have at least one cluster bound to them
  const { rows: boundTagRows } = await db.execute(
    `SELECT COUNT(DISTINCT tag_id) AS n
     FROM face_clusters
     WHERE tag_id IS NOT NULL
       AND tag_id IN (SELECT id FROM tags WHERE category = 'people')`,
  );

  const tagsBound = boundTagRows[0]?.n ?? 0;
  const unboundClusters = unboundRows[0]?.n ?? 0;
  const facesWithEmbeddings = embeddedFaceRows[0]?.n ?? 0;

  return {
    peopleTags,
    tagsBound,
    tagsUnbound: peopleTags - tagsBound,
    unboundClusters,
    facesWithEmbeddings,
  };
}

/**
 * Quick check: returns true if there are faces with embeddings that could be
 * linked to existing people tags but aren't yet.
 *
 * @returns {Promise<boolean>}
 */
async function needsBackfill() {
  const stats = await getBackfillStats();
  // Needs backfill if there are people tags not yet bound OR free clusters
  return stats.tagsUnbound > 0 || stats.unboundClusters > 0;
}

// ─── Backfill ─────────────────────────────────────────────────────────────────

/**
 * Scan all media_tags rows for people-category tags, then for each tagged
 * asset bind its face clusters to that tag.
 *
 * @param {{
 *   onProgress?: (done: number, total: number, tagName: string) => void
 *   onError?:    (tagName: string, err: Error) => void
 * }} opts
 * @returns {Promise<{ bound: number, created: number, skipped: number, errors: number }>}
 */
async function backfillAll(opts = {}) {
  const { onProgress = null, onError = null } = opts;
  const db = DatabaseService.getDb();

  // ── Step 1: collect all people-category tags that have media tags ─────────
  const { rows: tagRows } = await db.execute(
    `SELECT DISTINCT t.id, t.name
     FROM   media_tags mt
     JOIN   tags t ON t.id = mt.tag_id
     WHERE  t.category = 'people'
     ORDER  BY t.name`,
  );

  const total = tagRows.length;
  let bound = 0;
  let created = 0;
  let skipped = 0;
  let errors = 0;

  console.log(
    `[FaceClusterBackfill] Starting — ${total} people tags with media entries`,
  );

  // ── Step 2: for each people tag process all tagged assets ─────────────────
  for (let i = 0; i < tagRows.length; i++) {
    const { id: tagId, name: tagName } = tagRows[i];

    try {
      const { rows: mediaRows } = await db.execute(
        'SELECT media_uri FROM media_tags WHERE tag_id = ?',
        [tagId],
      );

      // Yield every FACE_YIELD_EVERY face operations so we don't lock the
      // JS thread for tags that have many tagged photos.
      const FACE_YIELD_EVERY = 20;
      let faceOps = 0;

      for (const { media_uri: mediaUri } of mediaRows) {
        const faces = await FaceService.getFacesForAsset(mediaUri);

        for (const face of faces) {
          // Skip sentinel rows (no-face marker).
          if (face.faceIndex === -1) continue;

          if (face.clusterId) {
            // ── Face already in a cluster ───────────────────────────────────
            const cluster = await FaceClusterService.getCluster(face.clusterId);
            if (!cluster) {
              skipped++;
              continue;
            }

            if (cluster.tagId === tagId) {
              // Already bound to the correct tag — update centroid if we can.
              const emb = await FaceEmbeddingService.getEmbedding(
                face.faceId,
                FACE_MODEL_VERSION,
              );
              if (emb) {
                await FaceClusterService.updateClusterCentroid(
                  face.clusterId,
                  emb,
                );
              }
              skipped++;
            } else if (cluster.tagId == null) {
              // Unbound cluster — bind it now.
              await FaceClusterService.bindClusterToTag(face.clusterId, tagId);
              bound++;
            } else {
              // Bound to a DIFFERENT tag — leave the existing assignment alone.
              skipped++;
            }
          } else {
            // ── Face not yet clustered — try to create from embedding ────────
            const emb = await FaceEmbeddingService.getEmbedding(
              face.faceId,
              FACE_MODEL_VERSION,
            );
            if (!emb) {
              skipped++;
              continue;
            } // embedding not available yet

            // Try to find a nearby cluster already bound to this tag.
            const tagClusters = await FaceClusterService.getClustersForTag(
              tagId,
            );
            let assignedClusterId = null;

            for (const tc of tagClusters) {
              if (!tc.centroid || tc.centroid.length !== emb.length) continue;
              const sim = _cosineSim(tc.centroid, emb);
              if (sim >= FaceClusterService.CLUSTER_THRESHOLD) {
                assignedClusterId = tc.clusterId;
                break;
              }
            }

            if (assignedClusterId) {
              await FaceService.assignCluster(face.faceId, assignedClusterId);
              await FaceClusterService.updateClusterCentroid(
                assignedClusterId,
                emb,
              );
              bound++;
            } else {
              // No compatible cluster exists for this tag yet — create one.
              const newClusterId = await FaceClusterService.createCluster(emb);
              await FaceService.assignCluster(face.faceId, newClusterId);
              await FaceClusterService.bindClusterToTag(newClusterId, tagId);
              created++;
            }
          }

          // Yield periodically to keep the JS thread responsive.
          if (++faceOps % FACE_YIELD_EVERY === 0) await _sleep(0);
        }
      }

      onProgress?.(i + 1, total, tagName);
    } catch (err) {
      errors++;
      onError?.(tagName, err);
      console.warn(
        `[FaceClusterBackfill] Failed for tag "${tagName}":`,
        err?.message ?? err,
      );
    }

    // Yield to avoid blocking the JS thread between tags.
    await _sleep(0);
  }

  console.log(
    `[FaceClusterBackfill] Done — bound ${bound}, created ${created} clusters, skipped ${skipped}, errors ${errors}`,
  );
  return { bound, created, skipped, errors };
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function _cosineSim(a, b) {
  if (a.length !== b.length) return -1;
  let s = 0;
  for (let i = 0; i < a.length; i++) s += a[i] * b[i];
  return s;
}

function _sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

// ─── Batch-learning cluster strategy ─────────────────────────────────────────

/**
 * Find or claim the best cluster for a batch-learning face embedding.
 *
 * Priority order (batch images carry ground-truth labels so we are strict):
 *   1. An existing cluster already bound to THIS tag whose centroid is within
 *      CLUSTER_THRESHOLD — update its centroid and reuse it.
 *   2. An UNBOUND anonymous cluster within threshold — absorb/claim it for
 *      this tag (avoids fragmenting the cluster space).
 *   3. No match — create a brand-new cluster and bind it to this tag.
 *
 * This approach intentionally never touches clusters already bound to a
 * DIFFERENT tag, so a similar-looking face from another person's folder cannot
 * corrupt their cluster.
 *
 * @param {Float32Array} faceEmb  - L2-normalised face embedding
 * @param {number}       tagId   - id from the tags table for this person
 * @returns {Promise<{ clusterId: string, action: 'updated'|'claimed'|'created' }>}
 */
async function findOrClaimClusterForTag(faceEmb, tagId) {
  const THRESHOLD = FaceClusterService.CLUSTER_THRESHOLD;

  // ── 1. Search among clusters already bound to this tag ───────────────────
  const tagClusters = await FaceClusterService.getClustersForTag(tagId);
  let bestTagClusterId = null;
  let bestTagScore = -1;
  for (const tc of tagClusters) {
    if (!tc.centroid || tc.centroid.length !== faceEmb.length) continue;
    const score = _cosineSim(tc.centroid, faceEmb);
    if (score > bestTagScore) {
      bestTagScore = score;
      bestTagClusterId = tc.clusterId;
    }
  }
  if (bestTagScore >= THRESHOLD && bestTagClusterId) {
    await FaceClusterService.updateClusterCentroid(bestTagClusterId, faceEmb);
    return { clusterId: bestTagClusterId, action: 'updated' };
  }

  // ── 2. Search unbound anonymous clusters — absorb the nearest one ─────────
  const allClusters = await FaceClusterService.getAllClusters();
  let bestAnonId = null;
  let bestAnonScore = -1;
  for (const c of allClusters) {
    if (
      c.tagId != null ||
      !c.centroid ||
      c.centroid.length !== faceEmb.length
    ) {
      continue;
    }
    const score = _cosineSim(c.centroid, faceEmb);
    if (score > bestAnonScore) {
      bestAnonScore = score;
      bestAnonId = c.clusterId;
    }
  }
  if (bestAnonScore >= THRESHOLD && bestAnonId) {
    await FaceClusterService.updateClusterCentroid(bestAnonId, faceEmb);
    await FaceClusterService.bindClusterToTag(bestAnonId, tagId);
    return { clusterId: bestAnonId, action: 'claimed' };
  }

  // ── 3. Nothing close enough — create a new cluster ────────────────────────
  const newId = await FaceClusterService.createCluster(faceEmb);
  await FaceClusterService.bindClusterToTag(newId, tagId);
  return { clusterId: newId, action: 'created' };
}

// ─── Post-learning anonymous cluster merge ────────────────────────────────────

/**
 * Merge unbound anonymous clusters into the nearest tag-bound cluster.
 *
 * After batch learning has run, some anonymous clusters (created by FaceIndexer
 * scanning gallery images before labels existed) will be geometrically close to
 * a newly trained tag-bound cluster.  Merging them:
 *   – Re-routes every detected_face that pointed to the old anonymous cluster
 *     to the bound one, so _getFaceSuggestions() can find the tag name.
 *   – Removes the now-redundant anonymous cluster row.
 *   – Updates the winning cluster's centroid (online mean blend).
 *
 * @param {{ onProgress?: (done: number, total: number) => void }} opts
 * @returns {Promise<{ merged: number, skipped: number }>}
 */
async function mergeAnonymousClusters(opts = {}) {
  const { onProgress = null } = opts;
  const db = DatabaseService.getDb();
  const THRESHOLD = FaceClusterService.CLUSTER_THRESHOLD;

  const allClusters = await FaceClusterService.getAllClusters();
  const anonClusters = allClusters.filter(
    c => c.tagId == null && c.centroid && c.n > 0,
  );
  const boundClusters = allClusters.filter(c => c.tagId != null && c.centroid);

  if (!anonClusters.length || !boundClusters.length) {
    return { merged: 0, skipped: anonClusters.length };
  }

  console.log(
    `[FaceClusterBackfill] mergeAnonymousClusters — ${anonClusters.length} anonymous, ${boundClusters.length} bound`,
  );

  let merged = 0;
  let skipped = 0;

  for (let i = 0; i < anonClusters.length; i++) {
    const anon = anonClusters[i];

    // Find the nearest tag-bound cluster for this anonymous centroid.
    let bestBoundId = null;
    let bestScore = -1;
    for (const bc of boundClusters) {
      if (bc.centroid.length !== anon.centroid.length) continue;
      const score = _cosineSim(bc.centroid, anon.centroid);
      if (score > bestScore) {
        bestScore = score;
        bestBoundId = bc.clusterId;
      }
    }

    if (bestScore >= THRESHOLD && bestBoundId) {
      // Re-route all detected_faces that reference this anonymous cluster.
      await db.execute(
        'UPDATE detected_faces SET cluster_id = ? WHERE cluster_id = ?',
        [bestBoundId, anon.clusterId],
      );
      // Blend the anonymous centroid into the winning cluster.
      await FaceClusterService.updateClusterCentroid(
        bestBoundId,
        anon.centroid,
      );
      // Delete the now-absorbed anonymous cluster.
      await db.execute('DELETE FROM face_clusters WHERE cluster_id = ?', [
        anon.clusterId,
      ]);
      merged++;
    } else {
      skipped++;
    }

    if (i % 20 === 0) await _sleep(0);
    onProgress?.(i + 1, anonClusters.length);
  }

  // Invalidate the in-memory cluster cache — we deleted rows and changed centroids.
  FaceClusterService.invalidateClusterCache();

  console.log(
    `[FaceClusterBackfill] mergeAnonymousClusters done — merged ${merged}, skipped ${skipped}`,
  );
  return { merged, skipped };
}

/**
 * Re-match already-indexed faces to tag-bound clusters.
 *
 * Why this is needed:
 *   When batch learning runs AFTER FaceIndexer has already processed a gallery
 *   image, the face rows in detected_faces point to anonymous unbound clusters
 *   (created during the initial indexing scan).  The newly trained tag-bound
 *   clusters sit in face_clusters but no detected_face row points to them, so
 *   _getFaceSuggestions() can never surface them.
 *
 *   This function scans every detected_face that has an embedding but whose
 *   current cluster either (a) has no tag_id or (b) is null, and re-assigns
 *   it to the nearest tag-bound cluster if cosine similarity >= threshold.
 *
 * @param {{
 *   onProgress?: (done: number, total: number) => void
 * }} opts
 * @returns {Promise<{ reassigned: number, skipped: number }>}
 */
async function rebindUnassignedFaces(opts = {}) {
  const { onProgress = null } = opts;
  const db = DatabaseService.getDb();

  // ── Collect all face_ids that have embeddings but whose cluster is unbound ─
  const { rows: faceRows } = await db.execute(
    `SELECT fe.face_id
     FROM   face_embeddings fe
     JOIN   detected_faces  df ON df.face_id = fe.face_id
     WHERE  fe.model_version = ?
       AND  df.face_index != -1
       AND  (
         df.cluster_id IS NULL
         OR df.cluster_id IN (
           SELECT cluster_id FROM face_clusters WHERE tag_id IS NULL
         )
       )`,
    [FACE_MODEL_VERSION],
  );

  if (!faceRows.length) return { reassigned: 0, skipped: 0 };

  // ── Load all tag-bound clusters (decoded centroids already in cache) ────────
  const allClusters = await FaceClusterService.getAllClusters();
  const boundClusters = allClusters.filter(c => c.tagId != null && c.centroid);

  if (!boundClusters.length) return { reassigned: 0, skipped: faceRows.length };

  console.log(
    `[FaceClusterBackfill] rebindUnassignedFaces — ${faceRows.length} candidates, ${boundClusters.length} bound clusters`,
  );

  let reassigned = 0;
  let skipped = 0;
  const YIELD_EVERY = 50;

  for (let i = 0; i < faceRows.length; i++) {
    const { face_id: faceId } = faceRows[i];
    try {
      const emb = await FaceEmbeddingService.getEmbedding(
        faceId,
        FACE_MODEL_VERSION,
      );
      if (!emb) {
        skipped++;
        continue;
      }

      // Find the nearest TAG-BOUND cluster.
      let bestId = null;
      let bestScore = -1;
      for (const bc of boundClusters) {
        if (bc.centroid.length !== emb.length) continue;
        const score = _cosineSim(bc.centroid, emb);
        if (score > bestScore) {
          bestScore = score;
          bestId = bc.clusterId;
        }
      }

      if (bestScore >= FaceClusterService.CLUSTER_THRESHOLD && bestId) {
        await FaceService.assignCluster(faceId, bestId);
        reassigned++;
      } else {
        skipped++;
      }
    } catch {
      skipped++;
    }
    if (i % YIELD_EVERY === 0) await _sleep(0);
    onProgress?.(i + 1, faceRows.length);
  }

  console.log(
    `[FaceClusterBackfill] rebindUnassignedFaces done — reassigned ${reassigned}, skipped ${skipped}`,
  );
  return { reassigned, skipped };
}

// ─── Export ─────────────────────────────────────────────────────────────────

export const FaceClusterBackfillService = {
  backfillAll,
  findOrClaimClusterForTag,
  mergeAnonymousClusters,
  rebindUnassignedFaces,
  getBackfillStats,
  needsBackfill,
};
