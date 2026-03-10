/**
 * SearchService
 *
 * Cross-library media search:
 *   • Filename substring match (case-insensitive)
 *   • Tag name match — finds tags whose names include the query, then returns
 *     all media associated with those tags
 *
 * Also owns NSFW tag-list persistence so the Settings screen and the Search
 * screen share a single source of truth.
 */

import { DatabaseService } from './DatabaseService';

// ─── NSFW defaults ────────────────────────────────────────────────────────────

const DEFAULT_NSFW_TAGS = ['nsfw', 'adult', 'explicit', 'nude', 'nudity'];
const NSFW_PREF_KEY = 'nsfw_tag_list';

// ─── NSFW tag list persistence ────────────────────────────────────────────────

async function getNsfwTagNames() {
  try {
    const db = DatabaseService.getDb();
    if (!db) return [...DEFAULT_NSFW_TAGS];
    const { rows } = await db.execute(
      'SELECT value FROM user_preferences WHERE key = ?',
      [NSFW_PREF_KEY],
    );
    if (rows[0]?.value) {
      const parsed = JSON.parse(rows[0].value);
      if (Array.isArray(parsed)) return parsed;
    }
  } catch {}
  return [...DEFAULT_NSFW_TAGS];
}

async function saveNsfwTagNames(tags) {
  const db = DatabaseService.getDb();
  await db.execute(
    `INSERT INTO user_preferences (key, value, updated_at) VALUES (?, ?, ?)
     ON CONFLICT(key) DO UPDATE SET
       value      = excluded.value,
       updated_at = excluded.updated_at`,
    [NSFW_PREF_KEY, JSON.stringify(tags), Date.now()],
  );
}

// ─── Row normaliser ───────────────────────────────────────────────────────────

/**
 * Maps a raw media_index row to the MediaItem-like shape expected by
 * MediaThumbnail and MediaViewer.
 */
function normalizeRow(row) {
  return {
    ...row,
    id: row.uri,
    type: row.media_type, // MediaItem uses 'type', DB uses 'media_type'
    fileSize: row.file_size,
    albumName: row.album_name ?? '',
    // CameraRoll / MediaItem timestamps are Unix seconds; DB stores ms
    timestamp: row.device_created_at ? row.device_created_at / 1000 : null,
  };
}

// ─── Search ───────────────────────────────────────────────────────────────────

/**
 * Full-library media search across filenames and tag names.
 *
 * Supports multi-token queries separated by commas and/or spaces.
 * e.g. "dog, scooby" or "dog scooby" will search for files that match
 * EITHER of these conditions:
 *   • Filename contains any of the tokens  (OR match across tokens)
 *   • File is tagged with a tag matching EVERY token  (AND intersection)
 *
 * Single-token behaviour is unchanged (filename LIKE OR tag match).
 *
 * @param {string}  query
 * @param {object}  opts
 * @param {'all'|'images'|'videos'} opts.mediaType
 * @param {boolean} opts.excludeNsfw
 * @param {string[]} opts.nsfwTagNames  — list of tag names treated as NSFW
 * @param {boolean} opts.showHidden  — when true, include hidden files and files in hidden albums
 * @returns {Promise<object[]>}  — normalised MediaItem-like objects
 */
async function searchMedia(
  query,
  {
    mediaType = 'all',
    excludeNsfw = false,
    nsfwTagNames = [],
    showHidden = false,
  } = {},
) {
  const db = DatabaseService.getDb();
  if (!db) return [];

  const trimmed = (query ?? '').trim();
  if (!trimmed) return [];

  // ── Parse multi-token query (comma and/or whitespace delimiters) ─────────
  const tokens = trimmed
    .split(/[\s,]+/)
    .map(t => t.trim())
    .filter(Boolean);

  // ── 1. Resolve NSFW tag IDs ──────────────────────────────────────────────
  let nsfwTagIds = [];
  if (excludeNsfw && nsfwTagNames.length > 0) {
    const ph = nsfwTagNames.map(() => '?').join(',');
    const { rows } = await db.execute(
      `SELECT id FROM tags WHERE LOWER(name) IN (${ph})`,
      nsfwTagNames.map(t => t.toLowerCase()),
    );
    nsfwTagIds = rows.map(r => r.id);
  }

  // ── 2. For each token find matching tag IDs ──────────────────────────────
  const tokenTagIds = []; // tokenTagIds[i] = tag id array for tokens[i]
  for (const token of tokens) {
    const { rows: tagRows } = await db.execute(
      'SELECT id FROM tags WHERE name LIKE ? COLLATE NOCASE',
      [`%${token}%`],
    );
    tokenTagIds.push(tagRows.map(r => r.id));
  }

  // ── 3. Build per-token AND conditions ───────────────────────────────────
  //   Each token must be satisfied individually:
  //     (filename LIKE %token% OR file is tagged with a matching tag)
  //   All tokens are AND-ed together, so "dog scooby" only returns files
  //   that satisfy both "dog" and "scooby".
  const perTokenParts = [];
  const searchParams = [];

  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i];
    const tagIds = tokenTagIds[i];

    if (tagIds.length > 0) {
      const ph = tagIds.map(() => '?').join(',');
      // Token satisfied if filename contains it OR file is tagged with it
      perTokenParts.push(
        `(m.filename LIKE ? COLLATE NOCASE OR m.uri IN (SELECT media_uri FROM media_tags WHERE tag_id IN (${ph})))`,
      );
      searchParams.push(`%${token}%`, ...tagIds);
    } else {
      // No matching tags for this token — filename match only
      perTokenParts.push('(m.filename LIKE ? COLLATE NOCASE)');
      searchParams.push(`%${token}%`);
    }
  }

  // All token conditions must hold (AND)
  const searchCondition = perTokenParts.join(' AND ');

  // ── 5. Build helper clauses ──────────────────────────────────────────────
  const mediaTypeClause =
    mediaType === 'images'
      ? "AND m.media_type = 'image'"
      : mediaType === 'videos'
      ? "AND m.media_type = 'video'"
      : '';

  const nsfwExcludeClause =
    nsfwTagIds.length > 0
      ? `AND m.uri NOT IN (SELECT media_uri FROM media_tags WHERE tag_id IN (${nsfwTagIds
          .map(() => '?')
          .join(',')}))`
      : '';

  const hiddenClause = showHidden
    ? ''
    : 'AND m.hidden = 0 AND (a.hidden IS NULL OR a.hidden = 0)';

  // ── 6. Execute ─────────────────────────────────────────────────────────
  const sql = `
    SELECT m.*, a.name AS album_name
    FROM   media_index m
    LEFT   JOIN albums a ON a.id = m.album_id
    WHERE  ${searchCondition}
    ${mediaTypeClause}
    ${hiddenClause}
    ${nsfwExcludeClause}
    ORDER  BY m.device_created_at DESC
    LIMIT  500
  `;

  // Parameter order:
  //   1. searchParams  → per-token (filename LIKE + tag IN) conditions
  //   2. nsfwTagIds    → NSFW exclusion
  const params = [...searchParams, ...nsfwTagIds];

  const { rows } = await db.execute(sql, params);
  return rows.map(normalizeRow);
}

// ─── NSFW URI set ─────────────────────────────────────────────────────────────

/**
 * Returns a Set of all media URIs that are tagged with at least one NSFW tag.
 * Used by screens to filter their loaded items client-side.
 *
 * @param {string[]} nsfwTagNames
 * @returns {Promise<Set<string>>}
 */
async function getNsfwTaggedUris(nsfwTagNames = []) {
  if (!nsfwTagNames.length) return new Set();
  const db = DatabaseService.getDb();
  if (!db) return new Set();

  const ph = nsfwTagNames.map(() => '?').join(',');
  const { rows: tagRows } = await db.execute(
    `SELECT id FROM tags WHERE LOWER(name) IN (${ph})`,
    nsfwTagNames.map(t => t.toLowerCase()),
  );
  const tagIds = tagRows.map(r => r.id);
  if (!tagIds.length) return new Set();

  const idPh = tagIds.map(() => '?').join(',');
  const { rows } = await db.execute(
    `SELECT DISTINCT media_uri FROM media_tags WHERE tag_id IN (${idPh})`,
    tagIds,
  );
  return new Set(rows.map(r => r.media_uri));
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const SearchService = {
  searchMedia,
  getNsfwTagNames,
  saveNsfwTagNames,
  getNsfwTaggedUris,
  DEFAULT_NSFW_TAGS,
};
