/**
 * ScopedStorageService
 *
 * Wraps `react-native-scoped-storage` to provide Android Scoped Storage
 * (Storage Access Framework / SAF) operations without requiring
 * MANAGE_EXTERNAL_STORAGE or WRITE_EXTERNAL_STORAGE.
 *
 * How SAF works:
 *   1. Call openDirectory() — the system file-picker opens and the user
 *      selects a folder.  The app receives a persistent document-tree URI
 *      (content://com.android.externalstorage.documents/tree/...).
 *   2. All subsequent read/write/delete operations on that tree URI require
 *      NO special permissions — the user's one-time consent is enough.
 *   3. Persist the URI in AsyncStorage so it survives app restarts.
 *      Pass the same key to getSavedDirectory() to restore access.
 *
 * Typical usage (Sandbox .nomedia toggle):
 *
 *   // 1. Ask user to pick the EssentialSpace folder (once ever)
 *   const dir = await ScopedStorageService.openDirectory({ persist: true });
 *   await ScopedStorageService.saveDirectory('essentialSpace', dir.uri);
 *
 *   // 2. Later sessions — restore saved directory
 *   const dir = await ScopedStorageService.getSavedDirectory('essentialSpace');
 *   if (!dir) { // permission revoked — ask again }
 *
 *   // 3. Create / delete .nomedia
 *   await ScopedStorageService.createFile(dir.uri, '.nomedia', 'application/octet-stream');
 *   await ScopedStorageService.deleteFile(nomediaUri);
 *
 *   // 4. Check existence
 *   const files = await ScopedStorageService.listFiles(dir.uri);
 *   const exists = files.some(f => f.name === '.nomedia');
 */

import { Platform } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as ScopedStorage from 'react-native-scoped-storage';

const PERM_KEY_PREFIX = 'scoped_storage_dir_';

// ─── Helpers ──────────────────────────────────────────────────────────────────

function assertAndroid(method) {
  if (Platform.OS !== 'android') {
    throw Object.assign(
      new Error(`ScopedStorageService.${method} is Android-only`),
      { code: 'UNSUPPORTED' },
    );
  }
}

// ─── Directory access ─────────────────────────────────────────────────────────

/**
 * Open the system folder-picker so the user can grant access to a directory.
 *
 * @param {{ persist?: boolean }} options
 *   persist — whether to persist the URI permission across reboots (default: true)
 * @returns {Promise<import('react-native-scoped-storage').FileType>}
 *   The picked directory's FileType (uri, name, path, …)
 */
async function openDirectory(options = {}) {
  assertAndroid('openDirectory');
  const { persist = true } = options;
  const dir = await ScopedStorage.openDocumentTree(persist);
  return dir;
}

/**
 * Persist a directory URI in AsyncStorage under [key] for later retrieval.
 *
 * @param {string} key  App-defined identifier, e.g. 'essentialSpace'
 * @param {string} uri  Document-tree URI returned by openDirectory()
 */
async function saveDirectory(key, uri) {
  await AsyncStorage.setItem(`${PERM_KEY_PREFIX}${key}`, uri);
}

/**
 * Retrieve and validate a previously saved directory URI.
 *
 * Returns the FileType object if the permission is still granted,
 * or null if the user revoked access (or no URI was saved yet).
 *
 * @param {string} key
 * @returns {Promise<import('react-native-scoped-storage').FileType | null>}
 */
async function getSavedDirectory(key) {
  assertAndroid('getSavedDirectory');
  const uri = await AsyncStorage.getItem(`${PERM_KEY_PREFIX}${key}`);
  if (!uri) return null;

  // Validate the URI is still in the persisted-permission list
  const persistedUris = await ScopedStorage.getPersistedUriPermissions();
  if (!persistedUris.includes(uri)) {
    // Permission was revoked — clean up local storage
    await AsyncStorage.removeItem(`${PERM_KEY_PREFIX}${key}`);
    return null;
  }

  try {
    // stat() to get metadata (name, path, lastModified, etc.)
    const info = await ScopedStorage.stat(uri);
    return info ?? { uri };
  } catch {
    // Directory might still be accessible even if stat fails for some reasons
    return { uri };
  }
}

/**
 * Release the persisted URI permission and remove the saved key.
 *
 * @param {string} key
 */
async function forgetDirectory(key) {
  assertAndroid('forgetDirectory');
  const uri = await AsyncStorage.getItem(`${PERM_KEY_PREFIX}${key}`);
  if (uri) {
    try {
      await ScopedStorage.releasePersistableUriPermission(uri);
    } catch {
      /* ignore */
    }
    await AsyncStorage.removeItem(`${PERM_KEY_PREFIX}${key}`);
  }
}

/**
 * Get all currently persisted document-tree URIs.
 *
 * @returns {Promise<string[]>}
 */
async function getPersistedUris() {
  assertAndroid('getPersistedUris');
  return ScopedStorage.getPersistedUriPermissions();
}

// ─── File operations ──────────────────────────────────────────────────────────

/**
 * List all files and sub-directories inside a directory.
 *
 * @param {string} dirUri  Document-tree URI of the directory.
 * @returns {Promise<import('react-native-scoped-storage').FileType[]>}
 */
async function listFiles(dirUri) {
  assertAndroid('listFiles');
  return ScopedStorage.listFiles(dirUri);
}

/**
 * Create a new empty file inside a directory.
 *
 * @param {string} dirUri    Document-tree URI of the parent directory.
 * @param {string} fileName  Name of the file to create (e.g. '.nomedia').
 * @param {string} mimeType  MIME type (e.g. 'image/jpeg', 'application/octet-stream').
 * @returns {Promise<import('react-native-scoped-storage').FileType>}
 */
async function createFile(dirUri, fileName, mimeType = 'application/octet-stream') {
  assertAndroid('createFile');
  return ScopedStorage.createFile(dirUri, fileName, mimeType);
}

/**
 * Write [data] to an existing file URI.
 * If the file does not exist yet, it will be created (requires dirUri + fileName
 * or use createFile() first).
 *
 * @param {string} fileUri   Document-tree URI of the target file.
 * @param {string} data      Content to write.
 * @param {string} encoding  'utf8' | 'base64' | 'ascii'  (default: 'utf8')
 * @returns {Promise<string>}  Resolves to the file URI.
 */
async function writeFile(fileUri, data, encoding = 'utf8') {
  assertAndroid('writeFile');
  return ScopedStorage.writeFile(
    fileUri,
    data,
    undefined,
    undefined,
    encoding,
    false,
  );
}

/**
 * Read the text content of a file.
 *
 * @param {string} fileUri
 * @param {string} encoding  'utf8' | 'base64' | 'ascii'  (default: 'utf8')
 * @returns {Promise<string>}
 */
async function readFile(fileUri, encoding = 'utf8') {
  assertAndroid('readFile');
  return ScopedStorage.readFile(fileUri, encoding);
}

/**
 * Delete a file or directory at the given document URI.
 *
 * @param {string} uri  Document-tree URI of the file/directory to delete.
 * @returns {Promise<boolean>}  true if deletion succeeded.
 */
async function deleteFile(uri) {
  assertAndroid('deleteFile');
  return ScopedStorage.deleteFile(uri);
}

/**
 * Create a sub-directory inside a directory.
 *
 * @param {string} parentUri  Document-tree URI of the parent directory.
 * @param {string} dirName    Name for the new sub-directory.
 * @returns {Promise<import('react-native-scoped-storage').FileType>}
 */
async function createDirectory(parentUri, dirName) {
  assertAndroid('createDirectory');
  return ScopedStorage.createDirectory(parentUri, dirName);
}

// ─── High-level helpers ───────────────────────────────────────────────────────

/**
 * Find a file by name inside a directory.
 * Returns the FileType if found, or null.
 *
 * @param {string} dirUri
 * @param {string} fileName
 * @returns {Promise<import('react-native-scoped-storage').FileType | null>}
 */
async function findFile(dirUri, fileName) {
  assertAndroid('findFile');
  const files = await ScopedStorage.listFiles(dirUri);
  return files.find(f => f.name === fileName) ?? null;
}

/**
 * Ensure a .nomedia file exists inside [dirUri].
 * No-op if it already exists.
 *
 * Uses application/octet-stream MIME type (the wildcard *\/* is rejected by
 * many SAF providers on Samsung, Xiaomi, etc.).  If createFile still fails,
 * falls back to writeFile which creates-on-write.
 *
 * @param {string} dirUri
 * @returns {Promise<import('react-native-scoped-storage').FileType>}
 *   The .nomedia FileType (newly created or pre-existing).
 */
async function ensureNomedia(dirUri) {
  assertAndroid('ensureNomedia');
  const existing = await findFile(dirUri, '.nomedia');
  if (existing) return existing;

  try {
    const created = await ScopedStorage.createFile(
      dirUri,
      '.nomedia',
      'application/octet-stream',
    );
    if (created) return created;
  } catch (_) {
    // createFile failed — fall through to writeFile approach
  }

  // Fallback: writeFile creates the file if it doesn't exist
  await ScopedStorage.writeFile(
    dirUri,
    '',
    '.nomedia',
    'application/octet-stream',
    'utf8',
    false,
  );
  // Re-find the file to return a proper FileType object
  const created = await findFile(dirUri, '.nomedia');
  if (created) return created;
  throw new Error('Failed to create .nomedia file');
}

/**
 * Remove the .nomedia file from [dirUri] if it exists.
 * Resolves true if it was deleted, false if it didn't exist.
 *
 * @param {string} dirUri
 * @returns {Promise<boolean>}
 */
async function removeNomedia(dirUri) {
  assertAndroid('removeNomedia');
  const existing = await findFile(dirUri, '.nomedia');
  if (!existing) return false;
  return ScopedStorage.deleteFile(existing.uri);
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const ScopedStorageService = {
  // Directory access
  openDirectory,
  saveDirectory,
  getSavedDirectory,
  forgetDirectory,
  getPersistedUris,

  // File operations
  listFiles,
  createFile,
  writeFile,
  readFile,
  deleteFile,
  createDirectory,

  // High-level helpers
  findFile,
  ensureNomedia,
  removeNomedia,
};
