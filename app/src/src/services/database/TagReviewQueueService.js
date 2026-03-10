/**
 * TagReviewQueueService — CRUD for tag_review_queue.
 *
 * Each row is one AI suggestion for one tag.  The user can:
 *   Accept  → apply the suggestion as-is
 *   Edit    → modify the value then apply
 *   Reject  → dismiss; suggestion is never applied
 *
 * analysis_type values: 'category' | 'description' | 'alias' | 'canonical_name'
 * status values:        'pending'  | 'accepted'    | 'edited' | 'rejected'
 */

import { DatabaseService } from './DatabaseService';

// ─── Add ─────────────────────────────────────────────────────────────────────

/**
 * Adds a batch of suggestions for a single tag.
 * Each suggestion: { analysis_type, suggested_value, reasoning }
 * Returns the inserted row IDs.
 */
async function addSuggestions(tagId, suggestions) {
  const db = DatabaseService.getDb();
  if (!db || !suggestions.length) return [];
  const now = Date.now();
  const ids = [];
  for (const s of suggestions) {
    try {
      const { rows } = await db.execute(
        `INSERT INTO tag_review_queue
           (tag_id, analysis_type, suggested_value, reasoning, status, created_at)
         VALUES (?, ?, ?, ?, 'pending', ?)
         RETURNING id`,
        [tagId, s.analysis_type, s.suggested_value, s.reasoning ?? null, now],
      );
      if (rows[0]?.id != null) ids.push(rows[0].id);
    } catch (err) {
      // If the table doesn't exist yet (migration pending), re-throw so the
      // caller's error handler can surface it to the user.
      throw err;
    }
  }
  return ids;
}

// ─── Query ────────────────────────────────────────────────────────────────────

async function getPendingForTag(tagId) {
  const db = DatabaseService.getDb();
  if (!db) return [];
  const { rows } = await db.execute(
    `SELECT * FROM tag_review_queue
     WHERE tag_id = ? AND status = 'pending'
     ORDER BY created_at ASC`,
    [tagId],
  );
  return rows;
}

async function getAllPending() {
  const db = DatabaseService.getDb();
  if (!db) return [];
  const { rows } = await db.execute(
    `SELECT q.*, t.name AS tag_name, t.category AS tag_category
     FROM tag_review_queue q
     JOIN tags t ON t.id = q.tag_id
     WHERE q.status = 'pending'
     ORDER BY q.created_at ASC`,
  );
  return rows;
}

async function getStats() {
  const db = DatabaseService.getDb();
  if (!db) return { pending: 0, accepted: 0, rejected: 0, edited: 0 };
  const { rows } = await db.execute(
    `SELECT status, COUNT(*) AS cnt FROM tag_review_queue GROUP BY status`,
  );
  const out = { pending: 0, accepted: 0, rejected: 0, edited: 0 };
  for (const r of rows) {
    if (out[r.status] !== undefined) out[r.status] = r.cnt;
  }
  return out;
}

// ─── Update ───────────────────────────────────────────────────────────────────

async function updateStatus(id, status, editedValue = null) {
  const db = DatabaseService.getDb();
  if (!db) return;
  await db.execute(
    `UPDATE tag_review_queue
     SET status = ?, edited_value = ?, reviewed_at = ?
     WHERE id = ?`,
    [status, editedValue, Date.now(), id],
  );
}

/**
 * Reject all pending suggestions for a tag (e.g. user cancels analysis).
 */
async function rejectAllPendingForTag(tagId) {
  const db = DatabaseService.getDb();
  if (!db) return;
  await db.execute(
    `UPDATE tag_review_queue
     SET status = 'rejected', reviewed_at = ?
     WHERE tag_id = ? AND status = 'pending'`,
    [Date.now(), tagId],
  );
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const TagReviewQueueService = {
  addSuggestions,
  getPendingForTag,
  getAllPending,
  getStats,
  updateStatus,
  rejectAllPendingForTag,
};
