/**
 * TagService
 *
 * Manages the `tags` table and the `media_tags` junction table.
 *
 * usage_count is kept denormalised on the tags row so the tag picker can sort
 * by popularity without a GROUP BY join on every render.
 */

import { DatabaseService } from './DatabaseService';
import { getCategoryForTag } from '../../constants/tagCategories';

// ─── Tag CRUD ─────────────────────────────────────────────────────────────────

/**
 * Create a new tag (no-op if name already exists).
 * @returns {number} id of the new or existing tag
 */
async function createTag(
  name,
  color = '#888888',
  icon = null,
  description = null,
  category = null,
) {
  const db = DatabaseService.getDb();
  const now = Date.now();

  const trimmed = (name ?? '').trim();
  if (!trimmed) return null;

  // Auto-classify category when not explicitly provided
  const resolvedCategory = category ?? getCategoryForTag(trimmed);

  // Prevent duplicates that differ only by case (SQLite UNIQUE is case-sensitive
  // unless the column is declared with NOCASE collation).
  const existing = await db.execute(
    'SELECT id FROM tags WHERE name = ? COLLATE NOCASE',
    [trimmed],
  );
  if (existing.rows[0]?.id) return existing.rows[0].id;

  // Try to insert; if name already exists the INSERT is ignored
  const result = await db.execute(
    'INSERT OR IGNORE INTO tags (name, color, icon, category, created_at, description, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
    [trimmed, color, icon, resolvedCategory, now, description ?? null, now],
  );

  if (result.insertId) return result.insertId;

  // Already existed — return its id
  const { rows } = await db.execute(
    'SELECT id FROM tags WHERE name = ? COLLATE NOCASE',
    [trimmed],
  );
  return rows[0]?.id ?? null;
}

/**
 * Fetch all tags, sorted by usage (most-used first) then alphabetically.
 */
async function getAllTags() {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT * FROM tags ORDER BY usage_count DESC, name COLLATE NOCASE ASC',
  );
  return rows;
}

async function getTagById(id) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute('SELECT * FROM tags WHERE id = ?', [id]);
  return rows[0] ?? null;
}

async function getTagByName(name) {
  const db = DatabaseService.getDb();
  const trimmed = (name ?? '').trim();
  const { rows } = await db.execute(
    'SELECT * FROM tags WHERE name = ? COLLATE NOCASE',
    [trimmed],
  );
  return rows[0] ?? null;
}

/**
 * Update tag name and/or colour/icon.
 */
async function updateTag(id, updates = {}) {
  const db = DatabaseService.getDb();
  const fields = [];
  const params = [];

  if (updates.name != null) {
    fields.push('name = ?');
    params.push(updates.name.trim());
  }
  if (updates.color != null) {
    fields.push('color = ?');
    params.push(updates.color);
  }
  if (updates.icon != null) {
    fields.push('icon = ?');
    params.push(updates.icon);
  }
  if (updates.category != null) {
    fields.push('category = ?');
    params.push(updates.category);
  }
  // description may be explicitly set to empty string to clear it
  if (updates.description !== undefined) {
    fields.push('description = ?');
    params.push(updates.description ?? null);
  }

  if (!fields.length) return;

  // Always record when metadata was last changed
  fields.push('updated_at = ?');
  params.push(Date.now());

  params.push(id);
  await db.execute(`UPDATE tags SET ${fields.join(', ')} WHERE id = ?`, params);
}

// ─── Category helpers ──────────────────────────────────────────────────────────────────

/**
 * Return all tags for a given category key, ordered by popularity.
 * @param {string} category
 */
async function getTagsByCategory(category) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT * FROM tags WHERE category = ? ORDER BY usage_count DESC, name COLLATE NOCASE ASC',
    [category],
  );
  return rows;
}

/**
 * Backfill the category column for tags whose category is still the default
 * 'misc' by re-classifying their names with getCategoryForTag().
 *
 * Run once in the background after the v9 migration so existing tags get
 * their correct category without blocking startup.
 *
 * Safe to call multiple times — only updates rows still marked as 'misc'.
 */
async function backfillTagCategories() {
  try {
    const db = DatabaseService.getDb();
    const { rows } = await db.execute(
      "SELECT id, name FROM tags WHERE category = 'misc' OR category IS NULL",
    );
    if (!rows.length) return;

    await db.executeBatch(
      rows.map(row => [
        'UPDATE tags SET category = ? WHERE id = ?',
        [getCategoryForTag(row.name), row.id],
      ]),
    );
  } catch (err) {
    console.warn(
      '[TagService] backfillTagCategories error:',
      err?.message ?? err,
    );
  }
}

/**
 * Delete a tag — ON DELETE CASCADE removes all media_tags rows automatically.
 * usage_count on remaining tags is not affected (only the deleted tag is gone).
 */
async function deleteTag(id) {
  const db = DatabaseService.getDb();
  await db.execute('DELETE FROM tags WHERE id = ?', [id]);
}

// ─── Tagging operations ────────────────────────────────────────────────────────

/**
 * Add a tag to a single media item.
 * Silently no-ops if the tag is already applied.
 */
async function addTagToMedia(mediaUri, tagId) {
  const db = DatabaseService.getDb();
  const result = await db.execute(
    'INSERT OR IGNORE INTO media_tags (media_uri, tag_id, tagged_at) VALUES (?, ?, ?)',
    [mediaUri, tagId, Date.now()],
  );
  // Increment usage_count only if a new row was inserted
  if (result.rowsAffected > 0) {
    await db.execute(
      'UPDATE tags SET usage_count = usage_count + 1 WHERE id = ?',
      [tagId],
    );
  }
}

/**
 * Remove a tag from a single media item.
 */
async function removeTagFromMedia(mediaUri, tagId) {
  const db = DatabaseService.getDb();
  const result = await db.execute(
    'DELETE FROM media_tags WHERE media_uri = ? AND tag_id = ?',
    [mediaUri, tagId],
  );
  if (result.rowsAffected > 0) {
    await db.execute(
      'UPDATE tags SET usage_count = MAX(0, usage_count - 1) WHERE id = ?',
      [tagId],
    );
  }
}

/**
 * Apply a tag to many media items at once (e.g. select-all → tag).
 * Uses executeBatch for a single native round-trip.
 */
async function batchAddTag(mediaUris = [], tagId) {
  if (!mediaUris.length) return;
  const db = DatabaseService.getDb();
  const now = Date.now();

  const result = await db.executeBatch(
    mediaUris.map(uri => [
      'INSERT OR IGNORE INTO media_tags (media_uri, tag_id, tagged_at) VALUES (?, ?, ?)',
      [uri, tagId, now],
    ]),
  );

  // result.rowsAffected is the total across all statements in the batch
  const added = result.rowsAffected ?? 0;
  if (added > 0) {
    await db.execute(
      'UPDATE tags SET usage_count = usage_count + ? WHERE id = ?',
      [added, tagId],
    );
  }
}

/**
 * Remove a tag from many media items at once.
 */
async function batchRemoveTag(mediaUris = [], tagId) {
  if (!mediaUris.length) return;
  const db = DatabaseService.getDb();

  const result = await db.executeBatch(
    mediaUris.map(uri => [
      'DELETE FROM media_tags WHERE media_uri = ? AND tag_id = ?',
      [uri, tagId],
    ]),
  );

  const removed = result.rowsAffected ?? 0;
  if (removed > 0) {
    await db.execute(
      'UPDATE tags SET usage_count = MAX(0, usage_count - ?) WHERE id = ?',
      [removed, tagId],
    );
  }
}

/**
 * Remove ALL tags from a single media item.
 */
async function clearTagsForMedia(mediaUri) {
  if (!mediaUri) return;
  const db = DatabaseService.getDb();
  // Decrement usage_count for every tag that was applied to this item.
  const { rows: applied } = await db.execute(
    'SELECT tag_id FROM media_tags WHERE media_uri = ?',
    [mediaUri],
  );
  await db.execute('DELETE FROM media_tags WHERE media_uri = ?', [mediaUri]);
  if (applied.length) {
    await db.executeBatch(
      applied.map(r => [
        'UPDATE tags SET usage_count = MAX(0, usage_count - 1) WHERE id = ?',
        [r.tag_id],
      ]),
    );
  }
}

/**
 * Remove ALL tags from a batch of media items.
 */
async function batchClearTagsForMedia(mediaUris = []) {
  if (!mediaUris.length) return;
  const db = DatabaseService.getDb();
  const placeholders = mediaUris.map(() => '?').join(', ');
  // Collect tag IDs and counts before deleting.
  const { rows: applied } = await db.execute(
    `SELECT tag_id, COUNT(*) AS cnt FROM media_tags WHERE media_uri IN (${placeholders}) GROUP BY tag_id`,
    mediaUris,
  );
  await db.executeBatch(
    mediaUris.map(uri => [
      'DELETE FROM media_tags WHERE media_uri = ?',
      [uri],
    ]),
  );
  if (applied.length) {
    await db.executeBatch(
      applied.map(r => [
        'UPDATE tags SET usage_count = MAX(0, usage_count - ?) WHERE id = ?',
        [r.cnt, r.tag_id],
      ]),
    );
  }
}

// ─── Read ─────────────────────────────────────────────────────────────────────

/**
 * Return all tags applied to a specific media item.
 */
async function getTagsForMedia(mediaUri) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    `SELECT t.*
     FROM   tags t
     INNER JOIN media_tags mt ON mt.tag_id = t.id
     WHERE  mt.media_uri = ?
     ORDER  BY t.name COLLATE NOCASE ASC`,
    [mediaUri],
  );
  return rows;
}

/**
 * Return paginated media URIs that have a specific tag.
 * Useful for a "Tag view" screen.
 */
async function getMediaForTag(
  tagId,
  { limit = 60, offset = 0, showHidden = false } = {},
) {
  const db = DatabaseService.getDb();
  const hiddenClause = showHidden
    ? ''
    : 'AND m.hidden = 0 AND (a.hidden IS NULL OR a.hidden = 0)';
  const { rows } = await db.execute(
    `SELECT m.*
     FROM   media_index m
     INNER JOIN media_tags mt ON mt.media_uri = m.uri
     LEFT  JOIN albums a ON a.id = m.album_id
     WHERE  mt.tag_id = ?
       ${hiddenClause}
     ORDER  BY m.device_created_at DESC
     LIMIT  ? OFFSET ?`,
    [tagId, limit, offset],
  );
  return rows;
}

/**
 * Returns true if this tag has at least one media item that is NOT tagged
 * with any of the NSFW tag names.  Used to decide whether a tag card should
 * be hidden when the NSFW filter is enabled.
 *
 * Uses a correlated sub-select so the check short-circuits at the first
 * non-NSFW item — efficient even for large tags.
 *
 * @param {number}   tagId
 * @param {string[]} nsfwTagNames  — lower-cased NSFW tag name strings
 * @param {object}   [opts]
 * @param {boolean}  [opts.showHidden=false] — if true, includes hidden files and hidden albums
 * @returns {Promise<boolean>}
 */
async function hasVisibleMedia(tagId, nsfwTagNames = [], opts = {}) {
  if (!nsfwTagNames.length) return true;
  const db = DatabaseService.getDb();
  const showHidden = opts.showHidden === true;
  const hiddenClause = showHidden
    ? ''
    : 'AND m.hidden = 0 AND (a.hidden IS NULL OR a.hidden = 0)';
  const ph = nsfwTagNames.map(() => '?').join(',');
  const { rows } = await db.execute(
    `SELECT 1
     FROM   media_index m
     INNER JOIN media_tags mt ON mt.media_uri = m.uri
     LEFT  JOIN albums a ON a.id = m.album_id
     WHERE  mt.tag_id = ?
       ${hiddenClause}
       AND  m.uri NOT IN (
         SELECT media_uri FROM media_tags
         WHERE  tag_id IN (
           SELECT id FROM tags WHERE LOWER(name) IN (${ph})
         )
       )
     LIMIT 1`,
    [tagId, ...nsfwTagNames],
  );
  return rows.length > 0;
}

// ─── Recently active ──────────────────────────────────────────────────────────

/**
 * Returns the N most-recently-active tags.
 * "Active" = whichever is later: last time media was tagged OR last metadata edit.
 * The derived `last_active` column is returned alongside the normal tag fields.
 */
async function getRecentlyActiveTags(limit = 4) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    `SELECT t.*,
            CASE
              WHEN MAX(COALESCE(mt.tagged_at, 0)) > COALESCE(t.updated_at, 0)
              THEN MAX(COALESCE(mt.tagged_at, 0))
              ELSE COALESCE(t.updated_at, t.created_at)
            END AS last_active
     FROM   tags t
     LEFT   JOIN media_tags mt ON mt.tag_id = t.id
     GROUP  BY t.id
     ORDER  BY last_active DESC
     LIMIT  ?`,
    [limit],
  );
  return rows;
}

// ─── Co-occurrence (related tags) ─────────────────────────────────────────────

/**
 * Returns tags that frequently appear on the same media items as `tagId`.
 * Ordered by co-occurrence count descending.
 */
async function getRelatedTags(tagId, limit = 5) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    `SELECT t.*, COUNT(*) AS co_count
     FROM   tags t
     INNER  JOIN media_tags mt2 ON mt2.tag_id = t.id
     WHERE  mt2.media_uri IN (
              SELECT media_uri FROM media_tags WHERE tag_id = ?
            )
       AND  t.id != ?
     GROUP  BY t.id
     ORDER  BY co_count DESC
     LIMIT  ?`,
    [tagId, tagId, limit],
  );
  return rows;
}

// ─── Library total ────────────────────────────────────────────────────────────

/**
 * Returns the total number of non-hidden media items — used to compute
 * "X% of library" in the Tag Detail header.
 */
async function getTotalMediaCount() {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    `SELECT COUNT(*) AS total
     FROM   media_index m
     LEFT  JOIN albums a ON a.id = m.album_id
     WHERE  m.hidden = 0
       AND  (a.hidden IS NULL OR a.hidden = 0)`,
  );
  return rows[0]?.total ?? 0;
}

// ─── Extended tag stats ──────────────────────────────────────────────────────

/**
 * Like getTagById but also returns `first_tagged_at` — the Unix ms timestamp
 * of the earliest media_tags row for this tag.
 */
async function getTagStats(tagId) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    `SELECT t.*,
            (SELECT MIN(mt.tagged_at) FROM media_tags mt WHERE mt.tag_id = t.id) AS first_tagged_at
     FROM   tags t
     WHERE  t.id = ?`,
    [tagId],
  );
  return rows[0] ?? null;
}

// ─── Merge ────────────────────────────────────────────────────────────────────

/**
 * Merge `sourceId` into `targetId`.
 *
 * Steps:
 *   1. Copy every media_tags row from source → target (INSERT OR IGNORE skips
 *      items that already carry the target tag).
 *   2. Recount usage_count on the target from the actual junction table rows
 *      so the number is always accurate.
 *   3. Delete the source tag — ON DELETE CASCADE removes its media_tags rows.
 */
async function mergeTag(sourceId, targetId) {
  const db = DatabaseService.getDb();
  const now = Date.now();

  // Re-assign all media associations
  await db.execute(
    `INSERT OR IGNORE INTO media_tags (media_uri, tag_id, tagged_at)
     SELECT media_uri, ?, tagged_at FROM media_tags WHERE tag_id = ?`,
    [targetId, sourceId],
  );

  // Recompute accurate usage_count for target
  await db.execute(
    `UPDATE tags
     SET usage_count = (SELECT COUNT(*) FROM media_tags WHERE tag_id = ?),
         updated_at  = ?
     WHERE id = ?`,
    [targetId, now, targetId],
  );

  // Delete source (cascades media_tags for source)
  await db.execute('DELETE FROM tags WHERE id = ?', [sourceId]);
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const TagService = {
  createTag,
  getAllTags,
  getTagById,
  getTagByName,
  updateTag,
  deleteTag,
  mergeTag,
  addTagToMedia,
  removeTagFromMedia,
  batchAddTag,
  batchRemoveTag,
  clearTagsForMedia,
  batchClearTagsForMedia,
  getTagsForMedia,
  getMediaForTag,
  hasVisibleMedia,
  getRecentlyActiveTags,
  getRelatedTags,
  getTotalMediaCount,
  getTagStats,
  getTagsByCategory,
  backfillTagCategories,
};
