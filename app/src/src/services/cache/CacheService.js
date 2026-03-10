/**
 * CacheService — AsyncStorage-backed persistent key-value cache.
 *
 * Features:
 *  • TTL-based expiry on every entry
 *  • Versioned keys (bump CACHE_VERSION to invalidate all caches on upgrade)
 *  • cache-aside helper (getOrSet)
 *  • Stats introspection
 */

import AsyncStorage from '@react-native-async-storage/async-storage';

const CACHE_VERSION = '1';
const KEY_PREFIX = `@pandora_v${CACHE_VERSION}::`;
const DEFAULT_TTL_MS = 5 * 60 * 1000;      // 5 minutes
const ALBUM_INDEX_TTL_MS = 5 * 60 * 1000;  // 5 minutes
const MEDIA_LIST_TTL_MS = 2 * 60 * 1000;   // 2 minutes

// ─── Internal helpers ────────────────────────────────────────────────────────

function buildKey(key) {
  return `${KEY_PREFIX}${key}`;
}

function isExpired(entry) {
  if (!entry.ttl) return false;
  return Date.now() - entry.storedAt > entry.ttl;
}

// ─── Core API ────────────────────────────────────────────────────────────────

/**
 * Store a value under `key` with an optional TTL (ms).
 */
async function set(key, data, ttlMs = DEFAULT_TTL_MS) {
  try {
    const entry = { data, storedAt: Date.now(), ttl: ttlMs };
    await AsyncStorage.setItem(buildKey(key), JSON.stringify(entry));
    return true;
  } catch (err) {
    console.warn('[CacheService] set error:', err);
    return false;
  }
}

/**
 * Retrieve a value. Returns null if missing or expired.
 */
async function get(key) {
  try {
    const raw = await AsyncStorage.getItem(buildKey(key));
    if (!raw) return null;

    const entry = JSON.parse(raw);
    if (isExpired(entry)) {
      // Clean up silently in background
      AsyncStorage.removeItem(buildKey(key));
      return null;
    }
    return entry.data;
  } catch (err) {
    console.warn('[CacheService] get error:', err);
    return null;
  }
}

/**
 * Delete a single cache entry.
 */
async function remove(key) {
  try {
    await AsyncStorage.removeItem(buildKey(key));
    return true;
  } catch (err) {
    console.warn('[CacheService] remove error:', err);
    return false;
  }
}

/**
 * Wipe every cache entry created by this app version.
 */
async function clearAll() {
  try {
    const allKeys = await AsyncStorage.getAllKeys();
    const ourKeys = allKeys.filter((k) => k.startsWith(KEY_PREFIX));
    if (ourKeys.length) await AsyncStorage.multiRemove(ourKeys);
    return true;
  } catch (err) {
    console.warn('[CacheService] clearAll error:', err);
    return false;
  }
}

/**
 * Cache-aside pattern:
 *   1. Return cached value if fresh
 *   2. Otherwise call `factory()`, cache the result, and return it
 */
async function getOrSet(key, factory, ttlMs = DEFAULT_TTL_MS) {
  const cached = await get(key);
  if (cached !== null) return cached;

  const fresh = await factory();
  if (fresh !== null && fresh !== undefined) {
    await set(key, fresh, ttlMs);
  }
  return fresh;
}

/**
 * Return cache statistics.
 */
async function getStats() {
  try {
    const allKeys = await AsyncStorage.getAllKeys();
    const ourKeys = allKeys.filter((k) => k.startsWith(KEY_PREFIX));
    return { totalEntries: ourKeys.length, keys: ourKeys };
  } catch {
    return { totalEntries: 0, keys: [] };
  }
}

// ─── Export ──────────────────────────────────────────────────────────────────

export const CacheService = {
  set,
  get,
  remove,
  clearAll,
  getOrSet,
  getStats,
  TTL: {
    DEFAULT: DEFAULT_TTL_MS,
    ALBUM_INDEX: ALBUM_INDEX_TTL_MS,
    MEDIA_LIST: MEDIA_LIST_TTL_MS,
    DAY: 24 * 60 * 60 * 1000,
    WEEK: 7 * 24 * 60 * 60 * 1000,
  },
};
