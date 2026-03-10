import React, { createContext, useContext, useReducer } from 'react';
import { mediaReducer, initialMediaState } from './reducers';

const MediaContext = createContext(null);

export function MediaContextProvider({ children }) {
  const [state, dispatch] = useReducer(mediaReducer, initialMediaState);
  return (
    <MediaContext.Provider value={{ state, dispatch }}>
      {children}
    </MediaContext.Provider>
  );
}

export function useMediaContext() {
  const ctx = useContext(MediaContext);
  if (!ctx) throw new Error('useMediaContext must be used inside AppProvider');
  return ctx;
}
