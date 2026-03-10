/**
 * StandbyService
 *
 * Power-saver / standby mode for when the app is moved to the background.
 * Keeps the app alive (process not killed) but cancels CPU/RAM-heavy work so
 * the OS does not throttle or OOM-kill the process.
 *
 * What it does on SUSPEND (app → background):
 *   1. Waits SUSPEND_DELAY_MS before acting — tolerates quick app switches
 *      (e.g. user swipes to another app for 2 seconds then comes back).
 *   2. Cancels any in-progress FaceIndexer run.
 *   3. Cancels any in-progress EmbeddingIndexer run.
 *   4. Clears the in-memory image LRU cache (frees JS-heap RAM; disk cache
 *      is untouched so images are still fast on resume).
 *
 * What it does on WAKE (app → foreground):
 *   1. Cancels the pending suspend timer (if the app came back quickly).
 *   2. Marks the service as active again.
 *   3. Notifies all registered listeners — hooks / screens can react.
 *
 * Usage:
 *   // Mount once at the navigation root (AppNavigator)
 *   useStandbyMode();
 *
 *   // Or use the service directly
 *   StandbyService.start();
 *   const remove = StandbyService.addListener(isStandby => { ... });
 *   StandbyService.stop();        // on unmount
 *   StandbyService.isStandby();   // synchronous read
 */

import { AppState } from 'react-native';
import { FaceIndexer } from './ml/FaceIndexer';
import { EmbeddingIndexer } from './ml/EmbeddingIndexer';
import { ImageCache } from './cache/ImageCache';
import { FaceClusterService } from './database/FaceClusterService';

// ─── Tuning ───────────────────────────────────────────────────────────────────

/**
 * How long (ms) the app must remain backgrounded before we actually suspend.
 * Short app-switches (notification shade, quick settings, etc.) are ignored.
 */
const SUSPEND_DELAY_MS = 3_000;

// ─── Module-level state ───────────────────────────────────────────────────────

let _appStateSub = null; // AppState subscription handle
let _suspendTimer = null; // setTimeout handle for the deferred suspend
let _standby = false; // current standby state

/** Set of (isStandby: boolean) => void callbacks. */
const _listeners = new Set();

// ─── Internal helpers ─────────────────────────────────────────────────────────

function _notifyListeners() {
  _listeners.forEach(fn => {
    try {
      fn(_standby);
    } catch (err) {
      console.warn('[StandbyService] listener error:', err);
    }
  });
}

/**
 * Actually perform the suspend.  Called only after SUSPEND_DELAY_MS has
 * elapsed with the app still in the background.
 */
function _suspend() {
  if (_standby) return; // already suspended
  _standby = true;

  // ── Cancel CPU/GPU-heavy ML work ─────────────────────────────────────────
  try {
    FaceIndexer.cancel();
  } catch (e) {
    console.warn('[StandbyService] FaceIndexer.cancel error:', e);
  }
  try {
    EmbeddingIndexer.cancel();
  } catch (e) {
    console.warn('[StandbyService] EmbeddingIndexer.cancel error:', e);
  }

  // ── Free RAM: clear in-memory image metadata LRU ─────────────────────────
  // Disk cache is kept so images reload quickly on resume.
  try {
    ImageCache.clearAll();
  } catch (e) {
    console.warn('[StandbyService] ImageCache.clearAll error:', e);
  }

  // ── Free RAM: release face cluster Float32Array cache ──────────────────
  // The cache can hold thousands of 128–512-dim Float32Arrays.  It will be
  // lazily rebuilt from the DB the next time face suggestions are needed.
  try {
    FaceClusterService.invalidateClusterCache();
  } catch (e) {
    console.warn('[StandbyService] invalidateClusterCache error:', e);
  }

  _notifyListeners();
  console.log(
    '[StandbyService] SUSPENDED — ML jobs cancelled, image cache trimmed',
  );
}

/**
 * Transition back to active state.
 */
function _wake() {
  if (!_standby) return; // already awake
  _standby = false;
  _notifyListeners();
  console.log('[StandbyService] ACTIVE');
}

/**
 * AppState change handler.
 * @param {'active'|'background'|'inactive'} nextState
 */
function _handleAppState(nextState) {
  if (nextState === 'active') {
    // Cancel any pending suspend and immediately wake.
    if (_suspendTimer !== null) {
      clearTimeout(_suspendTimer);
      _suspendTimer = null;
    }
    _wake();
  } else {
    // 'background' or 'inactive' — defer suspend so quick switches are ignored.
    if (_suspendTimer === null && !_standby) {
      _suspendTimer = setTimeout(() => {
        _suspendTimer = null;
        _suspend();
      }, SUSPEND_DELAY_MS);
    }
  }
}

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Start watching AppState.  Call once at the root level (via useStandbyMode).
 * Idempotent — safe to call multiple times.
 */
function start() {
  if (_appStateSub) return;
  _standby = false;
  _appStateSub = AppState.addEventListener('change', _handleAppState);
  console.log('[StandbyService] started');
}

/**
 * Stop watching AppState and cancel any pending timer.
 */
function stop() {
  if (_suspendTimer !== null) {
    clearTimeout(_suspendTimer);
    _suspendTimer = null;
  }
  if (_appStateSub) {
    _appStateSub.remove();
    _appStateSub = null;
  }
  _standby = false;
  console.log('[StandbyService] stopped');
}

/**
 * Returns true while the app is suspended (backgrounded beyond delay).
 */
function isStandby() {
  return _standby;
}

/**
 * Register a listener that fires whenever standby state changes.
 * @param {(isStandby: boolean) => void} fn
 * @returns {() => void} removal function
 */
function addListener(fn) {
  _listeners.add(fn);
  return () => _listeners.delete(fn);
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const StandbyService = {
  start,
  stop,
  isStandby,
  addListener,
  /** Exposed so callers can build UI tooltips or debug logs. */
  SUSPEND_DELAY_MS,
};
