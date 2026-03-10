/**
 * BackgroundSyncService
 *
 * Lightweight incremental sync that runs silently in the background while
 * the user is not actively doing anything.
 *
 * What it does each run:
 *   1. Waits for all in-flight JS interactions to finish (navigation
 *      animations, gesture responders, etc.).
 *   2. Discovers media items added since the last completed sync.
 *   3. Warms the thumbnail cache for those new items (single batch native call).
 *   4. Refreshes album cover URIs for any album that gained new items.
 *   5. Detects modified files (device_modified_at changed) and updates the DB.
 *
 * What keeps it non-intrusive:
 *   • Runs only after the JS thread is idle (requestIdleCallback).
 *   • Yields to the JS thread (~1 frame) between every work unit.
 *   • Hard wall-clock budget: cancels itself if a run exceeds MAX_RUN_MS.
 *   • Hard item budget: never processes more than MAX_ITEMS_PER_RUN items.
 *   • notifyBusy() / notifyIdle() let callers pause/resume the sync.
 *   • AppState subscription pauses when the app is backgrounded.
 *   • Subsequent ticks are re-armed only *after* a run finishes (no overlap).
 */

import { AppState } from 'react-native';
import { DatabaseService } from '../database/DatabaseService';
import { ScanLogService } from '../database/ScanLogService';
import { AlbumIndexService } from '../database/AlbumIndexService';
import { streamResolveThumbnailUris } from '../cache/ThumbnailCache';

// ─── Tuning constants ─────────────────────────────────────────────────────────

/** How often to attempt a run (measured idle→idle, not wall-clock). */
const RUN_INTERVAL_MS = 3 * 60 * 1000; // 3 minutes

/** Abort a run that has been executing for longer than this. */
const MAX_RUN_MS = 25_000; // 25 seconds

/** Max new/modified DB rows to process per run. */
const MAX_ITEMS_PER_RUN = 200;

/** Max thumbnails to pre-warm per run (each batch = 1 native bridge call). */
const MAX_THUMB_WARM = 80;

/**
 * Max "orphan" thumbnails to warm per run — items that exist in the DB but
 * have never had their thumb_uri resolved.  Processed AFTER new/modified
 * items to avoid slowing down the incremental sync.
 */
const MAX_ORPHAN_THUMB_WARM = 60;

/** ms to sleep between work steps (~1 animation frame). */
const YIELD_MS = 16;

// ─── Module-level state ───────────────────────────────────────────────────────

let _timer = null; // setTimeout handle for the next scheduled run
let _running = false; // true while _runOnce is executing
let _token = null; // cancellation token for the current run
let _userBusy = false; // pause requested by a caller
let _appActive = true; // false while app is in background
let _appStateSub = null; // AppState event subscription

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Sleep for YIELD_MS and check wall-clock budget.
 * Mutates _token.cancelled if budget is exceeded.
 */
async function _yield(token, startTime) {
  await new Promise(r => setTimeout(r, YIELD_MS));
  if (Date.now() - startTime > MAX_RUN_MS) {
    _cancel(token, 'wall-clock budget exceeded');
  }
}

function _shouldStop(token) {
  return token.cancelled || _userBusy || !_appActive;
}

function _cancel(token, reason = '') {
  if (!token.cancelled) {
    token.cancelled = true;
    if (__DEV__) {
      console.log(`[BGSync] cancelled — ${reason}`);
    }
  }
}

// ─── Core run ─────────────────────────────────────────────────────────────────

async function _runOnce() {
  // Guard: don't overlap runs; don't run when caller is busy or app is hidden
  if (_running || _userBusy || !_appActive) return;

  // DB must be open before we do anything
  try {
    if (!DatabaseService.getDb()) return;
  } catch {
    return;
  }

  _running = true;
  const token = { cancelled: false };
  _token = token;
  const startTime = Date.now();

  let scanId = null;
  const stats = { added: 0, removed: 0, updated: 0 };

  try {
    // ── Wait until the JS thread is idle before doing any work ────────────
    await new Promise(resolve =>
      requestIdleCallback(resolve, { timeout: 2000 }),
    );
    if (_shouldStop(token)) return;

    // ── Record start of this scan ──────────────────────────────────────────
    scanId = await ScanLogService.startScan('incremental');

    // ── Determine window: everything since last incremental scan ──────────
    const lastScan = await ScanLogService.getLastCompletedScan('incremental');
    // Subtract 60 s overlap to tolerate clock skew / partial prev-run
    const sinceMs = lastScan
      ? Math.max(0, (lastScan.finished_at ?? 0) - 60_000)
      : 0;

    await _yield(token, startTime);
    if (_shouldStop(token)) return;

    const db = DatabaseService.getDb();

    // ── Step 1: new items (created after last scan) ────────────────────────
    const { rows: newRows } = await db.execute(
      `SELECT uri, album_id, device_modified_at
       FROM   media_index
       WHERE  indexed_at > ? AND hidden = 0
       ORDER  BY indexed_at DESC
       LIMIT  ?`,
      [sinceMs, MAX_ITEMS_PER_RUN],
    );

    await _yield(token, startTime);
    if (_shouldStop(token)) return;

    // ── Step 2: modified items (device_modified_at changed since last scan) –
    const { rows: modRows } = await db.execute(
      `SELECT uri, album_id
       FROM   media_index
       WHERE  device_modified_at > ? AND indexed_at <= ? AND hidden = 0
       ORDER  BY device_modified_at DESC
       LIMIT  ?`,
      [sinceMs, sinceMs, Math.max(0, MAX_ITEMS_PER_RUN - newRows.length)],
    );

    await _yield(token, startTime);
    if (_shouldStop(token)) return;

    const allRows = [...newRows, ...modRows];
    stats.added = newRows.length;
    stats.updated = modRows.length;

    // ── Step 3: warm thumbnail cache for newly discovered / changed items ──
    if (allRows.length > 0) {
      const urisToWarm = allRows.map(r => r.uri).slice(0, MAX_THUMB_WARM);

      // streamResolveThumbnailUris issues one native batch call for all misses
      await streamResolveThumbnailUris(urisToWarm, () => {
        // onBatch callback: thumbnails are now in the L1 cache.
        // No UI state update needed — screens will pick them up on next render.
      });

      await _yield(token, startTime);
      if (_shouldStop(token)) return;
    }

    // ── Step 3b: warm orphan thumbnails (items with no thumb_uri yet) ──────
    // This catches items that were indexed before the thumbnail pre-warming
    // was introduced, or whose previous warm attempt was interrupted.
    // Runs every cycle until the entire library has thumbnails.
    if (!_shouldStop(token)) {
      try {
        const { rows: orphanRows } = await db.execute(
          `SELECT uri FROM media_index
           WHERE  thumb_uri IS NULL AND hidden = 0
           ORDER  BY device_created_at DESC
           LIMIT  ?`,
          [MAX_ORPHAN_THUMB_WARM],
        );

        if (orphanRows.length > 0) {
          const orphanUris = orphanRows.map(r => r.uri);

          await streamResolveThumbnailUris(orphanUris, () => {
            // Thumbnails persisted to L1 + L2 by streamResolveThumbnailUris.
          });

          if (__DEV__) {
            console.log(
              `[BGSync] warmed ${orphanUris.length} orphan thumbnails`,
            );
          }

          await _yield(token, startTime);
        }
      } catch {
        /* non-fatal — will retry next cycle */
      }
    }

    // ── Step 4: refresh album covers for affected albums ───────────────────
    if (allRows.length > 0) {
      const albumIds = [
        ...new Set(allRows.map(r => r.album_id).filter(Boolean)),
      ];

      for (let i = 0; i < albumIds.length; i++) {
        if (_shouldStop(token)) break;

        const albumId = albumIds[i];
        try {
          await db.execute(
            `UPDATE albums
             SET cover_uri = (
               SELECT uri FROM media_index
               WHERE  album_id = ? AND hidden = 0
               ORDER  BY device_created_at DESC LIMIT 1
             ),
             last_scanned_at = ?
             WHERE id = ?`,
            [albumId, Date.now(), albumId],
          );
        } catch {
          /* non-fatal — stale cover is acceptable */
        }

        // Yield every other album to stay responsive
        if (i % 2 === 0) await _yield(token, startTime);
      }
    }

    // ── Finish ─────────────────────────────────────────────────────────────
    if (!token.cancelled) {
      await ScanLogService.completeScan(scanId, stats);
      scanId = null;

      if (__DEV__) {
        const elapsed = Date.now() - startTime;
        console.log(
          `[BGSync] done in ${elapsed} ms — ` +
            `new: ${stats.added}  modified: ${stats.updated}`,
        );
      }
    }
  } catch (err) {
    console.warn('[BGSync] run error:', err);
    if (scanId) {
      ScanLogService.failScan(scanId, String(err)).catch(() => {});
    }
  } finally {
    _running = false;
    _token = null;
  }
}

// ─── Scheduler ────────────────────────────────────────────────────────────────

function _scheduleNext() {
  if (_timer !== null) return; // already pending
  _timer = setTimeout(async () => {
    _timer = null; // clear before run so _scheduleNext works during/after
    await _runOnce();
    _scheduleNext(); // re-arm only after the run completes (never overlaps)
  }, RUN_INTERVAL_MS);
}

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Start the background sync scheduler.
 *
 * Safe to call multiple times — subsequent calls are no-ops.
 * Recommended: call from the app root once the DB is initialised.
 *
 *   useEffect(() => {
 *     BackgroundSyncService.start();
 *     return () => BackgroundSyncService.stop();
 *   }, []);
 */
function start() {
  if (_timer !== null || _appStateSub) return; // already started

  // Subscribe to AppState changes so we pause when the user backgrounds the app
  _appStateSub = AppState.addEventListener('change', nextState => {
    _appActive = nextState === 'active';
    if (!_appActive && _token) {
      _cancel(_token, 'app moved to background');
    }
  });

  _scheduleNext();
}

/**
 * Stop the scheduler and cancel any in-progress run.
 * Call from app teardown (or when permissions are revoked).
 */
function stop() {
  if (_timer !== null) {
    clearTimeout(_timer);
    _timer = null;
  }
  if (_token) _cancel(_token, 'service stopped');
  _appStateSub?.remove();
  _appStateSub = null;
}

/**
 * Signal that a heavy user-initiated task is starting (scan, bulk import,
 * ML indexing, etc.). The background sync cancels itself immediately and will
 * not run again until notifyIdle() is called.
 *
 * @param {string} [reason]  — debug label
 */
function notifyBusy(reason = '') {
  _userBusy = true;
  if (_token) _cancel(_token, reason || 'caller busy');
}

/**
 * Signal that the heavy task has finished. The next scheduled tick will
 * run normally.
 */
function notifyIdle() {
  _userBusy = false;
}

/**
 * Trigger a run immediately, bypassing the scheduled interval.
 * Useful after the user explicitly adds / imports media.
 * Has no effect if a run is already in progress or if busy.
 */
function runNow() {
  _runOnce().catch(err => console.warn('[BGSync] runNow error:', err));
}

/**
 * True while a sync run is executing.
 * Can be polled by debug screens.
 */
function isRunning() {
  return _running;
}

export const BackgroundSyncService = {
  start,
  stop,
  notifyBusy,
  notifyIdle,
  runNow,
  isRunning,
};
