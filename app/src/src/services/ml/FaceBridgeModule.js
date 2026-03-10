/**
 * FaceBridgeModule — JS wrapper for the FaceBridgeModule native module.
 *
 * Native module: android/app/src/main/java/com/pandorabox/FaceBridgeModule.kt
 *
 * Face detection model:
 *   ML Kit Face Detection — auto-downloaded via Play Services on first use.
 *   No file to manage; always available.
 *
 * Face embedding model:
 *   Downloaded via ModelManager to:
 *   DocumentDirectory/models/mobilefacenet/{version}/model.tflite
 *   Input : [1, 112, 112, 3]  FLOAT32  pixel/128.0 - 1.0  → range [-1, 1]
 *
 *   Supported models (output dim is read dynamically from the loaded model):
 *     MobileFaceNet        — Output: [1, 128]  FLOAT32  (~1 MB fp32)
 *     ArcFace ResNet100 fp16 — Output: [1, 512]  FLOAT32  (~120 MB fp16)
 *
 * All functions return graceful defaults when the native module or model is
 * unavailable, so callers never need to guard against exceptions.
 */

import { NativeModules } from 'react-native';
import { decodeEmbedding } from '../database/ImageEmbeddingService';

const Native = NativeModules.FaceBridgeModule;

export const FACE_MODEL_VERSION = 'mobilefacenet_v1';
/** Fallback dim used only when no model is loaded (MobileFaceNet). */
export const FACE_EMBEDDING_DIM = 128;
/** Named constant for MobileFaceNet output size. */
export const FACE_EMBEDDING_DIM_128 = 128;
/** Named constant for ArcFace ResNet100 output size. */
export const FACE_EMBEDDING_DIM_512 = 512;
export const FACE_INPUT_SIZE = 112;

// ─── Hot-swap ─────────────────────────────────────────────────────────────────

/**
 * Reload the MobileFaceNet TFLite model from a downloaded file path.
 * Called by ModelManager after a successful download + verification.
 *
 * @param {string} filePath  — absolute path to the .tflite file
 * @returns {Promise<boolean>}
 */
export async function reloadFaceModel(filePath) {
  if (!Native) return false;
  try {
    return await Native.reloadFaceModel(filePath);
  } catch (err) {
    console.warn('[FaceBridge] reloadFaceModel error:', err?.message ?? err);
    return false;
  }
}

// ─── Model info ───────────────────────────────────────────────────────────────

/**
 * Return face model metadata (no inference).
 *
 * @returns {Promise<{ modelAvailable: boolean, modelVersion: string, outputDim: number, inputSize: number }>}
 */
export async function getFaceModelInfo() {
  if (!Native) {
    return {
      modelAvailable: false,
      modelVersion: FACE_MODEL_VERSION,
      outputDim: FACE_EMBEDDING_DIM,
      inputSize: FACE_INPUT_SIZE,
    };
  }
  try {
    return await Native.getFaceModelInfo();
  } catch {
    return {
      modelAvailable: false,
      modelVersion: FACE_MODEL_VERSION,
      outputDim: FACE_EMBEDDING_DIM,
      inputSize: FACE_INPUT_SIZE,
    };
  }
}

/**
 * Quick synchronous check — returns true only if the native module is linked.
 * Actual model availability requires an async call to getFaceModelInfo().
 */
export function isNativeAvailable() {
  return !!Native;
}

// ─── Face detection ───────────────────────────────────────────────────────────

/**
 * Detect faces in an image.
 *
 * @param {string} assetId  — content:// URI
 * @returns {Promise<Array<{
 *   faceId: string,
 *   faceIndex: number,
 *   leftNorm: number, topNorm: number, rightNorm: number, bottomNorm: number,
 *   widthPx: number, heightPx: number,
 *   yaw: number, pitch: number, roll: number,
 *   qualityScore: number,
 *   leftEyeOpen: number, rightEyeOpen: number, smileProb: number,
 * }>>}
 */
export async function detectFaces(assetId) {
  if (!Native) return [];
  try {
    const results = await Native.detectFaces(assetId);
    // results is a WritableArray from Kotlin — cast to plain array
    return Array.isArray(results) ? results : Array.from(results);
  } catch (err) {
    console.warn('[FaceBridge] detectFaces error:', err?.message ?? err);
    return [];
  }
}

// ─── Face embedding ───────────────────────────────────────────────────────────

/**
 * Compute a MobileFaceNet embedding for a face crop.
 *
 * @param {string} assetId
 * @param {{ leftNorm, topNorm, rightNorm, bottomNorm }} bbox  — normalised 0–1
 * @returns {Promise<{ embedding: Float32Array, dim: number, modelVersion: string, modelAvailable: boolean }>}
 */
export async function embedFace(assetId, bbox) {
  const empty = {
    embedding: new Float32Array(FACE_EMBEDDING_DIM),
    dim: FACE_EMBEDDING_DIM,
    modelVersion: FACE_MODEL_VERSION,
    modelAvailable: false,
  };

  if (!Native) return empty;

  try {
    const result = await Native.embedFace(
      assetId,
      bbox.leftNorm,
      bbox.topNorm,
      bbox.rightNorm,
      bbox.bottomNorm,
    );
    return {
      embedding: decodeEmbedding(result.embedding),
      dim: result.dim,
      modelVersion: result.modelVersion,
      modelAvailable: result.modelAvailable,
    };
  } catch (err) {
    console.warn('[FaceBridge] embedFace error:', err?.message ?? err);
    return empty;
  }
}

export const FaceBridgeModule = {
  reloadFaceModel,
  getFaceModelInfo,
  isNativeAvailable,
  detectFaces,
  embedFace,
  FACE_MODEL_VERSION,
  FACE_EMBEDDING_DIM,
  FACE_EMBEDDING_DIM_128,
  FACE_EMBEDDING_DIM_512,
  FACE_INPUT_SIZE,
};
