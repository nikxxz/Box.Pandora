/**
 * useStandbyMode
 *
 * Mounts StandbyService for the lifetime of the component that calls this
 * hook (typically AppNavigator — the navigation root).
 *
 * The hook also returns the current standby state so any child can read it:
 *
 *   const { isStandby } = useStandbyMode();
 *
 * Standby activates after the app has been in the background for
 * StandbyService.SUSPEND_DELAY_MS (default 3 s) and deactivates the moment
 * the app returns to the foreground.  While suspended, CPU/RAM-heavy ML
 * indexers are cancelled and the in-memory image cache is flushed.
 */

import { useEffect, useState } from 'react';
import { StandbyService } from '../services/StandbyService';

export function useStandbyMode() {
  const [isStandby, setIsStandby] = useState(false);

  useEffect(() => {
    // Start the AppState watcher.
    StandbyService.start();

    // Mirror standby state into React so hooks / screens can subscribe.
    const removeListener = StandbyService.addListener(setIsStandby);

    return () => {
      removeListener();
      StandbyService.stop();
    };
  }, []);

  return { isStandby };
}
