import { useState, useCallback, useRef, useMemo } from 'react';
import RNFS from 'react-native-fs';
import { MediaService } from '../services/media/MediaService';
import { MediaIndexer } from '../services/media/MediaIndexer';
import { MediaStoreModule } from '../services/media/MediaStoreModule';
import { ScopedStorageService } from '../services/media/ScopedStorageService';
import { PermissionService } from '../services/permissions/PermissionService';
import { ImageCache } from '../services/cache/ImageCache';
import {
  batchResolveThumbnailUris,
  streamResolveThumbnailUris,
  warmCacheFromDb,
} from '../services/cache/ThumbnailCache';
import { AlbumIndexService } from '../services/database/AlbumIndexService';
import { MediaIndexService } from '../services/database/MediaIndexService';
import { TagService } from '../services/database/TagService';
import { useMediaContext } from '../store/MediaContext';
import { useAppContext } from '../store/AppContext';
import { MediaActions } from '../store/actions';

// ─── Dev-only logging ────────────────────────────────────────────────────────
// eslint-disable-next-line no-undef
const devWarn = __DEV__ ? console.warn.bind(console) : () => {};
// eslint-disable-next-line no-undef
const devInfo = __DEV__ ? console.info.bind(console) : () => {};

// ─── Helpers (module-level) ─────────────────────────────────────────────────

/**
 * AsyncStorage key for persisted SAF directory permissions per album.
 * Used as a fallback when MANAGE_EXTERNAL_STORAGE is not granted.
 */
const safKeyForAlbum = name => `album_nomedia_${name}`;

/**
 * Resolve the filesystem folder path for an album by looking up its first
 * media item via CameraRoll and stripping the filename.
 *
 * @param {string} albumName
 * @returns {Promise<string|null>}  Absolute folder path, or null if unresolvable.
 */
async function resolveAlbumFolderPath(albumName) {
  const firstPage = await MediaService.getAlbumMedia(albumName, {
    first: 1,
    assetType: 'All',
  });
  const firstUri = firstPage?.items?.[0]?.uri;
  if (!firstUri) return null;
  const filePath = await MediaService.resolveFilePath(firstUri);
  if (!filePath) return null;
  return filePath.substring(0, filePath.lastIndexOf('/'));
}

/**
 * Ensure MANAGE_EXTERNAL_STORAGE is available.
 * Returns true if already granted or if the user grants it after being prompted.
 *
 * @returns {Promise<boolean>}
 */
async function ensureFullFileAccess() {
  const has = await PermissionService.checkManageExternalStorage();
  if (has) return true;
  return PermissionService.requestFullFileAccess();
}

/**
 * Derive the best MIME type from a media item's type and filename extension.
 * Used when inserting into MediaStore via saveToGallery.
 */
function getMimeType(item) {
  if (item.type === 'video') {
    const ext = item.filename?.split('.').pop()?.toLowerCase();
    switch (ext) {
      case 'mp4':
        return 'video/mp4';
      case 'mkv':
        return 'video/x-matroska';
      case 'mov':
        return 'video/quicktime';
      case 'avi':
        return 'video/x-msvideo';
      case 'webm':
        return 'video/webm';
      default:
        return 'video/mp4';
    }
  }
  const ext = item.filename?.split('.').pop()?.toLowerCase();
  switch (ext) {
    case 'png':
      return 'image/png';
    case 'gif':
      return 'image/gif';
    case 'webp':
      return 'image/webp';
    case 'bmp':
      return 'image/bmp';
    case 'heic':
      return 'image/heic';
    default:
      return 'image/jpeg';
  }
}

/**
 * Strip the Android external-storage root from an absolute path, returning
 * the MediaStore-compatible RELATIVE_PATH (e.g. "DCIM/CDe").
 *
 * Tries several known device path variants so the result is not dependent on
 * RNFS.ExternalStorageDirectoryPath returning the exact same prefix as the
 * path resolved via RNFS.stat().
 *
 * @param {string} absPath  e.g. "/storage/emulated/0/DCIM/CDe"
 * @returns {string|null}   e.g. "DCIM/CDe", or null if no root matched
 */
function stripToRelativePath(absPath) {
  if (!absPath) return null;
  const roots = [
    RNFS.ExternalStorageDirectoryPath,
    '/storage/emulated/0',
    '/sdcard',
    '/mnt/sdcard',
  ];
  for (const root of roots) {
    const r = root.replace(/\/$/, ''); // normalise: no trailing slash
    if (absPath.startsWith(r + '/')) {
      return absPath.slice(r.length + 1);
    }
  }
  return null;
}

/**
 * Given a filename and a Set of existing lowercase filenames, produce a unique
 * filename by appending _(copy), _(copy 2), _(copy 3), … before the extension.
 *
 * @param {string}      filename      e.g. "photo.jpg"
 * @param {Set<string>} existingNames lowercase filenames already present
 * @returns {string}                  guaranteed unique filename
 */
function generateUniqueName(filename, existingNames) {
  const lastDot = filename.lastIndexOf('.');
  const base = lastDot > 0 ? filename.slice(0, lastDot) : filename;
  const ext = lastDot > 0 ? filename.slice(lastDot) : '';

  let candidate = `${base}_(copy)${ext}`;
  let counter = 2;
  while (existingNames.has(candidate.toLowerCase())) {
    candidate = `${base}_(copy ${counter})${ext}`;
    counter++;
  }
  return candidate;
}

/**
 * useMediaLibrary
 *
 * Primary hook for loading, indexing, and manipulating device media.
 *
 * SQLite integration:
 *  • loadIndex()      — served from SQLite when fresh; re-scans CameraRoll when stale
 *  • loadAlbumMedia() — after each page load, batch-upserts items into media_index
 *  • deleteMedia()    — removes URIs from media_index after physical deletion
 *  • toggleFavorite() — updates SQLite + React state atomically
 *  • loadUserData()   — bootstraps favorites and tags from SQLite into React state
 *                       (call once on app startup, inside AppNavigator)
 */
export function useMediaLibrary() {
  const { state, dispatch } = useMediaContext();
  const { state: appState } = useAppContext();
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);
  const indexingRef = useRef(false);

  // ─── Index ──────────────────────────────────────────────────────────────────

  /**
   * Build (or restore from SQLite) the album index.
   * @param {boolean} forceRefresh  — bypass SQLite cache and re-scan CameraRoll
   * @param {{ includeHidden?: boolean }} options — pass true to show hidden folders
   */
  const loadIndex = useCallback(
    async (forceRefresh = false, { includeHidden } = {}) => {
      if (indexingRef.current) return;
      indexingRef.current = true;
      setIsLoading(true);
      dispatch(MediaActions.setIsIndexing(true));

      // Use caller-supplied flag, fall back to current app-state preference
      const showHidden = includeHidden ?? appState.showHidden ?? false;

      try {
        // Yield to the JS thread so any pending animations can settle first
        await new Promise(resolve => requestIdleCallback(resolve));

        const index = await MediaIndexer.buildIndex(forceRefresh, {
          includeHidden: showHidden,
        });
        dispatch(MediaActions.setIndex(index));

        setError(null);
        return index;
      } catch (err) {
        setError(err.message);
        return null;
      } finally {
        setIsLoading(false);
        dispatch(MediaActions.setIsIndexing(false));
        indexingRef.current = false;
      }
    },
    [dispatch, appState.showHidden],
  );

  const refreshFolderIndex = useCallback(async () => {
    try {
      await AlbumIndexService.refreshAlbumCounts();
      const freshIndex = await AlbumIndexService.buildIndexShape({
        includeHidden: true,
      });
      dispatch(MediaActions.setIndex(freshIndex ?? null));
    } catch (err) {
      devWarn('[useMediaLibrary] refreshFolderIndex error:', err);
    }
  }, [dispatch]);

  // ─── Album media ─────────────────────────────────────────────────────────────

  /**
   * DB-first load (Simple Gallery pattern).
   *
   * Returns items from SQLite immediately — no CameraRoll bridge call needed.
   * Thumb URIs are pre-populated from the persisted thumb_uri column so the
   * grid renders actual thumbnails on first paint, not grey placeholder tiles.
   *
   * Call this BEFORE loadAlbumMedia on the first page load. When CameraRoll
   * results arrive via loadAlbumMedia they will replace these cached items,
   * picking up any new/deleted/changed files.
   *
   * Returns [] when the album is not yet in SQLite (fresh install / first scan).
   *
   * @param {string} albumName
   * @param {string} assetType  'Photos' | 'Videos' | 'All'
   * @param {number} [limit=60]
   * @returns {Promise<MediaItem[]>}
   */
  const loadCachedItems = useCallback(
    async (albumName, assetType, limit = 60) => {
      try {
        const albumRow = await AlbumIndexService.getByName(albumName);
        if (!albumRow?.id) return [];

        const mediaTypeFilter =
          assetType === 'Photos'
            ? 'image'
            : assetType === 'Videos'
            ? 'video'
            : undefined;

        const rows = await MediaIndexService.query(
          {
            albumId: albumRow.id,
            ...(mediaTypeFilter ? { mediaType: mediaTypeFilter } : {}),
          },
          { by: 'date', order: 'DESC' },
          { limit },
        );
        if (!rows.length) return [];

        // Warm L1 ThumbnailCache from SQLite so cached thumbs render on first paint
        // without any native bridge call.
        await warmCacheFromDb(rows.map(r => r.uri));

        return rows.map(row => ({
          id: row.uri,
          uri: row.uri,
          filename: row.filename ?? '',
          type: row.media_type ?? 'image',
          timestamp: row.device_created_at
            ? Math.floor(row.device_created_at / 1000)
            : 0,
          fileSize: row.file_size ?? 0,
          width: row.width ?? 0,
          height: row.height ?? 0,
          duration: row.duration ?? null,
          albumName,
          thumbUri: row.thumb_uri ?? null, // pre-populated — zero bridge calls
        }));
      } catch {
        return [];
      }
    },
    [],
  );

  /**
   * Load a page of media from a specific album.
   * After loading, upserts the page into media_index (SQLite) and warms ImageCache.
   *
   * @param {string} albumName
   * @param {{ first?, after?, assetType? }} options
   */
  const loadAlbumMedia = useCallback(async (albumName, options = {}) => {
    try {
      // ── Hidden / .nomedia folder: CameraRoll ignores these folders, so we
      //    serve their media directly from the SQLite media_index instead.
      let albumRow = null;
      try {
        albumRow = await AlbumIndexService.getByName(albumName);
      } catch {
        // DB not yet initialised — fall through to CameraRoll path
      }

      // ── Hidden album: CameraRoll ignores .nomedia folders entirely ──────────
      if (albumRow?.hidden === 1) {
        const mediaTypeFilter =
          options.assetType === 'Photos'
            ? 'image'
            : options.assetType === 'Videos'
            ? 'video'
            : undefined;

        const rows = await MediaIndexService.query(
          {
            albumId: albumRow.id,
            showHidden: true,
            ...(mediaTypeFilter ? { mediaType: mediaTypeFilter } : {}),
          },
          { by: 'date', order: 'DESC' },
          { limit: options.first ?? 500 },
        );

        // Prime L1 ThumbnailCache from SQLite so batchResolveThumbnailUris
        // serves hits from the JS Map instead of the native bridge.
        await warmCacheFromDb(rows.map(r => r.uri));

        const items = rows.map(row => ({
          id: row.uri,
          uri: row.uri,
          filename: row.filename ?? '',
          type: row.media_type ?? 'image',
          // device_created_at is stored in ms; consumers expect Unix seconds
          timestamp: row.device_created_at
            ? Math.floor(row.device_created_at / 1000)
            : 0,
          fileSize: row.file_size ?? 0,
          width: row.width ?? 0,
          height: row.height ?? 0,
          duration: row.duration ?? null,
          albumName,
        }));

        // Warm ImageCache LRU with file metadata (non-blocking).
        // This lets the grid skip layout re-measurements on first render.
        requestIdleCallback(() => {
          ImageCache.preCacheBatch(items);
        });

        // If the caller handles streaming itself (FolderScreen loadPage with
        // skipThumbs:true), skip the blocking batch resolve and let it stream.
        if (options.skipThumbs) {
          return { items, nextCursor: null, hasMore: false };
        }

        // Resolve pre-sized thumbnail URIs.  L1 hits (primed above) are
        // synchronous; misses go to a single native bridge call.
        const thumbUris = await batchResolveThumbnailUris(
          items.map(it => it.uri),
        );
        const enrichedItems = items.map((it, i) => ({
          ...it,
          thumbUri: thumbUris[i],
        }));

        // Kick off expo-image native prefetch so bitmaps are decoded before
        // items scroll into view (non-blocking fire-and-forget).
        requestIdleCallback(() => {
          ImageCache.prefetchUris(
            enrichedItems
              .filter(it => it.type !== 'video' && it.thumbUri)
              .map(it => it.thumbUri),
          );
        });

        return { items: enrichedItems, nextCursor: null, hasMore: false };
      }

      // ── Normal CameraRoll path ────────────────────────────────────────────
      const result = await MediaService.getAlbumMedia(albumName, options);

      // ── DB fallback for recently-unhidden folders ─────────────────────────
      // After removing a .nomedia file, Android MediaStore may not rescan for
      // minutes.  CameraRoll returns 0 for such folders until then.  When:
      //  • CameraRoll returned nothing, AND
      //  • the album has a path (filesystem-discovered folder)
      // serve the files we indexed with reindexFolderByPath() instead so the
      // folder appears populated immediately.  Once MediaStore rescans and
      // CameraRoll starts returning items, this branch is never entered again.
      if (!result?.items?.length && albumRow?.path != null) {
        const mediaTypeFilter =
          options.assetType === 'Photos'
            ? 'image'
            : options.assetType === 'Videos'
            ? 'video'
            : undefined;

        const rows = await MediaIndexService.query(
          {
            albumId: albumRow.id,
            showHidden: false, // respect per-file hidden flags
            ...(mediaTypeFilter ? { mediaType: mediaTypeFilter } : {}),
          },
          { by: 'date', order: 'DESC' },
          { limit: options.first ?? 500 },
        );

        if (rows.length > 0) {
          // Prime L1 ThumbnailCache from SQLite.  For file:// URIs freshly
          // indexed by reindexFolderByPath this will be a no-op (no thumb_uri
          // stored yet), but on subsequent opens it avoids native bridge calls.
          await warmCacheFromDb(rows.map(r => r.uri));

          const dbItems = rows.map(row => ({
            id: row.uri,
            uri: row.uri,
            filename: row.filename ?? '',
            type: row.media_type ?? 'image',
            timestamp: row.device_created_at
              ? Math.floor(row.device_created_at / 1000)
              : 0,
            fileSize: row.file_size ?? 0,
            width: row.width ?? 0,
            height: row.height ?? 0,
            duration: row.duration ?? null,
            albumName,
          }));

          // Warm ImageCache LRU with file metadata (non-blocking).
          requestIdleCallback(() => {
            ImageCache.preCacheBatch(dbItems);
          });

          // Respect skipThumbs — FolderScreen's loadPage will stream them.
          if (options.skipThumbs) {
            return { items: dbItems, nextCursor: null, hasMore: false };
          }

          const thumbUris = await batchResolveThumbnailUris(
            dbItems.map(it => it.uri),
          );
          const enrichedItems = dbItems.map((it, i) => ({
            ...it,
            thumbUri: thumbUris[i],
          }));

          // Kick off expo-image native prefetch (non-blocking fire-and-forget).
          requestIdleCallback(() => {
            ImageCache.prefetchUris(
              enrichedItems
                .filter(it => it.type !== 'video' && it.thumbUri)
                .map(it => it.thumbUri),
            );
          });

          return { items: enrichedItems, nextCursor: null, hasMore: false };
        }
      }

      if (!result?.items?.length) return result;

      // Warm the in-memory image cache (non-blocking)
      requestIdleCallback(() => {
        ImageCache.preCacheBatch(result.items);
      });

      // Persist media items to SQLite in the background (non-blocking)
      requestIdleCallback(async () => {
        try {
          const rowForWrite =
            albumRow ?? (await AlbumIndexService.getByName(albumName));
          await MediaIndexService.batchUpsert(
            result.items,
            rowForWrite?.id ?? null,
          );
        } catch (err) {
          devWarn('[useMediaLibrary] SQLite index write error:', err);
        }
      });

      // Resolve pre-sized thumbnail URIs (Android: cacheDir/thumbnails/{id}.jpg).
      // ContentResolver.loadThumbnail() (API 29+) generates a compact JPEG on first
      // access; subsequent calls return the disk-cached file instantly (<2 ms/item).
      // Pass skipThumbs:true from FolderScreen to skip this blocking await and
      // let the caller stream thumbs in the background for instant item delivery.
      if (options.skipThumbs) {
        return { ...result, items: result.items };
      }
      const thumbUris = await batchResolveThumbnailUris(
        result.items.map(it => it.uri),
      );
      const itemsWithThumbs = result.items.map((it, i) => ({
        ...it,
        thumbUri: thumbUris[i],
      }));

      return { ...result, items: itemsWithThumbs };
    } catch (err) {
      setError(err.message);
      return null;
    }
  }, []);

  // ─── Delete ──────────────────────────────────────────────────────────────────

  /**
   * Delete media files by URI.
   *
   * Flow:
   *  1. Physically delete via CameraRoll.deletePhotos() (MediaStore API — no
   *     special permission needed beyond READ_MEDIA_* / READ_EXTERNAL_STORAGE).
   *  2. For every successful deletion:
   *     • Evict from in-memory ImageCache LRU.
   *     • Remove from SQLite media_index.
   *     • Dispatch REMOVE_MEDIA_ITEMS so favorites/tags state stays clean.
   *  3. Invalidate album index so counts re-scan on next loadIndex().
   *
   * @param {string[]} uris
   * @returns {Promise<{ results: Array<{uri: string, success: boolean, error?: string}> }>}
   */
  const deleteMedia = useCallback(
    async (uris, options = {}) => {
      const { suppressIndexRefresh = false } = options;
      if (!uris.length) return { results: [] };
      try {
        const results = await MediaService.deleteMedia(uris);
        const successUris = results.filter(r => r.success).map(r => r.uri);
        if (successUris.length > 0) {
          successUris.forEach(uri => ImageCache.evict(uri));
          await MediaIndexService.removeByUris(successUris);
          dispatch(MediaActions.removeMediaItems(successUris));
          await MediaIndexer.invalidate();
          if (!suppressIndexRefresh) {
            await refreshFolderIndex();
          }
        }
        return { results };
      } catch (err) {
        devWarn('[useMediaLibrary] deleteMedia error:', err);
        setError(err.message);
        return {
          results: uris.map(uri => ({
            uri,
            success: false,
            error: err.message,
          })),
        };
      }
    },
    [dispatch, refreshFolderIndex],
  );

  // ─── Favorites ───────────────────────────────────────────────────────────────

  /**
   * Toggle favorite state for a media item.
   * Updates React state immediately (optimistic) then persists to SQLite.
   *
   * @param {{ uri: string }} item
   */
  const toggleFavorite = useCallback(
    async item => {
      const isCurrentlyFavorite = state.favorites.includes(item.uri);
      const newValue = !isCurrentlyFavorite;

      // Update UI immediately
      dispatch(MediaActions.toggleFavorite(item.uri));

      // Persist to SQLite (non-blocking, errors are non-fatal)
      MediaIndexService.setFavorite(item.uri, newValue).catch(err =>
        devWarn('[useMediaLibrary] setFavorite error:', err),
      );
    },
    [dispatch, state.favorites],
  );

  /**
   * Set/clear favorite for a batch of items in one operation.
   * @param {{ uri: string }[]} items
   * @param {boolean} toFavorite  true = add, false = remove
   */
  const batchToggleFavorite = useCallback(
    async (items, toFavorite) => {
      if (!items.length) return;
      const uris = items.map(i => i.uri);

      // Optimistic UI — recompute full favorites list
      const newFavorites = toFavorite
        ? [...new Set([...state.favorites, ...uris])]
        : state.favorites.filter(uri => !uris.includes(uri));
      dispatch(MediaActions.setFavorites(newFavorites));

      // Persist to SQLite (non-blocking)
      MediaIndexService.batchSetFavorite(uris, toFavorite).catch(err =>
        devWarn('[useMediaLibrary] batchSetFavorite error:', err),
      );
    },
    [dispatch, state.favorites],
  );

  // ─── Hide / Unhide ───────────────────────────────────────────────────────────

  /**
   * Toggle the hidden state of a single media file.
   *
   * Strategy:
   *  1. Optimistically update React state so the UI responds immediately.
   *  2. Check/request MANAGE_EXTERNAL_STORAGE for the filesystem rename.
   *     If granted, rename the file on-disk (dot-prefix via MediaStore).
   *     If denied, skip the rename — the file is still hidden in-app via SQLite.
   *  3. Persist the hidden flag in SQLite (non-blocking, errors are non-fatal).
   *
   * @param {{ uri: string, filename?: string }} item
   * @returns {Promise<{ renamed: boolean }>}
   */
  const toggleMediaHidden = useCallback(
    async item => {
      const isCurrentlyHidden = state.hiddenUris.includes(item.uri);
      const toHide = !isCurrentlyHidden;
      let renamed = false;

      // 1. Optimistic UI
      if (toHide) {
        dispatch(MediaActions.addHiddenUri(item.uri));
      } else {
        dispatch(MediaActions.removeHiddenUri(item.uri));
      }

      // 2. Filesystem rename (dot-prefix) — needs MANAGE_EXTERNAL_STORAGE on API 30+
      if (item.filename) {
        const hidden = item.filename.startsWith('.');
        const newName = toHide
          ? hidden
            ? item.filename
            : '.' + item.filename
          : hidden
          ? item.filename.slice(1)
          : item.filename;
        if (newName !== item.filename) {
          const hasAccess = await ensureFullFileAccess();
          if (hasAccess) {
            try {
              await MediaStoreModule.renameMedia(item.uri, newName);
              renamed = true;
            } catch (err) {
              devWarn('[useMediaLibrary] renameMedia failed:', err.message);
            }
          } else {
            devInfo(
              '[useMediaLibrary] MANAGE_EXTERNAL_STORAGE denied — file hidden in-app only',
            );
          }
        }
      }

      // 3. SQLite
      MediaIndexService.setHidden(item.uri, toHide).catch(err =>
        devWarn('[useMediaLibrary] setHidden error:', err),
      );

      return { renamed };
    },
    [dispatch, state.hiddenUris],
  );

  /**
   * Batch-toggle hidden state for multiple items (multi-select scenario).
   *
   * Checks/requests MANAGE_EXTERNAL_STORAGE once before processing all renames.
   * If denied, files are hidden in-app only (SQLite flag).
   *
   * @param {Array<{ uri: string, filename?: string }>} items
   * @param {boolean} toHide
   * @returns {Promise<{ renamedCount: number }>}
   */
  const batchToggleMediaHidden = useCallback(
    async (items, toHide) => {
      if (!items.length) return { renamedCount: 0 };
      const uris = items.map(i => i.uri);

      // 1. Optimistic UI
      dispatch(MediaActions.batchSetHiddenUris(uris, toHide));

      // 2. Check MANAGE_EXTERNAL_STORAGE once for the entire batch
      const hasAccess = await ensureFullFileAccess();
      let renamedCount = 0;

      if (hasAccess) {
        // Perform all renames in parallel (fire-and-forget for individual errors)
        const renamePromises = items
          .filter(item => {
            if (!item.filename) return false;
            const hidden = item.filename.startsWith('.');
            const newName = toHide
              ? hidden
                ? item.filename
                : '.' + item.filename
              : hidden
              ? item.filename.slice(1)
              : item.filename;
            return newName !== item.filename;
          })
          .map(async item => {
            const hidden = item.filename.startsWith('.');
            const newName = toHide
              ? hidden
                ? item.filename
                : '.' + item.filename
              : hidden
              ? item.filename.slice(1)
              : item.filename;
            try {
              await MediaStoreModule.renameMedia(item.uri, newName);
              renamedCount++;
            } catch {
              // Individual file rename failure — non-fatal
            }
          });
        await Promise.all(renamePromises);
      } else {
        devInfo(
          '[useMediaLibrary] MANAGE_EXTERNAL_STORAGE denied — batch hidden in-app only',
        );
      }

      // 3. SQLite batch
      MediaIndexService.batchSetHidden(uris, toHide).catch(err =>
        devWarn('[useMediaLibrary] batchSetHidden error:', err),
      );

      return { renamedCount };
    },
    [dispatch],
  );

  /**
   * Toggle the hidden state of an entire album/folder.
   *
   * Multi-strategy approach for .nomedia file management:
   *
   *  Strategy 1 — Direct filesystem (MANAGE_EXTERNAL_STORAGE)
   *    If the user has already granted "All files access", we can create/delete
   *    .nomedia instantly at the absolute folder path. Best UX, no extra prompts.
   *
   *  Strategy 2 — Request MANAGE_EXTERNAL_STORAGE
   *    If not yet granted, prompt the user to enable it in Settings.
   *    If they return and it's now granted, proceed with Strategy 1.
   *
   *  Strategy 3 — SAF fallback (ScopedStorageService)
   *    If the user declines MANAGE_EXTERNAL_STORAGE, fall back to the Storage
   *    Access Framework. Requires a one-time folder picker per album, but
   *    works without any special permissions. The picked URI is persisted in
   *    AsyncStorage so subsequent toggles for the same album are seamless.
   *
   *  Always: update the hidden flag in SQLite and reload the album index.
   *
   * @param {{ name: string }} folder
   * @returns {Promise<{ method: 'direct'|'saf'|'db-only' }>}
   */
  const toggleAlbumHidden = useCallback(
    async folder => {
      const albumRow = await AlbumIndexService.getByName(folder.name);
      if (!albumRow) {
        devWarn(
          '[useMediaLibrary] toggleAlbumHidden: album not found',
          folder.name,
        );
        return { method: 'db-only' };
      }

      const toHide = !albumRow.hidden;
      let nomediaSuccess = false;
      let method = 'db-only';

      // ── Resolve folder path once ───────────────────────────────────────────
      // albumRow.path is populated by scanNomediaFolders for every hidden-by-
      // .nomedia folder (including those hidden by other apps).  It is the
      // ONLY reliable source for these folders because CameraRoll never returns
      // .nomedia-hidden directories — so resolveAlbumFolderPath() would always
      // return null for them.
      let folderPath = albumRow.path ?? null;
      if (!folderPath) {
        // Fallback: derive from a CameraRoll item (works for visible albums).
        folderPath = await resolveAlbumFolderPath(folder.name).catch(
          () => null,
        );
      }

      // ── Strategy 1: Direct filesystem (already has MANAGE_EXTERNAL_STORAGE) ──
      const hasFullAccess =
        await PermissionService.checkManageExternalStorage();
      if (hasFullAccess && folderPath) {
        try {
          if (toHide) {
            await MediaStoreModule.createFileAtPath(
              folderPath + '/.nomedia',
              '',
            );
          } else {
            await MediaStoreModule.deleteFileAtPath(folderPath + '/.nomedia');
          }
          nomediaSuccess = true;
          method = 'direct';
        } catch (err) {
          devWarn('[useMediaLibrary] .nomedia direct op failed:', err.message);
        }
      }

      // ── Strategy 2: Request MANAGE_EXTERNAL_STORAGE, then retry direct ──
      if (!nomediaSuccess && !hasFullAccess && folderPath) {
        const granted = await PermissionService.requestFullFileAccess();
        if (granted) {
          try {
            if (toHide) {
              await MediaStoreModule.createFileAtPath(
                folderPath + '/.nomedia',
                '',
              );
            } else {
              await MediaStoreModule.deleteFileAtPath(folderPath + '/.nomedia');
            }
            nomediaSuccess = true;
            method = 'direct';
          } catch (err) {
            devWarn(
              '[useMediaLibrary] .nomedia direct op failed after grant:',
              err.message,
            );
          }
        }
      }

      // ── Strategy 3: SAF fallback (no MANAGE_EXTERNAL_STORAGE needed) ──
      // Used when the app doesn't (and can't) obtain MANAGE_EXTERNAL_STORAGE,
      // OR when no folder path could be resolved (should not happen for
      // .nomedia-discovered albums since albumRow.path is always set).
      if (!nomediaSuccess) {
        try {
          const safKey = safKeyForAlbum(folder.name);

          // Check for a previously persisted SAF URI for this album.
          let dirUri = null;
          const savedDir = await ScopedStorageService.getSavedDirectory(safKey);
          if (savedDir) {
            dirUri = savedDir.uri;
          } else {
            // Ask the user to pick the album folder via system file picker.
            // This is a one-time action per album — the URI is persisted.
            const picked = await ScopedStorageService.openDirectory({
              persist: true,
            });
            if (picked?.uri) {
              await ScopedStorageService.saveDirectory(safKey, picked.uri);
              dirUri = picked.uri;
            }
          }

          if (dirUri) {
            if (toHide) {
              await ScopedStorageService.ensureNomedia(dirUri);
            } else {
              await ScopedStorageService.removeNomedia(dirUri);
            }
            nomediaSuccess = true;
            method = 'saf';
          }
        } catch (err) {
          // User cancelled the SAF picker or another SAF error.
          devWarn(
            '[useMediaLibrary] .nomedia SAF fallback failed:',
            err.message,
          );
        }
      }

      // ── Update SQLite hidden flag ──────────────────────────────────────────
      await AlbumIndexService.setHidden(albumRow.id, toHide);

      // ── Re-index folder files immediately after unhiding ──────────────────
      // After a .nomedia deletion the Android MediaStore may not rescan for
      // minutes. Re-index with RNFS (file:// URIs) right away so the gallery
      // content appears immediately in the app.  The file:// URIs get replaced
      // by proper content:// URIs once the OS media scanner runs.
      if (nomediaSuccess && !toHide && folderPath) {
        await MediaIndexer.reindexFolderByPath(folderPath).catch(err =>
          devWarn('[useMediaLibrary] reindexFolderByPath failed:', err.message),
        );
      }

      // ── Targeted UI update — no full CameraRoll rescan needed ─────────────
      // setHidden() + reindexFolderByPath() have already written the correct
      // state to SQLite, so re-reading the albums table is sufficient.
      // Calling loadIndex(true) here would trigger _scanAndPersist() which
      // fires a full CameraRoll bridge call for no benefit.
      const freshIndex = await AlbumIndexService.buildIndexShape({
        includeHidden: appState.showHidden ?? false,
      });
      if (freshIndex) {
        dispatch(MediaActions.setIndex(freshIndex));
      }

      return { method };
    },
    [dispatch, appState.showHidden],
  );

  // ─── Bootstrap ───────────────────────────────────────────────────────────────

  /**
   * Load persisted user data (favorites + tags) from SQLite into React state.
   *
   * Call once at app startup after DatabaseService.init() completes.
   * Subsequent in-session toggles keep state + SQLite in sync via toggleFavorite().
   */
  const loadUserData = useCallback(async () => {
    try {
      // ── Favorites ──────────────────────────────────────────────────────────
      const favoriteRows = await MediaIndexService.query(
        { favorite: true },
        {},
        { limit: 10000 },
      );
      dispatch(MediaActions.setFavorites(favoriteRows.map(r => r.uri)));

      // ── Hidden URIs ────────────────────────────────────────────────────────
      const hiddenUris = await MediaIndexService.getHiddenUris();
      dispatch(MediaActions.setHiddenUris(hiddenUris));

      // ── Tags ───────────────────────────────────────────────────────────────
      // Rebuild the { [uri]: string[] } map that TagsScreen consumes from state.tags
      const allTags = await TagService.getAllTags();
      const tagsMap = {};

      await Promise.all(
        allTags.map(async tag => {
          const mediaRows = await TagService.getMediaForTag(tag.id, {
            limit: 5000,
            showHidden: true,
          });
          mediaRows.forEach(row => {
            if (!tagsMap[row.uri]) tagsMap[row.uri] = [];
            if (!tagsMap[row.uri].includes(tag.name)) {
              tagsMap[row.uri].push(tag.name);
            }
          });
        }),
      );

      dispatch(MediaActions.setTags(tagsMap));
    } catch (err) {
      // Non-fatal — app works without persisted user data (fresh install)
      devWarn('[useMediaLibrary] loadUserData error:', err);
    }
  }, [dispatch]);

  // ─── Selection helpers ───────────────────────────────────────────────────────

  const selectFolder = useCallback(
    folder => dispatch(MediaActions.setSelectedFolder(folder)),
    [dispatch],
  );

  const openMedia = useCallback(
    item => dispatch(MediaActions.setCurrentMedia(item)),
    [dispatch],
  );

  const closeMedia = useCallback(
    () => dispatch(MediaActions.setCurrentMedia(null)),
    [dispatch],
  );

  // ─── Rename media file ────────────────────────────────────────────────────

  /**
   * Rename a media file on-device and update SQLite.
   *
   * Flow:
   *  1. Check/request MANAGE_EXTERNAL_STORAGE.
   *  2. Call MediaStoreModule.renameMedia(uri, newName).
   *  3. Update SQLite media_index filename + extension.
   *  4. Evict old ImageCache entry (thumbnail URL won't change but filename did).
   *
   * @param {{ uri: string, filename?: string }} item
   * @param {string} newName  — new filename including extension
   * @returns {Promise<{ success: boolean, error?: string }>}
   */
  const renameMedia = useCallback(async (item, newName) => {
    if (!item?.uri || !newName?.trim()) {
      return { success: false, error: 'Invalid arguments' };
    }
    const trimmed = newName.trim();
    try {
      const hasAccess = await ensureFullFileAccess();
      if (!hasAccess) {
        return {
          success: false,
          error: 'Storage permission required to rename files.',
        };
      }
      await MediaStoreModule.renameMedia(item.uri, trimmed);

      // Update SQLite filename + extension
      await MediaIndexService.renameByUri(item.uri, trimmed).catch(err =>
        devWarn('[useMediaLibrary] renameByUri error:', err),
      );

      // Evict cache entry so any stale thumbnail is cleared
      ImageCache.evict(item.uri);

      return { success: true };
    } catch (err) {
      return { success: false, error: err?.message ?? 'Rename failed.' };
    }
  }, []);

  // ─── Rename album/folder ──────────────────────────────────────────────────

  /**
   * Rename a folder/album and refresh the index.
   *
   * Note: Renaming the underlying filesystem folder is OS-protected on
   * Android (requires MANAGE_EXTERNAL_STORAGE).  We attempt a MediaStore
   * DISPLAY_NAME update via renameMedia (which renames the content-URI's
   * display name, not the folder path itself — folder rename on Android's
   * shared storage is not directly supported via MediaStore).
   *
   * For this reason the rename operates at the SQLite level only:
   * the album name in the `albums` table is updated so the in-app label
   * changes immediately, and the index is invalidated so the next scan
   * picks up the correct state from CameraRoll.
   *
   * @param {{ name: string, id?: number }} folder
   * @param {string} newName
   * @returns {Promise<{ success: boolean, error?: string }>}
   */
  const renameAlbum = useCallback(
    async (folder, newName) => {
      if (!folder?.name || !newName?.trim()) {
        return { success: false, error: 'Invalid arguments' };
      }
      const trimmed = newName.trim();
      if (trimmed === folder.name) return { success: true };

      try {
        const hasAccess = await ensureFullFileAccess();
        if (!hasAccess) {
          return {
            success: false,
            error: 'Storage permission required to rename folders.',
          };
        }

        // ── 1. Resolve actual filesystem path ─────────────────────────────
        const oldPath = await resolveAlbumFolderPath(folder.name);
        if (!oldPath) {
          return {
            success: false,
            error:
              'Could not locate folder on device. Try scanning media first.',
          };
        }

        // Sanity-check: the last path segment should match the album name
        const actualDirName = oldPath.split('/').pop();
        if (actualDirName !== folder.name) {
          return {
            success: false,
            error: `Folder path mismatch ("${actualDirName}" vs "${folder.name}"). Cannot rename safely.`,
          };
        }

        // ── 2. Rename on filesystem ────────────────────────────────────────
        const parentPath = oldPath.substring(0, oldPath.lastIndexOf('/'));
        const newPath = `${parentPath}/${trimmed}`;
        await RNFS.moveFile(oldPath, newPath);

        // ── 3. Update SQLite album row ─────────────────────────────────────
        let albumRow = null;
        try {
          albumRow = await AlbumIndexService.getByName(folder.name);
        } catch (e) {
          devWarn('[useMediaLibrary] renameAlbum getByName error:', e);
        }
        if (albumRow?.id) {
          await AlbumIndexService.rename(albumRow.id, trimmed);
        }

        // ── 4. Update in-memory state immediately (no CameraRoll rescan) ───
        if (state.index?.folders) {
          const { [folder.name]: oldEntry, ...rest } = state.index.folders;
          if (oldEntry) {
            dispatch(
              MediaActions.setIndex({
                ...state.index,
                folders: { ...rest, [trimmed]: { ...oldEntry, name: trimmed } },
              }),
            );
          }
        }

        // ── 5. Background resync after MediaStore catches up ───────────────
        // The filesystem rename is now real; MediaStore will re-index the
        // new folder name within a few seconds via its FileObserver.
        setTimeout(() => loadIndex(true), 3000);

        return { success: true };
      } catch (err) {
        return { success: false, error: err?.message ?? 'Rename failed.' };
      }
    },
    [state.index, dispatch, loadIndex],
  );

  // ─── Copy / Move media to folder ────────────────────────────────────────────

  /**
   * Detect filename conflicts between `items` and the destination folder.
   *
   * Returns every item whose filename matches an existing file in dest.
   * Also returns the full set of existing lowercase filenames so the caller
   * can generate unique names without a second DB round-trip.
   *
   * Sources checked (first that returns data wins):
   *  1. SQLite media_index for the dest album  — fastest, has URIs
   *  2. RNFS.readDir on the resolved folder path — fallback (no URIs)
   *
   * @param {object[]} items
   * @param {string}   destFolderName
   * @returns {Promise<{ conflicts: Array<{item, filename, existingUri?}>, existingNames: Set<string> }>}
   */
  const checkDestConflicts = useCallback(
    async (items, destFolderName, hintPath = null) => {
      try {
        const existingNames = new Set();
        const uriByName = new Map(); // lowercase name → existing dest URI

        const destAlbumRow = await AlbumIndexService.getByName(
          destFolderName,
        ).catch(() => null);
        if (destAlbumRow?.id) {
          const pairs = await MediaIndexService.getFilenamesAndUrisByAlbumId(
            destAlbumRow.id,
          );
          pairs.forEach(({ filename, uri }) => {
            if (filename) {
              existingNames.add(filename.toLowerCase());
              uriByName.set(filename.toLowerCase(), uri);
            }
          });
        }

        // Fallback: filesystem listing when album isn't indexed yet
        if (!existingNames.size) {
          const absPath =
            hintPath ??
            destAlbumRow?.path ??
            (await resolveAlbumFolderPath(destFolderName));
          if (absPath) {
            try {
              const entries = await RNFS.readDir(absPath);
              entries.forEach(e => {
                if (!e.isDirectory()) existingNames.add(e.name.toLowerCase());
              });
            } catch {}
          }
        }

        const conflicts = items
          .map(item => {
            const fn = (item.filename ?? '').toLowerCase();
            if (fn && existingNames.has(fn)) {
              return {
                item,
                filename: item.filename ?? fn,
                existingUri: uriByName.get(fn) ?? null,
              };
            }
            return null;
          })
          .filter(Boolean);

        return { conflicts, existingNames };
      } catch (err) {
        devWarn('[useMediaLibrary] checkDestConflicts error:', err);
        return { conflicts: [], existingNames: new Set() };
      }
    },
    [],
  );

  /**
   * Copy a batch of media items into a destination gallery folder.
   *
   * Strategy:
   *  1. Resolve each source file to an absolute path via MediaService.
   *  2. Use MediaStoreModule.saveToGallery() to insert a copy in the
   *     destination album — no MANAGE_EXTERNAL_STORAGE needed on API 29+.
   *  3. Index the new MediaStore URI in SQLite under the destination album.
   *  4. Invalidate MediaIndexer so counts refresh on next loadIndex().
   *
   * @param {Array<{ uri, filename, type, fileSize?, width?, height?, duration? }>} items
   * @param {string} destFolderName  — target album / gallery folder name
   * @returns {Promise<{ results: Array<{ uri, newUri?, success, error? }> }>}
   */
  const copyMediaToFolder = useCallback(
    async (items, destFolderName, options = {}) => {
      if (!items.length) return { results: [] };

      const {
        skipUris = new Set(), // source URIs to skip entirely
        renameMap = {}, // srcUri → new filename for this item
        replaceExistingUris = {}, // srcUri → existing dest URI to delete first
        destFolderPath = null, // absolute path hint from the folder picker (avoids DB re-lookup)
        suppressIndexRefresh = false, // skip immediate index refresh (used by move)
      } = options;

      // Resolve destination album row once (for SQLite album_id foreign key)
      let destAlbumRow = null;
      try {
        destAlbumRow = await AlbumIndexService.getByName(destFolderName);
      } catch {
        // DB not yet initialised — proceed without album FK
      }

      // ── Resolve the real on-device path of the destination folder ────────────
      // Priority:
      //  1. SQLite albums.path — populated during _scanAndPersist via resolveFilePath.
      //     Most reliable: fixed at scan time, unaffected by later wrong copies.
      //  2. resolveAlbumFolderPath() — CameraRoll lookup (fallback for albums not
      //     yet re-scanned after this patch).
      // The resulting absolute path is converted to a MediaStore RELATIVE_PATH
      // (e.g. "DCIM/CDe") via stripToRelativePath() which tries several known
      // storage root variants so device-specific path differences don't break it.
      let saveToGalleryAlbumArg = destFolderName; // plain name fallback → Pictures/<name>
      try {
        // Priority: hint from picker → DB path → CameraRoll lookup
        const absPath =
          destFolderPath ??
          destAlbumRow?.path ??
          (await resolveAlbumFolderPath(destFolderName));
        const rel = stripToRelativePath(absPath);
        if (rel) saveToGalleryAlbumArg = rel;
      } catch (err) {
        devWarn(
          '[useMediaLibrary] copyMediaToFolder: dest path resolution failed, ' +
            'falling back to folder name.',
          err,
        );
      }

      // ── Delete files being replaced (use RNFS.unlink if possible, else MediaStore) ──
      const urisToReplace = Object.entries(replaceExistingUris)
        .filter(([, destUri]) => destUri)
        .map(([, destUri]) => destUri);

      if (urisToReplace.length > 0) {
        const hasFullAccess =
          await PermissionService.checkManageExternalStorage();
        if (hasFullAccess) {
          await Promise.all(
            urisToReplace.map(async destUri => {
              try {
                const p = await MediaService.resolveFilePath(destUri);
                if (p) await RNFS.unlink(p);
              } catch {}
            }),
          );
        } else {
          // Falls back to MediaStore.createDeleteRequest (single system dialog)
          await MediaService.deleteMedia(urisToReplace).catch(() => {});
        }
        await MediaIndexService.removeByUris(urisToReplace).catch(() => {});
      }

      const results = await Promise.all(
        items.map(async item => {
          // ── Skip items the user explicitly excluded ──
          if (skipUris.has(item.uri)) {
            return { uri: item.uri, success: false, skipped: true };
          }

          try {
            const sourcePath = await MediaService.resolveFilePath(item.uri);
            if (!sourcePath) {
              throw new Error('Cannot resolve source file path');
            }

            const mimeType = getMimeType(item);
            // Honour rename override (conflict resolution) → fallback to original name
            const filename =
              renameMap[item.uri] ||
              item.filename ||
              sourcePath.split('/').pop();

            // Insert into MediaStore under the destination album
            const newUri = await MediaStoreModule.saveToGallery(
              sourcePath,
              mimeType,
              filename,
              saveToGalleryAlbumArg, // full relative path, e.g. "DCIM/CD"
            );

            // Index new entry + transfer metadata eagerly so that a subsequent
            // move (which removes the source URI) cannot race with the write.
            try {
              await MediaIndexService.upsert(
                {
                  uri: newUri,
                  filename,
                  type: item.type ?? 'image',
                  fileSize: item.fileSize ?? 0,
                  width: item.width ?? 0,
                  height: item.height ?? 0,
                  duration: item.duration ?? null,
                  extension: filename.includes('.')
                    ? filename.split('.').pop().toLowerCase()
                    : '',
                  // timestamp in Unix seconds, MediaIndexService converts to ms
                  timestamp: Math.floor(Date.now() / 1000),
                },
                destAlbumRow?.id ?? null,
              );
              // Transfer tags and user metadata (rating, favorite, hidden, notes)
              // from the original URI to the newly created entry.
              await MediaIndexService.copyUserMetadata(item.uri, newUri);
            } catch (e) {
              devWarn('[useMediaLibrary] copyMediaToFolder index error:', e);
            }

            return { uri: item.uri, newUri, success: true };
          } catch (err) {
            devWarn('[useMediaLibrary] copyMediaToFolder item error:', err);
            return {
              uri: item.uri,
              success: false,
              error: err?.message ?? 'Copy failed',
            };
          }
        }),
      );

      // Invalidate index and refresh folder state immediately.
      await MediaIndexer.invalidate().catch(() => {});
      if (!suppressIndexRefresh) {
        await refreshFolderIndex();
      }

      return { results };
    },
    [refreshFolderIndex],
  );

  /**
   * Move a batch of media items to a destination gallery folder.
   *
   * Strategy:
   *  1. copyMediaToFolder() — inserts copies in MediaStore destination.
   *  2. deleteMedia() → MediaStoreModule.deleteMediaFiles() — removes originals.
   *     When MANAGE_EXTERNAL_STORAGE is granted, deletion is direct (no dialog).
   *     Otherwise a single system consent dialog is shown for all files.
   *  3. Remove successfully deleted originals from SQLite + React state.
   *
   * @param {Array<{ uri, filename, type, fileSize?, width?, height?, duration? }>} items
   * @param {string} destFolderName
   * @returns {Promise<{ results, movedUris: string[] }>}
   */
  const moveMediaToFolder = useCallback(
    async (items, destFolderName, options = {}) => {
      if (!items.length) return { results: [], movedUris: [] };

      // 1. Copy all items to destination (passes conflict-resolution options through)
      const { results: copyResults } = await copyMediaToFolder(
        items,
        destFolderName,
        {
          ...options,
          suppressIndexRefresh: true,
        },
      );

      const successCopied = items.filter((_, i) => copyResults[i]?.success);

      if (!successCopied.length) {
        return { results: copyResults, movedUris: [] };
      }

      // 2. Delete originals via MediaStoreModule.deleteMediaFiles.
      //    When MANAGE_EXTERNAL_STORAGE is granted, deleteMedia → deleteMediaFiles
      //    calls deleteLegacy() which removes both the physical file AND the
      //    MediaStore row without showing a consent dialog — identical behaviour
      //    to the old RNFS.unlink path but correctly cleans up the MediaStore
      //    entry so ghost items no longer reappear in the source folder.
      //    When MANAGE_EXTERNAL_STORAGE is NOT granted, deleteMediaFiles shows a
      //    single system consent dialog for all files (one tap for the user).
      const successUris = successCopied.map(it => it.uri);
      const { results: delRes } = await deleteMedia(successUris);
      const movedUris = delRes.filter(r => r.success).map(r => r.uri);

      return {
        results: copyResults.map((r, i) => ({
          ...r,
          moved: movedUris.includes(items[i]?.uri),
        })),
        movedUris,
      };
    },
    [copyMediaToFolder, deleteMedia],
  );

  // ─── Sync media metrics ───────────────────────────────────────────────────

  /**
   * Push fresh device-measured metrics (dimensions, file size, duration) for
   * a single media item into SQLite.
   *
   * Call this after displaying a full-resolution image/video when accurate
   * dimensions become available (e.g. Image.onLoad event).
   *
   * @param {{ uri: string, filename?: string, fileSize?: number, width?: number, height?: number, duration?: number | null, type?: string }} item
   */
  const syncMediaMetrics = useCallback(async item => {
    if (!item?.uri) return;
    try {
      await MediaIndexService.syncMetrics(item.uri, {
        filename: item.filename,
        fileSize: item.fileSize,
        width: item.width,
        height: item.height,
        duration: item.duration ?? null,
        mediaType: item.type,
        extension: item.filename?.includes('.')
          ? item.filename.split('.').pop().toLowerCase()
          : undefined,
      });
    } catch (err) {
      devWarn('[useMediaLibrary] syncMediaMetrics error:', err);
    }
  }, []);

  /**
   * Batch-sync device-measured metrics for multiple items.
   * Non-blocking — fire and forget after a page load.
   *
   * @param {Array<{ uri: string, filename?: string, fileSize?: number, width?: number, height?: number, duration?: number | null, type?: string }>} items
   */
  const syncBatchMetrics = useCallback(async (items = []) => {
    if (!items.length) return;
    try {
      await MediaIndexService.batchSyncMetrics(
        items.map(it => ({
          uri: it.uri,
          filename: it.filename,
          fileSize: it.fileSize,
          width: it.width,
          height: it.height,
          duration: it.duration ?? null,
          mediaType: it.type,
          extension: it.filename?.includes('.')
            ? it.filename.split('.').pop().toLowerCase()
            : undefined,
        })),
      );
    } catch (err) {
      devWarn('[useMediaLibrary] syncBatchMetrics error:', err);
    }
  }, []);

  // ─── Derived data ─────────────────────────────────────────────────────────────

  // Memoised so the reference is stable across re-renders that don't
  // change state.index.  Without this, every render creates a new array
  // (Object.values().sort()), which re-fires warmCovers in HomeScreen
  // → setCoverThumbMap → re-render → new folders ref → infinite loop.
  const folders = useMemo(
    () => MediaIndexer.getFoldersByLatest(state.index),
    [state.index],
  );

  return {
    // State
    index: state.index,
    folders,
    selectedFolder: state.selectedFolder,
    currentMedia: state.currentMedia,
    isLoading,
    isIndexing: state.isIndexing,
    error,

    // Actions
    loadIndex,
    loadCachedItems,
    loadAlbumMedia,
    deleteMedia,
    toggleFavorite,
    batchToggleFavorite,
    toggleMediaHidden,
    batchToggleMediaHidden,
    toggleAlbumHidden,
    renameMedia,
    renameAlbum,
    copyMediaToFolder,
    moveMediaToFolder,
    checkDestConflicts,
    syncMediaMetrics,
    syncBatchMetrics,
    loadUserData,
    selectFolder,
    openMedia,
    closeMedia,
  };
}
