/**
 * Formatting utilities for dates, file sizes, and counts.
 */

/**
 * Format a Unix timestamp (seconds) into a relative label.
 * e.g. "Today", "Yesterday", "3 days ago", "Jan 2025"
 */
export function formatRelativeDate(timestampSeconds) {
  if (!timestampSeconds) return '';
  const date = new Date(timestampSeconds * 1000);
  const now = new Date();
  const diffMs = now - date;
  const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));

  if (diffDays === 0) return 'Today';
  if (diffDays === 1) return 'Yesterday';
  if (diffDays < 7) return `${diffDays} days ago`;
  if (diffDays < 30)
    return `${Math.floor(diffDays / 7)} week${diffDays >= 14 ? 's' : ''} ago`;
  return date.toLocaleDateString('en-US', { month: 'short', year: 'numeric' });
}

/**
 * Split a Unix timestamp into { month: 'Dec', day: '05', year: '2020' } for
 * the date display on FolderCard.
 */
export function formatShortDateParts(timestampSeconds) {
  if (!timestampSeconds) return { month: '', day: '--', year: '' };
  const d = new Date(timestampSeconds * 1000);
  const month = d.toLocaleDateString('en-US', { month: 'short' });
  const day = String(d.getDate()).padStart(2, '0');
  const year = String(d.getFullYear());
  return { month, day, year };
}

/**
 * Format a Unix timestamp to short date: "05 Dec 25"
 */
export function formatShortDate(timestampSeconds) {
  if (!timestampSeconds) return '';
  const d = new Date(timestampSeconds * 1000);
  const day = String(d.getDate()).padStart(2, '0');
  const month = d.toLocaleDateString('en-US', { month: 'short' });
  const year = String(d.getFullYear()).slice(2);
  return `${day} ${month} ${year}`;
}

/**
 * Format a Unix timestamp to a full date string: "24 Feb 2026"
 */
export function formatFullDate(timestampSeconds) {
  if (!timestampSeconds) return '';
  return new Date(timestampSeconds * 1000).toLocaleDateString('en-US', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
}

/**
 * Format bytes into KB / MB / GB.
 */
export function formatFileSize(bytes = 0) {
  if (bytes === 0) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
}

/**
 * Format a count with a compact suffix: 1.2K, 3.5M, etc.
 */
export function formatCount(n = 0) {
  if (n < 1000) return String(n);
  if (n < 1_000_000) return `${(n / 1000).toFixed(1)}K`;
  return `${(n / 1_000_000).toFixed(1)}M`;
}

/**
 * Format image dimensions as "1920 × 1080".
 */
export function formatDimensions(width, height) {
  if (!width || !height) return '';
  return `${width} × ${height}`;
}

/**
 * Format video duration in seconds to "mm:ss" or "h:mm:ss".
 */
export function formatDuration(seconds = 0) {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0)
    return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  return `${m}:${String(s).padStart(2, '0')}`;
}

/**
 * Format a Unix-seconds timestamp as "DD Month YYYY HH:MM".
 * Returns '' when input is falsy.
 */
export function formatDateTimeDisplay(timestampSeconds) {
  if (!timestampSeconds) return '';
  const d = new Date(timestampSeconds * 1000);
  const day = d.getDate();
  const month = d.toLocaleDateString('en-US', { month: 'long' });
  const year = d.getFullYear();
  const hours = String(d.getHours()).padStart(2, '0');
  const minutes = String(d.getMinutes()).padStart(2, '0');
  return `${day} ${month} ${year} ${hours}:${minutes}`;
}
