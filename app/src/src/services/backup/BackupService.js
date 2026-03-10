/**
 * BackupService
 *
 * Serialises all user-generated data (preferences, favourites, hidden files,
 * tags, hidden/pinned albums) to a portable JSON file, and restores it on
 * import.
 *
 * Media files themselves are NOT included — they live on storage and will be
 * re-linked after a "Re-index Media" run on import.
 *
 * ─── Backup schema v1 ────────────────────────────────────────────────────────
 * {
 *   version:          "1",
 *   exportedAt:       "ISO-8601 string",
 *   app: {
 *     theme:          "dark" | "light",
 *     showHidden:     boolean,
 *     sortBy:         "date" | "name" | "count",
 *     accentColor:    "#hex" | null,
 *     mediaFilter:    "all" | "images" | "videos",
 *   },
 *   preferences: {
 *     lastFilters:    object,
 *     lastSort:       object,
 *   },
 *   favorites:        string[],           // media content URIs
 *   hiddenUris:       string[],           // media content URIs
 *   hiddenAlbums:     string[],           // album names (device-independent)
 *   pinnedAlbums:     string[],           // album names
 *   tags:             Array<{ name, color, icon }>,
 *   mediaTagMappings: Array<{ mediaUri, tagName }>,
 * }
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Restoration on a fresh install (same device):
 *   1. Import  → prefs applied immediately; stub rows inserted for all URIs.
 *   2. Re-index → real rows upserted; UPSERT preserves favourite/hidden flags
 *                 because the ON CONFLICT clause purposely excludes them.
 *   3. Everything is live.
 *
 * Restoration on a new device:
 *   Tag definitions (name + colour) carry over fully.
 *   URI-linked data (favourites, hidden, tag assignments) requires the same
 *   content URIs to exist after a re-scan — typical if the media was copied
 *   over first.
 */

import * as ScopedStorage from 'react-native-scoped-storage';
import { MediaIndexService } from '../database/MediaIndexService';
import { AlbumIndexService } from '../database/AlbumIndexService';
import { TagService } from '../database/TagService';
import { PreferenceService } from '../database/PreferenceService';
import { DatabaseService } from '../database/DatabaseService';

const BACKUP_VERSION = '1';
const BACKUP_FILENAME_PREFIX = 'PandoraBackup-';

// ─── Export ───────────────────────────────────────────────────────────────────

/**
 * Export all user data to a JSON file then open the Android share sheet so the
 * user can save it to Files, send it via a messenger app, etc.
 *
 * @param {object} appState  Current app reducer state (from useAppContext)
 * @returns {Promise<void>}
 */
async function exportBackup(appState) {
  // ── 1. App preferences ────────────────────────────────────────────────────
  const app = {
    theme: appState.theme ?? 'dark',
    showHidden: appState.showHidden ?? false,
    sortBy: appState.sortBy ?? 'date',
    accentColor: appState.accentColor ?? null,
    mediaFilter: appState.mediaFilter ?? 'all',
  };

  // ── 2. Persisted filter / sort ────────────────────────────────────────────
  const [lastFilters, lastSort] = await Promise.all([
    PreferenceService.getLastFilters(),
    PreferenceService.getLastSort(),
  ]);

  // ── 3. Favourites ─────────────────────────────────────────────────────────
  const favoriteRows = await MediaIndexService.query(
    { favorite: true },
    {},
    { limit: 999999, offset: 0 },
  );
  const favorites = favoriteRows.map(r => r.uri);

  // ── 4. Hidden URIs ────────────────────────────────────────────────────────
  const hiddenUris = await MediaIndexService.getHiddenUris();

  // ── 5. Hidden + pinned albums ─────────────────────────────────────────────
  const allAlbums = await AlbumIndexService.getAll({ includeHidden: true });
  const hiddenAlbums = allAlbums.filter(a => a.hidden === 1).map(a => a.name);
  const pinnedAlbums = allAlbums.filter(a => a.pinned === 1).map(a => a.name);

  // ── 6. Tag definitions ────────────────────────────────────────────────────
  const allTags = await TagService.getAllTags();
  const tags = allTags.map(t => ({
    name: t.name,
    color: t.color,
    icon: t.icon ?? null,
  }));

  // ── 7. Media-tag mappings (direct DB query for efficiency) ────────────────
  const db = DatabaseService.getDb();
  const { rows: mappingRows } = await db.execute(
    `SELECT mt.media_uri, t.name AS tag_name
       FROM media_tags mt
       INNER JOIN tags t ON t.id = mt.tag_id`,
    [],
  );
  const mediaTagMappings = mappingRows.map(r => ({
    mediaUri: r.media_uri,
    tagName: r.tag_name,
  }));

  // ── 8. Build payload ──────────────────────────────────────────────────────
  const payload = {
    version: BACKUP_VERSION,
    exportedAt: new Date().toISOString(),
    app,
    preferences: { lastFilters, lastSort },
    favorites,
    hiddenUris,
    hiddenAlbums,
    pinnedAlbums,
    tags,
    mediaTagMappings,
  };

  // ── 9. Build filename ─────────────────────────────────────────────────────
  const dateStr = new Date()
    .toISOString()
    .replace(/T/, '_')
    .replace(/:/g, '-')
    .replace(/\..+/, '');
  const filename = `${BACKUP_FILENAME_PREFIX}${dateStr}.json`;

  // ── 10. Open SAF "save" dialog — user picks destination, we write directly ─
  // Uses Android Storage Access Framework — no file:// URI, no FileProvider
  // needed, no MANAGE_EXTERNAL_STORAGE permission required.
  const saved = await ScopedStorage.createDocument(
    filename,
    'application/json',
    JSON.stringify(payload, null, 2),
    'utf8',
  );

  // null means the user dismissed the picker without choosing a location
  if (!saved) throw new Error('Export cancelled.');
}

// ─── Import ───────────────────────────────────────────────────────────────────

/**
 * Open the system file picker, read the selected backup JSON, validate it,
 * then restore all user data into the SQLite DB.
 *
 * Returns a snapshot of the restored state so the caller can update React
 * context via dispatch, or null if the user cancelled the file picker.
 *
 * @returns {Promise<{
 *   appPrefs: object,
 *   favorites: string[],
 *   hiddenUris: string[],
 *   tagsMap: { [uri: string]: string[] },
 * } | null>}
 */
async function importBackup() {
  // ── 1. Open system file picker (returns file content when readData=true) ──
  let pickedFile;
  try {
    pickedFile = await ScopedStorage.openDocument(true, 'utf8');
  } catch (err) {
    // User cancelled — the native module rejects with a non-descriptive error
    const msg = err?.message ?? '';
    if (
      msg.toLowerCase().includes('cancel') ||
      msg.toLowerCase().includes('dismiss') ||
      err?.code === 'CANCEL' ||
      err?.code === 'E_CANCELLED'
    ) {
      return null;
    }
    throw err;
  }

  if (!pickedFile) return null;

  // ── 2. Parse + validate ───────────────────────────────────────────────────
  const content = pickedFile.data ?? pickedFile;
  if (!content) throw new Error('Could not read the selected file.');

  let backup;
  try {
    backup = JSON.parse(
      typeof content === 'string' ? content : String(content),
    );
  } catch {
    throw new Error('Invalid backup file: not valid JSON.');
  }

  _validate(backup);

  const db = DatabaseService.getDb();

  // ── 3. App preferences ────────────────────────────────────────────────────
  const { app = {}, preferences = {} } = backup;
  if (preferences.lastFilters) {
    await PreferenceService.saveLastFilters(preferences.lastFilters);
  }
  if (preferences.lastSort) {
    await PreferenceService.saveLastSort(preferences.lastSort);
  }
  if (app.accentColor !== undefined) {
    await PreferenceService.saveAccentColor(app.accentColor);
  }

  // ── 4. Recreate tag definitions (idempotent — INSERT OR IGNORE by name) ───
  const tagNameToId = {};
  for (const tag of backup.tags ?? []) {
    const id = await TagService.createTag(
      tag.name,
      tag.color ?? '#888888',
      tag.icon ?? null,
    );
    if (id != null) tagNameToId[tag.name] = id;
  }

  // ── 5. Insert stub rows for every URI that needs user metadata ───────────
  //    INSERT OR IGNORE — won't overwrite real rows already present.
  //    UPSERT preserves favourite/hidden flags (excluded from DO UPDATE SET).
  const allUserUris = new Set([
    ...(backup.favorites ?? []),
    ...(backup.hiddenUris ?? []),
    ...(backup.mediaTagMappings ?? []).map(m => m.mediaUri),
  ]);

  if (allUserUris.size > 0) {
    const now = Date.now();
    const insertBatch = [...allUserUris].map(uri => [
      `INSERT OR IGNORE INTO media_index
         (uri, filename, extension, media_type, indexed_at, scanned_at, favorite, hidden)
       VALUES (?, ?, ?, ?, ?, ?, 0, 0)`,
      [uri, _filenameFromUri(uri), _extensionFromUri(uri), 'image', now, now],
    ]);
    await db.executeBatch(insertBatch);
  }

  // ── 6. Restore favourite flags ────────────────────────────────────────────
  if ((backup.favorites ?? []).length > 0) {
    await MediaIndexService.batchSetFavorite(backup.favorites, true);
  }

  // ── 7. Restore hidden-URI flags ───────────────────────────────────────────
  if ((backup.hiddenUris ?? []).length > 0) {
    await MediaIndexService.batchSetHidden(backup.hiddenUris, true);
  }

  // ── 8. Restore media-tag assignments (grouped by tag for efficiency) ──────
  const tagToUris = {};
  for (const { mediaUri, tagName } of backup.mediaTagMappings ?? []) {
    if (!tagToUris[tagName]) tagToUris[tagName] = [];
    tagToUris[tagName].push(mediaUri);
  }
  for (const [tagName, uris] of Object.entries(tagToUris)) {
    const tagId = tagNameToId[tagName];
    if (tagId != null && uris.length > 0) {
      await TagService.batchAddTag(uris, tagId);
    }
  }

  // ── 9. Restore hidden / pinned album flags (matched by name) ─────────────
  for (const name of backup.hiddenAlbums ?? []) {
    const album = await AlbumIndexService.getByName(name);
    if (album) await AlbumIndexService.setHidden(album.id, true);
  }
  for (const name of backup.pinnedAlbums ?? []) {
    const album = await AlbumIndexService.getByName(name);
    if (album) await AlbumIndexService.setPinned(album.id, true);
  }

  // ── 10. Build tags map for MediaContext ───────────────────────────────────
  const tagsMap = {};
  for (const { mediaUri, tagName } of backup.mediaTagMappings ?? []) {
    if (!tagsMap[mediaUri]) tagsMap[mediaUri] = [];
    tagsMap[mediaUri].push(tagName);
  }

  return {
    appPrefs: app,
    favorites: backup.favorites ?? [],
    hiddenUris: backup.hiddenUris ?? [],
    tagsMap,
  };
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function _validate(backup) {
  if (!backup || typeof backup !== 'object') {
    throw new Error('Invalid backup file: expected a JSON object.');
  }
  if (!backup.version) {
    throw new Error('Invalid backup file: missing "version" field.');
  }
  if (backup.version !== BACKUP_VERSION) {
    throw new Error(
      `Unsupported backup version: ${backup.version}. ` +
        `This app supports version ${BACKUP_VERSION}.`,
    );
  }
}

function _filenameFromUri(uri) {
  try {
    const decoded = decodeURIComponent(uri);
    const parts = decoded.split('/');
    return parts[parts.length - 1] || '';
  } catch {
    return '';
  }
}

function _extensionFromUri(uri) {
  const filename = _filenameFromUri(uri);
  const dotIdx = filename.lastIndexOf('.');
  if (dotIdx < 0) return '';
  return filename.slice(dotIdx + 1).toLowerCase();
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const BackupService = { exportBackup, importBackup };
