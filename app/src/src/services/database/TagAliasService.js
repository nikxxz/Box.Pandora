/**
 * TagAliasService — CRUD for tag_aliases.
 *
 * Aliases are alternate search terms (e.g. "doggo", "puppy") that resolve
 * to a canonical tag during AI search candidate pre-filtering.
 */

import { DatabaseService } from './DatabaseService';

// ─── Add ─────────────────────────────────────────────────────────────────────

async function addAlias(tagId, alias, source = 'ai') {
  const db = DatabaseService.getDb();
  if (!db) return null;
  const norm = alias.trim().toLowerCase();
  if (!norm) return null;
  try {
    const { rows } = await db.execute(
      `INSERT INTO tag_aliases (tag_id, alias, source, created_at)
       VALUES (?, ?, ?, ?)
       ON CONFLICT (tag_id, alias) DO NOTHING
       RETURNING *`,
      [tagId, norm, source, Date.now()],
    );
    return rows[0] ?? null;
  } catch {
    return null;
  }
}

// ─── Get ──────────────────────────────────────────────────────────────────────

async function getAliasesForTag(tagId) {
  const db = DatabaseService.getDb();
  if (!db) return [];
  const { rows } = await db.execute(
    'SELECT * FROM tag_aliases WHERE tag_id = ? ORDER BY alias ASC',
    [tagId],
  );
  return rows;
}

/**
 * Returns a Map<alias → tagId> for all aliases in the DB.
 * Used by TagResolverService to expand candidates.
 */
async function getAllAliasMap() {
  const db = DatabaseService.getDb();
  if (!db) return new Map();
  const { rows } = await db.execute('SELECT alias, tag_id FROM tag_aliases');
  const map = new Map();
  for (const r of rows) {
    if (!map.has(r.alias)) map.set(r.alias, []);
    map.get(r.alias).push(r.tag_id);
  }
  return map;
}

// ─── Delete ───────────────────────────────────────────────────────────────────

async function removeAlias(id) {
  const db = DatabaseService.getDb();
  if (!db) return;
  await db.execute('DELETE FROM tag_aliases WHERE id = ?', [id]);
}

async function removeAllAliasesForTag(tagId) {
  const db = DatabaseService.getDb();
  if (!db) return;
  await db.execute('DELETE FROM tag_aliases WHERE tag_id = ?', [tagId]);
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const TagAliasService = {
  addAlias,
  getAliasesForTag,
  getAllAliasMap,
  removeAlias,
  removeAllAliasesForTag,
};
