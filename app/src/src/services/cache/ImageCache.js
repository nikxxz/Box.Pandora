/**
 * ImageCache — Two-tier image metadata cache + expo-image integration.
 *
 * Tier 1: In-memory LRU map  (instant access, lost on app restart)
 * Tier 2: media_index SQLite  (survives restarts, no TTL — rows live until
 *                              the file is deleted from the device)
 * Tier 3: expo-image native cache  (memory + disk pixel cache managed by the
 *                                   native image pipeline; survives restarts)
 *
 * Stores lightweight metadata (dimensions, fileSize, type, filename)
 * so the grid can skip re-measuring images and avoid layout jumps.
 *
 * NOTE: evict() and clearAll() only clear the in-memory LRU.
 * The SQLite row is the persistent index and must NOT be deleted here —
 * only MediaIndexService.removeByUris() (called after physical file deletion)
 * should remove rows from media_index.
 */

import { Image as ExpoImage } from 'expo-image';
import { MediaIndexService } from '../database/MediaIndexService';

const MEMORY_MAX = 500; // max in-memory entries (bumped from 300: expo-image reduces per-entry cost)

// ─── In-memory LRU ───────────────────────────────────────────────────────────

// Map preserves insertion order; delete + re-insert to move to "recent" end
const memCache = new Map();

function lruPut(key, value) {
  if (memCache.has(key)) memCache.delete(key);
  if (memCache.size >= MEMORY_MAX) {
    memCache.delete(memCache.keys().next().value);
  }
  memCache.set(key, value);
}

function lruGet(key) {
  if (!memCache.has(key)) return null;
  const value = memCache.get(key);
  memCache.delete(key);
  memCache.set(key, value);
  return value;
}

// ─── SQLite row → ImageCache metadata shape ───────────────────────────────────

function _rowToMeta(row) {
  if (!row) return null;
  return {
    width: row.width ?? 0,
    height: row.height ?? 0,
    fileSize: row.file_size ?? 0,
    type: row.media_type ?? 'image',
    filename: row.filename ?? '',
    _storedAt: row.scanned_at ?? Date.now(),
  };
}

// ─── Public API ──────────────────────────────────────────────────────────────

/**
 * Persist metadata for a single URI.
 * Warms the LRU and writes a lightweight row into media_index (SQLite).
 *
 * @param {string} uri
 * @param {{ width, height, fileSize, type, filename }} metadata
 */
async function cacheMetadata(uri, metadata) {
  if (!uri) return;

  const entry = { ...metadata, _storedAt: Date.now() };
  lruPut(uri, entry);

  // Write to SQLite (fire-and-forget; errors are non-fatal)
  try {
    await MediaIndexService.upsertDimensions(uri, {
      filename: metadata.filename,
      fileSize: metadata.fileSize,
      width: metadata.width,
      height: metadata.height,
      extension: metadata.filename?.split('.').pop()?.toLowerCase() ?? '',
      type: metadata.type,
    });
  } catch (err) {
    console.warn('[ImageCache] SQLite write error:', err);
  }
}

/**
 * Retrieve metadata for a URI (memory-first, then SQLite).
 * Returns null on miss.
 */
async function getMetadata(uri) {
  if (!uri) return null;

  // 1. Memory hit (fastest path)
  const memHit = lruGet(uri);
  if (memHit) return memHit;

  // 2. SQLite hit
  try {
    const row = await MediaIndexService.getByUri(uri);
    const meta = _rowToMeta(row);
    if (meta) {
      lruPut(uri, meta); // warm LRU
      return meta;
    }
  } catch {
    // DB not ready yet or row missing — fall through to null
  }

  return null;
}

/**
 * Pre-cache a batch of media items (runs in background-friendly chunks).
 * Call this after fetching a page of media to warm the LRU + expo-image.
 *
 * Strategy:
 *  1. Warm metadata LRU (dimensions, type, filename) — synchronous per item
 *  2. Kick off expo-image prefetch for items not yet in the native cache
 *     so thumbnails decode instantly when they scroll into view.
 *
 * @param {Array<{ uri, width, height, fileSize, type, filename }>} items
 */
async function preCacheBatch(items = []) {
  // Only process items not already in LRU
  const uncached = items.filter(it => !lruGet(it.uri));
  if (!uncached.length) return;

  const CHUNK = 25;
  for (let i = 0; i < uncached.length; i += CHUNK) {
    const chunk = uncached.slice(i, i + CHUNK);
    await Promise.all(
      chunk.map(it =>
        cacheMetadata(it.uri, {
          width: it.width,
          height: it.height,
          fileSize: it.fileSize,
          type: it.type,
          filename: it.filename,
        }),
      ),
    );
    // Yield to the JS thread between chunks to avoid jank
    await new Promise(r => setTimeout(r, 0));
  }

  // Fire-and-forget: prefetch thumbnail URIs (NOT full-res content:// URIs)
  // into expo-image's native cache. Prefetching full-res images would cause
  // the native decode pipeline to process multi-megapixel images that will
  // never be displayed at that resolution in the grid, saturating decode
  // threads and competing with visible thumbnails.
  const thumbUris = uncached
    .filter(it => !it.type?.includes('video') && it.thumbUri)
    .map(it => it.thumbUri);
  if (thumbUris.length > 0) {
    prefetchUris(thumbUris);
  }
}

/**
 * Prefetch a list of URIs into expo-image's native memory+disk cache.
 * Non-blocking fire-and-forget — failures are silently ignored.
 *
 * @param {string[]} uris
 */
function prefetchUris(uris) {
  if (!uris?.length) return;
  // expo-image's prefetch accepts an array and handles concurrency internally
  ExpoImage.prefetch(uris).catch(() => {
    // Silent — prefetch failures are non-fatal
  });
}

/**
 * Evict a single URI from the in-memory LRU only.
 * The SQLite row is intentionally preserved — it is the persistent file index.
 * Call MediaIndexService.removeByUris() separately after physical file deletion.
 */
function evict(uri) {
  memCache.delete(uri);
}

/**
 * Clear all entries from the in-memory LRU only.
 * SQLite rows are NOT removed.
 */
function clearAll() {
  memCache.clear();
}

/**
 * Clear expo-image's native disk cache.
 * Call this from a "Clear Cache" settings action.
 * Also clears the in-memory LRU.
 *
 * @returns {Promise<boolean>} true if successful
 */
async function clearDiskCache() {
  memCache.clear();
  try {
    await ExpoImage.clearDiskCache();
    await ExpoImage.clearMemoryCache();
    return true;
  } catch (err) {
    console.warn('[ImageCache] clearDiskCache error:', err);
    return false;
  }
}

/**
 * Return memory tier statistics.
 */
function memStats() {
  return {
    size: memCache.size,
    capacity: MEMORY_MAX,
    utilization: `${Math.round((memCache.size / MEMORY_MAX) * 100)}%`,
  };
}

// ─── Export ──────────────────────────────────────────────────────────────────

export const ImageCache = {
  cacheMetadata,
  getMetadata,
  preCacheBatch,
  prefetchUris,
  evict,
  clearAll,
  clearDiskCache,
  memStats,
};
