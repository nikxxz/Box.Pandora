/**
 * MLBridgeModule — JS wrapper for the MLBridgeModule native module.
 *
 * Native functions:
 *   labelImage(assetId)    → { text, confidence }[]   (ML Kit image labels)
 *   recognizeText(assetId) → string[]                 (ML Kit OCR lines)
 */

import { NativeModules, Platform } from 'react-native';

const Native = NativeModules.MLBridgeModule;

/**
 * Run ML Kit on-device image labeling for an asset (Android only).
 *
 * @param {string} assetId - CameraRoll content:// URI
 * @returns {Promise<Array<{text: string, confidence: number}>>}
 */
export async function labelImage(assetId) {
  if (Platform.OS !== 'android' || !Native) return [];
  try {
    const result = await Native.labelImage(assetId);
    return result ?? [];
  } catch (err) {
    console.warn('[MLBridge] labelImage error:', err);
    return [];
  }
}

/**
 * Run ML Kit text recognition (OCR) for an asset (Android only).
 *
 * @param {string} assetId - CameraRoll content:// URI
 * @returns {Promise<string[]>} lines of recognized text
 */
export async function recognizeText(assetId) {
  if (Platform.OS !== 'android' || !Native) return [];
  try {
    const result = await Native.recognizeText(assetId);
    return result ?? [];
  } catch (err) {
    console.warn('[MLBridge] recognizeText error:', err);
    return [];
  }
}
