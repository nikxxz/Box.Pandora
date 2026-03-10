/**
 * ModelManager
 *
 * Manages downloadable TFLite model files stored in the app's Documents
 * directory.  Models are versioned, SHA-256 verified, and atomically installed.
 * Previous versions are kept until the new one passes verification so a failed
 * download never leaves the app without a working model.
 *
 * Directory layout:
 *   DocumentDirectoryPath/models/
 *     {modelId}/
 *       {version}/
 *         model.tflite
 *       CURRENT.json   ← { version, sha256, installedAt }
 *
 * Usage:
 *   const path = ModelManager.getModelPath('mobilenetv3_scene');
 *   if (path) { /* use model *\/ }
 *
 *   await ModelManager.downloadAndInstall('mobilenetv3_scene', onProgress);
 */

import RNFS from 'react-native-fs';
import { reloadFaceModel } from './FaceBridgeModule';

// Bundled manifest (shipped with the APK as a fallback).
// When a live URL is available, checkForUpdates() fetches a fresh manifest.
import BUNDLED_MANIFEST from '../../assets/model_manifest.json';

// ─── Constants ────────────────────────────────────────────────────────────────

export const MODEL_IDS = {
  SCENE: 'mobilenetv3_scene',
  FACE: 'mobilefacenet',
};

const MODELS_ROOT = `${RNFS.DocumentDirectoryPath}/models`;

// Live manifest URL (optional — falls back to bundled copy if unreachable).
// Set to null to always use the bundled manifest.
const MANIFEST_URL = null; // e.g. 'https://raw.githubusercontent.com/.../model_manifest.json'

// ─── In-memory state ─────────────────────────────────────────────────────────

/** modelId → { version, sha256, installedAt, path } */
const _installed = {};

/** modelId → download progress 0–1 (undefined = not downloading) */
const _downloadProgress = {};

/** modelId → RNFS download job handle (for cancellation) */
const _activeJobs = {};

// ─── Init / scan ─────────────────────────────────────────────────────────────

/**
 * Scan the models directory and populate the _installed cache.
 * Call once at app startup (from AppProvider or similar).
 */
async function init() {
  try {
    await RNFS.mkdir(MODELS_ROOT);
    for (const modelId of Object.values(MODEL_IDS)) {
      await _loadCurrentState(modelId);
    }
  } catch (err) {
    console.warn('[ModelManager] init error:', err?.message ?? err);
  }
}

async function _loadCurrentState(modelId) {
  const currentPath = `${MODELS_ROOT}/${modelId}/CURRENT.json`;
  try {
    const json = await RNFS.readFile(currentPath, 'utf8');
    const current = JSON.parse(json);
    const modelPath = `${MODELS_ROOT}/${modelId}/${current.version}/model.tflite`;
    const exists = await RNFS.exists(modelPath);
    if (exists) {
      _installed[modelId] = { ...current, path: modelPath };
    } else {
      delete _installed[modelId];
    }
  } catch {
    delete _installed[modelId];
  }
}

// ─── Public queries ───────────────────────────────────────────────────────────

/**
 * Return the absolute path to the installed model file, or null if not installed.
 *
 * @param {string} modelId
 * @returns {string|null}
 */
function getModelPath(modelId) {
  return _installed[modelId]?.path ?? null;
}

/**
 * @param {string} modelId
 * @returns {string|null}
 */
function getInstalledVersion(modelId) {
  return _installed[modelId]?.version ?? null;
}

/**
 * @param {string} modelId
 * @returns {boolean}
 */
function isInstalled(modelId) {
  return !!_installed[modelId];
}

/**
 * @param {string} modelId
 * @returns {{ version, sha256, installedAt, path }|null}
 */
function getModelInfo(modelId) {
  return _installed[modelId] ?? null;
}

/**
 * Return download progress (0–1) for a model, or null if not downloading.
 *
 * @param {string} modelId
 * @returns {number|null}
 */
function getDownloadProgress(modelId) {
  return _downloadProgress[modelId] ?? null;
}

// ─── Manifest ─────────────────────────────────────────────────────────────────

/**
 * Fetch (or return bundled) manifest models list.
 *
 * @returns {Promise<Array>} manifest model entries
 */
async function checkForUpdates() {
  if (MANIFEST_URL) {
    try {
      const res = await fetch(MANIFEST_URL, {
        signal: AbortSignal.timeout?.(8000),
      });
      if (res.ok) {
        const manifest = await res.json();
        return manifest.models ?? [];
      }
    } catch (err) {
      console.warn(
        '[ModelManager] manifest fetch failed, using bundled:',
        err?.message,
      );
    }
  }
  return BUNDLED_MANIFEST.models ?? [];
}

/**
 * Get the manifest entry for a specific modelId.
 *
 * @param {string} modelId
 * @returns {Promise<object|null>}
 */
async function getManifestEntry(modelId) {
  const models = await checkForUpdates();
  return models.find(m => m.id === modelId) ?? null;
}

// ─── Download & Install ───────────────────────────────────────────────────────

/**
 * Download, verify, and atomically install a model.
 *
 * @param {string}   modelId
 * @param {function} [onProgress]  — (progress: 0–1) => void
 * @returns {Promise<string>} installed model file path
 */
async function downloadAndInstall(modelId, onProgress) {
  if (_activeJobs[modelId]) {
    throw new Error(`[ModelManager] Already downloading ${modelId}`);
  }

  const entry = await getManifestEntry(modelId);
  if (!entry) {
    throw new Error(`[ModelManager] No manifest entry for ${modelId}`);
  }

  const versionDir = `${MODELS_ROOT}/${modelId}/${entry.version}`;
  const finalPath = `${versionDir}/model.tflite`;
  const tempPath = `${versionDir}/model.tflite.tmp`;

  // Create version directory
  await RNFS.mkdir(versionDir);

  _downloadProgress[modelId] = 0;

  try {
    // ── Download ──────────────────────────────────────────────────────────
    const jobResult = await new Promise((resolve, reject) => {
      const job = RNFS.downloadFile({
        fromUrl: entry.url,
        toFile: tempPath,
        progress: res => {
          const pct =
            res.contentLength > 0 ? res.bytesWritten / res.contentLength : 0;
          _downloadProgress[modelId] = pct;
          onProgress?.(pct);
        },
        progressDivider: 5,
      });
      _activeJobs[modelId] = job;
      job.promise.then(resolve).catch(reject);
    });

    if (jobResult.statusCode < 200 || jobResult.statusCode >= 300) {
      throw new Error(`HTTP ${jobResult.statusCode}`);
    }

    // ── SHA-256 verification ──────────────────────────────────────────────
    if (entry.sha256 && !entry.sha256.startsWith('REPLACE_')) {
      const hash = await RNFS.hash(tempPath, 'sha256');
      if (hash.toLowerCase() !== entry.sha256.toLowerCase()) {
        await RNFS.unlink(tempPath).catch(() => {});
        throw new Error(`[ModelManager] SHA-256 mismatch for ${modelId}`);
      }
    }

    // ── Atomic install: rename temp → final ───────────────────────────────
    if (await RNFS.exists(finalPath)) {
      await RNFS.unlink(finalPath);
    }
    await RNFS.moveFile(tempPath, finalPath);

    // ── Write CURRENT.json ────────────────────────────────────────────────
    const currentPath = `${MODELS_ROOT}/${modelId}/CURRENT.json`;
    await RNFS.writeFile(
      currentPath,
      JSON.stringify({
        version: entry.version,
        sha256: entry.sha256,
        installedAt: Date.now(),
      }),
      'utf8',
    );

    // ── Update in-memory state ─────────────────────────────────────────────
    _installed[modelId] = {
      version: entry.version,
      sha256: entry.sha256,
      installedAt: Date.now(),
      path: finalPath,
    };

    _downloadProgress[modelId] = 1;
    onProgress?.(1);

    // ── Hot-swap: notify native bridge so it reloads from the new path ────
    if (modelId === MODEL_IDS.FACE) {
      reloadFaceModel(finalPath).catch(err =>
        console.warn(
          '[ModelManager] reloadFaceModel failed:',
          err?.message ?? err,
        ),
      );
    }

    console.log(
      `[ModelManager] Installed ${modelId} v${entry.version} → ${finalPath}`,
    );
    return finalPath;
  } catch (err) {
    // Rollback: delete temp file if it exists
    await RNFS.unlink(tempPath).catch(() => {});
    throw err;
  } finally {
    delete _activeJobs[modelId];
    delete _downloadProgress[modelId];
  }
}

/**
 * Cancel an in-progress download.
 *
 * @param {string} modelId
 */
function cancelDownload(modelId) {
  if (_activeJobs[modelId]) {
    RNFS.stopDownload(_activeJobs[modelId].jobId);
    delete _activeJobs[modelId];
    delete _downloadProgress[modelId];
  }
}

/**
 * Delete an installed model and clear its CURRENT.json.
 *
 * @param {string} modelId
 */
async function uninstall(modelId) {
  const modelDir = `${MODELS_ROOT}/${modelId}`;
  if (await RNFS.exists(modelDir)) {
    await RNFS.unlink(modelDir);
  }
  delete _installed[modelId];
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const ModelManager = {
  MODEL_IDS,
  init,
  getModelPath,
  getInstalledVersion,
  isInstalled,
  getModelInfo,
  getDownloadProgress,
  checkForUpdates,
  getManifestEntry,
  downloadAndInstall,
  cancelDownload,
  uninstall,
};
