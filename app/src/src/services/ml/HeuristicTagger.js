/**
 * HeuristicTagger
 *
 * Pure JS + lightweight native-stats heuristic detectors.
 * All detectors return { tagKey, score, detected } — no ML model required.
 *
 * Implemented detectors (v1):
 *   screenshot  — filename / directory pattern (pure JS; no native call)
 *   monochrome  — mean saturation < threshold (native pixel stats)
 *   night       — mean luminance < threshold (native pixel stats)
 *
 * Implemented detectors (v2):
 *   document    — native laplacian + OCR text presence (native pixel stats)
 *   blurry      — Laplacian variance < threshold (native pixel stats)
 *
 * Running all detectors:
 *   const results = await HeuristicTagger.runAll(assetId, mediaRecord);
 *   // results: Array<{ tagKey, score, detected }>
 *
 * The caller (SmartTagSuggestionEngine) only propagates hits where detected=true.
 */

import { getImageStats } from './EmbeddingBridgeModule';

// ─── Thresholds ───────────────────────────────────────────────────────────────
// Tune these empirically. Current values are conservative starting points:

/** Mean HSV-saturation below which an image is considered monochrome. */
const MONO_SAT_THRESHOLD = 0.08; // [0, 1]; lower = more strict

/** Mean luminance (0–1 scale) below which an image is considered night/dark. */
const NIGHT_LUMINANCE_THRESHOLD = 0.22; // [0, 1]; typical dark scene ≈ 0.15–0.20

/** Laplacian variance below which an image is considered blurry. */
const BLUR_LAP_THRESHOLD = 50.0; // empirical; sharper photos > 200

/** Mean saturation above which a photo is almost certainly colour. */
const COLOUR_SAT_CLEAR = 0.2;

// ─── Screenshot detector (pure JS) ───────────────────────────────────────────

// Known screenshot directory names (case-insensitive substring match).
const SCREENSHOT_DIRS = [
  'screenshot',
  'screenshots',
  'screen recordings',
  'screenrecord',
  'screen record',
  'screencap',
];

// Filename prefix/suffix patterns (lowercase).
const SCREENSHOT_PREFIXES = ['screenshot_', 'screen_shot_', 'capture_'];
const SCREENSHOT_SUFFIXES = ['_screenshot'];

/**
 * Detect screenshots using filename and album name heuristics.
 *
 * @param {{ filename?: string, album_name?: string, uri?: string }} mediaRecord
 *   — row from media_index (album_name may come from a joined query)
 * @returns {{ tagKey: 'screenshot', score: number, detected: boolean }}
 */
export function detectScreenshot(mediaRecord) {
  const filename = (mediaRecord?.filename ?? '').toLowerCase();
  const albumName = (mediaRecord?.album_name ?? '').toLowerCase();
  const uri = (mediaRecord?.uri ?? '').toLowerCase();

  // 1. Album is named "Screenshots" or similar.
  for (const dir of SCREENSHOT_DIRS) {
    if (albumName.includes(dir))
      return { tagKey: 'screenshot', score: 1.0, detected: true };
  }

  // 2. URI path contains a screenshot directory.
  for (const dir of SCREENSHOT_DIRS) {
    if (uri.includes('/' + dir + '/')) {
      return { tagKey: 'screenshot', score: 1.0, detected: true };
    }
  }

  // 3. Filename prefix patterns.
  for (const pfx of SCREENSHOT_PREFIXES) {
    if (filename.startsWith(pfx))
      return { tagKey: 'screenshot', score: 0.95, detected: true };
  }

  // 4. Filename suffix patterns (before extension).
  const nameWithoutExt = filename.replace(/\.[^.]+$/, '');
  for (const sfx of SCREENSHOT_SUFFIXES) {
    if (nameWithoutExt.endsWith(sfx)) {
      return { tagKey: 'screenshot', score: 0.9, detected: true };
    }
  }

  return { tagKey: 'screenshot', score: 0.0, detected: false };
}

// ─── Monochrome detector ──────────────────────────────────────────────────────

/**
 * Detect monochrome / black-and-white photos via mean HSV saturation.
 * Requires a native getImageStats call.
 *
 * @param {string} assetId
 * @returns {Promise<{ tagKey: 'monochrome', score: number, detected: boolean }>}
 */
export async function detectMonochrome(assetId) {
  const stats = await getImageStats(assetId);
  const sat = stats.meanSaturation ?? 0.5;

  if (sat < MONO_SAT_THRESHOLD) {
    // Map saturation to score: 0 sat → 1.0 score, threshold sat → 0.5 score.
    const score = 1.0 - (sat / MONO_SAT_THRESHOLD) * 0.5;
    return { tagKey: 'monochrome', score, detected: true };
  }
  if (sat >= COLOUR_SAT_CLEAR) {
    // Clearly coloured — definitely not monochrome.
    return { tagKey: 'monochrome', score: 0.0, detected: false };
  }
  return { tagKey: 'monochrome', score: 0.0, detected: false };
}

// ─── Night / low-light detector ───────────────────────────────────────────────

/**
 * Detect night or low-light photos via mean luminance.
 * Requires a native getImageStats call.
 *
 * @param {string} assetId
 * @returns {Promise<{ tagKey: 'night', score: number, detected: boolean }>}
 */
export async function detectNight(assetId) {
  const stats = await getImageStats(assetId);
  const lum = stats.meanLuminance ?? 0.5;

  if (lum < NIGHT_LUMINANCE_THRESHOLD) {
    // Map to score: 0 lum → 1.0 score, threshold lum → 0.5 score.
    const score = 1.0 - (lum / NIGHT_LUMINANCE_THRESHOLD) * 0.5;
    return { tagKey: 'night', score, detected: true };
  }
  return { tagKey: 'night', score: 0.0, detected: false };
}

// ─── Blurry detector ─────────────────────────────────────────────────────────

/**
 * Detect blurry images via Laplacian variance.
 * Requires a native getImageStats call.
 *
 * @param {string} assetId
 * @returns {Promise<{ tagKey: 'blurry', score: number, detected: boolean }>}
 */
export async function detectBlurry(assetId) {
  const stats = await getImageStats(assetId);
  const lapVar = stats.laplacianVariance ?? 999;

  if (lapVar < BLUR_LAP_THRESHOLD) {
    // Score: 0 variance → 1.0, threshold variance → 0.5
    const score = Math.max(0.5, 1.0 - (lapVar / BLUR_LAP_THRESHOLD) * 0.5);
    return { tagKey: 'blurry', score, detected: true };
  }
  return { tagKey: 'blurry', score: 0.0, detected: false };
}

// ─── Document detector (v2 — stub in v1) ─────────────────────────────────────

/**
 * Detect document / text-heavy images.
 * v1: uses high-luminance + very low saturation heuristic as a proxy.
 * v2: wire in OCR text character count from recognizeText().
 *
 * @param {string} assetId
 * @param {{ filename?: string }} mediaRecord
 * @returns {Promise<{ tagKey: 'document', score: number, detected: boolean }>}
 */
export async function detectDocument(assetId, _mediaRecord = {}) {
  const stats = await getImageStats(assetId);
  const sat = stats.meanSaturation ?? 0.5;
  const lum = stats.meanLuminance ?? 0.5;

  // Typical document: high luminance (light background), very low saturation.
  // These are loose thresholds — OCR-based v2 will be more precise.
  const highLuminance = lum > 0.7;
  const veryLowSat = sat < 0.06;

  if (highLuminance && veryLowSat) {
    return { tagKey: 'document', score: 0.7, detected: true };
  }
  return { tagKey: 'document', score: 0.0, detected: false };
}

// ─── runAll — convenience wrapper ────────────────────────────────────────────

/**
 * Run all v1 heuristic detectors for a single asset.
 *
 * Screenshot uses only the mediaRecord; all others need a native stats call
 * (they share a single getImageStats call to avoid duplicating the bitmap decode).
 *
 * @param {string} assetId
 * @param {{ filename?: string, album_name?: string, uri?: string }} mediaRecord
 * @returns {Promise<Array<{ tagKey: string, score: number, detected: boolean }>>}
 */
export async function runAll(assetId, mediaRecord = {}) {
  // Screenshot is pure JS — no native call needed.
  const screenshotResult = detectScreenshot(mediaRecord);

  // All pixel-stats-based detectors share one native call.
  let stats;
  try {
    stats = await getImageStats(assetId);
  } catch {
    stats = { meanSaturation: 0.5, meanLuminance: 0.5, laplacianVariance: 200 };
  }

  const sat = stats.meanSaturation ?? 0.5;
  const lum = stats.meanLuminance ?? 0.5;
  const lapVar = stats.laplacianVariance ?? 200;

  // Monochrome
  let monoResult;
  if (sat < MONO_SAT_THRESHOLD) {
    const score = 1.0 - (sat / MONO_SAT_THRESHOLD) * 0.5;
    monoResult = { tagKey: 'monochrome', score, detected: true };
  } else {
    monoResult = { tagKey: 'monochrome', score: 0.0, detected: false };
  }

  // Night
  let nightResult;
  if (lum < NIGHT_LUMINANCE_THRESHOLD) {
    const score = 1.0 - (lum / NIGHT_LUMINANCE_THRESHOLD) * 0.5;
    nightResult = { tagKey: 'night', score, detected: true };
  } else {
    nightResult = { tagKey: 'night', score: 0.0, detected: false };
  }

  // Blurry
  let blurryResult;
  if (lapVar < BLUR_LAP_THRESHOLD) {
    const score = Math.max(0.5, 1.0 - (lapVar / BLUR_LAP_THRESHOLD) * 0.5);
    blurryResult = { tagKey: 'blurry', score, detected: true };
  } else {
    blurryResult = { tagKey: 'blurry', score: 0.0, detected: false };
  }

  // Document
  const highLuminance = lum > 0.7;
  const veryLowSat = sat < 0.06;
  const documentResult =
    highLuminance && veryLowSat
      ? { tagKey: 'document', score: 0.7, detected: true }
      : { tagKey: 'document', score: 0.0, detected: false };

  return [
    screenshotResult,
    monoResult,
    nightResult,
    documentResult,
    blurryResult,
  ];
}

export const HeuristicTagger = {
  detectScreenshot,
  detectMonochrome,
  detectNight,
  detectBlurry,
  detectDocument,
  runAll,
  // Exposed constants for debug/test
  MONO_SAT_THRESHOLD,
  NIGHT_LUMINANCE_THRESHOLD,
  BLUR_LAP_THRESHOLD,
};
