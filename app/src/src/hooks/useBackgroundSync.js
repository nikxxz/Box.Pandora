/**
 * useBackgroundSync
 *
 * Mounts the BackgroundSyncService for the lifetime of the component that
 * calls this hook (typically App.js or AppNavigator).
 *
 * Usage:
 *   // In App.js or AppNavigator.js — call once at the root level
 *   useBackgroundSync();
 *
 * The hook also exposes helpers so any child component can pause/resume the
 * sync around heavyweight operations without importing the service directly.
 */

import { useEffect, useCallback } from 'react';
import { BackgroundSyncService } from '../services/media/BackgroundSyncService';

export function useBackgroundSync() {
  // Start the scheduler when the component mounts, stop on unmount.
  useEffect(() => {
    BackgroundSyncService.start();
    return () => BackgroundSyncService.stop();
  }, []);

  /** Call before a heavy operation (bulk import, forced re-index, ML pass…) */
  const notifyBusy = useCallback((reason = '') => {
    BackgroundSyncService.notifyBusy(reason);
  }, []);

  /** Call after the heavy operation completes. */
  const notifyIdle = useCallback(() => {
    BackgroundSyncService.notifyIdle();
  }, []);

  /**
   * Trigger an immediate incremental sync — useful after the user adds media
   * so previews / thumbnails warm up right away without waiting 3 minutes.
   */
  const syncNow = useCallback(() => {
    BackgroundSyncService.runNow();
  }, []);

  return { notifyBusy, notifyIdle, syncNow };
}
