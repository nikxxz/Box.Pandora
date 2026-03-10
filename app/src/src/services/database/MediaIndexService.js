/**
 * MediaIndexService
 *
 * Handles all reads/writes to the `media_index` table.
 *
 * Key design decisions:
 *  • upsert preserves user metadata (rating, favorite, hidden, notes) on conflict
 *  • batchUpsert uses executeBatch — single native round-trip for bulk indexing
 *  • query() is driven by a WHERE builder so every filter combination is handled
 *    without string concatenation vulnerabilities
 *  • Sort column is validated against an allowlist before use in ORDER BY
 */

import { DatabaseService } from './DatabaseService';
import RNFS from 'react-native-fs';

// ─── Sort column allowlist ────────────────────────────────────────────────────

const SORT_COLUMN = {
  date: 'm.device_created_at',
  name: 'm.filename COLLATE NOCASE',
  size: 'm.file_size',
  rating: 'm.rating',
  duration: 'm.duration',
};

// ─── Upsert SQL (reused in single + batch insert) ────────────────────────────

const UPSERT_SQL = `
  INSERT INTO media_index
    (uri, filename, album_id, file_size, width, height, duration,
     extension, media_type, device_created_at, device_modified_at,
     indexed_at, scanned_at)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  ON CONFLICT(uri) DO UPDATE SET
    filename           = excluded.filename,
    album_id           = excluded.album_id,
    file_size          = excluded.file_size,
    width              = excluded.width,
    height             = excluded.height,
    duration           = excluded.duration,
    extension          = excluded.extension,
    media_type         = excluded.media_type,
    device_created_at  = excluded.device_created_at,
    device_modified_at = excluded.device_modified_at,
    scanned_at         = excluded.scanned_at
`;
// NOTE: rating / favorite / hidden / notes are intentionally excluded from the
// UPDATE SET — they are user-set and must survive re-indexing.

function buildParams(item, albumId, now) {
  return [
    item.uri,
    item.filename ?? '',
    albumId ?? null,
    item.fileSize ?? 0,
    item.width ?? 0,
    item.height ?? 0,
    item.duration ?? null, // null for images
    item.extension ?? '',
    item.type ?? 'image',
    item.timestamp ? item.timestamp * 1000 : null, // CameraRoll gives Unix seconds → ms
    item.modifiedAt ?? null,
    now, // indexed_at  (ignored on UPDATE by the ON CONFLICT clause)
    now, // scanned_at
  ];
}

// ─── Write API ───────────────────────────────────────────────────────────────

/**
 * Insert or update a single media item.
 */
async function upsert(item, albumId = null) {
  const db = DatabaseService.getDb();
  await db.execute(UPSERT_SQL, buildParams(item, albumId, Date.now()));
}

/**
 * Lightweight upsert used by ImageCache to persist dimension/size metadata.
 *
 * Only writes: filename, file_size, width, height, extension, media_type.
 * On conflict it only updates non-zero dimension fields — all other columns
 * (album_id, timestamps, rating, favorite, hidden, notes) are preserved.
 *
 * @param {string} uri
 * @param {{ filename?, fileSize?, width?, height?, extension?, type? }} meta
 */
async function upsertDimensions(uri, meta) {
  const db = DatabaseService.getDb();
  const now = Date.now();

  await db.execute(
    `INSERT INTO media_index
       (uri, filename, file_size, width, height, extension, media_type,
        indexed_at, scanned_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(uri) DO UPDATE SET
       file_size  = CASE WHEN excluded.file_size > 0  THEN excluded.file_size ELSE file_size END,
       width      = CASE WHEN excluded.width > 0      THEN excluded.width     ELSE width     END,
       height     = CASE WHEN excluded.height > 0     THEN excluded.height    ELSE height    END,
       scanned_at = excluded.scanned_at`,
    [
      uri,
      meta.filename ?? '',
      meta.fileSize ?? 0,
      meta.width ?? 0,
      meta.height ?? 0,
      meta.extension ?? '',
      meta.type ?? 'image',
      now,
      now,
    ],
  );
}

/**
 * Bulk upsert — uses executeBatch so all rows go in one native call.
 * Dramatically faster than looping upsert() for initial indexing.
 *
 * @param {object[]} items  — normalised MediaItem objects from MediaService
 * @param {number|null} albumId
 */
async function batchUpsert(items, albumId = null) {
  if (!items.length) return;
  const db = DatabaseService.getDb();
  const now = Date.now();
  const batch = items.map(item => [
    UPSERT_SQL,
    buildParams(item, albumId, now),
  ]);
  await db.executeBatch(batch);
}

/**
 * Mark a set of URIs as "seen now" without re-indexing their file attributes.
 * Useful for incremental scans that confirm file existence cheaply.
 */
async function touchScannedAt(uris = []) {
  if (!uris.length) return;
  const db = DatabaseService.getDb();
  const now = Date.now();
  const batch = uris.map(uri => [
    'UPDATE media_index SET scanned_at = ? WHERE uri = ?',
    [now, uri],
  ]);
  await db.executeBatch(batch);
}

// ─── User metadata setters ────────────────────────────────────────────────────

async function setFavorite(uri, value) {
  const db = DatabaseService.getDb();
  await db.execute('UPDATE media_index SET favorite = ? WHERE uri = ?', [
    value ? 1 : 0,
    uri,
  ]);
}

async function setRating(uri, rating) {
  const db = DatabaseService.getDb();
  const val = Math.max(0, Math.min(5, Math.round(rating)));
  await db.execute('UPDATE media_index SET rating = ? WHERE uri = ?', [
    val,
    uri,
  ]);
}

async function setHidden(uri, value) {
  const db = DatabaseService.getDb();
  await db.execute('UPDATE media_index SET hidden = ? WHERE uri = ?', [
    value ? 1 : 0,
    uri,
  ]);
}

async function setNotes(uri, notes) {
  const db = DatabaseService.getDb();
  await db.execute('UPDATE media_index SET notes = ? WHERE uri = ?', [
    notes ?? null,
    uri,
  ]);
}

// ─── Bulk user metadata ───────────────────────────────────────────────────────

async function batchSetFavorite(uris = [], value) {
  if (!uris.length) return;
  const db = DatabaseService.getDb();
  const val = value ? 1 : 0;
  await db.executeBatch(
    uris.map(uri => [
      'UPDATE media_index SET favorite = ? WHERE uri = ?',
      [val, uri],
    ]),
  );
}

async function batchSetHidden(uris = [], value) {
  if (!uris.length) return;
  const db = DatabaseService.getDb();
  const val = value ? 1 : 0;
  await db.executeBatch(
    uris.map(uri => [
      'UPDATE media_index SET hidden = ? WHERE uri = ?',
      [val, uri],
    ]),
  );
}

// ─── Read API ─────────────────────────────────────────────────────────────────

/**
 * Fetch a single row by URI.
 * @returns {object|null}
 */
async function getByUri(uri) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    `SELECT m.*, a.name AS album_name
     FROM media_index m
     LEFT JOIN albums a ON a.id = m.album_id
     WHERE m.uri = ?`,
    [uri],
  );
  return rows[0] ?? null;
}

/**
 * Query media with arbitrary filters and sort.
 *
 * @param {MediaFilters} filters
 * @param {MediaSort}    sort    — { by: 'date'|'name'|'size'|'rating'|'duration', order: 'ASC'|'DESC' }
 * @param {MediaPage}    page    — { limit: number, offset: number }
 * @returns {object[]} — rows from media_index joined with album name
 */
async function query(filters = {}, sort = {}, page = {}) {
  const db = DatabaseService.getDb();
  const { conditions, params } = buildWhere(filters);

  const sortCol = SORT_COLUMN[sort.by] ?? SORT_COLUMN.date;
  const sortDir = sort.order === 'ASC' ? 'ASC' : 'DESC';
  const limit = page.limit ?? 60; // pass limit:0 or limit:null for no limit
  const offset = page.offset ?? 0;
  const hasLimit = limit != null && limit > 0;

  const sql = `
    SELECT
      m.*,
      a.name   AS album_name,
      a.hidden AS album_hidden,
      a.pinned AS album_pinned
    FROM   media_index m
    LEFT JOIN albums a ON a.id = m.album_id
    WHERE  ${conditions.join(' AND ')}
    ORDER  BY ${sortCol} ${sortDir}
    ${hasLimit ? 'LIMIT  ? OFFSET ?' : ''}
  `;

  const { rows } = await db.execute(
    sql,
    hasLimit ? [...params, limit, offset] : params,
  );
  return rows;
}

/**
 * Total row count for a given filter set (for pagination UI).
 */
async function count(filters = {}) {
  const db = DatabaseService.getDb();
  const { conditions, params } = buildWhere(filters);

  const { rows } = await db.execute(
    `SELECT COUNT(*) AS total
     FROM   media_index m
     LEFT JOIN albums a ON a.id = m.album_id
     WHERE  ${conditions.join(' AND ')}`,
    params,
  );
  return rows[0]?.total ?? 0;
}

/**
 * Return URIs that were last scanned before `timestampMs`.
 * Used by ScanService to find stale/deleted files.
 */
async function getUrisScannedBefore(timestampMs, albumId = null) {
  const db = DatabaseService.getDb();
  const conditions = ['scanned_at < ?'];
  const params = [timestampMs];

  if (albumId != null) {
    conditions.push('album_id = ?');
    params.push(albumId);
  }

  const { rows } = await db.execute(
    `SELECT uri FROM media_index WHERE ${conditions.join(' AND ')}`,
    params,
  );
  return rows.map(r => r.uri);
}

/**
 * Return all URIs currently flagged hidden.
 * Used at startup to hydrate MediaContext.hiddenUris.
 */
async function getHiddenUris() {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT uri FROM media_index WHERE hidden = 1',
  );
  return rows.map(r => r.uri);
}

/**
 * Scan every URI in media_index against the real filesystem / MediaStore and
 * delete rows whose backing file no longer exists.
 *
 * URI handling:
 *  • file://  — RNFS.exists() on the decoded path
 *  • /        — RNFS.exists() on the bare path
 *  • content: — RNFS.stat() call; throws when the MediaStore entry is gone
 *
 * Orphaned rows are removed via removeByUris(). Because media_tags uses
 * ON DELETE CASCADE, associated tag assignments are cleaned up automatically.
 *
 * @param {{ batchSize?: number, onProgress?: (done: number, total: number) => void }} options
 * @returns {Promise<{ checked: number, removed: number }>}
 */
async function pruneOrphanedEntries({ batchSize = 40, onProgress } = {}) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute('SELECT uri FROM media_index');
  const uris = rows.map(r => r.uri);
  const total = uris.length;
  const orphaned = [];

  for (let i = 0; i < uris.length; i += batchSize) {
    const batch = uris.slice(i, i + batchSize);
    await Promise.all(
      batch.map(async uri => {
        try {
          if (uri.startsWith('file://')) {
            const path = decodeURIComponent(uri.slice('file://'.length));
            const exists = await RNFS.exists(path);
            if (!exists) orphaned.push(uri);
          } else if (uri.startsWith('/')) {
            const exists = await RNFS.exists(uri);
            if (!exists) orphaned.push(uri);
          } else if (uri.startsWith('content://')) {
            // RNFS.stat() resolves the content URI via MediaStore;
            // it throws when the underlying file no longer exists.
            await RNFS.stat(uri);
          }
          // Unknown schemes — leave untouched.
        } catch {
          orphaned.push(uri);
        }
      }),
    );
    onProgress?.(Math.min(i + batchSize, total), total);
    // Yield between batches so the JS thread stays responsive.
    await new Promise(r => setTimeout(r, 0));
  }

  if (orphaned.length) {
    await removeByUris(orphaned);
  }

  return { checked: total, removed: orphaned.length };
}

/**
 * Delete rows by URI — called when files are confirmed missing on device.
 */
async function removeByUris(uris = []) {
  if (!uris.length) return;
  const db = DatabaseService.getDb();
  await db.executeBatch(
    uris.map(uri => ['DELETE FROM media_index WHERE uri = ?', [uri]]),
  );
}

/**
 * Copy user metadata (rating, favorite, hidden, notes) and tag assignments
 * from sourceUri to destUri. Called after a copy/move so the new file inherits
 * all user-applied metadata without requiring a re-tag.
 */
async function copyUserMetadata(sourceUri, destUri) {
  if (!sourceUri || !destUri || sourceUri === destUri) return;
  const db = DatabaseService.getDb();
  const now = Date.now();

  // Copy scalar user metadata fields
  await db.execute(
    `UPDATE media_index
     SET rating   = (SELECT rating   FROM media_index WHERE uri = ?),
         favorite = (SELECT favorite FROM media_index WHERE uri = ?),
         hidden   = (SELECT hidden   FROM media_index WHERE uri = ?),
         notes    = (SELECT notes    FROM media_index WHERE uri = ?)
     WHERE uri = ?`,
    [sourceUri, sourceUri, sourceUri, sourceUri, destUri],
  );

  // Copy media_tags junction rows — INSERT OR IGNORE to skip dupes
  await db.execute(
    `INSERT OR IGNORE INTO media_tags (media_uri, tag_id, tagged_at)
     SELECT ?, tag_id, ? FROM media_tags WHERE media_uri = ?`,
    [destUri, now, sourceUri],
  );
}

// ─── WHERE builder (internal) ─────────────────────────────────────────────────

/**
 * Builds a safe parameterised WHERE clause from a filters object.
 *
 * Supported filter keys:
 *   albumId        {number}   — filter to one album
 *   mediaType      {string}   — 'image' | 'video' | 'audio' | 'All'
 *   favorite       {boolean}
 *   ratingMin      {number}   — 1–5 (0 means no filter)
 *   showHidden     {boolean}  — if false, hides both hidden files and hidden albums
 *   sizeMin        {number}   — bytes
 *   sizeMax        {number}   — bytes
 *   durationMin    {number}   — seconds
 *   durationMax    {number}   — seconds
 *   widthMin       {number}   — px
 *   heightMin      {number}   — px
 *   tags           {number[]} — array of tag IDs (OR match: file has ANY of these tags)
 *   createdAfter   {number}   — Unix ms
 *   createdBefore  {number}   — Unix ms
 *   sinceScannedAt {number}   — Unix ms (for incremental scan helpers)
 */
function buildWhere(filters) {
  const conditions = ['1=1'];
  const params = [];

  if (filters.albumId != null) {
    conditions.push('m.album_id = ?');
    params.push(filters.albumId);
  }

  if (filters.mediaType && filters.mediaType !== 'All') {
    conditions.push('m.media_type = ?');
    params.push(filters.mediaType);
  }

  if (filters.favorite === true) {
    conditions.push('m.favorite = 1');
  }

  if (filters.ratingMin != null && filters.ratingMin > 0) {
    conditions.push('m.rating >= ?');
    params.push(filters.ratingMin);
  }

  // Hide hidden files + files inside hidden albums unless caller opts in
  if (!filters.showHidden) {
    conditions.push('m.hidden = 0');
    conditions.push('(a.hidden IS NULL OR a.hidden = 0)');
  }

  if (filters.sizeMin != null) {
    conditions.push('m.file_size >= ?');
    params.push(filters.sizeMin);
  }
  if (filters.sizeMax != null) {
    conditions.push('m.file_size <= ?');
    params.push(filters.sizeMax);
  }

  if (filters.durationMin != null) {
    conditions.push('m.duration >= ?');
    params.push(filters.durationMin);
  }
  if (filters.durationMax != null) {
    conditions.push('m.duration <= ?');
    params.push(filters.durationMax);
  }

  if (filters.widthMin != null) {
    conditions.push('m.width >= ?');
    params.push(filters.widthMin);
  }
  if (filters.heightMin != null) {
    conditions.push('m.height >= ?');
    params.push(filters.heightMin);
  }

  // Tag filter — file must have AT LEAST ONE of the specified tag IDs
  if (filters.tags && filters.tags.length > 0) {
    const ph = filters.tags.map(() => '?').join(',');
    conditions.push(
      `m.uri IN (SELECT media_uri FROM media_tags WHERE tag_id IN (${ph}))`,
    );
    params.push(...filters.tags);
  }

  if (filters.createdAfter != null) {
    conditions.push('m.device_created_at >= ?');
    params.push(filters.createdAfter);
  }
  if (filters.createdBefore != null) {
    conditions.push('m.device_created_at <= ?');
    params.push(filters.createdBefore);
  }

  if (filters.sinceScannedAt != null) {
    conditions.push('m.scanned_at >= ?');
    params.push(filters.sinceScannedAt);
  }

  return { conditions, params };
}

// ─── Export ───────────────────────────────────────────────────────────────────

// ─── Metrics sync ─────────────────────────────────────────────────────────────

/**
 * Sync device-measured metrics for a single media item into SQLite.
 *
 * Only writes non-zero / non-null values — existing data is preserved when the
 * caller does not supply a field.  Never overwrites user metadata (rating,
 * favorite, hidden, notes).
 *
 * @param {string} uri
 * @param {{ fileSize?: number, width?: number, height?: number, duration?: number | null, filename?: string, extension?: string, mediaType?: string }} meta
 */
async function syncMetrics(uri, meta = {}) {
  const db = DatabaseService.getDb();
  const now = Date.now();

  const setClauses = ['scanned_at = ?'];
  const params = [now];

  if (meta.fileSize != null && meta.fileSize > 0) {
    setClauses.push('file_size = ?');
    params.push(meta.fileSize);
  }
  if (meta.width != null && meta.width > 0) {
    setClauses.push('width = ?');
    params.push(meta.width);
  }
  if (meta.height != null && meta.height > 0) {
    setClauses.push('height = ?');
    params.push(meta.height);
  }
  if (meta.duration != null) {
    setClauses.push('duration = ?');
    params.push(meta.duration);
  }
  if (meta.filename != null && meta.filename) {
    setClauses.push('filename = ?');
    params.push(meta.filename);
  }
  if (meta.extension != null && meta.extension) {
    setClauses.push('extension = ?');
    params.push(meta.extension);
  }
  if (meta.mediaType != null && meta.mediaType) {
    setClauses.push('media_type = ?');
    params.push(meta.mediaType);
  }

  params.push(uri);

  // Ensure the row exists first (stub insert — user metadata defaults used on fresh rows)
  await db.execute(
    `INSERT OR IGNORE INTO media_index
       (uri, filename, extension, media_type, indexed_at, scanned_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
    [
      uri,
      meta.filename ?? '',
      meta.extension ?? '',
      meta.mediaType ?? 'image',
      now,
      now,
    ],
  );

  // Now update just the metric columns
  await db.execute(
    `UPDATE media_index SET ${setClauses.join(', ')} WHERE uri = ?`,
    params,
  );
}

/**
 * Sync metrics for a batch of items in a single native round-trip.
 *
 * @param {Array<{ uri: string, fileSize?: number, width?: number, height?: number, duration?: number | null, filename?: string, extension?: string, mediaType?: string }>} items
 */
async function batchSyncMetrics(items = []) {
  if (!items.length) return;
  const db = DatabaseService.getDb();
  const now = Date.now();

  // Two passes: one INSERT OR IGNORE batch for stub rows, one UPDATE batch for metrics
  const insertBatch = items
    .filter(it => it.uri)
    .map(it => [
      `INSERT OR IGNORE INTO media_index
         (uri, filename, extension, media_type, indexed_at, scanned_at)
       VALUES (?, ?, ?, ?, ?, ?)`,
      [
        it.uri,
        it.filename ?? '',
        it.extension ?? '',
        it.mediaType ?? 'image',
        now,
        now,
      ],
    ]);

  const updateBatch = items
    .filter(it => it.uri)
    .map(it => {
      const setClauses = ['scanned_at = ?'];
      const params = [now];

      if (it.fileSize != null && it.fileSize > 0) {
        setClauses.push('file_size = ?');
        params.push(it.fileSize);
      }
      if (it.width != null && it.width > 0) {
        setClauses.push('width = ?');
        params.push(it.width);
      }
      if (it.height != null && it.height > 0) {
        setClauses.push('height = ?');
        params.push(it.height);
      }
      if (it.duration != null) {
        setClauses.push('duration = ?');
        params.push(it.duration);
      }
      if (it.filename != null && it.filename) {
        setClauses.push('filename = ?');
        params.push(it.filename);
      }
      if (it.extension != null && it.extension) {
        setClauses.push('extension = ?');
        params.push(it.extension);
      }
      if (it.mediaType != null && it.mediaType) {
        setClauses.push('media_type = ?');
        params.push(it.mediaType);
      }

      params.push(it.uri);
      return [
        `UPDATE media_index SET ${setClauses.join(', ')} WHERE uri = ?`,
        params,
      ];
    });

  await db.executeBatch([...insertBatch, ...updateBatch]);
}

/**
 * Rename a media item in SQLite — updates `filename` and `scanned_at`.
 * Call this after a successful MediaStore rename on-device.
 *
 * @param {string} uri
 * @param {string} newFilename
 */
async function renameByUri(uri, newFilename) {
  const db = DatabaseService.getDb();
  const ext = newFilename.includes('.')
    ? newFilename.split('.').pop().toLowerCase()
    : '';
  await db.execute(
    'UPDATE media_index SET filename = ?, extension = ?, scanned_at = ? WHERE uri = ?',
    [newFilename, ext, Date.now(), uri],
  );
}

/**
 * Return all { filename, uri } pairs for a given album.
 * Used for conflict detection before copy/move operations.
 *
 * @param {number} albumId
 * @returns {Promise<Array<{ filename: string, uri: string }>>}
 */
async function getFilenamesAndUrisByAlbumId(albumId) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT filename, uri FROM media_index WHERE album_id = ?',
    [albumId],
  );
  return rows.map(r => ({ filename: r.filename ?? '', uri: r.uri ?? '' }));
}

// ─── Thumbnail URI persistence ────────────────────────────────────────────────

/**
 * Persist resolved thumb URIs for a batch of media items.
 *
 * Called by ThumbnailCache after batch resolution so subsequent folder opens
 * can serve thumb URIs directly from SQLite (zero bridge calls on warm start).
 *
 * @param {Array<{ uri: string, thumbUri: string }>} items
 */
async function batchSetThumbUris(items = []) {
  if (!items.length) return;
  const db = DatabaseService.getDb();
  await db.executeBatch(
    items.map(({ uri, thumbUri }) => [
      'UPDATE media_index SET thumb_uri = ? WHERE uri = ?',
      [thumbUri, uri],
    ]),
  );
}

/**
 * Return a Map<uri, thumbUri> for all rows in a given album that already
 * have a persisted thumb_uri. Used by loadAlbumMedia for the DB-first path.
 *
 * @param {number} albumId
 * @returns {Promise<Map<string, string>>}
 */
async function getThumbUrisByAlbumId(albumId) {
  const db = DatabaseService.getDb();
  const { rows } = await db.execute(
    'SELECT uri, thumb_uri FROM media_index WHERE album_id = ? AND thumb_uri IS NOT NULL',
    [albumId],
  );
  const map = new Map();
  for (const r of rows) {
    map.set(r.uri, r.thumb_uri);
  }
  return map;
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const MediaIndexService = {
  // Write
  upsert,
  batchUpsert,
  upsertDimensions,
  touchScannedAt,
  setFavorite,
  setRating,
  setHidden,
  setNotes,
  batchSetFavorite,
  batchSetHidden,
  removeByUris,
  copyUserMetadata,
  pruneOrphanedEntries,
  // Metrics sync
  syncMetrics,
  batchSyncMetrics,
  renameByUri,
  // Read
  getByUri,
  getHiddenUris,
  getFilenamesAndUrisByAlbumId,
  query,
  count,
  getUrisScannedBefore,
  // Thumbnail URI persistence
  batchSetThumbUris,
  getThumbUrisByAlbumId,
};
