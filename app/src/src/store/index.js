import React from 'react';
import { AppContextProvider } from './AppContext';
import { MediaContextProvider } from './MediaContext';

/**
 * Combined provider — wrap your app root with this once.
 */
export function AppProvider({ children }) {
  return (
    <AppContextProvider>
      <MediaContextProvider>{children}</MediaContextProvider>
    </AppContextProvider>
  );
}

export { useAppContext } from './AppContext';
export { useMediaContext } from './MediaContext';
export { AppActions, MediaActions } from './actions';
