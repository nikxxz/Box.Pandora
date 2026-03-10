/**
 * ThumbnailCache
 *
 * Resolves full-res content:// URIs to compact JPEG thumbnails.
 *
 * Three-level cache (Simple Gallery pattern):
 *   L1: JS-side Map<uri, thumbUri>  — sync, zero-allocation hot path
 *   L2: SQLite media_index.thumb_uri — survives app restarts; populated after
 *       first resolution so subsequent folder opens need ZERO bridge calls
 *   L3: Native disk cache (cacheDir/thumbnails/{mediaId}.jpg) — populated by
 *       MediaStoreModule.getThumbnailUriBatch (Android API 29+ loadThumbnail)
 *
 * Key change vs. previous version:
 *   batchResolveThumbnailUris / streamResolveThumbnailUris now make ONE native
 *   bridge call (getThumbnailUriBatch) instead of N concurrent individual calls.
 *   This eliminates N×bridge-overhead during first folder open.
 *
 * On iOS every call resolves with the original URI (expo-image handles scaling).
 */

import { Platform } from 'react-native';
import { MediaStoreModule } from '../media/MediaStoreModule';
import { MediaIndexService } from '../database/MediaIndexService';
import { DatabaseService } from '../database/DatabaseService';

// ─── Configuration ────────────────────────────────────────────────────────────

const THUMB_SIZE = 320;

// ─── L1 JS-side cache ─────────────────────────────────────────────────────────

/** uri → thumbUri  (both strings; on iOS thumbUri === uri) */
const _cache = new Map();

// ─── SQLite persistence (write-through) ───────────────────────────────────────

/**
 * Flush resolved {uri → thumbUri} pairs to SQLite (L2).
 * Fire-and-forget — never blocks the UI.
 */
function _persistBatch(resolvedMap) {
  if (!resolvedMap.size) return;
  const items = [];
  for (const [uri, thumbUri] of resolvedMap) {
    // Only persist when the thumb is different from the original (i.e. actually resolved)
    if (thumbUri && thumbUri !== uri) {
      items.push({ uri, thumbUri });
    }
  }
  if (items.length) {
    MediaIndexService.batchSetThumbUris(items).catch(() => {});
  }
}

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Pre-populate L1 cache from SQLite for a set of URIs.
 *
 * Call this once per folder open (before streaming resolution) so that items
 * with a previously-resolved thumb_uri display thumbnails on the FIRST render
 * with zero bridge calls.
 *
 * @param {string[]} uris
 * @returns {Promise<void>}
 */
export async function warmCacheFromDb(uris) {
  if (!uris.length || Platform.OS !== 'android') return;
  // Pull thumb URIs from SQLite for these specific URIs in one query
  try {
    const db = DatabaseService.getDb();
    const placeholders = uris.map(() => '?').join(',');
    const { rows } = await db.execute(
      `SELECT uri, thumb_uri FROM media_index WHERE uri IN (${placeholders}) AND thumb_uri IS NOT NULL`,
      uris,
    );
    for (const r of rows) {
      if (!_cache.has(r.uri)) {
        _cache.set(r.uri, r.thumb_uri);
      }
    }
  } catch {
    // Non-fatal — L1 miss will fall through to native batch
  }
}

/**
 * Resolve a single URI to its thumbnail URI.
 * Checks L1 cache first (sync). Falls back to the native single-URI call.
 * Prefer batchResolveThumbnailUris for sets of items.
 *
 * @param {string} uri
 * @returns {Promise<string>}
 */
export async function resolveThumbnailUri(uri) {
  if (!uri) return uri;
  if (Platform.OS !== 'android') return uri;
  if (_cache.has(uri)) return _cache.get(uri);

  let thumbUri = uri;
  try {
    thumbUri = await MediaStoreModule.getThumbnailUri(uri, THUMB_SIZE);
  } catch {
    // Non-fatal fallback
  }

  const resolved = thumbUri ?? uri;
  _cache.set(uri, resolved);
  // Persist single resolution to SQLite
  _persistBatch(new Map([[uri, resolved]]));
  return resolved;
}

/**
 * Batch-resolve an array of URIs using ONE native bridge call.
 *
 * Returns a parallel array: result[i] is the thumb URI for uris[i].
 * On iOS, resolves synchronously with the original URIs.
 *
 * Flow:
 *   1. L1 hits (JS Map) — returned immediately, zero cost
 *   2. SQLite hits — already in L1 after warmCacheFromDb()
 *   3. L1 misses → single getThumbnailUriBatch() native call → L3 disk
 *   4. Write-through: resolved results persisted to SQLite (L2)
 *
 * @param {string[]} uris
 * @returns {Promise<string[]>}
 */
export async function batchResolveThumbnailUris(uris = []) {
  if (!uris.length) return [];
  if (Platform.OS !== 'android') return [...uris];

  const results = new Array(uris.length);
  const misses = []; // { idx, uri }

  for (let i = 0; i < uris.length; i++) {
    const uri = uris[i];
    if (!uri || _cache.has(uri)) {
      results[i] = uri ? _cache.get(uri) : uri;
    } else {
      misses.push({ idx: i, uri });
    }
  }

  if (!misses.length) return results;

  // ONE bridge call for all misses
  try {
    const missUris = misses.map(m => m.uri);
    const batchResult = await MediaStoreModule.getThumbnailUriBatch(missUris, THUMB_SIZE);
    const toWrite = new Map();
    for (const { idx, uri } of misses) {
      const thumb = batchResult[uri] ?? uri;
      results[idx] = thumb;
      _cache.set(uri, thumb);
      toWrite.set(uri, thumb);
    }
    _persistBatch(toWrite);
  } catch {
    // getThumbnailUriBatch unavailable (old build) — fall back to individual calls
    for (const { idx, uri } of misses) {
      results[idx] = await resolveThumbnailUri(uri);
    }
  }

  return results;
}

/**
 * Streaming batch resolver — resolves thumbnails in ONE native batch call and
 * calls `onBatch` once with all results (hits from L1 immediately, then the
 * single native call result).
 *
 * Replaces the previous fan-out worker model. The grid sees two onBatch calls
 * at most per page:
 *   1. Immediate: L1 cache hits (zero latency)
 *   2. One async: native batch for misses (single bridge roundtrip)
 *
 * @param {string[]} uris
 * @param {(batch: Map<string,string>) => void} onBatch
 * @returns {Promise<void>}
 */
export async function streamResolveThumbnailUris(uris, onBatch) {
  if (!uris.length || !onBatch) return;

  // Flush L1 hits synchronously
  const hits = new Map();
  const misses = [];
  for (const uri of uris) {
    if (!uri) continue;
    if (_cache.has(uri)) {
      hits.set(uri, _cache.get(uri));
    } else {
      misses.push(uri);
    }
  }
  if (hits.size) onBatch(hits);
  if (!misses.length || Platform.OS !== 'android') return;

  // ONE native call for all misses
  try {
    const batchResult = await MediaStoreModule.getThumbnailUriBatch(misses, THUMB_SIZE);
    const resolved = new Map();
    const toWrite = new Map();
    for (const uri of misses) {
      const thumb = batchResult[uri] ?? uri;
      resolved.set(uri, thumb);
      _cache.set(uri, thumb);
      toWrite.set(uri, thumb);
    }
    _persistBatch(toWrite);
    onBatch(resolved);
  } catch {
    // Fallback: fan-out individual calls (old native build without getThumbnailUriBatch)
    const CONCURRENCY = 8;
    const pending = new Map();
    let cursor = 0;
    async function worker() {
      while (cursor < misses.length) {
        const uri = misses[cursor++];
        const thumb = await resolveThumbnailUri(uri);
        pending.set(uri, thumb);
        if (pending.size >= CONCURRENCY) {
          onBatch(new Map(pending));
          pending.clear();
        }
      }
    }
    await Promise.all(
      Array.from({ length: Math.min(CONCURRENCY, misses.length) }, worker),
    );
    if (pending.size) onBatch(new Map(pending));
  }
}

/**
 * Synchronous peek — returns the cached thumbUri without triggering resolution.
 * @param {string} uri
 * @returns {string | null}  null means not yet resolved
 */
export function getCachedThumbUri(uri) {
  if (!uri) return null;
  return _cache.has(uri) ? _cache.get(uri) : null;
}

/**
 * Clear the JS-side L1 cache (e.g. after clearing app storage or for testing).
 * Does NOT delete the disk cache or SQLite entries.
 */
export function clearThumbCache() {
  _cache.clear();
}
