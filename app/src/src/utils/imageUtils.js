import { Dimensions } from 'react-native';

const { width: SCREEN_WIDTH } = Dimensions.get('window');

/**
 * Calculate scaled dimensions that fit within maxWidth × maxHeight
 * while preserving aspect ratio.
 */
export function scaleToFit(width, height, maxWidth = SCREEN_WIDTH, maxHeight = SCREEN_WIDTH) {
  if (!width || !height) return { width: maxWidth, height: maxHeight };
  const ratio = Math.min(maxWidth / width, maxHeight / height);
  return {
    width: Math.round(width * ratio),
    height: Math.round(height * ratio),
  };
}

/**
 * Calculate aspect ratio as a decimal (width / height).
 */
export function getAspectRatio(width, height) {
  if (!height) return 1;
  return width / height;
}

/**
 * Determine if an image is portrait, landscape, or square.
 */
export function getOrientation(width, height) {
  if (!width || !height) return 'unknown';
  const ratio = width / height;
  if (ratio > 1.05) return 'landscape';
  if (ratio < 0.95) return 'portrait';
  return 'square';
}

/**
 * Build a thumbnail grid layout for variable-size images (Pinterest-style).
 * Returns items with pre-computed height for a fixed column width.
 */
export function buildGridLayout(items = [], columnWidth = SCREEN_WIDTH / 2) {
  return items.map((item) => {
    const ratio = getAspectRatio(item.width || 1, item.height || 1);
    return {
      ...item,
      displayWidth: columnWidth,
      displayHeight: Math.round(columnWidth / ratio),
    };
  });
}

/**
 * Format file size in bytes to a human-readable string.
 * (Duplicate of formatters.js — kept here for image-specific convenience.)
 */
export function formatFileSize(bytes = 0) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
