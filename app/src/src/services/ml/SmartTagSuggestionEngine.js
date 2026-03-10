/**
 * SmartTagSuggestionEngine
 *
 * 7-signal unified scoring engine for photo tag suggestions.
 *
 * Signals (evaluated in parallel):
 *   1. heuristic  — HeuristicTagger (screenshot, night, blur, mono, document)
 *   2. learned    — MobileNetV3 cosine similarity vs tag prototypes
 *   3. mlkit      — ML Kit image labels cached in tag_suggestions table
 *   4. face       — Face cluster → bound people tags
 *   5. cooccur    — Tags that co-occur with already-applied tags (first-class candidates)
 *   6. group      — Clique companions: tags from a mutually co-occurring group
 *                   when some members are already applied (own base score)
 *   7. cooccurBoost — Additional score boost added to any candidate whose tag
 *                     co-occurs with already-applied tags
 *
 * Scoring per candidate:
 *   final_score = max(base_score) + cooccur_boost + recency_boost − rejection_penalty
 *
 * Output: up to 8 suggestions, slot-balanced:
 *   ≤ 2 type/heuristic  |  ≤ 2 people (face)  |  ≤ 5 content (learned/mlkit/cooccur/group)
 *
 * Every signal source is wrapped in try/catch — the engine degrades
 * gracefully when any individual source is unavailable.
 */

import { HeuristicTagger } from './HeuristicTagger';
import { HeuristicTagService } from '../database/HeuristicTagService';
import { ImageEmbeddingService } from '../database/ImageEmbeddingService';
import {
  TagPrototypeService,
  MIN_EXAMPLES,
} from '../database/TagPrototypeService';
import { TagSuggestionService } from '../database/TagSuggestionService';
import { FaceService } from '../database/FaceService';
import { FaceClusterService } from '../database/FaceClusterService';
import { CooccurrenceService } from '../database/CooccurrenceService';
import { DatabaseService } from '../database/DatabaseService';
import { MODEL_VERSION } from './EmbeddingBridgeModule';
import { isSceneIndexingEnabled } from './EmbeddingIndexer';
import { FaceBridgeModule } from './FaceBridgeModule';
import { isFaceIndexingEnabled } from './FaceIndexer';

// ─── Tuning parameters ────────────────────────────────────────────────────────

/** Maximum number of learned (prototype) suggestions. */
const TOP_K = 5;

/** Minimum cosine similarity to include a learned suggestion. */
const CUSTOM_THRESHOLD = 0.35;

/**
 * Margin rule: only accept the full topK if top1 − topK ≥ this value.
 * Prevents bunched noisy ties.  Set 0 to disable.
 */
const MARGIN_MIN = 0.03;

/** Discard ML Kit results older than this. */
const MLKIT_MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000; // 30 days

const MAX_SUGGESTIONS = 8;
const MAX_TYPE_SLOTS = 2; // heuristic sources
const MAX_PEOPLE_SLOTS = 2; // face sources
const MAX_CONTENT_SLOTS = 5; // learned / mlkit

/**
 * Cache heuristic results to DB when running.
 * Set to false during unit tests or when you want stateless behaviour.
 */
let _persistHeuristics = true;

// ─── Cosine similarity (dot product of L2-normalised vectors) ─────────────────

function cosineSim(a, b) {
  if (a.length !== b.length) return -1;
  let sum = 0;
  for (let i = 0; i < a.length; i++) sum += a[i] * b[i];
  return sum;
}

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Get all suggestions for an asset.
 *
 * @param {string}   assetId
 * @param {{ filename?: string, album_name?: string, uri?: string }} mediaRecord
 * @param {number[]} existingTagIds   — tag IDs already applied to this asset
 * @param {{ forceRefreshHeuristics?: boolean }} opts
 * @returns {Promise<Array<{ tagKey: string, score: number, source: string, detected: boolean }>>}
 */
export async function getSuggestionsForAsset(
  assetId,
  mediaRecord = {},
  existingTagIds = [],
  opts = {},
) {
  const { forceRefreshHeuristics = false } = opts;

  // All 7 signals + supporting data load in parallel.
  const [
    heuristicCandidates,
    learnedCandidates,
    mlkitCandidates,
    faceCandidates,
    groupCandidates,
    cooccurrenceCandidates,
    cooccurMap,
    rejectionCounts,
    tagRecencyMap,
  ] = await Promise.all([
    _getHeuristicSuggestions(
      assetId,
      mediaRecord,
      forceRefreshHeuristics,
    ).catch(() => []),
    _getLearnedSuggestions(assetId).catch(() => []),
    _getMlKitSuggestions(assetId).catch(() => []),
    _getFaceSuggestions(assetId).catch(() => []),
    _getGroupSuggestions(existingTagIds).catch(() => []),
    _getCooccurrenceCandidates(existingTagIds).catch(() => []),
    _getCooccurrenceMap(existingTagIds).catch(() => ({})),
    _loadRejectionCounts().catch(() => ({})),
    _loadTagRecencyMap().catch(() => ({})),
  ]);

  // Merge all candidates into a map keyed by tagKey, keeping the highest
  // base score from any single source.
  const byKey = new Map();
  const addCandidate = c => {
    const existing = byKey.get(c.tagKey);
    if (!existing || c.score > existing.score) {
      byKey.set(c.tagKey, c);
    }
  };
  for (const c of [
    ...heuristicCandidates,
    ...learnedCandidates,
    ...mlkitCandidates,
    ...faceCandidates,
    ...groupCandidates,
    ...cooccurrenceCandidates,
  ]) {
    addCandidate(c);
  }

  // Score each unique candidate.
  const now = Date.now();
  const scored = [];

  for (const [tagKey, candidate] of byKey) {
    const rejectionPenalty = _rejectionPenalty(tagKey, rejectionCounts);
    if (rejectionPenalty === Infinity) continue; // permanently suppressed

    const cooccurBoost = cooccurMap[tagKey] ?? 0;
    const recencyBoost = _recencyBoost(tagKey, tagRecencyMap, now);
    const finalScore =
      candidate.score + cooccurBoost + recencyBoost - rejectionPenalty;

    scored.push({ ...candidate, score: finalScore });
  }

  scored.sort((a, b) => b.score - a.score);

  return _applySlotRules(scored);
}

// ─── Signal 1: Heuristic ──────────────────────────────────────────────────────

async function _getHeuristicSuggestions(assetId, mediaRecord, forceRefresh) {
  if (!forceRefresh) {
    const cached = await HeuristicTagService.getHeuristicTagsForAsset(assetId);
    if (cached.length > 0) {
      return cached
        .filter(r => r.score > 0)
        .map(r => ({
          tagKey: r.tagKey,
          score: r.score,
          source: 'heuristic',
          detected: true,
        }));
    }
  }

  const results = await HeuristicTagger.runAll(assetId, mediaRecord);
  const hits = results.filter(r => r.detected);

  if (_persistHeuristics && hits.length > 0) {
    await HeuristicTagService.saveHeuristicTagsBatch(
      assetId,
      hits.map(r => ({ tagKey: r.tagKey, score: r.score })),
    );
  }

  return hits.map(r => ({
    tagKey: r.tagKey,
    score: r.score,
    source: 'heuristic',
    detected: true,
  }));
}

// ─── Signal 2: Learned scene prototype ───────────────────────────────────────

async function _getLearnedSuggestions(assetId) {
  if (!isSceneIndexingEnabled()) return []; // scene model disabled by user
  const embedding = await ImageEmbeddingService.getEmbedding(
    assetId,
    MODEL_VERSION,
  );
  if (!embedding || embedding.length === 0) return [];

  const prototypes = await TagPrototypeService.getAllPrototypes(
    MODEL_VERSION,
    MIN_EXAMPLES,
  );
  if (!prototypes.length) return [];

  const scored = prototypes
    .map(p => ({
      tagKey: p.tagKey,
      score: cosineSim(embedding, p.prototype),
      source: 'learned',
      detected: true,
    }))
    .filter(s => s.score >= CUSTOM_THRESHOLD)
    .sort((a, b) => b.score - a.score);

  if (!scored.length) return [];

  const topK = scored.slice(0, TOP_K);
  if (topK.length >= 2 && MARGIN_MIN > 0) {
    const topScore = topK[0].score;
    const kthScore = topK[topK.length - 1].score;
    if (topScore - kthScore < MARGIN_MIN) return topK.slice(0, 1);
  }

  return topK;
}

// ─── Signal 3: ML Kit labels ──────────────────────────────────────────────────

async function _getMlKitSuggestions(assetId) {
  const results = await TagSuggestionService.getMlkitSuggestionsForAsset(
    assetId,
    {
      maxAgeMs: MLKIT_MAX_AGE_MS,
    },
  );
  // getMlkitSuggestionsForAsset returns { text, confidence, createdAt }
  return results.map(r => ({
    tagKey: r.text,
    score: r.confidence,
    source: 'mlkit',
    detected: true,
  }));
}

// ─── Signal 4: Face cluster → people tag ─────────────────────────────────────

async function _getFaceSuggestions(assetId) {
  if (!isFaceIndexingEnabled()) return []; // face model disabled by user
  if (!FaceBridgeModule.isNativeAvailable()) return [];

  const faces = await FaceService.getFacesForAsset(assetId);
  if (!faces.length) return [];

  // Collect faces that have a cluster assignment (skip sentinels).
  const facesWithClusters = faces.filter(
    f => f.faceIndex !== -1 && f.clusterId,
  );
  if (!facesWithClusters.length) return [];

  // Resolve all clusters in parallel — cache hits after the first warm-up,
  // so this is effectively O(n) Map lookups with no extra DB round-trips.
  const clusters = await Promise.all(
    facesWithClusters.map(f => FaceClusterService.getCluster(f.clusterId)),
  );

  // Collect the best quality score per unique tagId.
  const tagBestQuality = new Map(); // tagId → best qualityScore
  for (let i = 0; i < facesWithClusters.length; i++) {
    const cluster = clusters[i];
    if (!cluster?.tagId) continue;
    const q = facesWithClusters[i].qualityScore ?? 0.5;
    const existing = tagBestQuality.get(cluster.tagId);
    if (existing === undefined || q > existing) {
      tagBestQuality.set(cluster.tagId, q);
    }
  }
  if (!tagBestQuality.size) return [];

  // Single batch query for all tag names (replaces N individual queries).
  const db = DatabaseService.getDb();
  const ids = Array.from(tagBestQuality.keys());
  const placeholders = ids.map(() => '?').join(', ');
  const { rows } = await db.execute(
    `SELECT id, name FROM tags WHERE id IN (${placeholders})`,
    ids,
  );

  return rows.map(r => ({
    tagKey: r.name,
    score: Math.min(0.95, (tagBestQuality.get(r.id) ?? 0.5) * 0.9),
    source: 'face',
    detected: true,
  }));
}

// ─── Signal 5a: Co-occurrence candidates (first-class suggestions) ────────────

/**
 * Tags that frequently co-occur with already-applied tags become standalone
 * suggestions.  Score is calibrated to the co-occurrence count so tags that
 * appear together constantly rank higher than occasional pairings.
 */
async function _getCooccurrenceCandidates(existingTagIds) {
  if (!existingTagIds.length) return [];

  const cooccurring = await CooccurrenceService.getCooccurringSuggestions(
    existingTagIds,
    20,
  );

  return cooccurring
    .filter(r => r.count >= 2) // require at least 2 co-occurrences
    .map(r => ({
      tagKey: r.tagName,
      // count 2 → 0.38, count 5 → 0.50, count 10 → 0.60, count 20 → 0.70 (cap)
      score: Math.min(0.70, 0.30 + r.count * 0.04),
      source: 'cooccur',
      detected: true,
    }));
}

// ─── Signal 5b: Co-occurrence (boost map for existing candidates) ─────────────

async function _getCooccurrenceMap(existingTagIds) {
  if (!existingTagIds.length) return {};

  const cooccurring = await CooccurrenceService.getCooccurringSuggestions(
    existingTagIds,
    10,
  );
  const map = {};
  for (const r of cooccurring) {
    // count of 1 → +0.01 boost, count of 15 → +0.15 (capped)
    map[r.tagName] = Math.min(0.15, r.count * 0.01);
  }
  return map;
}

// ─── Signal 6: Group (clique) companions ─────────────────────────────────────

/**
 * Returns first-class tag suggestions derived from tag cliques.
 *
 * When some members of a mutually co-occurring group are already applied to
 * this photo, the remaining members become strong suggestions — the more of
 * the group already present, the higher the score.
 *
 * Score mapping:
 *   confidence 1.0 (all-but-one applied) → 0.90
 *   confidence 0.5                        → 0.65
 *   confidence < 0.33                     → 0.50  (one member of a 4+-group)
 *
 * @param {number[]} existingTagIds
 * @returns {Promise<Array<{ tagKey: string, score: number, source: string, detected: boolean }>>}
 */
async function _getGroupSuggestions(existingTagIds) {
  if (!existingTagIds.length) return [];

  const companions = await CooccurrenceService.getCliqueCompanions(
    existingTagIds,
  );
  if (!companions.length) return [];

  return companions.map(c => ({
    tagKey: c.tagName,
    // confidence → score: 0.50 base + 0.40 * confidence
    score: Math.min(0.9, 0.5 + 0.4 * c.confidence),
    source: 'group',
    detected: true,
    // extra metadata surfaced for debug / UI
    groupSize: c.groupSize,
    appliedCount: c.appliedCount,
  }));
}

// ─── Scoring helpers ──────────────────────────────────────────────────────────

function _rejectionPenalty(tagKey, rejectionCounts) {
  const count = rejectionCounts[tagKey] ?? 0;
  if (count >= 20) return Infinity; // permanently suppress
  if (count >= 10) return 0.3;
  if (count >= 5) return 0.18;
  if (count >= 2) return 0.08;
  if (count >= 1) return 0.04; // first dismiss → immediate small nudge
  return 0;
}

function _recencyBoost(tagKey, tagRecencyMap, now) {
  const updatedAt = tagRecencyMap[tagKey] ?? 0;
  const ageMs = now - updatedAt;
  if (ageMs < 7 * 86_400_000) return 0.08;
  if (ageMs < 30 * 86_400_000) return 0.04;
  if (ageMs < 90 * 86_400_000) return 0.02;
  return 0;
}

// ─── Data loaders ─────────────────────────────────────────────────────────────

async function _loadRejectionCounts() {
  return TagPrototypeService.getAllRejectionCounts();
}

async function _loadTagRecencyMap() {
  try {
    const db = DatabaseService.getDb();
    const { rows } = await db.execute('SELECT name, updated_at FROM tags');
    const map = {};
    for (const r of rows) map[r.name] = r.updated_at ?? 0;
    return map;
  } catch {
    return {};
  }
}

// ─── Slot rules ───────────────────────────────────────────────────────────────

function _applySlotRules(sorted) {
  const result = [];
  let typeCount = 0;
  let peopleCount = 0;
  let contentCount = 0;

  for (const s of sorted) {
    if (result.length >= MAX_SUGGESTIONS) break;

    if (s.source === 'heuristic') {
      if (typeCount >= MAX_TYPE_SLOTS) continue;
      typeCount++;
    } else if (s.source === 'face') {
      if (peopleCount >= MAX_PEOPLE_SLOTS) continue;
      peopleCount++;
    } else {
      // learned, mlkit, group all share the content slots
      if (contentCount >= MAX_CONTENT_SLOTS) continue;
      contentCount++;
    }

    result.push(s);
  }

  return result;
}

// ─── Configuration ────────────────────────────────────────────────────────────

/** Allow tests to disable DB persistence. */
export function _setPersistHeuristics(val) {
  _persistHeuristics = val;
}

export const SmartTagSuggestionEngine = {
  getSuggestionsForAsset,
  // Exposed constants for debug screen.
  TOP_K,
  CUSTOM_THRESHOLD,
  MARGIN_MIN,
};
