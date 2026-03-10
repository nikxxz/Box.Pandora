/**
 * PreferenceService
 *
 * Thin wrapper over the `user_preferences` table.
 * Values are stored as JSON so any serialisable shape is supported.
 *
 * Well-known keys are exposed as typed helpers (getLastFilters, saveLastSort, …)
 * so call-sites never have to hard-code key strings.
 */

import { DatabaseService } from './DatabaseService';

// ─── Default shapes ───────────────────────────────────────────────────────────

/**
 * Default filter state — used as fallback when no preference is persisted yet,
 * and as a reference for "reset to defaults" UIs.
 *
 * All keys correspond to the `filters` argument accepted by
 * MediaIndexService.query() and MediaIndexService.count().
 */
export const DEFAULT_FILTERS = {
  mediaType: 'All', // 'All' | 'image' | 'video' | 'audio'
  favorite: false,
  ratingMin: 0, // 0 = no filter; 1–5 = minimum star rating
  showHidden: false,
  // Size range (bytes)
  sizeMin: null,
  sizeMax: null,
  // Video duration range (seconds)
  durationMin: null,
  durationMax: null,
  // Resolution minimum (px)
  widthMin: null,
  heightMin: null,
  // Tag filter — array of tag IDs (OR match)
  tags: [],
  // Date range (Unix ms)
  createdAfter: null,
  createdBefore: null,
};

/**
 * Default sort state.
 */
export const DEFAULT_SORT = {
  by: 'date', // 'date' | 'name' | 'size' | 'rating' | 'duration'
  order: 'DESC', // 'ASC' | 'DESC'
};

// Preference keys — centralised to avoid typos across the codebase
const KEYS = {
  LAST_FILTERS: 'last_filters',
  LAST_SORT: 'last_sort',
  LAST_ALBUM_ID: 'last_album_id',
  ACCENT_COLOR: 'accent_color',
  ML_SCENE_ENABLED: 'ml_scene_enabled',
  ML_FACE_ENABLED: 'ml_face_enabled',
};

// ─── Generic get / set ────────────────────────────────────────────────────────

async function get(key) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT value FROM user_preferences WHERE key = ?',
    [key],
  );
  const row = rows[0];
  if (!row) return null;
  try {
    return JSON.parse(row.value);
  } catch {
    return null;
  }
}

async function set(key, value) {
  const db = DatabaseService.getDb();
  await db.execute(
    `INSERT INTO user_preferences (key, value, updated_at) VALUES (?, ?, ?)
     ON CONFLICT(key) DO UPDATE SET
       value      = excluded.value,
       updated_at = excluded.updated_at`,
    [key, JSON.stringify(value), Date.now()],
  );
}

async function remove(key) {
  const db = DatabaseService.getDb();
  await db.execute('DELETE FROM user_preferences WHERE key = ?', [key]);
}

// ─── Typed helpers ────────────────────────────────────────────────────────────

/**
 * Retrieve the last-used filter set, merged with defaults so any new filter
 * keys introduced after the preference was saved are always present.
 */
async function getLastFilters() {
  const saved = await get(KEYS.LAST_FILTERS);
  return { ...DEFAULT_FILTERS, ...(saved ?? {}) };
}

async function saveLastFilters(filters) {
  await set(KEYS.LAST_FILTERS, filters);
}

async function resetFilters() {
  await remove(KEYS.LAST_FILTERS);
}

async function getLastSort() {
  const saved = await get(KEYS.LAST_SORT);
  return { ...DEFAULT_SORT, ...(saved ?? {}) };
}

async function saveLastSort(sort) {
  await set(KEYS.LAST_SORT, sort);
}

/** Remember which album the user last opened (for restore-on-resume). */
async function getLastAlbumId() {
  return get(KEYS.LAST_ALBUM_ID);
}

async function saveLastAlbumId(albumId) {
  await set(KEYS.LAST_ALBUM_ID, albumId);
}

// ─── Accent colour ────────────────────────────────────────────────────────────

/**
 * Returns the persisted accent colour hex string, or null if none is set
 * (meaning "use theme default").
 */
async function getAccentColor() {
  const saved = await get(KEYS.ACCENT_COLOR);
  // Validate it's a hex string or null
  if (typeof saved === 'string' && /^#[0-9A-Fa-f]{3,8}$/.test(saved)) {
    return saved;
  }
  return null;
}

/**
 * Persist the accent colour.  Pass null to revert to theme default.
 */
async function saveAccentColor(hex) {
  if (hex === null || hex === undefined) {
    await remove(KEYS.ACCENT_COLOR);
  } else {
    await set(KEYS.ACCENT_COLOR, hex);
  }
}

// ─── ML model toggles ─────────────────────────────────────────────────────────

/**
 * Returns true if scene embedding (MobileNetV3) is enabled.
 * Defaults to true when no preference has been saved yet.
 */
async function getMlSceneEnabled() {
  const val = await get(KEYS.ML_SCENE_ENABLED);
  return val !== false; // default true
}

async function saveMlSceneEnabled(enabled) {
  await set(KEYS.ML_SCENE_ENABLED, Boolean(enabled));
}

/**
 * Returns true if face embedding (MobileFaceNet) is enabled.
 * Defaults to true when no preference has been saved yet.
 */
async function getMlFaceEnabled() {
  const val = await get(KEYS.ML_FACE_ENABLED);
  return val !== false; // default true
}

async function saveMlFaceEnabled(enabled) {
  await set(KEYS.ML_FACE_ENABLED, Boolean(enabled));
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const PreferenceService = {
  // Generic
  get,
  set,
  remove,
  // Typed
  getLastFilters,
  saveLastFilters,
  resetFilters,
  getLastSort,
  saveLastSort,
  getLastAlbumId,
  saveLastAlbumId,
  getAccentColor,
  saveAccentColor,
  getMlSceneEnabled,
  saveMlSceneEnabled,
  getMlFaceEnabled,
  saveMlFaceEnabled,
  // Constants (re-exported for convenience)
  DEFAULT_FILTERS,
  DEFAULT_SORT,
  KEYS,
};
