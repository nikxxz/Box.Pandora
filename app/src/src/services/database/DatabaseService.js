/**
 * DatabaseService — singleton that owns the op-sqlite connection.
 *
 * Call `DatabaseService.init()` once at app startup (e.g. in AppNavigator).
 * Every other service calls `DatabaseService.getDb()` to get the open handle.
 *
 * WAL mode is enabled for faster concurrent reads.
 * Foreign keys are enforced so ON DELETE CASCADE works on media_tags.
 *
 * init() is concurrent-safe: multiple simultaneous callers share a single
 * in-flight Promise and all receive the same handle once it resolves.
 */

import { open } from '@op-engineering/op-sqlite';
import { DB_VERSION, TABLE_SQL, INDEX_SQL, MIGRATIONS } from './schema';

const DB_NAME = 'pandora.db';
const PREF_KEY_VERSION = 'db_schema_version';

let _db          = null;
let _initPromise = null;   // shared promise so concurrent init() calls coalesce

// ─── Public API ──────────────────────────────────────────────────────────────

/**
 * Open the database, create schema if needed.
 * Safe to call multiple times and from multiple places concurrently.
 */
async function init() {
  if (_db) return _db;
  if (_initPromise) return _initPromise;   // already in flight — share it

  _initPromise = _doInit().finally(() => {
    _initPromise = null;  // clear after settle so a failed init can be retried
  });
  return _initPromise;
}

async function _doInit() {
  _db = open({ name: DB_NAME });

  // Performance + correctness PRAGMAs (must be set per-connection)
  _db.executeSync('PRAGMA journal_mode=WAL');
  _db.executeSync('PRAGMA foreign_keys=ON');
  _db.executeSync('PRAGMA synchronous=NORMAL');  // safe under WAL

  // ── Schema migration ────────────────────────────────────────────────────
  // Check the stored schema version. On a fresh install user_preferences
  // doesn't exist yet — the SELECT will throw, which we catch and treat as
  // storedVersion === 0 (skip migration; CREATE TABLE IF NOT EXISTS below
  // creates all tables including the new ML ones for the first time).
  let storedVersion = 0;
  try {
    const versionRow = await _db.execute(
      `SELECT value FROM user_preferences WHERE key = ?`,
      [PREF_KEY_VERSION],
    );
    storedVersion = versionRow.rows[0]
      ? parseInt(JSON.parse(versionRow.rows[0].value), 10)
      : 0;
  } catch {
    // user_preferences table not yet created — fresh install
    storedVersion = 0;
  }

  if (storedVersion > 0 && storedVersion < DB_VERSION) {
    console.log(`[DatabaseService] Migrating schema v${storedVersion} → v${DB_VERSION}`);
    for (let v = storedVersion + 1; v <= DB_VERSION; v++) {
      const steps = MIGRATIONS[v] ?? [];
      for (const sql of steps) {
        try {
          await _db.execute(sql);
        } catch (stepErr) {
          // ALTER TABLE … ADD COLUMN throws if the column already exists
          // (can happen if a migration was partially applied). Treat it as
          // a no-op so the migration can continue.
          const msg = stepErr?.message ?? '';
          if (msg.includes('duplicate column name') || msg.includes('already exists')) {
            console.warn(`[DatabaseService] Migration v${v} step skipped (already applied):`, msg);
          } else {
            throw stepErr; // real error — propagate
          }
        }
      }
      console.log(`[DatabaseService] Migration to v${v} done`);
    }
    await _db.execute(
      `UPDATE user_preferences SET value = ?, updated_at = ? WHERE key = ?`,
      [JSON.stringify(DB_VERSION), Date.now(), PREF_KEY_VERSION],
    );
  }
  // ── End migration ───────────────────────────────────────────────────────

  // Create tables
  for (const sql of TABLE_SQL) {
    await _db.execute(sql);
  }

  // Create indexes
  for (const sql of INDEX_SQL) {
    await _db.execute(sql);
  }

  // Persist schema version (used for future migration checks)
  await _db.execute(
    `INSERT OR IGNORE INTO user_preferences (key, value, updated_at)
     VALUES (?, ?, ?)`,
    [PREF_KEY_VERSION, String(DB_VERSION), Date.now()],
  );

  console.log(`[DatabaseService] Ready — schema v${DB_VERSION}`);
  return _db;
}

/**
 * Returns the open DB handle.
 * Throws if init() has not been called yet.
 */
function getDb() {
  if (!_db) {
    throw new Error(
      '[DatabaseService] Database not initialised. Call DatabaseService.init() before using any service.',
    );
  }
  return _db;
}

/**
 * Close the database connection.
 * Typically only needed in tests or on explicit logout.
 */
function close() {
  if (_db) {
    _db.close();
    _db = null;
    console.log('[DatabaseService] Closed.');
  }
}

export const DatabaseService = { init, getDb, close };
