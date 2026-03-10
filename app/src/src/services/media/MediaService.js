/**
 * MediaService
 *
 * Abstraction over @react-native-camera-roll/camera-roll, react-native-fs,
 * and our custom MediaStoreModule native bridge.
 *
 * Delete strategy (Android):
 *   react-native-fs / RNFS.unlink() operates on raw filesystem paths and does
 *   NOT implement the MediaStore deletion consent flow required on Android 10+.
 *   On API 30+ the correct path is MediaStore.createDeleteRequest (shows the
 *   system "Allow app to delete?" dialog). On API 29, ContentResolver.delete
 *   is used. Both are handled transparently by MediaStoreModule.deleteMediaFiles.
 *
 * Write strategy (Android):
 *   Files should be created in shared storage via ContentResolver / MediaStore
 *   (no WRITE_EXTERNAL_STORAGE needed on API 29+). MediaStoreModule.saveToGallery
 *   handles the full insert+write+IS_PENDING flow.
 *
 * RNFS is kept for:
 *   • Resolving content:// URIs to real paths (RNFS.stat → _data column)
 *   • Copy/move within the app's own private directories (cacheDir, filesDir)
 */

import { Platform } from 'react-native';
import { CameraRoll } from '@react-native-camera-roll/camera-roll';
import RNFS from 'react-native-fs';
import { MediaStoreModule } from './MediaStoreModule';

// Fields included in every CameraRoll query for full metadata
const INCLUDE_FIELDS = [
  'filename',
  'fileSize',
  'imageSize',
  'fileExtension',
  'location',
  'orientation',
  'albums',
  'playableDuration', // required for video duration badge in thumbnails
];

// ─── Normalisation ────────────────────────────────────────────────────────────

/**
 * Normalise a CameraRoll edge into a flat MediaItem object.
 */
function normalizeEdge(edge) {
  const { node } = edge;
  // node.type may be 'image', 'video', or a MIME type like 'image/jpeg' on some
  // Android versions.  Normalise to a plain 'image' | 'video' token so
  // downstream queries (WHERE media_type = 'image') always match.
  const rawType = (node.type || 'image').toLowerCase();
  const mediaType = rawType.startsWith('video') ? 'video' : 'image';
  const isVideo = mediaType === 'video';
  return {
    id: node.image.uri, // URI is the stable identifier
    uri: node.image.uri,
    filename: node.image.filename || '',
    fileSize: node.image.fileSize || 0,
    width: node.image.width || 0,
    height: node.image.height || 0,
    // playableDuration is provided by CameraRoll for video files (seconds).
    // null for images so media_index stores NULL (not 0) for non-video rows.
    duration: isVideo ? node.image.playableDuration ?? null : null,
    extension: node.image.fileExtension || '',
    type: mediaType, // always 'image' | 'video'
    timestamp: node.timestamp, // Unix seconds
    albumName: node.group_name || '',
    location: node.location || null,
  };
}

// ─── Albums ───────────────────────────────────────────────────────────────────

/**
 * List all albums (folders) on the device.
 * Returns [{ title, count, type }]
 */
async function getAlbums(assetType = 'All') {
  try {
    return await CameraRoll.getAlbums({ assetType });
  } catch (err) {
    console.error('[MediaService] getAlbums:', err);
    return [];
  }
}

// ─── Media listing ────────────────────────────────────────────────────────────

/**
 * Fetch paginated media from a specific album.
 *
 * @param {string} albumName
 * @param {{ first?: number, after?: string, assetType?: string }} options
 * @returns {{ items, hasMore, nextCursor }}
 */
async function getAlbumMedia(albumName, options = {}) {
  const { first = 60, after = null, assetType = 'All' } = options;

  try {
    const result = await CameraRoll.getPhotos({
      first,
      after: after ?? undefined,
      groupTypes: 'Album',
      groupName: albumName,
      assetType,
      include: INCLUDE_FIELDS,
    });

    return {
      items: result.edges.map(normalizeEdge),
      hasMore: result.page_info.has_next_page,
      nextCursor: result.page_info.end_cursor,
    };
  } catch (err) {
    console.error('[MediaService] getAlbumMedia:', err);
    return { items: [], hasMore: false, nextCursor: null };
  }
}

/**
 * Fetch paginated media from all albums combined.
 *
 * @param {{ first?: number, after?: string, assetType?: string }} options
 * @returns {{ items, hasMore, nextCursor }}
 */
async function getAllMedia(options = {}) {
  const { first = 100, after = null, assetType = 'All' } = options;

  try {
    const result = await CameraRoll.getPhotos({
      first,
      after: after ?? undefined,
      assetType,
      include: INCLUDE_FIELDS,
    });

    return {
      items: result.edges.map(normalizeEdge),
      hasMore: result.page_info.has_next_page,
      nextCursor: result.page_info.end_cursor,
    };
  } catch (err) {
    console.error('[MediaService] getAllMedia:', err);
    return { items: [], hasMore: false, nextCursor: null };
  }
}

/**
 * Fetch cover image URIs for a list of album names (one photo each).
 * Returns { [albumName]: uri | null }
 */
async function getAlbumCovers(albumNames = []) {
  const covers = {};
  await Promise.all(
    albumNames.map(async name => {
      const result = await getAlbumMedia(name, { first: 1 });
      covers[name] = result.items[0]?.uri ?? null;
    }),
  );
  return covers;
}

// ─── Delete ───────────────────────────────────────────────────────────────────

/**
 * Delete media files by URI.
 *
 * Android 11+ (API 30+):
 *   MediaStoreModule shows the OS-level "Allow app to delete?" consent dialog
 *   (MediaStore.createDeleteRequest). The promise settles AFTER the user
 *   interacts. Resolves true on approval; rejects with DELETE_CANCELLED if
 *   the user taps "Don't allow".
 *
 * Android < 11 (API < 30):
 *   ContentResolver.delete is used directly (no dialog). Requires
 *   WRITE_EXTERNAL_STORAGE (declared in manifest with maxSdkVersion="29").
 *
 * iOS:
 *   Uses CameraRoll.deletePhotos (Photos framework handles authorisation).
 *
 * @param {string[]} uris
 * @returns {Promise<Array<{ uri, success, error?, code? }>>}
 */
async function deleteMedia(uris = []) {
  if (!uris.length) return [];

  if (Platform.OS === 'android') {
    try {
      await MediaStoreModule.deleteMediaFiles(uris);
      return uris.map(uri => ({ uri, success: true }));
    } catch (err) {
      // Surface structured result so callers can show per-item feedback
      return uris.map(uri => ({
        uri,
        success: false,
        error: err?.message ?? String(err),
        code: err?.code,
      }));
    }
  }

  // iOS
  try {
    await CameraRoll.deletePhotos(uris);
    return uris.map(uri => ({ uri, success: true }));
  } catch (err) {
    return uris.map(uri => ({ uri, success: false, error: err.message }));
  }
}

// ─── Save to gallery (Android) ────────────────────────────────────────────────

/**
 * Copy a file from an app-private or cache path into shared MediaStore gallery
 * storage (Pictures/ or Movies/).
 *
 * This is the correct Android-native approach for writing to shared storage on
 * API 29+ — it uses ContentResolver.insert with IS_PENDING and does NOT require
 * WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE.
 *
 * iOS equivalent: CameraRoll.save(sourcePath, { type, album }).
 *
 * @param {string}  sourcePath   Absolute path to the source file.
 * @param {string}  mimeType     e.g. 'image/jpeg', 'video/mp4'
 * @param {string}  displayName  Filename shown in the gallery.
 * @param {string}  [albumName]  Sub-folder (default: 'Pandora').
 * @returns {Promise<{ success, uri?, error? }>}
 */
async function saveMediaToGallery(
  sourcePath,
  mimeType,
  displayName,
  albumName = 'Pandora',
) {
  try {
    if (Platform.OS === 'android') {
      const uri = await MediaStoreModule.saveToGallery(
        sourcePath,
        mimeType,
        displayName,
        albumName,
      );
      return { success: true, uri };
    }

    // iOS — CameraRoll.save handles album creation automatically
    const type = mimeType.startsWith('video/') ? 'video' : 'photo';
    const uri = await CameraRoll.save(sourcePath, { type, album: albumName });
    return { success: true, uri };
  } catch (err) {
    console.error('[MediaService] saveMediaToGallery:', err);
    return {
      success: false,
      error: err?.message ?? String(err),
      code: err?.code,
    };
  }
}

// ─── Copy / Move ──────────────────────────────────────────────────────────────

/**
 * Copy a media file to `destPath` using react-native-fs.
 *
 * ⚠️  RNFS.copyFile works only within the app's own private/cache directories
 * or with MANAGE_EXTERNAL_STORAGE on Android 11+. To copy INTO shared storage
 * (gallery), use saveMediaToGallery() instead.
 *
 * @returns {{ success, destPath?, error? }}
 */
async function copyMedia(sourceUri, destPath) {
  try {
    const sourcePath = await resolveFilePath(sourceUri);
    if (!sourcePath) throw new Error('Cannot resolve source URI');

    await RNFS.copyFile(sourcePath, destPath);
    return { success: true, destPath };
  } catch (err) {
    console.error('[MediaService] copyMedia:', err);
    return { success: false, error: err.message };
  }
}

/**
 * Move (rename) a media file to `destPath` using react-native-fs.
 *
 * ⚠️  RNFS.moveFile works only within the app's own private/cache directories
 * or with MANAGE_EXTERNAL_STORAGE on Android 11+. To move a shared storage file
 * to a new gallery location use saveMediaToGallery() + deleteMedia() instead.
 *
 * @returns {{ success, destPath?, error? }}
 */
async function moveMedia(sourceUri, destPath) {
  try {
    const sourcePath = await resolveFilePath(sourceUri);
    if (!sourcePath) throw new Error('Cannot resolve source URI');

    await RNFS.moveFile(sourcePath, destPath);
    return { success: true, destPath };
  } catch (err) {
    console.error('[MediaService] moveMedia:', err);
    return { success: false, error: err.message };
  }
}

// ─── Path resolution ──────────────────────────────────────────────────────────

/**
 * Resolve a content:// or file:// URI to an absolute filesystem path.
 *
 * On Android, CameraRoll returns content:// MediaStore URIs.
 * RNFS.stat() queries the MediaStore _data column (accessible when
 * MANAGE_EXTERNAL_STORAGE is granted) and returns the real path.
 * The result may come back with a file:// prefix which we strip.
 *
 * Returns null if no real path can be determined (caller should handle).
 */
async function resolveFilePath(uri) {
  if (!uri) return null;

  // Already a bare absolute path
  if (uri.startsWith('/')) return uri;

  // Strip file:// scheme
  if (uri.startsWith('file://')) {
    return decodeURIComponent(uri.slice('file://'.length));
  }

  // content:// — query MediaStore via RNFS.stat()
  if (uri.startsWith('content://')) {
    try {
      const stat = await RNFS.stat(uri);
      // RNFS may return the real path in .path or .originalFilepath
      const raw = stat?.originalFilepath ?? stat?.path ?? null;
      if (raw && raw !== uri) {
        // Strip file:// if present
        const cleaned = raw.startsWith('file://')
          ? decodeURIComponent(raw.slice('file://'.length))
          : raw;
        if (cleaned.startsWith('/')) return cleaned;
      }
    } catch {}
    return null; // Could not resolve — caller must fall back
  }

  return uri;
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const MediaService = {
  getAlbums,
  getAlbumMedia,
  getAllMedia,
  getAlbumCovers,
  deleteMedia,
  saveMediaToGallery,
  copyMedia,
  moveMedia,
  resolveFilePath,
};
