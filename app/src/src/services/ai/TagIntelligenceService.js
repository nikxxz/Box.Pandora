/**
 * TagIntelligenceService — orchestrates the full Analyze → Suggest flow.
 *
 * Responsibilities:
 *   1. Load a tag + sample media filenames from SQLite
 *   2. Call OpenAIService.analyzeTag() to get raw suggestions
 *   3. Convert AI output into tag_review_queue rows
 *   4. Mark the tag as last_reviewed_at = now
 *
 * This service never writes to tags directly. All mutations go through the
 * review queue. The UI is responsible for Accept / Edit / Reject.
 *
 * Apply helpers (acceptSuggestion / rejectSuggestion) are also here so the
 * screen has a single import.
 */

import { DatabaseService } from '../database/DatabaseService';
import { TagReviewQueueService } from '../database/TagReviewQueueService';
import { TagChangeHistoryService } from '../database/TagChangeHistoryService';
import { TagAliasService } from '../database/TagAliasService';
import { OpenAIService } from './OpenAIService';

// ─── Analyze ──────────────────────────────────────────────────────────────────

/**
 * Runs AI analysis for one tag.  Returns the array of pending queue rows
 * just inserted (or empty if AI found nothing to suggest).
 *
 * @param {{ id, name, category, description, usage_count }} tag
 * @returns {Promise<object[]>} inserted review queue rows
 */
async function analyzeTag(tag) {
  const db = DatabaseService.getDb();
  if (!db) throw new Error('Database not ready.');

  // Guard: ensure v11 tables exist before proceeding. If they don't, the app
  // hasn't been fully restarted since the schema was updated.
  try {
    await db.execute('SELECT 1 FROM tag_review_queue LIMIT 1');
  } catch {
    throw new Error(
      'Tag Intelligence tables are not ready yet.\n\nPlease fully restart the app (kill + reopen) to apply the database update, then try again.',
    );
  }

  // Load up to 5 sample filenames so the AI has context
  const { rows: sampleRows } = await db.execute(
    `SELECT m.filename
     FROM media_tags mt
     JOIN media_index m ON m.uri = mt.media_uri
     WHERE mt.tag_id = ?
     ORDER BY m.device_created_at DESC
     LIMIT 5`,
    [tag.id],
  );
  const sampleFilenames = sampleRows.map(r => r.filename);

  // Call AI
  const aiResult = await OpenAIService.analyzeTag(tag, sampleFilenames);

  // Convert AI result into suggestion rows
  const suggestions = [];

  if (aiResult.category?.suggested) {
    suggestions.push({
      analysis_type: 'category',
      suggested_value: aiResult.category.suggested,
      reasoning: aiResult.category.reasoning ?? null,
    });
  }

  if (aiResult.description?.suggested) {
    suggestions.push({
      analysis_type: 'description',
      suggested_value: aiResult.description.suggested,
      reasoning: aiResult.description.reasoning ?? null,
    });
  }

  if (Array.isArray(aiResult.aliases)) {
    for (const a of aiResult.aliases) {
      if (a.alias && typeof a.alias === 'string') {
        suggestions.push({
          analysis_type: 'alias',
          suggested_value: a.alias.trim().toLowerCase(),
          reasoning: a.reasoning ?? null,
        });
      }
    }
  }

  if (aiResult.canonical_name?.suggested) {
    suggestions.push({
      analysis_type: 'canonical_name',
      suggested_value: aiResult.canonical_name.suggested,
      reasoning: aiResult.canonical_name.reasoning ?? null,
    });
  }

  // Persist to queue
  await TagReviewQueueService.addSuggestions(tag.id, suggestions);

  // Mark tag as reviewed (gracefully skip if migration hasn't run yet)
  try {
    await db.execute(
      'UPDATE tags SET last_reviewed_at = ? WHERE id = ?',
      [Date.now(), tag.id],
    );
  } catch {
    // last_reviewed_at column not yet present — migration pending; ignore
  }

  // Re-fetch queue for this tag and return
  return TagReviewQueueService.getPendingForTag(tag.id);
}

// ─── Apply ────────────────────────────────────────────────────────────────────

/**
 * Accepts (or edit+accepts) a single suggestion from the review queue.
 * Applies the change to the tags table (or tag_aliases) and logs it.
 *
 * @param {object} queueRow  — row from tag_review_queue
 * @param {string} tagName   — current tag name (for the history log)
 * @param {string|null} editedValue  — user-edited value, or null to use suggested_value
 */
async function acceptSuggestion(queueRow, tagName, editedValue = null) {
  const db = DatabaseService.getDb();
  if (!db) throw new Error('Database not ready.');

  const valueToApply = editedValue ?? queueRow.suggested_value;
  const status = editedValue != null ? 'edited' : 'accepted';

  // Apply the change
  const { analysis_type, tag_id, id: queueId } = queueRow;

  if (analysis_type === 'category') {
    await db.execute(
      'UPDATE tags SET category = ?, updated_at = ? WHERE id = ?',
      [valueToApply, Date.now(), tag_id],
    );
    await TagChangeHistoryService.logChange({
      tagId: tag_id,
      tagName,
      fieldChanged: 'category',
      oldValue: queueRow.tag_category ?? null,
      newValue: valueToApply,
      reviewQueueId: queueId,
    });

  } else if (analysis_type === 'description') {
    await db.execute(
      'UPDATE tags SET description = ?, updated_at = ? WHERE id = ?',
      [valueToApply, Date.now(), tag_id],
    );
    await TagChangeHistoryService.logChange({
      tagId: tag_id,
      tagName,
      fieldChanged: 'description',
      oldValue: queueRow.current_description ?? null,
      newValue: valueToApply,
      reviewQueueId: queueId,
    });

  } else if (analysis_type === 'alias') {
    await TagAliasService.addAlias(tag_id, valueToApply, 'ai');
    await TagChangeHistoryService.logChange({
      tagId: tag_id,
      tagName,
      fieldChanged: 'alias_added',
      oldValue: null,
      newValue: valueToApply,
      reviewQueueId: queueId,
    });

  } else if (analysis_type === 'canonical_name') {
    await db.execute(
      'UPDATE tags SET name = ?, updated_at = ? WHERE id = ?',
      [valueToApply, Date.now(), tag_id],
    );
    await TagChangeHistoryService.logChange({
      tagId: tag_id,
      tagName,
      fieldChanged: 'name',
      oldValue: tagName,
      newValue: valueToApply,
      reviewQueueId: queueId,
    });
  }

  // Mark queue row as accepted/edited
  await TagReviewQueueService.updateStatus(queueId, status, editedValue);
}

/**
 * Rejects a single suggestion. No DB change is applied.
 */
async function rejectSuggestion(queueRow) {
  await TagReviewQueueService.updateStatus(queueRow.id, 'rejected');
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const TagIntelligenceService = {
  analyzeTag,
  acceptSuggestion,
  rejectSuggestion,
};
