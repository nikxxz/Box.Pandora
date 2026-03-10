/**
 * TagChangeHistoryService — log of every accepted tag change.
 *
 * Called after the user accepts (or edits+accepts) a suggestion from the
 * review queue, or when a user manually edits a tag field.
 *
 * field_changed values: 'category' | 'description' | 'alias_added' |
 *                       'alias_removed' | 'canonical_name' | 'name'
 */

import { DatabaseService } from './DatabaseService';

// ─── Log ─────────────────────────────────────────────────────────────────────

async function logChange({
  tagId,
  tagName,
  fieldChanged,
  oldValue,
  newValue,
  changeSource = 'ai_review',
  reviewQueueId = null,
}) {
  const db = DatabaseService.getDb();
  if (!db) return;
  await db.execute(
    `INSERT INTO tag_change_history
       (tag_id, tag_name, field_changed, old_value, new_value, change_source, review_queue_id, changed_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      tagId,
      tagName,
      fieldChanged,
      oldValue ?? null,
      newValue,
      changeSource,
      reviewQueueId ?? null,
      Date.now(),
    ],
  );
}

// ─── Query ────────────────────────────────────────────────────────────────────

async function getHistoryForTag(tagId, limit = 50) {
  const db = DatabaseService.getDb();
  if (!db) return [];
  const { rows } = await db.execute(
    `SELECT * FROM tag_change_history
     WHERE tag_id = ?
     ORDER BY changed_at DESC
     LIMIT ?`,
    [tagId, limit],
  );
  return rows;
}

async function getRecentHistory(limit = 100) {
  const db = DatabaseService.getDb();
  if (!db) return [];
  const { rows } = await db.execute(
    `SELECT h.*, t.name AS current_tag_name
     FROM tag_change_history h
     JOIN tags t ON t.id = h.tag_id
     ORDER BY h.changed_at DESC
     LIMIT ?`,
    [limit],
  );
  return rows;
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const TagChangeHistoryService = {
  logChange,
  getHistoryForTag,
  getRecentHistory,
};
