/**
 * EmbeddingBridgeModule — JS wrapper for the EmbeddingBridgeModule native module.
 *
 * Native module: android/app/src/main/java/com/pandorabox/EmbeddingBridgeModule.kt
 *
 * Model spec:
 *   Name    : MobileNetV3-Large (100% depth, Float16 quantized)
 *   Asset   : android/app/src/main/assets/mobilenet_v3_large_224_embedding_fp16.tflite
 *   Input   : [1, 224, 224, 3]  FLOAT32  pixel/255.0  → range [0, 1]
 *   Output  : [1, 1280]         FLOAT32  Global Average Pool
 *   Post    : L2-normalized in native before returning
 *   Disk    : ~7 MB (Float16)
 *   Version : mv3l_224_fp16_v1
 *
 * Download model:
 *   https://tfhub.dev/google/lite-model/imagenet/mobilenet_v3_large_100_224/feature_vector/5/metadata/1
 *   Rename to mobilenet_v3_large_224_embedding_fp16.tflite
 *   Place at: android/app/src/main/assets/
 */

import { NativeModules, Platform } from 'react-native';
import { decodeEmbedding } from '../database/ImageEmbeddingService';

const Native = NativeModules.EmbeddingBridgeModule;

// ─── Model constants (mirrors Kotlin companion object) ────────────────────────
export const MODEL_VERSION = 'mv3l_224_fp16_v1';
export const EMBEDDING_DIM = 1280;
export const INPUT_SIZE = 224;

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Run TFLite inference on an image and return the L2-normalized embedding.
 *
 * @param {string} assetId  — content:// URI (Android) or ph:// URI (iOS)
 * @returns {Promise<{ dim: number, embedding: Float32Array, modelVersion: string }>}
 */
export async function embedImage(assetId) {
  if (!Native) {
    // Native module not available (iOS stub, or module not linked).
    return {
      dim: EMBEDDING_DIM,
      embedding: new Float32Array(EMBEDDING_DIM),
      modelVersion: MODEL_VERSION,
    };
  }
  try {
    const result = await Native.embedImage(assetId);
    return {
      dim: result.dim,
      embedding: decodeEmbedding(result.embedding),
      modelVersion: result.modelVersion,
    };
  } catch (err) {
    console.warn('[EmbeddingBridge] embedImage error:', err?.message ?? err);
    return {
      dim: EMBEDDING_DIM,
      embedding: new Float32Array(EMBEDDING_DIM),
      modelVersion: MODEL_VERSION,
    };
  }
}

/**
 * Return static model metadata (no I/O).
 *
 * @returns {Promise<{ modelVersion: string, dim: number, inputSize: number, modelAvailable: boolean }>}
 */
export async function getEmbeddingModelInfo() {
  if (!Native) {
    return {
      modelVersion: MODEL_VERSION,
      dim: EMBEDDING_DIM,
      inputSize: INPUT_SIZE,
      modelAvailable: false,
    };
  }
  try {
    return await Native.getEmbeddingModelInfo();
  } catch {
    return {
      modelVersion: MODEL_VERSION,
      dim: EMBEDDING_DIM,
      inputSize: INPUT_SIZE,
      modelAvailable: false,
    };
  }
}

/**
 * Return pixel-level statistics for heuristic detectors.
 *
 * @param {string} assetId
 * @returns {Promise<{
 *   meanR: number, meanG: number, meanB: number,    [0, 255]
 *   meanSaturation: number,                          [0, 1]
 *   meanLuminance: number,                           [0, 1]
 *   laplacianVariance: number                        higher = sharper
 * }>}
 */
export async function getImageStats(assetId) {
  if (!Native) {
    return {
      meanR: 128,
      meanG: 128,
      meanB: 128,
      meanSaturation: 0.5,
      meanLuminance: 0.5,
      laplacianVariance: 100,
    };
  }
  try {
    return await Native.getImageStats(assetId);
  } catch (err) {
    console.warn('[EmbeddingBridge] getImageStats error:', err?.message ?? err);
    return {
      meanR: 128,
      meanG: 128,
      meanB: 128,
      meanSaturation: 0.5,
      meanLuminance: 0.5,
      laplacianVariance: 100,
    };
  }
}
