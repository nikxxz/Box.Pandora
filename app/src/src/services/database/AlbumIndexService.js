/**
 * AlbumIndexService
 *
 * Manages the `albums` table.
 *
 * Albums are synced from CameraRoll via MediaService.getAlbums() and stored
 * here so we can:
 *  • Serve the folder grid instantly from SQLite (no CameraRoll round-trip)
 *  • Hide/show individual albums (hidden flag)
 *  • Pin albums to the top of the grid
 *  • Cache per-type covers (photo, video) and counts
 *  • Track when each album was last scanned for incremental updates
 */

import { DatabaseService } from './DatabaseService';
import RNFS from 'react-native-fs';

// ─── Write ────────────────────────────────────────────────────────────────────

/**
 * Insert or update an album row.
 * On conflict (same name) refreshes all device-sourced fields but preserves
 * user flags (hidden, pinned).
 *
 * @param {object} album
 *   { name, path?, albumType?, count?, photoCount?, videoCount?,
 *     coverUri?, photoCoverUri?, videoCoverUri?, lastModifiedAt? }
 * @returns {number} id of the inserted/updated row
 */
async function upsert(album) {
  const db = DatabaseService.getDb();
  const now = Date.now();

  const { rows } = await db.execute(
    `INSERT INTO albums
       (name, path, album_type, cover_uri, photo_cover_uri, video_cover_uri,
        media_count, photo_count, video_count, last_modified_at,
        last_scanned_at, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(name) DO UPDATE SET
       path             = COALESCE(excluded.path, path),
       album_type       = excluded.album_type,
       cover_uri        = COALESCE(excluded.cover_uri,        cover_uri),
       photo_cover_uri  = COALESCE(excluded.photo_cover_uri,  photo_cover_uri),
       video_cover_uri  = COALESCE(excluded.video_cover_uri,  video_cover_uri),
       media_count      = excluded.media_count,
       photo_count      = excluded.photo_count,
       video_count      = excluded.video_count,
       last_modified_at = COALESCE(excluded.last_modified_at, last_modified_at),
       last_scanned_at  = excluded.last_scanned_at
     RETURNING id`,
    [
      album.name,
      album.path ?? null,
      album.albumType ?? 'Album',
      album.coverUri ?? null,
      album.photoCoverUri ?? null,
      album.videoCoverUri ?? null,
      album.count ?? 0,
      album.photoCount ?? 0,
      album.videoCount ?? 0,
      album.lastModifiedAt ?? null,
      now,
      now,
    ],
  );
  return rows[0]?.id ?? null;
}

/**
 * Bulk upsert — single native round-trip via executeBatch.
 */
async function batchUpsert(albums = []) {
  if (!albums.length) return;
  const db = DatabaseService.getDb();
  const now = Date.now();

  const sql = `
    INSERT INTO albums
      (name, path, album_type, cover_uri, photo_cover_uri, video_cover_uri,
       media_count, photo_count, video_count, last_modified_at,
       last_scanned_at, created_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(name) DO UPDATE SET
      path             = COALESCE(excluded.path, path),
      album_type       = excluded.album_type,
      cover_uri        = COALESCE(excluded.cover_uri,        cover_uri),
      photo_cover_uri  = COALESCE(excluded.photo_cover_uri,  photo_cover_uri),
      video_cover_uri  = COALESCE(excluded.video_cover_uri,  video_cover_uri),
      media_count      = excluded.media_count,
      photo_count      = excluded.photo_count,
      video_count      = excluded.video_count,
      last_modified_at = COALESCE(excluded.last_modified_at, last_modified_at),
      last_scanned_at  = excluded.last_scanned_at
  `;

  await db.executeBatch(
    albums.map(a => [
      sql,
      [
        a.name,
        a.path ?? null,
        a.albumType ?? 'Album',
        a.coverUri ?? null,
        a.photoCoverUri ?? null,
        a.videoCoverUri ?? null,
        a.count ?? 0,
        a.photoCount ?? 0,
        a.videoCount ?? 0,
        a.lastModifiedAt ?? null,
        now,
        now,
      ],
    ]),
  );
}

async function touchScannedAt(albumId) {
  const db = DatabaseService.getDb();
  await db.execute('UPDATE albums SET last_scanned_at = ? WHERE id = ?', [
    Date.now(),
    albumId,
  ]);
}

async function setHidden(albumId, value) {
  const db = DatabaseService.getDb();
  await db.execute('UPDATE albums SET hidden = ? WHERE id = ?', [
    value ? 1 : 0,
    albumId,
  ]);
}

async function setPinned(albumId, value) {
  const db = DatabaseService.getDb();
  await db.execute('UPDATE albums SET pinned = ? WHERE id = ?', [
    value ? 1 : 0,
    albumId,
  ]);
}

async function updateCover(albumId, coverUri) {
  const db = DatabaseService.getDb();
  await db.execute('UPDATE albums SET cover_uri = ? WHERE id = ?', [
    coverUri ?? null,
    albumId,
  ]);
}

async function updateCount(albumId, count) {
  const db = DatabaseService.getDb();
  await db.execute('UPDATE albums SET media_count = ? WHERE id = ?', [
    count,
    albumId,
  ]);
}

// ─── Read ─────────────────────────────────────────────────────────────────────

async function getById(id) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute('SELECT * FROM albums WHERE id = ?', [id]);
  return rows[0] ?? null;
}

async function getByName(name) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute('SELECT * FROM albums WHERE name = ?', [
    name,
  ]);
  return rows[0] ?? null;
}

/**
 * Fetch all albums.
 *
 * @param {object} opts
 * @param {boolean} opts.includeHidden — default false
 * @param {string}  opts.sortBy        — 'name' | 'count' | 'date'
 */
async function getAll({ includeHidden = false, sortBy = 'name' } = {}) {
  const db = DatabaseService.getDb();

  const SORT_MAP = {
    name: 'name COLLATE NOCASE ASC',
    count: 'media_count DESC',
    date: 'last_scanned_at DESC',
  };
  const orderClause = SORT_MAP[sortBy] ?? SORT_MAP.name;
  const whereClause = includeHidden ? '' : 'WHERE hidden = 0';

  const { rows } = await db.execute(
    `SELECT * FROM albums ${whereClause} ORDER BY pinned DESC, ${orderClause}`,
  );
  return rows;
}

/**
 * Return albums not scanned since `timestampMs` (stale detection).
 */
async function getStaleAlbums(timestampMs) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT * FROM albums WHERE last_scanned_at IS NULL OR last_scanned_at < ?',
    [timestampMs],
  );
  return rows;
}

/**
 * Build the folder-index shape that MediaIndexer and downstream consumers use.
 *
 * Returns the same structure that MediaIndexer.buildIndex() produces, so callers
 * are unaffected when the index is served from SQLite vs a live CameraRoll scan.
 *
 * Shape: { folders: { [albumName]: FolderEntry }, totalCount, lastIndexed } | null
 * Returns null if no albums are stored yet (first launch / cleared DB).
 */
async function buildIndexShape({ includeHidden = false } = {}) {
  const db = DatabaseService.getDb();

  const whereClause = includeHidden ? '' : 'WHERE hidden = 0';
  const { rows } = await db.execute(
    `SELECT * FROM albums ${whereClause} ORDER BY last_modified_at DESC NULLS LAST, media_count DESC`,
  );

  if (!rows.length) return null;

  const folders = {};
  let totalCount = 0;

  for (const row of rows) {
    folders[row.name] = {
      name: row.name,
      path: row.path ?? null,
      count: row.media_count,
      photoCount: row.photo_count,
      videoCount: row.video_count,
      coverUri: row.cover_uri ?? null,
      photoCoverUri: row.photo_cover_uri ?? null,
      videoCoverUri: row.video_cover_uri ?? null,
      // lastModified is stored as Unix ms; MediaIndexer uses Unix seconds
      lastModified: row.last_modified_at
        ? Math.floor(row.last_modified_at / 1000)
        : null,
      type: row.album_type ?? 'Album',
      hidden: row.hidden === 1,
    };
    totalCount += row.media_count;
  }

  // lastIndexed = oldest scanned_at in the set (worst freshness)
  const lastIndexed = rows.reduce(
    (min, r) =>
      r.last_scanned_at && r.last_scanned_at < min ? r.last_scanned_at : min,
    Date.now(),
  );

  return { folders, totalCount, lastIndexed };
}

/**
 * Recompute album counters/covers from media_index.
 *
 * This is a targeted refresh used after move/copy/delete operations where
 * media_index changes immediately but albums cache columns can lag until a full
 * CameraRoll re-scan.
 */
async function refreshAlbumCounts() {
  const db = DatabaseService.getDb();

  await db.execute(`
    UPDATE albums
    SET
      media_count = COALESCE(
        (SELECT COUNT(1) FROM media_index m WHERE m.album_id = albums.id),
        0
      ),
      photo_count = COALESCE(
        (
          SELECT COUNT(1)
          FROM media_index m
          WHERE m.album_id = albums.id AND m.media_type = 'image'
        ),
        0
      ),
      video_count = COALESCE(
        (
          SELECT COUNT(1)
          FROM media_index m
          WHERE m.album_id = albums.id AND m.media_type = 'video'
        ),
        0
      ),
      last_modified_at = (
        SELECT MAX(m.device_created_at)
        FROM media_index m
        WHERE m.album_id = albums.id
      ),
      cover_uri = (
        SELECT m.uri
        FROM media_index m
        WHERE m.album_id = albums.id
        ORDER BY COALESCE(m.device_created_at, 0) DESC, m.indexed_at DESC
        LIMIT 1
      ),
      photo_cover_uri = (
        SELECT m.uri
        FROM media_index m
        WHERE m.album_id = albums.id AND m.media_type = 'image'
        ORDER BY COALESCE(m.device_created_at, 0) DESC, m.indexed_at DESC
        LIMIT 1
      ),
      video_cover_uri = (
        SELECT m.uri
        FROM media_index m
        WHERE m.album_id = albums.id AND m.media_type = 'video'
        ORDER BY COALESCE(m.device_created_at, 0) DESC, m.indexed_at DESC
        LIMIT 1
      )
  `);
}

// ─── Export ───────────────────────────────────────────────────────────────────

/**
 * Rename an album in SQLite.
 * Note: this only updates the metadata row.  The physical folder rename
 * (MediaStore DISPLAY_NAME update) is handled by the native MediaStoreModule.
 *
 * @param {number} albumId  — id from the albums table
 * @param {string} newName  — new album/folder display name
 */
async function rename(albumId, newName) {
  const db = DatabaseService.getDb();
  await db.execute(
    'UPDATE albums SET name = ?, last_scanned_at = ? WHERE id = ?',
    [newName, Date.now(), albumId],
  );
}

/**
 * Remove album rows whose backing directory or content no longer exists.
 *
 * Two categories are pruned:
 *  1. Path-based albums (hidden .nomedia folders) — if RNFS.exists(path) is
 *     false the album AND all its media_index rows are deleted.
 *  2. Empty CameraRoll albums — no known path, media_count = 0, and
 *     last_scanned_at is older than staleCutoffMs.
 *
 * @param {{ staleCutoffMs?: number }} options
 * @returns {Promise<{ checked: number, removed: number }>}
 */
async function pruneOrphanedAlbums({
  staleCutoffMs = 7 * 24 * 60 * 60 * 1000, // 7 days
} = {}) {
  const db = DatabaseService.getDb();
  const { rows: albumRows } = await db.execute(
    'SELECT id, name, path, media_count, last_scanned_at FROM albums',
  );
  const toDelete = [];
  const cutoff = Date.now() - staleCutoffMs;

  await Promise.all(
    albumRows.map(async row => {
      if (row.path) {
        // Filesystem-sourced album — verify the directory still exists.
        try {
          const exists = await RNFS.exists(row.path);
          if (!exists) toDelete.push(row.id);
        } catch {
          toDelete.push(row.id);
        }
      } else if (
        (row.media_count ?? 0) === 0 &&
        row.last_scanned_at != null &&
        row.last_scanned_at < cutoff
      ) {
        // Stale CameraRoll stub with no media — safe to remove.
        toDelete.push(row.id);
      }
    }),
  );

  if (toDelete.length) {
    // Delete media rows before removing the album (FK is ON DELETE SET NULL,
    // not CASCADE, so an explicit delete is required here).
    await db.executeBatch(
      toDelete.map(id => ['DELETE FROM media_index WHERE album_id = ?', [id]]),
    );
    const ph = toDelete.map(() => '?').join(', ');
    await db.execute(`DELETE FROM albums WHERE id IN (${ph})`, toDelete);
  }

  return { checked: albumRows.length, removed: toDelete.length };
}

export const AlbumIndexService = {
  upsert,
  batchUpsert,
  touchScannedAt,
  setHidden,
  setPinned,
  updateCover,
  updateCount,
  rename,
  getById,
  getByName,
  getAll,
  getStaleAlbums,
  buildIndexShape,
  refreshAlbumCounts,
  pruneOrphanedAlbums,
};
