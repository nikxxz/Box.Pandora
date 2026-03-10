/**
 * TaggingService
 *
 * Thin orchestration layer that wraps TagService tag operations with
 * self-learning side effects.
 *
 * Learning is deliberately deferred to runSessionLearning() which is called
 * once when the tags modal closes.  This keeps every individual tag tap fast
 * (only the DB write + rejection-clear happen synchronously) and batches the
 * heavier prototype / co-occurrence / face work into a single background pass.
 *
 *   addTagToMedia / batchAddTag — fast path: DB write + rejection clear only
 *   runSessionLearning          — deferred: prototype, co-occurrence, face link
 */

import { TagService } from '../database/TagService';
import { ImageEmbeddingService } from '../database/ImageEmbeddingService';
import { TagPrototypeService } from '../database/TagPrototypeService';
import { FaceService } from '../database/FaceService';
import { FaceEmbeddingService } from '../database/FaceEmbeddingService';
import { FaceClusterService } from '../database/FaceClusterService';
import { CooccurrenceService } from '../database/CooccurrenceService';
import { MODEL_VERSION } from './EmbeddingBridgeModule';
import { FaceBridgeModule, FACE_MODEL_VERSION } from './FaceBridgeModule';

// ─── Internal helpers ─────────────────────────────────────────────────────────

/**
 * Look up the embedding for the asset and update the prototype for tagKey.
 * Silently no-ops if the embedding is not yet available.
 */
async function _updatePrototypeFor(mediaUri, tagKey) {
  try {
    const embedding = await ImageEmbeddingService.getEmbedding(
      mediaUri,
      MODEL_VERSION,
    );
    if (!embedding || embedding.length === 0) return; // not yet indexed — skip
    await TagPrototypeService.updatePrototype(tagKey, embedding, MODEL_VERSION);
  } catch (err) {
    console.warn(
      '[TaggingService] prototype update failed:',
      err?.message ?? err,
    );
  }
}

/**
 * When a people-category tag is applied, bind any detected face clusters on
 * this asset to that tag so future face suggestions carry the tag name.
 *
 * If a face has no cluster yet but its embedding is available, we create a
 * new cluster from that embedding and bind it immediately.
 */
async function _linkFacesToPeopleTag(mediaUri, tagId) {
  if (!FaceBridgeModule.isNativeAvailable()) return;

  try {
    const faces = await FaceService.getFacesForAsset(mediaUri);
    if (!faces.length) return;

    for (const face of faces) {
      // Skip sentinel rows (no-face marker).
      if (face.faceIndex === -1) continue;

      if (face.clusterId) {
        await FaceClusterService.bindClusterToTag(face.clusterId, tagId);
      } else {
        // No cluster yet — try to create one from this face's embedding.
        const emb = await FaceEmbeddingService.getEmbedding(
          face.faceId,
          FACE_MODEL_VERSION,
        );
        if (emb) {
          const clusterId = await FaceClusterService.createCluster(emb);
          await FaceService.assignCluster(face.faceId, clusterId);
          await FaceClusterService.bindClusterToTag(clusterId, tagId);
        }
      }
    }
  } catch (err) {
    console.warn('[TaggingService] face linking failed:', err?.message ?? err);
  }
}

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Add a tag to a media item AND update the learned prototype.
 *
 * @param {string} mediaUri
 * @param {number} tagId
 * @param {string} tagName  — used as the prototype key
 */
async function addTagToMedia(mediaUri, tagId, tagName) {
  await TagService.addTagToMedia(mediaUri, tagId);
  // Clear rejection immediately — fast DB write, important for UX.
  TagPrototypeService.clearRejection(tagName || String(tagId), mediaUri).catch(() => {});
  // Heavy learning (prototype, co-occurrence, face) is deferred to runSessionLearning().
}

/**
 * Remove a tag from a media item.
 * Does not update the prototype (removal is out-of-scope for online mean).
 *
 * @param {string} mediaUri
 * @param {number} tagId
 */
async function removeTagFromMedia(mediaUri, tagId) {
  await TagService.removeTagFromMedia(mediaUri, tagId);
}

/**
 * Batch-add a tag to many media items (multi-select).
 * Fires prototype updates for each asset asynchronously.
 *
 * @param {string[]} mediaUris
 * @param {number}   tagId
 * @param {string}   tagName
 */
async function batchAddTag(mediaUris, tagId, tagName) {
  await TagService.batchAddTag(mediaUris, tagId);
  const key = tagName || String(tagId);
  // Clear rejections immediately; heavy learning deferred to runSessionLearning().
  for (const uri of mediaUris) {
    TagPrototypeService.clearRejection(key, uri).catch(() => {});
  }
}

/**
 * Batch-remove a tag from many media items.
 *
 * @param {string[]} mediaUris
 * @param {number}   tagId
 */
async function batchRemoveTag(mediaUris, tagId) {
  await TagService.batchRemoveTag(mediaUris, tagId);
}

/**
 * Run all deferred learning for a tagging session.
 *
 * Call this once when the tags modal closes, passing every tag that was
 * added during the session.  All work is fire-and-forget — never throws.
 *
 * @param {Array<{ mediaUri: string, tagId: number, tagName: string }>} additions
 */
async function runSessionLearning(additions = []) {
  if (!additions.length) return;

  // Prototype + co-occurrence update per {uri, tag} pair.
  for (const { mediaUri, tagId, tagName } of additions) {
    const key = tagName || String(tagId);
    _updatePrototypeFor(mediaUri, key).catch(err =>
      console.warn('[TaggingService] bg prototype update error:', err?.message ?? err),
    );
    CooccurrenceService.incrementCooccurrence(mediaUri, tagId).catch(() => {});
  }

  // Face linking — look up DB category once per unique tagId.
  const uniqueTagIds = [...new Set(additions.map(a => a.tagId))];
  for (const tagId of uniqueTagIds) {
    TagService.getTagById(tagId)
      .then(tag => {
        if (tag?.category !== 'people') return;
        const uris = additions
          .filter(a => a.tagId === tagId)
          .map(a => a.mediaUri);
        for (const uri of uris) {
          _linkFacesToPeopleTag(uri, tagId).catch(() => {});
        }
      })
      .catch(() => {});
  }
}

export const TaggingService = {
  addTagToMedia,
  removeTagFromMedia,
  batchAddTag,
  batchRemoveTag,
  runSessionLearning,
};
