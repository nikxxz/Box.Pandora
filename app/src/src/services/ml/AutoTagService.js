/**
 * AutoTagService
 *
 * Drives the "Auto Tag a folder" workflow.
 *
 * For each image-type item in a folder it:
 *   1. Fetches ML Kit labels and applies any above-threshold labels as tags.
 *   2. Checks whether the image has detected faces with known cluster→person names.
 *      - Known face (cluster bound to a tag) → suspends and asks the caller to
 *        confirm the suggested name before continuing.
 *      - Face with no cluster / unbound cluster → silently skips (no name to suggest).
 *
 * The service communicates with the UI through callbacks so the modal can
 * remain fully declarative — it only needs to call `continue()` or
 * `skipFace()` / `renameAndContinue(newName)` when prompted.
 *
 * Usage (called from AutoTagModal):
 *   const session = AutoTagService.createSession({ items, onProgress, onFaceConfirm, onDone });
 *   session.start();     // begin scanning
 *   // when onFaceConfirm fires → user decides → call:
 *   session.confirmFace(confirmedName);   // apply name (original or corrected)
 *   session.skipFace();                   // skip this face, keep scanning
 */

import { labelImage } from './MLBridgeModule';
import { FaceService } from '../database/FaceService';
import { FaceClusterService } from '../database/FaceClusterService';
import { FaceEmbeddingService } from '../database/FaceEmbeddingService';
import { CooccurrenceService } from '../database/CooccurrenceService';
import { TagService } from '../database/TagService';
import { TaggingService } from './TaggingService';
import { FACE_MODEL_VERSION } from './FaceBridgeModule';

// Minimum ML Kit confidence to auto-apply a label as a tag.
const LABEL_CONFIDENCE_THRESHOLD = 0.7;

// Co-occurrence count needed to auto-apply a co-occurring tag.
// count=10 maps to the 0.70 confidence score in the scoring formula.
const COOCCUR_MIN_COUNT = 10;

// Minimum face quality score to bother processing a detected face.
const FACE_QUALITY_THRESHOLD = 0.60;

// ─── Session factory ──────────────────────────────────────────────────────────

/**
 * @param {{
 *   items: Array<{ uri: string, filename: string, type?: string }>,
 *   onProgress: (current: number, total: number, filename: string) => void,
 *   onFaceConfirm: (info: {
 *     uri: string,
 *     suggestedName: string,
 *     clusterId: string,
 *     faceId: string,
 *     faceBounds: { leftNorm, topNorm, rightNorm, bottomNorm },
 *   }) => void,
 *   onDone: (stats: { tagged: number, labelsApplied: number, facesNamed: number, skipped: number }) => void,
 * }} opts
 */
function createSession({ items, onProgress, onFaceConfirm, onDone }) {
  // Only process image-type items (DB column is media_type, not type)
  const imageItems = items.filter(
    it => !it.media_type?.includes('video') && it.uri,
  );

  let _cancelled = false;
  let _resolveConfirm = null; // set while paused for face confirmation

  // Session-level cache: clusterId → confirmed name (or null if skipped).
  // Each unique cluster is shown to the user exactly once; subsequent images
  // with the same cluster auto-apply the cached result without another dialog.
  const _clusterCache = new Map();

  const stats = { tagged: 0, labelsApplied: 0, facesNamed: 0, skipped: 0 };

  // ── Internal helpers ──────────────────────────────────────────────────────

  /**
   * Step 1 — Run ML Kit labels and apply those above threshold.
   * Returns { applied, newTagIds } so step 2 can use the tag IDs for
   * co-occurrence lookup.
   */
  async function _applyLabels(uri, existingNames) {
    try {
      const labels = await labelImage(uri);
      let applied = 0;
      const newTagIds = [];
      for (const { text, confidence } of labels) {
        if (!text || confidence < LABEL_CONFIDENCE_THRESHOLD) continue;
        if (existingNames.has(text.toLowerCase())) continue;
        const tagId = await TagService.createTag(text);
        if (tagId) {
          await TagService.addTagToMedia(uri, tagId);
          existingNames.add(text.toLowerCase());
          newTagIds.push(tagId);
          applied++;
        }
      }
      return { applied, newTagIds };
    } catch {
      return { applied: 0, newTagIds: [] };
    }
  }

  /**
   * Step 2 — For every tag just applied, find co-occurring tags and apply
   * any that exceed the confidence threshold.  Uses the existing+new tag IDs
   * so co-occurrence is evaluated against the full current tag set.
   */
  async function _applyCooccurringTags(allTagIds, existingNames, uri) {
    if (!allTagIds.length) return 0;
    try {
      const cooccurring = await CooccurrenceService.getCooccurringSuggestions(
        allTagIds,
        20,
      );
      let applied = 0;
      for (const { tagName, count } of cooccurring) {
        if (count < COOCCUR_MIN_COUNT) continue;
        if (existingNames.has(tagName.toLowerCase())) continue;
        const tagId = await TagService.createTag(tagName);
        if (tagId) {
          await TagService.addTagToMedia(uri, tagId);
          existingNames.add(tagName.toLowerCase());
          applied++;
        }
      }
      return applied;
    } catch {
      return 0;
    }
  }

  /**
   * Full item pass: load existing tags once, run ML Kit, then co-occurrence.
   */
  async function _processItem(item) {
    const existingTags = await TagService.getTagsForMedia(item.uri).catch(() => []);
    const existingIds = existingTags.map(t => t.id);
    const existingNames = new Set(existingTags.map(t => t.name.toLowerCase()));

    const { applied: mlkitCount, newTagIds } = await _applyLabels(item.uri, existingNames);
    const cooccurCount = await _applyCooccurringTags(
      [...existingIds, ...newTagIds],
      existingNames,
      item.uri,
    );
    return mlkitCount + cooccurCount;
  }

  async function _processFaces(uri) {
    try {
      const faces = await FaceService.getFacesForAsset(uri);
      for (const face of faces) {
        if (_cancelled) return;
        // Skip sentinel rows and low-quality detections.
        if (face.faceIndex === -1) continue;
        if ((face.qualityScore ?? 1) < FACE_QUALITY_THRESHOLD) continue;

        const cacheKey = face.clusterId ?? face.faceId;

        if (_clusterCache.has(cacheKey)) {
          // Already resolved this cluster/face — auto-apply silently.
          const cachedName = _clusterCache.get(cacheKey);
          if (cachedName) {
            const tagId = await TagService.createTag(cachedName, undefined, undefined, undefined, 'people');
            if (tagId) {
              await TagService.addTagToMedia(uri, tagId);
              stats.facesNamed++;
            }
          }
          continue;
        }

        // Get the face embedding to find ALL similar clusters (not just the
        // assigned one), giving the user a ranked list of name candidates.
        let clusterCandidates = []; // [{ clusterId, name, score }]
        const embedding = await FaceEmbeddingService.getEmbedding(
          face.faceId,
          FACE_MODEL_VERSION,
        ).catch(() => null);

        if (embedding) {
          const similar = await FaceClusterService.findSimilarClusters(
            embedding,
            0.50, // lower threshold to surface all plausible matches
          ).catch(() => []);

          // Resolve bound tag names for each similar cluster.
          for (const { clusterId, score } of similar) {
            const cluster = await FaceClusterService.getCluster(clusterId);
            if (!cluster?.tagId) continue;
            const tag = await TagService.getTagById(cluster.tagId);
            if (tag?.name) {
              clusterCandidates.push({ clusterId, name: tag.name, score });
            }
          }
          // Sort by similarity score descending.
          clusterCandidates.sort((a, b) => b.score - a.score);
        } else if (face.clusterId) {
          // No embedding available — fall back to the assigned cluster only.
          const cluster = await FaceClusterService.getCluster(face.clusterId);
          if (cluster?.tagId) {
            const tag = await TagService.getTagById(cluster.tagId);
            if (tag?.name) {
              clusterCandidates.push({ clusterId: face.clusterId, name: tag.name, score: 1 });
            }
          }
        }

        const suggestedName = clusterCandidates[0]?.name ?? null;

        // Pause and ask the user to confirm / name this face.
        const confirmedName = await _askConfirm({
          uri,
          suggestedName,
          // All candidate names passed so the modal can show them as chips.
          clusterCandidates: clusterCandidates.map(c => c.name),
          clusterId: face.clusterId ?? clusterCandidates[0]?.clusterId ?? null,
          faceId: face.faceId,
          faceBounds: {
            leftNorm: face.leftNorm,
            topNorm: face.topNorm,
            rightNorm: face.rightNorm,
            bottomNorm: face.bottomNorm,
          },
        });

        if (_cancelled) return;

        _clusterCache.set(cacheKey, confirmedName ?? null);

        if (!confirmedName) continue;

        const finalTagId = await TagService.createTag(confirmedName, undefined, undefined, undefined, 'people');
        if (finalTagId) {
          await TagService.addTagToMedia(uri, finalTagId);
          stats.facesNamed++;

          // Bind the face's cluster to the confirmed tag so future scans
          // recognise this person automatically.
          const bindClusterId = face.clusterId ?? clusterCandidates[0]?.clusterId;
          if (bindClusterId) {
            FaceClusterService.bindClusterToTag(bindClusterId, finalTagId).catch(() => {});
          }

          // User-confirmed face → real learning signal.
          TaggingService.runSessionLearning([
            { mediaUri: uri, tagId: finalTagId, tagName: confirmedName },
          ]).catch(() => {});
        }
      }
    } catch {
      // Gracefully skip on error
    }
  }

  function _askConfirm(info) {
    return new Promise(resolve => {
      _resolveConfirm = resolve;
      onFaceConfirm(info);
    });
  }

  // ── Public API ────────────────────────────────────────────────────────────

  async function start() {
    const total = imageItems.length;

    for (let i = 0; i < imageItems.length; i++) {
      if (_cancelled) break;

      const item = imageItems[i];
      onProgress(i + 1, total, item.filename ?? item.uri);

      // Apply learned suggestions + ML Kit labels (shares one existing-tag load).
      const labelsApplied = await _processItem(item);
      stats.labelsApplied += labelsApplied;

      // Process faces (may pause for confirmation)
      const facesNamedBefore = stats.facesNamed;
      await _processFaces(item.uri);

      // Count the image as tagged if it received any kind of tag,
      // skipped only if both passes produced nothing.
      if (labelsApplied > 0 || stats.facesNamed > facesNamedBefore) {
        stats.tagged++;
      } else {
        stats.skipped++;
      }

      if (_cancelled) break;

      // Small yield so the UI thread can breathe between items
      await new Promise(r => setTimeout(r, 30));
    }

    _resolveConfirm = null;
    if (!_cancelled) onDone(stats);
  }

  /** Call when the user confirms (or corrects) a face name. */
  function confirmFace(name) {
    if (_resolveConfirm) {
      const resolve = _resolveConfirm;
      _resolveConfirm = null;
      resolve(name ?? null);
    }
  }

  /** Call when the user wants to skip this face without naming it. */
  function skipFace() {
    confirmFace(null);
  }

  function cancel() {
    _cancelled = true;
    if (_resolveConfirm) skipFace(); // unblock the loop so it can exit
  }

  return { start, confirmFace, skipFace, cancel };
}

export const AutoTagService = { createSession };
