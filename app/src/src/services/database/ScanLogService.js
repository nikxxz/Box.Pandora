/**
 * ScanLogService
 *
 * Records the history of indexing / scan runs in the `scan_log` table.
 *
 * Used by ScanService (to be built) to:
 *  • Know the timestamp of the last successful scan (so an incremental scan
 *    only needs to look at files modified after that point).
 *  • Surface scan status in the UI (e.g. "Last updated 2 hours ago").
 *  • Debug / audit failed scans.
 */

import { DatabaseService } from './DatabaseService';

// ─── Write ────────────────────────────────────────────────────────────────────

/**
 * Open a new scan run and return its id.
 *
 * @param {'full'|'incremental'} scanType
 * @param {string|null} albumScope  — album name, or null for a global scan
 * @returns {number} scanId
 */
async function startScan(scanType = 'incremental', albumScope = null) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    `INSERT INTO scan_log (scan_type, album_scope, started_at, status)
     VALUES (?, ?, ?, 'running')
     RETURNING id`,
    [scanType, albumScope, Date.now()],
  );
  return rows[0]?.id ?? null;
}

/**
 * Mark a scan as successfully completed.
 *
 * @param {number} scanId
 * @param {{ added?: number, removed?: number, updated?: number }} stats
 */
async function completeScan(scanId, stats = {}) {
  const db = DatabaseService.getDb();
  await db.execute(
    `UPDATE scan_log SET
       finished_at   = ?,
       files_added   = ?,
       files_removed = ?,
       files_updated = ?,
       status        = 'done'
     WHERE id = ?`,
    [Date.now(), stats.added ?? 0, stats.removed ?? 0, stats.updated ?? 0, scanId],
  );
}

/**
 * Mark a scan as failed (stores an error message for debugging).
 *
 * @param {number} scanId
 * @param {string} errorMsg
 */
async function failScan(scanId, errorMsg = '') {
  const db = DatabaseService.getDb();
  await db.execute(
    `UPDATE scan_log SET finished_at = ?, status = 'failed', error = ? WHERE id = ?`,
    [Date.now(), errorMsg, scanId],
  );
}

// ─── Read ─────────────────────────────────────────────────────────────────────

/**
 * Return the most recent completed scan row (optionally filtered by type).
 * The `finished_at` timestamp is the key value for incremental scan logic.
 *
 * @param {'full'|'incremental'|null} scanType — null means any type
 * @returns {object|null}
 */
async function getLastCompletedScan(scanType = null) {
  const db = DatabaseService.getDb();

  const where  = scanType
    ? "WHERE scan_type = ? AND status = 'done'"
    : "WHERE status = 'done'";
  const params = scanType ? [scanType] : [];

  const { rows } = await db.execute(
    `SELECT * FROM scan_log ${where} ORDER BY finished_at DESC LIMIT 1`,
    params,
  );
  return rows[0] ?? null;
}

/**
 * Return the Unix-ms timestamp of the last successful full scan,
 * or null if no full scan has ever completed.
 * Useful for deciding whether a full re-index is needed.
 */
async function getLastFullScanTime() {
  const row = await getLastCompletedScan('full');
  return row?.finished_at ?? null;
}

/**
 * Recent scan history for a settings/debug screen.
 *
 * @param {number} limit
 */
async function getRecentScans(limit = 20) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT * FROM scan_log ORDER BY started_at DESC LIMIT ?',
    [limit],
  );
  return rows;
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const ScanLogService = {
  startScan,
  completeScan,
  failScan,
  getLastCompletedScan,
  getLastFullScanTime,
  getRecentScans,
};
