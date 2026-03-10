import React, { createContext, useContext, useEffect, useReducer } from 'react';
import { DatabaseService } from '../services/database/DatabaseService';
import { PreferenceService } from '../services/database/PreferenceService';
import { EmbeddingIndexer } from '../services/ml/EmbeddingIndexer';
import { FaceIndexer } from '../services/ml/FaceIndexer';
import { appReducer, initialAppState } from './reducers';

const AppContext = createContext(null);

export function AppContextProvider({ children }) {
  const [state, dispatch] = useReducer(appReducer, initialAppState);

  // Hydrate persisted preferences once the DB is ready
  useEffect(() => {
    let cancelled = false;
    async function hydrate() {
      try {
        await DatabaseService.init();
        const savedAccent = await PreferenceService.getAccentColor();
        if (!cancelled && savedAccent) {
          dispatch({ type: 'SET_ACCENT_COLOR', payload: savedAccent });
        }
        const savedNsfw = await PreferenceService.get('nsfw_filter_enabled');
        if (!cancelled && savedNsfw !== null) {
          dispatch({ type: 'SET_NSFW_FILTER_ENABLED', payload: savedNsfw });
        }
        // ML model enabled flags — apply to indexers immediately
        const [sceneEnabled, faceEnabled] = await Promise.all([
          PreferenceService.getMlSceneEnabled(),
          PreferenceService.getMlFaceEnabled(),
        ]);
        if (!cancelled) {
          dispatch({ type: 'SET_ML_SCENE_ENABLED', payload: sceneEnabled });
          dispatch({ type: 'SET_ML_FACE_ENABLED', payload: faceEnabled });
          EmbeddingIndexer.setSceneIndexingEnabled(sceneEnabled);
          FaceIndexer.setFaceIndexingEnabled(faceEnabled);
        }
      } catch {
        // Non-fatal — falls back to theme default
      }
    }
    hydrate();
    return () => {
      cancelled = true;
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <AppContext.Provider value={{ state, dispatch }}>
      {children}
    </AppContext.Provider>
  );
}

export function useAppContext() {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error('useAppContext must be used inside AppProvider');
  return ctx;
}
