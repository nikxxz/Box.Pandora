/**
 * Utilities for working with file paths and URIs on Android.
 */

const IMAGE_EXTENSIONS = [
  'jpg',
  'jpeg',
  'png',
  'gif',
  'webp',
  'heic',
  'heif',
  'bmp',
  'tiff',
];
const VIDEO_EXTENSIONS = [
  'mp4',
  'mov',
  'avi',
  'mkv',
  'webm',
  '3gp',
  'wmv',
  'm4v',
];
const AUDIO_EXTENSIONS = [
  'mp3',
  'aac',
  'wav',
  'flac',
  'ogg',
  'm4a',
  'wma',
  'opus',
];

/**
 * Extract filename from a URI or full path.
 * Works with both content:// and file:// URIs.
 */
export function getFilename(uri = '') {
  return uri.split('/').pop() || '';
}

/**
 * Strip the last extension from a filename.
 * 'photo.jpg' → 'photo'   'video.mp4' → 'video'   'noext' → 'noext'
 */
export function stripExtension(filename = '') {
  const lastDot = filename.lastIndexOf('.');
  return lastDot > 0 ? filename.substring(0, lastDot) : filename;
}

/**
 * Extract file extension (lowercase, no dot).
 */
export function getExtension(uri = '') {
  const filename = getFilename(uri);
  const parts = filename.split('.');
  return parts.length > 1 ? parts.pop().toLowerCase() : '';
}

/**
 * Determine media type from URI / mime string.
 * Returns 'image' | 'video' | 'audio' | 'unknown'
 */
export function getMediaType(uri = '', mimeType = '') {
  if (mimeType) {
    if (mimeType.startsWith('image/')) return 'image';
    if (mimeType.startsWith('video/')) return 'video';
    if (mimeType.startsWith('audio/')) return 'audio';
  }
  const ext = getExtension(uri);
  if (IMAGE_EXTENSIONS.includes(ext)) return 'image';
  if (VIDEO_EXTENSIONS.includes(ext)) return 'video';
  if (AUDIO_EXTENSIONS.includes(ext)) return 'audio';
  return 'unknown';
}

/**
 * Check whether two URIs point to the same file.
 */
export function isSameFile(uriA = '', uriB = '') {
  return uriA.replace(/\/$/, '') === uriB.replace(/\/$/, '');
}

/**
 * Convert a content:// URI to a human-readable display path (best-effort).
 * Actual path resolution requires native modules (e.g. react-native-fs).
 */
export function getDisplayPath(uri = '') {
  if (!uri.startsWith('content://')) return uri;
  // Strip the authority and return the path segment
  try {
    const url = new URL(uri);
    return decodeURIComponent(url.pathname);
  } catch {
    return uri;
  }
}

/**
 * Sanitize a string for use as a filename (no slashes, colons, etc.).
 */
export function sanitizeFilename(name = '') {
  return name.replace(/[/\\:*?"<>|]/g, '_').trim();
}

// ─── MIME type map (extension → MIME) ──────────────────────────────────────

const EXT_TO_MIME = {
  // images
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  gif: 'image/gif',
  webp: 'image/webp',
  heic: 'image/heic',
  heif: 'image/heif',
  bmp: 'image/bmp',
  tiff: 'image/tiff',
  tif: 'image/tiff',
  svg: 'image/svg+xml',
  // videos
  mp4: 'video/mp4',
  mov: 'video/quicktime',
  mkv: 'video/x-matroska',
  avi: 'video/x-msvideo',
  webm: 'video/webm',
  '3gp': 'video/3gpp',
  wmv: 'video/x-ms-wmv',
  m4v: 'video/x-m4v',
  // audio
  mp3: 'audio/mpeg',
  aac: 'audio/aac',
  wav: 'audio/wav',
  flac: 'audio/flac',
  ogg: 'audio/ogg',
  m4a: 'audio/mp4',
  wma: 'audio/x-ms-wma',
  opus: 'audio/opus',
};

/**
 * Derive the best MIME type for a media item.
 *
 * Resolution order:
 *   1. item.type when it is a full MIME type (contains '/')
 *   2. Extension of item.filename
 *   3. Extension extracted from item.uri path
 *   4. Wildcard from partial item.type ('image' → 'image/*', etc.)
 *   5. Fallback: 'application/octet-stream'
 *
 * @param {{ type?: string, filename?: string, uri?: string }} item
 * @returns {string}  MIME type string
 */
export function getMimeTypeForItem(item) {
  // Only use item.type when it is a proper MIME type (e.g. 'image/jpeg').
  // CameraRoll and our DB store partial tokens like 'image' or 'video' —
  // those are NOT valid Android MIME types and cause "No apps can perform
  // this action" when passed to ACTION_SEND.
  if (item?.type?.includes('/')) return item.type;

  // Try to resolve from the file extension first.
  const name = item?.filename ?? item?.uri ?? '';
  const ext = name.split('.').pop()?.toLowerCase();
  const fromExt = EXT_TO_MIME[ext];
  if (fromExt) return fromExt;

  // Fall back to a wildcard MIME derived from the partial type token.
  if (item?.type === 'video') return 'video/*';
  if (item?.type === 'image') return 'image/*';
  if (item?.type === 'audio') return 'audio/*';
  return 'application/octet-stream';
}

// Derive the broadest common MIME type category for an array of media items.
// Used for multi-select share sheets so Android filters the chooser correctly:
//   All images -> 'image/*', all videos -> 'video/*', mixed -> wildcard.
export function getCommonMimeType(items = []) {
  if (!items.length) return '*/*';
  const mimes = items.map(getMimeTypeForItem);
  const allImages = mimes.every(m => m.startsWith('image/'));
  if (allImages) return 'image/*';
  const allVideos = mimes.every(m => m.startsWith('video/'));
  if (allVideos) return 'video/*';
  return '*/*';
}
