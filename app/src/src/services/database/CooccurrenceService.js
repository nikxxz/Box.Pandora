/**
 * CooccurrenceService
 *
 * Tracks how often pairs of tags are applied to the same photo.
 * Used to generate "companion tag" suggestions: if the user just added "beach",
 * suggest "vacation", "sunset", etc. based on historical co-tagging patterns.
 *
 * Table: tag_cooccurrences(tag_id_a, tag_id_b, count, last_seen)
 *   — Symmetric: both (a,b) and (b,a) rows are kept for fast single-direction queries.
 *   — count increments every time tagA and tagB appear on the same photo.
 *   — last_seen updated on every co-occurrence so recency can factor into ranking.
 *
 * Group (clique) detection:
 *   Beyond simple pair boosts, the service can detect groups of tags where
 *   EVERY pair is strongly co-occurring (e.g. AB, BC, CA all high → group ABC).
 *   Uses Bron-Kerbosch maximal-clique algorithm on an in-memory adjacency graph
 *   built from pairs above CLIQUE_MIN_COUNT.
 */

import { DatabaseService } from './DatabaseService';

// ─── Constants ────────────────────────────────────────────────────────────────

/**
 * Minimum co-occurrence count for an edge to be included in clique detection.
 * Pairs seen fewer than this many times are treated as noise.
 */
export const CLIQUE_MIN_COUNT = 5;

// ─── Write ────────────────────────────────────────────────────────────────────

/**
 * Update co-occurrence counts after a new tag is applied to a photo.
 *
 * Finds all tags already on the photo, then increments (or inserts) a row for
 * each (existingTagId, newTagId) pair. Also inserts the symmetric (newTagId,
 * existingTagId) row.
 *
 * Fire-and-forget safe — never throws to caller.
 *
 * @param {string} mediaUri
 * @param {number} newTagId  — the tag that was just applied
 */
async function incrementCooccurrence(mediaUri, newTagId) {
  const db = DatabaseService.getDb();

  // Fetch existing tags on this photo (excluding the one we just added).
  const { rows: tagRows } = await db.execute(
    'SELECT tag_id FROM media_tags WHERE media_uri = ? AND tag_id != ?',
    [mediaUri, newTagId],
  );

  if (!tagRows.length) return;

  const now = Date.now();

  // Upsert both directions for each pair.
  await db.executeBatch(
    tagRows.flatMap(({ tag_id: existingId }) => [
      [
        `INSERT INTO tag_cooccurrences (tag_id_a, tag_id_b, count, last_seen)
         VALUES (?, ?, 1, ?)
         ON CONFLICT(tag_id_a, tag_id_b) DO UPDATE
           SET count = count + 1, last_seen = excluded.last_seen`,
        [existingId, newTagId, now],
      ],
      [
        `INSERT INTO tag_cooccurrences (tag_id_a, tag_id_b, count, last_seen)
         VALUES (?, ?, 1, ?)
         ON CONFLICT(tag_id_a, tag_id_b) DO UPDATE
           SET count = count + 1, last_seen = excluded.last_seen`,
        [newTagId, existingId, now],
      ],
    ]),
  );
}

// ─── Read ─────────────────────────────────────────────────────────────────────

/**
 * Return tags that frequently co-occur with the given applied tag IDs,
 * sorted by total co-occurrence count (then recency).
 * Excludes tags already applied to this photo.
 *
 * @param {number[]} appliedTagIds   — tag IDs already on the photo
 * @param {number}   [limit=10]
 * @returns {Promise<Array<{
 *   tagId: number,
 *   tagName: string,
 *   count: number,
 *   lastSeen: number,
 * }>>}
 */
async function getCooccurringSuggestions(appliedTagIds, limit = 10) {
  if (!appliedTagIds.length) return [];

  const db = DatabaseService.getDb();
  const placeholders = appliedTagIds.map(() => '?').join(',');

  const { rows } = await db.execute(
    `SELECT   tc.tag_id_b          AS tag_id,
              t.name               AS tag_name,
              SUM(tc.count)        AS total_count,
              MAX(tc.last_seen)    AS last_seen
     FROM     tag_cooccurrences tc
     JOIN     tags t ON t.id = tc.tag_id_b
     WHERE    tc.tag_id_a IN (${placeholders})
       AND    tc.tag_id_b NOT IN (${placeholders})
     GROUP BY tc.tag_id_b
     ORDER BY total_count DESC, last_seen DESC
     LIMIT    ?`,
    [...appliedTagIds, ...appliedTagIds, limit],
  );

  return rows.map(r => ({
    tagId: r.tag_id,
    tagName: r.tag_name,
    count: r.total_count,
    lastSeen: r.last_seen,
  }));
}

/**
 * Return the top co-occurring tag pairs (for debug display).
 *
 * @param {number} [limit=20]
 * @returns {Promise<Array<{ tagA: string, tagB: string, count: number }>>}
 */
async function getTopPairs(limit = 20) {
  const db = DatabaseService.getDb();
  // Only fetch (a < b) direction to avoid duplicates.
  const { rows } = await db.execute(
    `SELECT ta.name AS tag_a, tb.name AS tag_b, tc.count
     FROM   tag_cooccurrences tc
     JOIN   tags ta ON ta.id = tc.tag_id_a
     JOIN   tags tb ON tb.id = tc.tag_id_b
     WHERE  tc.tag_id_a < tc.tag_id_b
     ORDER  BY tc.count DESC
     LIMIT  ?`,
    [limit],
  );
  return rows.map(r => ({ tagA: r.tag_a, tagB: r.tag_b, count: r.count }));
}

// ─── Clique detection ─────────────────────────────────────────────────────────

/**
 * Find all maximal cliques in the co-occurrence graph using Bron-Kerbosch.
 *
 * A "clique" is a group of tags where every pair has co-occurred at least
 * `minCount` times — they are ALL mutually associated with each other.
 * Example: if AB, BC, and CA all have high counts, {A, B, C} is a clique.
 *
 * @param {number} [minCount=CLIQUE_MIN_COUNT]
 * @returns {Promise<Array<Array<{ tagId: number, tagName: string }>>>}
 *   Each entry is a group of 2+ mutually co-occurring tags.
 */
async function getTagCliques(minCount = CLIQUE_MIN_COUNT) {
  const db = DatabaseService.getDb();

  // Load edges above threshold (deduplicated: tag_id_a < tag_id_b).
  const { rows } = await db.execute(
    `SELECT tc.tag_id_a, tc.tag_id_b, ta.name AS name_a, tb.name AS name_b
     FROM   tag_cooccurrences tc
     JOIN   tags ta ON ta.id = tc.tag_id_a
     JOIN   tags tb ON tb.id = tc.tag_id_b
     WHERE  tc.tag_id_a < tc.tag_id_b
       AND  tc.count >= ?`,
    [minCount],
  );

  if (!rows.length) return [];

  // Build adjacency structure.
  const nodes = new Map(); // tagId → tagName
  const adj = new Map(); // tagId → Set<tagId>
  for (const r of rows) {
    nodes.set(r.tag_id_a, r.name_a);
    nodes.set(r.tag_id_b, r.name_b);
    if (!adj.has(r.tag_id_a)) adj.set(r.tag_id_a, new Set());
    if (!adj.has(r.tag_id_b)) adj.set(r.tag_id_b, new Set());
    adj.get(r.tag_id_a).add(r.tag_id_b);
    adj.get(r.tag_id_b).add(r.tag_id_a);
  }

  // Bron-Kerbosch with pivot to enumerate all maximal cliques.
  const cliques = [];
  _bronKerbosch(new Set(), new Set(nodes.keys()), new Set(), adj, cliques);

  return cliques
    .filter(c => c.size >= 2)
    .map(c => Array.from(c).map(id => ({ tagId: id, tagName: nodes.get(id) })));
}

/**
 * Given a set of already-applied tag IDs, return companion tags that belong
 * to the same clique(s) but are not yet applied, ordered by confidence.
 *
 * Confidence = appliedCount / (cliqueSize − 1).
 * e.g. 3 of 4 clique members already applied → confidence 1.0 (certain).
 *      1 of 3 members applied              → confidence 0.5.
 *
 * @param {number[]} appliedTagIds
 * @param {number}   [minCount=CLIQUE_MIN_COUNT]
 * @returns {Promise<Array<{
 *   tagId:        number,
 *   tagName:      string,
 *   confidence:   number,
 *   groupSize:    number,
 *   appliedCount: number,
 * }>>}
 */
async function getCliqueCompanions(appliedTagIds, minCount = CLIQUE_MIN_COUNT) {
  if (!appliedTagIds.length) return [];

  const cliques = await getTagCliques(minCount);
  if (!cliques.length) return [];

  const appliedSet = new Set(appliedTagIds);
  const best = new Map(); // tagName → best candidate object

  for (const group of cliques) {
    const matchCount = group.filter(m => appliedSet.has(m.tagId)).length;
    if (matchCount === 0) continue;

    // confidence: fraction of the remaining group already satisfied
    const confidence = matchCount / (group.length - 1);

    for (const member of group) {
      if (appliedSet.has(member.tagId)) continue;

      const prev = best.get(member.tagName);
      if (!prev || confidence > prev.confidence) {
        best.set(member.tagName, {
          tagId: member.tagId,
          tagName: member.tagName,
          confidence,
          groupSize: group.length,
          appliedCount: matchCount,
        });
      }
    }
  }

  return Array.from(best.values()).sort((a, b) => b.confidence - a.confidence);
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const CooccurrenceService = {
  incrementCooccurrence,
  getCooccurringSuggestions,
  getTopPairs,
  getTagCliques,
  getCliqueCompanions,
  CLIQUE_MIN_COUNT,
};

// ─── Bron-Kerbosch (private) ──────────────────────────────────────────────────

/**
 * Recursive Bron-Kerbosch with pivoting.
 * R = current clique, P = candidates, X = already-processed.
 * Appends maximal cliques (as Sets of tagIds) to `result`.
 */
function _bronKerbosch(R, P, X, adj, result) {
  if (P.size === 0 && X.size === 0) {
    if (R.size >= 2) result.push(new Set(R));
    return;
  }

  // Choose pivot u ∈ P ∪ X that maximises |N(u) ∩ P|.
  let pivot = null;
  let maxConn = -1;
  for (const u of [...P, ...X]) {
    const conn = [...(adj.get(u) ?? [])].filter(v => P.has(v)).length;
    if (conn > maxConn) {
      maxConn = conn;
      pivot = u;
    }
  }
  const pivotNeighbors = adj.get(pivot) ?? new Set();

  // Iterate over P \ N(pivot).
  for (const v of [...P]) {
    if (pivotNeighbors.has(v)) continue;
    const Nv = adj.get(v) ?? new Set();
    _bronKerbosch(
      new Set([...R, v]),
      new Set([...P].filter(u => Nv.has(u))),
      new Set([...X].filter(u => Nv.has(u))),
      adj,
      result,
    );
    P.delete(v);
    X.add(v);
  }
}
