/**
 * MediaStoreModule — JS bridge for the Android-native MediaStoreModule
 *
 * deleteMediaFiles(uris)
 *   • Android 11+ (API 30+): uses MediaStore.createDeleteRequest which shows
 *     the OS-level "Allow app to delete?" consent dialog. The promise settles
 *     AFTER the user interacts. Resolves true on approval; rejects with
 *     DELETE_CANCELLED on denial.
 *   • Android < 11 (API < 30): direct ContentResolver.delete (no dialog).
 *     Requires WRITE_EXTERNAL_STORAGE (declared in manifest with maxSdkVersion=29).
 *   • iOS: not available — callers should use CameraRoll.deletePhotos.
 *
 * saveToGallery(sourcePath, mimeType, displayName, albumName)
 *   • Copies a file from an app-private path into shared Pictures/ or Movies/
 *     via ContentResolver.insert + write, using IS_PENDING so the file only
 *     becomes visible once fully written.
 *   • No WRITE_EXTERNAL_STORAGE needed on API 29+ — the app always owns
 *     rows it inserts itself.
 *   • Returns the content:// URI string of the newly created gallery entry.
 *
 * createFileAtPath / deleteFileAtPath / fileExistsAtPath
 *   • Raw filesystem helpers for absolute paths (e.g. .nomedia toggles).
 *   • On Android 11+ (API 30+) these REQUIRE MANAGE_EXTERNAL_STORAGE to be
 *     manually granted by the user via Settings → Special app access.
 *   • Prefer ScopedStorageService for user-facing file creation — it uses the
 *     Storage Access Framework and needs no special permissions.
 *
 * Usage:
 *   import { MediaStoreModule } from './MediaStoreModule';
 *
 *   // delete (shows system consent dialog on Android 11+)
 *   const ok = await MediaStoreModule.deleteMediaFiles(['content://media/...']);
 *
 *   // save to gallery
 *   const uri = await MediaStoreModule.saveToGallery(
 *     '/data/user/0/com.pandorabox/cache/img.jpg',
 *     'image/jpeg', 'img.jpg', 'Pandora',
 *   );
 */

import { NativeModules, Platform } from 'react-native';

const { MediaStoreModule: Native } = NativeModules;

// ─── Error Codes ──────────────────────────────────────────────────────────────

export const MediaStoreError = {
  DELETE_CANCELLED: 'DELETE_CANCELLED', // user tapped "Deny" in the consent dialog
  DELETE_ERROR: 'DELETE_ERROR', // unexpected error during delete
  NO_ACTIVITY: 'NO_ACTIVITY', // no foreground Activity (should not happen)
  BUSY: 'BUSY', // another delete dialog is already open
  INSERT_FAILED: 'INSERT_FAILED', // ContentResolver.insert() returned null
  FILE_NOT_FOUND: 'FILE_NOT_FOUND', // source file for saveToGallery not found
  SAVE_ERROR: 'SAVE_ERROR', // unexpected error during save
  UNSUPPORTED: 'UNSUPPORTED', // module not available on this platform
};

// ─── Helpers ─────────────────────────────────────────────────────────────────

function notAvailable(methodName) {
  return Promise.reject(
    Object.assign(
      new Error(`MediaStoreModule.${methodName} is only available on Android`),
      {
        code: MediaStoreError.UNSUPPORTED,
      },
    ),
  );
}

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Delete media files by URI.
 *
 * On Android 11+ the OS presents a system consent dialog listing the files
 * to be deleted. The promise settles AFTER the user interacts with it.
 *
 * @param {string[]} uris  Array of content:// or file:// URI strings.
 * @returns {Promise<boolean>}  Resolves true when all files were deleted.
 * @throws  Rejects with code DELETE_CANCELLED if the user denies the dialog.
 */
function deleteMediaFiles(uris = []) {
  if (Platform.OS !== 'android' || !Native)
    return notAvailable('deleteMediaFiles');
  if (!uris.length) return Promise.resolve(true);
  return Native.deleteMediaFiles(uris);
}

/**
 * Copy a private/cache file into shared MediaStore gallery storage.
 *
 * @param {string} sourcePath   Absolute filesystem path to the file to insert.
 * @param {string} mimeType     e.g. 'image/jpeg', 'video/mp4'
 * @param {string} displayName  Filename as shown in the gallery.
 * @param {string} albumName    Sub-folder inside Pictures/ (images) or Movies/ (video).
 *                              Pass '' to place the file in the root Pictures/Movies folder.
 *                              Pass a path containing '/' (e.g. 'DCIM/CD') to use it as
 *                              the full MediaStore RELATIVE_PATH — the file will land in
 *                              exactly that folder on disk instead of Pictures/<name>.
 * @returns {Promise<string>}  Resolves to the content:// URI of the new entry.
 */
function saveToGallery(sourcePath, mimeType, displayName, albumName = '') {
  if (Platform.OS !== 'android' || !Native)
    return notAvailable('saveToGallery');
  return Native.saveToGallery(sourcePath, mimeType, displayName, albumName);
}

/**
 * Create a file at an absolute filesystem path, writing `content` to it.
 * Parent directories are created automatically (mkdirs).
 * Requires MANAGE_EXTERNAL_STORAGE on Android 11+ for shared storage paths.
 *
 * @param {string} absolutePath  Full path, e.g. '/storage/emulated/0/Pictures/Foo/.nomedia'
 * @param {string} [content]     File content (default: empty string)
 * @returns {Promise<boolean>}
 */
function createFileAtPath(absolutePath, content = '') {
  if (Platform.OS !== 'android' || !Native)
    return notAvailable('createFileAtPath');
  return Native.createFileAtPath(absolutePath, content);
}

/**
 * Delete the file at an absolute filesystem path.
 * Resolves true if the file was deleted, false if it did not exist.
 * Requires MANAGE_EXTERNAL_STORAGE on Android 11+ for shared storage paths.
 *
 * @param {string} absolutePath
 * @returns {Promise<boolean>}
 */
function deleteFileAtPath(absolutePath) {
  if (Platform.OS !== 'android' || !Native)
    return notAvailable('deleteFileAtPath');
  return Native.deleteFileAtPath(absolutePath);
}

/**
 * Check whether a file or directory exists at an absolute filesystem path.
 *
 * @param {string} absolutePath
 * @returns {Promise<boolean>}
 */
function fileExistsAtPath(absolutePath) {
  if (Platform.OS !== 'android' || !Native)
    return notAvailable('fileExistsAtPath');
  return Native.fileExistsAtPath(absolutePath);
}

/**
 * Share a media file via the Android system share sheet.
 *
 * Fires Intent.ACTION_SEND with the given MIME type (EXTRA_STREAM) so Android
 * filters the chooser to apps that handle the media type — image apps for
 * photos, video players for videos, etc.
 *
 * FLAG_GRANT_READ_URI_PERMISSION is set so content:// URIs can be read by
 * the receiving app without extra permissions.
 *
 * @param {string} uri       content:// or file:// URI of the file to share.
 * @param {string} mimeType  e.g. 'image/jpeg', 'video/mp4'
 * @param {string} [title]   Optional chooser dialog title.
 * @returns {Promise<boolean>}
 */
function shareFile(uri, mimeType, title = '') {
  if (Platform.OS !== 'android' || !Native) return notAvailable('shareFile');
  return Native.shareFile(uri, mimeType, title);
}

/**
 * Open a media file with the Android system "Open with" chooser.
 *
 * Uses Intent.ACTION_VIEW so Android shows apps that can *open/view* the
 * file (image viewers for photos, video players for videos) rather than the
 * broader share-sheet apps that ACTION_SEND surfaces.
 *
 * @param {string} uri       content:// or file:// URI of the file to open.
 * @param {string} mimeType  e.g. 'image/jpeg', 'video/mp4'
 * @returns {Promise<boolean>}
 */
function openWith(uri, mimeType) {
  if (Platform.OS !== 'android' || !Native) return notAvailable('openWith');
  return Native.openWith(uri, mimeType ?? '*/*');
}

// Share multiple media files via the Android system share sheet.
// Uses Intent.ACTION_SEND_MULTIPLE so the receiving app gets all files at once.
// Pass a broad MIME type that covers all items (e.g. all-images, all-videos, or wildcard).
function shareFiles(uris = [], mimeType = '*/*', title = '') {
  if (Platform.OS !== 'android' || !Native) return notAvailable('shareFiles');
  if (!uris.length) return Promise.resolve(false);
  return Native.shareFiles(uris, mimeType, title);
}

/**
 * Rename a media file by updating its DISPLAY_NAME in the MediaStore.
 * On Android 11+ (API 30+) this also renames the physical file on disk.
 * Requires MANAGE_EXTERNAL_STORAGE for files not owned by this app.
 *
 * @param {string} uri            content:// URI of the media file
 * @param {string} newDisplayName New filename (e.g. '.photo.jpg' or 'photo.jpg')
 * @returns {Promise<string>}     Resolves to the (unchanged) URI on success
 */
function renameMedia(uri, newDisplayName) {
  if (Platform.OS !== 'android' || !Native) return notAvailable('renameMedia');
  return Native.renameMedia(uri, newDisplayName);
}

/**
 * Open the "All files access" special-app-access settings screen.
 *
 * On Android 11+ (API 30+) this navigates directly to the per-app
 * MANAGE_EXTERNAL_STORAGE toggle — the only place this permission can
 * be granted. Falls back to the app-details screen on older APIs.
 *
 * @returns {Promise<boolean>}
 */
function openAllFilesAccessSettings() {
  if (Platform.OS !== 'android' || !Native)
    return notAvailable('openAllFilesAccessSettings');
  return Native.openAllFilesAccessSettings();
}

/**
 * Resolve a full-res MediaStore content:// URI to a compact JPEG thumbnail.
 *
 * The native side generates the thumbnail (ContentResolver.loadThumbnail on
 * API 29+, legacy Thumbnails API on older), saves it to the app's private
 * cache directory (cacheDir/thumbnails/{mediaId}.jpg), and returns a file://
 * URI.  Subsequent calls for the same media ID return the cached path instantly.
 *
 * Always resolves — never rejects. Falls back to the original URI on any error
 * (e.g. non-media URI, permission denied, low storage).
 *
 * @param {string} uri   content:// MediaStore URI.
 * @param {number} size  Target square dimension in pixels (default: 320).
 * @returns {Promise<string>}  file:// path to the thumbnail, or original URI.
 */
function getThumbnailUri(uri, size = 320) {
  if (Platform.OS !== 'android' || !Native) return Promise.resolve(uri);
  return Native.getThumbnailUri(uri, size);
}

/**
 * Batch-resolve an array of content:// URIs to their thumbnail file:// paths
 * in a SINGLE native bridge call, using Kotlin coroutines internally for
 * parallel thumbnail generation.
 *
 * Returns a plain object { [uri]: thumbUri } for all input URIs.
 * Entries that failed fall back to their original URI (never missing).
 *
 * Dramatically faster than N concurrent getThumbnailUri calls:
 *   • Eliminates N×bridge-overhead (N calls → 1 call)
 *   • Internal IO-thread parallelism via coroutines is faster than JS-managed concurrency
 *
 * @param {string[]} uris  Array of content:// URI strings
 * @param {number}   size  Target square dimension in pixels (default: 320)
 * @returns {Promise<{[uri: string]: string}>}
 */
function getThumbnailUriBatch(uris = [], size = 320) {
  if (Platform.OS !== 'android' || !Native) {
    // iOS/no-native: return identity map
    const map = {};
    for (const uri of uris) map[uri] = uri;
    return Promise.resolve(map);
  }
  if (!uris.length) return Promise.resolve({});
  return Native.getThumbnailUriBatch(uris, size);
}

/**
 * Check whether MANAGE_EXTERNAL_STORAGE ("All files access") is granted.
 *
 * Uses Environment.isExternalStorageManager() on the native side — the only
 * reliable way to check this special-app-access permission.
 *
 * react-native-permissions v5.x does NOT export MANAGE_EXTERNAL_STORAGE,
 * and PermissionsAndroid.check() always returns false for it.
 * This native bridge call is the canonical check.
 *
 * Returns true on API < 30 (not needed) and true when granted on API 30+.
 *
 * @returns {Promise<boolean>}
 */
function isExternalStorageManager() {
  if (Platform.OS !== 'android' || !Native) return Promise.resolve(true);
  return Native.isExternalStorageManager();
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const MediaStoreModule = {
  deleteMediaFiles,
  renameMedia,
  saveToGallery,
  shareFile,
  shareFiles,
  openWith,
  createFileAtPath,
  deleteFileAtPath,
  fileExistsAtPath,
  getThumbnailUri,
  getThumbnailUriBatch,
  openAllFilesAccessSettings,
  isExternalStorageManager,
};
