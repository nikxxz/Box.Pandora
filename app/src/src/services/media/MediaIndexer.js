/**
 * MediaIndexer
 *
 * Builds and maintains a structured index of every album on the device.
 *
 * Storage strategy (was: AsyncStorage TTL cache → now: SQLite):
 *  1. On buildIndex()   — check SQLite first (AlbumIndexService.buildIndexShape)
 *  2. If fresh enough   — return SQLite data instantly (no CameraRoll calls)
 *  3. If stale / empty  — re-scan CameraRoll, persist results to SQLite
 *  4. On invalidate()   — zero out last_scanned_at so next call forces a re-scan
 *
 * Index shape (unchanged so all callers continue to work):
 * {
 *   folders: { [albumName]: {
 *     name, count, photoCount, videoCount,
 *     coverUri, photoCoverUri, videoCoverUri,
 *     lastModified, type
 *   }},
 *   totalCount: number,
 *   lastIndexed: timestamp (ms),
 * }
 */

import { AppState } from 'react-native';
import { AlbumIndexService } from '../database/AlbumIndexService';
import { DatabaseService } from '../database/DatabaseService';
import { MediaIndexService } from '../database/MediaIndexService';
import { MediaService } from './MediaService';
import RNFS from 'react-native-fs';
import { EmbeddingIndexer } from '../ml/EmbeddingIndexer';
import { FaceIndexer } from '../ml/FaceIndexer';

// ─── Background / foreground pause ────────────────────────────────────────────
// Cancel CPU-intensive ML indexing passes when the app is backgrounded so we
// don't drain battery while the user is not actively using the app.
// Both indexers have internal _running guards — calling cancel() is safe even
// if they are not currently running.
AppState.addEventListener('change', nextState => {
  if (nextState === 'background' || nextState === 'inactive') {
    EmbeddingIndexer.cancel();
    FaceIndexer.cancel();
  }
});

// How long a scan result stays "fresh" before we re-scan CameraRoll (ms)
const SCAN_TTL_MS = 5 * 60 * 1000; // 5 minutes

// Directories to skip during .nomedia filesystem walk
const NOMEDIA_SKIP_DIRS = new Set([
  'Android',
  'data',
  'obb',
  '.trash',
  'lost+found',
  '.cache',
  'cache',
]);
// Max depth for the .nomedia filesystem walk (root = 0)
const NOMEDIA_MAX_DEPTH = 3;

// Recognised media extensions for direct-filesystem indexing
const IMAGE_EXTS = new Set([
  'jpg',
  'jpeg',
  'png',
  'gif',
  'bmp',
  'webp',
  'heic',
  'heif',
  'tiff',
  'tif',
  'raw',
  'cr2',
  'nef',
  'arw',
  'dng',
]);
const VIDEO_EXTS = new Set([
  'mp4',
  'mov',
  'avi',
  'mkv',
  'wmv',
  'flv',
  'webm',
  'm4v',
  '3gp',
  'ts',
  'mpg',
  'mpeg',
]);

// ─── Build / Refresh ──────────────────────────────────────────────────────────

/**
 * Return the album index.
 *
 * SQLite-first: if all albums were scanned within SCAN_TTL_MS, return the
 * cached shape from SQLite without touching CameraRoll.
 * Otherwise perform a full CameraRoll scan and persist results to SQLite.
 *
 * @param {boolean} forceRefresh     — skip freshness check and always re-scan
 * @param {{ includeHidden?: boolean }} options
 * @returns {Promise<Index | null>}
 */
async function buildIndex(
  forceRefresh = false,
  { includeHidden = false } = {},
) {
  // Make sure the DB is open (safe to call multiple times — idempotent)
  await DatabaseService.init();

  if (!forceRefresh) {
    const cached = await AlbumIndexService.buildIndexShape({ includeHidden });
    if (cached && _isFresh(cached.lastIndexed)) {
      return cached;
    }
  }

  // Re-scan CameraRoll and persist; then read back with the correct filter
  await _scanAndPersist();

  // Fire background embedding pass — deferred until the UI is idle so it
  // does not compete with scroll / animation frames on the JS thread.
  // EmbeddingIndexer has an internal _running guard so overlapping calls are no-ops.
  requestIdleCallback(() => {
    EmbeddingIndexer.runToCompletion().catch(err =>
      console.warn('[MediaIndexer] background scene embedding failed:', err),
    );
  });

  // Fire background face detection pass — runs after scene embedding so
  // the two indexers don't compete for CPU/memory at the same time.
  // FaceIndexer has its own _running guard.
  requestIdleCallback(() => {
    if (FaceIndexer.isFaceDetectionAvailable()) {
      FaceIndexer.runFaceBatch().catch(err =>
        console.warn('[MediaIndexer] background face batch failed:', err),
      );
    }
  });

  return AlbumIndexService.buildIndexShape({ includeHidden });
}

/** True if the oldest last_scanned_at in the set is within the TTL. */
function _isFresh(lastIndexed) {
  if (!lastIndexed) return false;
  return Date.now() - lastIndexed < SCAN_TTL_MS;
}

/** Scan CameraRoll and persist album rows to SQLite. Does NOT return an index. */
async function _scanAndPersist() {
  try {
    // Fetch all-type, photo-only, and video-only album lists in parallel
    const [albums, photoAlbums, videoAlbums] = await Promise.all([
      MediaService.getAlbums('All'),
      MediaService.getAlbums('Photos'),
      MediaService.getAlbums('Videos'),
    ]);

    if (!albums.length) return;

    // O(1) lookup maps for per-type counts
    const photoCounts = {};
    const videoCounts = {};
    photoAlbums.forEach(a => {
      photoCounts[a.title] = a.count;
    });
    videoAlbums.forEach(a => {
      videoCounts[a.title] = a.count;
    });

    // Fetch cover photos per album in batches to avoid I/O spikes
    const BATCH = 10;
    const albumRows = []; // will be batch-upserted into SQLite

    for (let i = 0; i < albums.length; i += BATCH) {
      const batch = albums.slice(i, i + BATCH);

      await Promise.all(
        batch.map(async album => {
          const [cover, photoCover, videoCover] = await Promise.all([
            MediaService.getAlbumMedia(album.title, {
              first: 1,
              assetType: 'All',
            }),
            MediaService.getAlbumMedia(album.title, {
              first: 1,
              assetType: 'Photos',
            }),
            MediaService.getAlbumMedia(album.title, {
              first: 1,
              assetType: 'Videos',
            }),
          ]);

          const photoCount = photoCounts[album.title] ?? 0;
          const videoCount = videoCounts[album.title] ?? 0;
          const latestItem = cover.items[0];
          const lastModified = latestItem?.timestamp ?? null; // Unix seconds

          // Resolve the real filesystem folder path from the cover URI.
          // Stored in SQLite so copyMediaToFolder can use it directly instead
          // of re-querying CameraRoll (which can return wrong paths when
          // duplicate-name folders exist after a copy gone wrong).
          let resolvedFolderPath = null;
          if (latestItem?.uri) {
            try {
              const fp = await MediaService.resolveFilePath(latestItem.uri);
              if (fp) resolvedFolderPath = fp.substring(0, fp.lastIndexOf('/'));
            } catch {
              // Non-fatal — resolveFilePath may fail without MANAGE_EXTERNAL_STORAGE
            }
          }

          // Prepare SQLite row (lastModifiedAt stored as Unix ms)
          albumRows.push({
            name: album.title,
            path: resolvedFolderPath,
            albumType: album.type ?? 'Album',
            count: album.count,
            photoCount,
            videoCount,
            coverUri: latestItem?.uri ?? null,
            photoCoverUri: photoCover.items[0]?.uri ?? null,
            videoCoverUri: videoCover.items[0]?.uri ?? null,
            lastModifiedAt: lastModified ? lastModified * 1000 : null,
          });
        }),
      );

      // Yield between batches to avoid blocking the JS thread
      await new Promise(r => setTimeout(r, 0));
    }

    // Persist all albums in a single batch write
    await AlbumIndexService.batchUpsert(albumRows);

    // ── Reconcile: remove album rows that CameraRoll no longer returns ────
    // batchUpsert only inserts/updates — it never deletes stale rows.  Any
    // album name absent from this scan's CameraRoll results is a deleted (or
    // renamed) folder and should be purged from SQLite.
    //
    // We only touch regular CameraRoll albums:
    //   • hidden = 0  ← user-hidden / .nomedia albums are managed separately
    //   • path IS NULL ← .nomedia albums always have a path set
    //
    // IMPORTANT: also exclude albums that have a path set (nomedia-discovered
    // folders). CameraRoll never returns these, so they would always be
    // incorrectly deleted here — especially right after unhiding, before the
    // Android MediaStore has had time to rescan the now-visible folder.
    if (albumRows.length > 0) {
      const scannedNames = albumRows.map(a => a.name);
      const ph = scannedNames.map(() => '?').join(', ');
      const db = DatabaseService.getDb();
      await db.execute(
        `DELETE FROM albums
         WHERE hidden = 0
           AND path IS NULL
           AND name NOT IN (${ph})`,
        scannedNames,
      );
    }

    // Discover folders hidden via .nomedia from other apps and add to SQLite
    await scanNomediaFolders();
  } catch (err) {
    console.error('[MediaIndexer] buildIndex scan error:', err);
  }
}

// ─── Orphan pruning ───────────────────────────────────────────────────────────

/**
 * Remove stale entries from both `media_index` and `albums` tables.
 *
 * Intended to be called as the first step of a forced re-index so that files
 * and folders deleted outside the app no longer appear in the database.
 *
 * Delegates to:
 *  • MediaIndexService.pruneOrphanedEntries()  — checks every media URI
 *  • AlbumIndexService.pruneOrphanedAlbums()   — checks every album path
 *
 * @param {{ onProgress?: (done: number, total: number) => void }} options
 * @returns {Promise<{ mediaChecked: number, mediaRemoved: number, albumsChecked: number, albumsRemoved: number }>}
 */
async function pruneOrphans({ onProgress } = {}) {
  try {
    await DatabaseService.init();
    const [mediaResult, albumResult] = await Promise.all([
      MediaIndexService.pruneOrphanedEntries({ onProgress }),
      AlbumIndexService.pruneOrphanedAlbums(),
    ]);
    return {
      mediaChecked: mediaResult.checked,
      mediaRemoved: mediaResult.removed,
      albumsChecked: albumResult.checked,
      albumsRemoved: albumResult.removed,
    };
  } catch (err) {
    console.warn('[MediaIndexer] pruneOrphans error:', err);
    return {
      mediaChecked: 0,
      mediaRemoved: 0,
      albumsChecked: 0,
      albumsRemoved: 0,
    };
  }
}

// ─── Invalidation ─────────────────────────────────────────────────────────────

/**
 * Force a fresh CameraRoll scan on the next buildIndex() call.
 * Used after deleting, adding, or moving media.
 *
 * Sets last_scanned_at = 0 on every album so they all read as stale.
 */
async function invalidate() {
  try {
    await DatabaseService.init();
    const db = DatabaseService.getDb();
    await db.execute('UPDATE albums SET last_scanned_at = 0');
  } catch (err) {
    console.warn('[MediaIndexer] invalidate error:', err);
  }
}

// ─── Folder re-index (unhide) ────────────────────────────────────────────────

/**
 * (Re-)index all media files inside a filesystem folder path and update that
 * album's row in SQLite with real counts, cover URI and timestamps.
 *
 * Called after removing a .nomedia file to immediately surface the folder's
 * contents in the app without waiting for the Android MediaStore to rescan
 * (which can take minutes or hours on some devices).
 *
 * The media items are indexed with file:// URIs.  They will be updated to
 * proper content:// URIs automatically the next time the Android MediaStore
 * runs its periodic rescan.
 *
 * @param {string} folderPath  Absolute path, e.g. '/storage/emulated/0/SomeName'
 * @returns {Promise<void>}
 */
async function reindexFolderByPath(folderPath) {
  if (!folderPath) return;
  try {
    await DatabaseService.init();
    const db = DatabaseService.getDb();
    const folderName =
      folderPath.split('/').filter(Boolean).pop() ?? folderPath;
    const { rows: albumRows } = await db.execute(
      'SELECT id FROM albums WHERE name = ?',
      [folderName],
    );
    const albumId = albumRows[0]?.id;
    if (!albumId) return;

    const now = Date.now();
    const stats = await _indexNomediaFolderFiles(folderPath, albumId, now);
    if (stats.count > 0) {
      await db.execute(
        `UPDATE albums
         SET media_count      = ?,
             photo_count      = ?,
             video_count      = ?,
             cover_uri        = COALESCE(?, cover_uri),
             last_modified_at = COALESCE(?, last_modified_at),
             last_scanned_at  = ?
         WHERE id = ?`,
        [
          stats.count,
          stats.photoCount,
          stats.videoCount,
          stats.coverUri,
          stats.lastModifiedAt,
          now,
          albumId,
        ],
      );
    }

    // ── Trigger ML processing for newly-visible media ─────────────────────
    // After unhiding, this folder's items are now eligible for scene
    // embedding and face detection.  Kick off both pipelines in idle
    // callbacks rather than awaiting them (non-blocking).
    requestIdleCallback(() => {
      EmbeddingIndexer.runToCompletion().catch(err =>
        console.warn('[MediaIndexer] post-reindex embedding failed:', err),
      );
    });
    requestIdleCallback(() => {
      if (FaceIndexer.isFaceDetectionAvailable()) {
        FaceIndexer.runFaceBatch().catch(err =>
          console.warn('[MediaIndexer] post-reindex face batch failed:', err),
        );
      }
    });
  } catch (err) {
    console.warn(
      '[MediaIndexer] reindexFolderByPath error for',
      folderPath,
      err,
    );
  }
}

// ─── .nomedia folder discovery ────────────────────────────────────────────────────────────────────────────

/**
 * Scan the filesystem for directories that contain a .nomedia file.
 *
 * These folders are invisible to CameraRoll (Android media scanner honours
 * .nomedia) but we want them registered in SQLite so the app can surface them
 * via the “Show Hidden” toggle.
 *
 * Uses RNFS to walk up to NOMEDIA_MAX_DEPTH levels below /storage/emulated/0.
 * Skips system-only directories (Android/, data/, obb/, etc.).
 */
async function scanNomediaFolders() {
  try {
    await DatabaseService.init();
    const rootPath = RNFS.ExternalStorageDirectoryPath; // /storage/emulated/0
    const discovered = [];
    await _walkForNomedia(rootPath, discovered, 0);

    if (!discovered.length) return 0;

    const db = DatabaseService.getDb();
    const now = Date.now();

    // 1. Upsert album rows — hidden=1, preserve if user explicitly un-hid
    const albumSQL = `
      INSERT INTO albums
        (name, path, album_type, hidden, media_count, photo_count, video_count,
         last_scanned_at, created_at)
      VALUES (?, ?, 'Album', 1, 0, 0, 0, ?, ?)
      ON CONFLICT(name) DO UPDATE SET
        path            = COALESCE(excluded.path, path),
        hidden          = CASE WHEN hidden = 0 THEN 0 ELSE 1 END,
        last_scanned_at = excluded.last_scanned_at
    `;
    await db.executeBatch(
      discovered.map(({ name, path }) => [albumSQL, [name, path, now, now]]),
    );

    // 2. For each discovered folder, index its media files into media_index
    for (const { name, path } of discovered) {
      try {
        const { rows: albumRows } = await db.execute(
          'SELECT id FROM albums WHERE name = ?',
          [name],
        );
        const albumId = albumRows[0]?.id;
        if (!albumId) continue;

        const stats = await _indexNomediaFolderFiles(path, albumId, now);

        // Update the album row with real counts/cover/timestamp
        if (stats.count > 0) {
          await db.execute(
            `UPDATE albums
             SET media_count       = ?,
                 photo_count       = ?,
                 video_count       = ?,
                 cover_uri         = COALESCE(?, cover_uri),
                 last_modified_at  = COALESCE(?, last_modified_at),
                 last_scanned_at   = ?
             WHERE id = ?`,
            [
              stats.count,
              stats.photoCount,
              stats.videoCount,
              stats.coverUri,
              stats.lastModifiedAt,
              now,
              albumId,
            ],
          );
        }
      } catch (err) {
        console.warn('[MediaIndexer] indexNomediaFolder error for', name, err);
      }
    }

    return discovered.length;
  } catch (err) {
    console.warn('[MediaIndexer] nomedia scan error:', err);
    return 0;
  }
}

/**
 * Recursively collect all media file entries from a directory tree.
 *
 * @param {string} dirPath   — absolute path to scan
 * @returns {Promise<Array<{ name, path, size, mtime, isDirectory }>>}
 */
async function _collectMediaEntries(dirPath) {
  let results = [];
  try {
    const entries = await RNFS.readDir(dirPath);
    for (const e of entries) {
      if (e.isDirectory()) {
        // Skip known system / cache dirs but recurse into everything else
        if (NOMEDIA_SKIP_DIRS.has(e.name) || e.name.startsWith('.')) continue;
        const sub = await _collectMediaEntries(e.path);
        results = results.concat(sub);
      } else {
        const ext = e.name.split('.').pop()?.toLowerCase() ?? '';
        if (IMAGE_EXTS.has(ext) || VIDEO_EXTS.has(ext)) {
          results.push(e);
        }
      }
    }
  } catch {
    // Permission denied or path does not exist — skip silently.
  }
  return results;
}

/**
 * Scan all image/video files inside a .nomedia folder **and its subdirectories**
 * and upsert them into the media_index table using file:// URIs.
 *
 * Since CameraRoll never sees these files, we drive their metadata entirely
 * from RNFS stat data.  Width/height are left at 0 and will be filled in
 * lazily by ImageCache when the file is first rendered.
 *
 * @param {string} dirPath   — absolute path of the .nomedia folder
 * @param {number} albumId   — SQLite id of the parent album row
 * @param {number} now       — Unix ms timestamp for indexed_at / scanned_at
 * @returns {{ count, photoCount, videoCount, coverUri, lastModifiedAt }}
 */
async function _indexNomediaFolderFiles(dirPath, albumId, now) {
  const mediaEntries = await _collectMediaEntries(dirPath);

  if (!mediaEntries.length) {
    return {
      count: 0,
      photoCount: 0,
      videoCount: 0,
      coverUri: null,
      lastModifiedAt: null,
    };
  }

  // Sort newest-first so the cover is the most recent file
  const sorted = [...mediaEntries].sort((a, b) => {
    const ta =
      a.mtime instanceof Date ? a.mtime.getTime() : Number(a.mtime) || 0;
    const tb =
      b.mtime instanceof Date ? b.mtime.getTime() : Number(b.mtime) || 0;
    return tb - ta;
  });

  let photoCount = 0;
  let videoCount = 0;
  let latestMtime = 0;

  // Build media items in the shape MediaIndexService.batchUpsert expects
  const items = sorted.map(e => {
    const ext = e.name.split('.').pop()?.toLowerCase() ?? '';
    const isVideo = VIDEO_EXTS.has(ext);
    const mtime =
      e.mtime instanceof Date ? e.mtime.getTime() : Number(e.mtime) || null;

    if (isVideo) videoCount++;
    else photoCount++;
    if (mtime && mtime > latestMtime) latestMtime = mtime;

    return {
      uri: `file://${e.path}`,
      filename: e.name,
      fileSize: parseInt(e.size, 10) || 0,
      width: 0,
      height: 0,
      duration: null,
      extension: ext,
      type: isVideo ? 'video' : 'image',
      // timestamp is Unix seconds (CameraRoll convention)
      timestamp: mtime ? Math.floor(mtime / 1000) : null,
      modifiedAt: mtime,
    };
  });

  await MediaIndexService.batchUpsert(items, albumId);

  return {
    count: sorted.length,
    photoCount,
    videoCount,
    coverUri: `file://${sorted[0].path}`,
    lastModifiedAt: latestMtime || null,
  };
}

/**
 * Recursive directory walker.
 * Adds an entry to `results` for every directory that contains a .nomedia file.
 * Does NOT recurse into directories that are themselves nomedia-hidden.
 */
async function _walkForNomedia(dirPath, results, depth) {
  if (depth > NOMEDIA_MAX_DEPTH) return;
  try {
    const entries = await RNFS.readDir(dirPath);
    const hasNomedia = entries.some(
      e => e.name === '.nomedia' && !e.isDirectory(),
    );
    if (hasNomedia) {
      const name = dirPath.split('/').filter(Boolean).pop() ?? dirPath;
      results.push({ name, path: dirPath });
      // Don’t recurse into a folder that is itself hidden—its children are
      // presumably also hidden by the same .nomedia contract.
      return;
    }
    for (const entry of entries) {
      if (
        entry.isDirectory() &&
        !NOMEDIA_SKIP_DIRS.has(entry.name) &&
        !entry.name.startsWith('.')
      ) {
        await _walkForNomedia(entry.path, results, depth + 1);
      }
    }
  } catch {
    // Permission denied or path does not exist — skip silently.
  }
}

// ─── In-memory transformations (unchanged) ────────────────────────────────────

/**
 * Return folder list sorted by item count (most content first).
 */
function getFoldersByCount(index) {
  if (!index?.folders) return [];
  return Object.values(index.folders).sort((a, b) => b.count - a.count);
}

/**
 * Return folder list sorted by the modification time of the latest file in
 * each folder — newest activity first.
 * Folders without a lastModified timestamp are pushed to the end.
 */
function getFoldersByLatest(index) {
  if (!index?.folders) return [];
  return Object.values(index.folders).sort((a, b) => {
    const ta = a.lastModified ?? 0;
    const tb = b.lastModified ?? 0;
    return tb - ta;
  });
}

/**
 * Group an array of MediaItems by year-month.
 * Returns [{ key: 'YYYY-MM', label: 'Month YYYY', items: [...] }] newest first.
 */
function groupByMonth(mediaItems = []) {
  const map = {};
  mediaItems.forEach(item => {
    const d = new Date((item.timestamp ?? 0) * 1000);
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(
      2,
      '0',
    )}`;
    if (!map[key]) {
      map[key] = {
        key,
        label: d.toLocaleString('default', { month: 'long', year: 'numeric' }),
        items: [],
      };
    }
    map[key].items.push(item);
  });
  return Object.values(map).sort((a, b) => b.key.localeCompare(a.key));
}

/**
 * Partition media items into { images: [], videos: [] }.
 */
function partitionByType(mediaItems = []) {
  return mediaItems.reduce(
    (acc, item) => {
      const bucket = item.type?.includes('video') ? 'videos' : 'images';
      acc[bucket].push(item);
      return acc;
    },
    { images: [], videos: [] },
  );
}

/**
 * Simple filename / album name search.
 */
function search(mediaItems = [], query = '') {
  const q = query.trim().toLowerCase();
  if (!q) return mediaItems;
  return mediaItems.filter(
    it =>
      it.filename?.toLowerCase().includes(q) ||
      it.albumName?.toLowerCase().includes(q),
  );
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const MediaIndexer = {
  buildIndex,
  invalidate,
  pruneOrphans,
  scanNomediaFolders,
  reindexFolderByPath,
  getFoldersByCount,
  getFoldersByLatest,
  groupByMonth,
  partitionByType,
  search,
};
