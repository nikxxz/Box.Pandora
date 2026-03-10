/**
 * PrototypeBackfillService
 *
 * Builds prototype vectors for ALL custom tags that already exist in the
 * database by joining `media_tags` with `image_embeddings`.
 *
 * Why this is needed:
 *   The self-learning prototype update in TaggingService only fires for tags
 *   applied *after* the smart-tagging feature was deployed.  Any tags the user
 *   had already applied to hundreds of photos will never appear as suggestions
 *   until their prototypes are seeded from the historic data.
 *
 * Algorithm (per tag):
 *   1. Load all embeddings for images labelled with that tag.
 *   2. Compute the arithmetic mean of those embeddings.
 *   3. L2-normalise the mean → prototype vector.
 *   4. Upsert into `tag_prototypes` with n = number of examples found.
 *
 * Usage:
 *   // One-time seed (only fills tags with no existing prototype)
 *   await PrototypeBackfillService.backfillAll(MODEL_VERSION, { onProgress });
 *
 *   // Force re-compute everything (e.g. after a model upgrade)
 *   await PrototypeBackfillService.backfillAll(MODEL_VERSION, { force: true });
 */

import { DatabaseService } from './DatabaseService';
import { decodeEmbedding, encodeEmbedding } from './ImageEmbeddingService';

// ─── L2 normalization ─────────────────────────────────────────────────────────

function l2Normalize(v) {
  let norm = 0;
  for (let i = 0; i < v.length; i++) norm += v[i] * v[i];
  norm = Math.sqrt(norm);
  if (norm > 0) for (let i = 0; i < v.length; i++) v[i] /= norm;
  return v;
}

// ─── Stats ────────────────────────────────────────────────────────────────────

/**
 * Returns a breakdown of which tags have / don't have prototypes.
 *
 * @param {string} modelVersion
 * @returns {Promise<{
 *   totalTags: number,
 *   tagsWithPrototype: number,
 *   tagsWithoutPrototype: number,
 *   tagsWithEmbeddableImages: number,  // tags that COULD be backfilled
 * }>}
 */
async function getBackfillStats(modelVersion) {
  const db = DatabaseService.getDb();

  const [{ rows: totalRows }] = await Promise.all([
    db.execute('SELECT COUNT(*) AS n FROM tags'),
  ]);
  const totalTags = totalRows[0]?.n ?? 0;

  const { rows: withProtoRows } = await db.execute(
    'SELECT COUNT(*) AS n FROM tag_prototypes WHERE model_version = ?',
    [modelVersion],
  );
  const tagsWithPrototype = withProtoRows[0]?.n ?? 0;

  // Tags that have at least one image with an embedding and no existing prototype
  const { rows: eligibleRows } = await db.execute(
    `SELECT COUNT(DISTINCT t.name) AS n
     FROM   media_tags mt
     JOIN   tags t ON t.id = mt.tag_id
     JOIN   image_embeddings ie
              ON ie.asset_id = mt.media_uri AND ie.model_version = ?
     WHERE  t.name NOT IN (
               SELECT tag_key FROM tag_prototypes WHERE model_version = ?
             )`,
    [modelVersion, modelVersion],
  );
  const tagsWithEmbeddableImages = eligibleRows[0]?.n ?? 0;

  return {
    totalTags,
    tagsWithPrototype,
    tagsWithoutPrototype: totalTags - tagsWithPrototype,
    tagsWithEmbeddableImages,
  };
}

/**
 * Quick check: returns true if there are tags with embeddings but no prototype.
 *
 * @param {string} modelVersion
 * @returns {Promise<boolean>}
 */
async function needsBackfill(modelVersion) {
  const stats = await getBackfillStats(modelVersion);
  return stats.tagsWithEmbeddableImages > 0;
}

// ─── Backfill ─────────────────────────────────────────────────────────────────

/**
 * Build / refresh prototype vectors for all tags using historic tag-image data.
 *
 * @param {string} modelVersion
 * @param {{
 *   force?:      boolean  — default false: re-compute even existing prototypes
 *   onProgress?: (done: number, total: number, tagName: string) => void
 *   onError?:    (tagName: string, err: Error) => void
 * }} opts
 * @returns {Promise<{ seeded: number, skipped: number, errors: number }>}
 */
async function backfillAll(modelVersion, opts = {}) {
  const { force = false, onProgress = null, onError = null } = opts;
  const db = DatabaseService.getDb();

  // ── Step 1: collect candidate tag IDs ──────────────────────────────────────
  // A candidate is any tag that has at least one linked image with an embedding.
  // If force=false we also exclude tags that already have a prototype.
  const excludeClause = force
    ? ''
    : `AND t.name NOT IN (
         SELECT tag_key FROM tag_prototypes WHERE model_version = ?
       )`;
  const excludeParams = force ? [] : [modelVersion];

  const { rows: tagRows } = await db.execute(
    `SELECT DISTINCT t.id, t.name
     FROM   media_tags mt
     JOIN   tags t ON t.id = mt.tag_id
     JOIN   image_embeddings ie
              ON ie.asset_id = mt.media_uri AND ie.model_version = ?
     ${excludeClause}
     ORDER  BY t.name`,
    [modelVersion, ...excludeParams],
  );

  const total = tagRows.length;
  let seeded = 0;
  let skipped = 0;
  let errors = 0;

  if (total === 0) {
    return { seeded, skipped, errors };
  }

  // ── Step 2: for each tag, load embeddings + compute batch mean ─────────────
  for (let i = 0; i < tagRows.length; i++) {
    const { id: tagId, name: tagName } = tagRows[i];

    try {
      const { rows: embRows } = await db.execute(
        `SELECT ie.embedding, ie.dim
         FROM   media_tags mt
         JOIN   image_embeddings ie
                  ON ie.asset_id = mt.media_uri AND ie.model_version = ?
         WHERE  mt.tag_id = ?`,
        [modelVersion, tagId],
      );

      if (!embRows.length) {
        skipped++;
        continue;
      }

      // Decode first embedding to get dimension
      const first = decodeEmbedding(embRows[0].embedding);
      if (!first || first.length === 0) {
        skipped++;
        continue;
      }

      const dim = first.length;

      // Batch mean: sum all embeddings then divide
      const sum = new Float32Array(dim);
      let validCount = 0;

      for (const row of embRows) {
        const emb = decodeEmbedding(row.embedding);
        if (!emb || emb.length !== dim) continue; // skip corrupt / dim-mismatch rows
        for (let d = 0; d < dim; d++) sum[d] += emb[d];
        validCount++;
      }

      if (validCount === 0) {
        skipped++;
        continue;
      }

      for (let d = 0; d < dim; d++) sum[d] /= validCount;
      const prototype = l2Normalize(sum);

      // Upsert into tag_prototypes
      await db.execute(
        `INSERT OR REPLACE INTO tag_prototypes
           (tag_key, prototype_blob, dim, n, model_version, updated_at)
         VALUES (?, ?, ?, ?, ?, ?)`,
        [
          tagName,
          encodeEmbedding(prototype),
          dim,
          validCount,
          modelVersion,
          Date.now(),
        ],
      );

      seeded++;
      onProgress?.(i + 1, total, tagName);
    } catch (err) {
      errors++;
      onError?.(tagName, err);
      console.warn(
        `[PrototypeBackfillService] Failed to backfill "${tagName}":`,
        err?.message ?? err,
      );
    }

    // Yield to avoid blocking the JS thread
    await _sleep(0);
  }

  console.log(
    `[PrototypeBackfillService] Done — seeded ${seeded}, skipped ${skipped}, errors ${errors} (modelVersion=${modelVersion})`,
  );
  return { seeded, skipped, errors };
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function _sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const PrototypeBackfillService = {
  backfillAll,
  getBackfillStats,
  needsBackfill,
};
