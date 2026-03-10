/**
 * shimmerState
 *
 * Tiny module-level pub/sub for scroll state.
 * MediaGrid writes to it; MediaThumbnail subscribes.
 *
 * Deliberately NOT React state — no re-renders, no bridge overhead.
 * Subscribers are called synchronously from the JS thread when MediaGrid's
 * scroll callbacks fire.
 */

let _isScrolling = false;
const _listeners = new Set();

export const ShimmerState = {
  get isScrolling() {
    return _isScrolling;
  },

  setScrolling(value) {
    if (_isScrolling === value) return; // no-op if unchanged
    _isScrolling = value;
    _listeners.forEach(cb => cb(value));
  },

  /** Returns an unsubscribe function. */
  subscribe(cb) {
    _listeners.add(cb);
    return () => _listeners.delete(cb);
  },
};
