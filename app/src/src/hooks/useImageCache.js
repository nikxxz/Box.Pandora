import { useCallback } from 'react';
import { ImageCache } from '../services/cache/ImageCache';

/**
 * useImageCache
 *
 * Thin hook wrapper around ImageCache for use in components.
 * Exposes expo-image-aware caching: metadata LRU + native prefetch + disk.
 */
export function useImageCache() {
  const getMetadata = useCallback(uri => ImageCache.getMetadata(uri), []);
  const cacheMetadata = useCallback(
    (uri, meta) => ImageCache.cacheMetadata(uri, meta),
    [],
  );
  const evict = useCallback(uri => ImageCache.evict(uri), []);
  const clearAll = useCallback(() => ImageCache.clearAll(), []);
  const clearDiskCache = useCallback(() => ImageCache.clearDiskCache(), []);
  const prefetchUris = useCallback(uris => ImageCache.prefetchUris(uris), []);
  const memStats = useCallback(() => ImageCache.memStats(), []);

  return {
    getMetadata,
    cacheMetadata,
    evict,
    clearAll,
    clearDiskCache,
    prefetchUris,
    memStats,
  };
}
