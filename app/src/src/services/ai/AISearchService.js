/**
 * AISearchService — Stage 3 of the AI search pipeline.
 *
 * Responsibility: execute a media query using ONLY pre-resolved tag IDs and
 * explicit metadata filters. No fuzzy tag discovery, no LIKE-based guessing.
 *
 * Input:
 *   tagIds    — number[]       resolved tag IDs from TagResolverService
 *   metadata  — object         { media_type, date_from, date_to, folder, sort }
 *   matchMode — "any"|"weighted"
 *               "any"      → media must have ≥1 resolved tag
 *               "weighted" → same inclusion, but ORDER BY match-count DESC
 *
 * This service does NOT decide which tags are relevant. That is
 * TagResolverService's job. This service is deliberately dumb and strict.
 */

import { DatabaseService } from '../database/DatabaseService';

function normalizeRow(row) {
  return {
    ...row,
    id: row.uri,
    type: row.media_type,
    fileSize: row.file_size,
    albumName: row.album_name ?? '',
    timestamp: row.device_created_at ? row.device_created_at / 1000 : null,
  };
}

async function search({
  tagIds = [],
  metadata = {},
  matchMode = 'weighted',
} = {}) {
  const db = DatabaseService.getDb();
  if (!db) return [];

  const {
    media_type = 'all',
    date_from = null,
    date_to = null,
    folder = null,
    sort = 'relevance',
  } = metadata;

  const conditions = [
    'm.hidden = 0',
    '(a.hidden IS NULL OR a.hidden = 0)',
  ];
  const params = [];

  // ── Tag filter (resolved IDs only, no SQL LIKE guessing) ──────────────────
  if (tagIds.length > 0) {
    const ph = tagIds.map(() => '?').join(',');
    conditions.push(
      `m.uri IN (SELECT DISTINCT media_uri FROM media_tags WHERE tag_id IN (${ph}))`,
    );
    params.push(...tagIds);
  }

  // ── Metadata filters ──────────────────────────────────────────────────────
  if (media_type === 'image') {
    conditions.push("m.media_type = 'image'");
  } else if (media_type === 'video') {
    conditions.push("m.media_type = 'video'");
  }

  if (date_from) {
    const ms = new Date(date_from).getTime();
    if (!isNaN(ms)) {
      conditions.push('m.device_created_at >= ?');
      params.push(ms);
    }
  }

  if (date_to) {
    const ms = new Date(date_to).getTime();
    if (!isNaN(ms)) {
      conditions.push('m.device_created_at <= ?');
      params.push(ms + 86_400_000); // inclusive end-of-day
    }
  }

  if (folder) {
    conditions.push('a.name LIKE ? COLLATE NOCASE');
    params.push(`%${folder}%`);
  }

  // ── ORDER BY ──────────────────────────────────────────────────────────────
  // weighted / relevance: rank by how many of the resolved tags the item has.
  // Tie-break: newest first.
  let orderBy = 'm.device_created_at DESC';

  if (sort === 'date_asc') {
    orderBy = 'm.device_created_at ASC';
  } else if (
    (sort === 'relevance' || matchMode === 'weighted') &&
    tagIds.length > 0
  ) {
    const ph = tagIds.map(() => '?').join(',');
    orderBy = `(SELECT COUNT(DISTINCT tag_id) FROM media_tags WHERE media_uri = m.uri AND tag_id IN (${ph})) DESC, m.device_created_at DESC`;
    params.push(...tagIds);
  }

  const sql = `
    SELECT m.*, a.name AS album_name
    FROM   media_index m
    LEFT   JOIN albums a ON a.id = m.album_id
    WHERE  ${conditions.join(' AND ')}
    ORDER  BY ${orderBy}
    LIMIT  300
  `;

  const { rows } = await db.execute(sql, params);
  return rows.map(normalizeRow);
}

export const AISearchService = { search };
