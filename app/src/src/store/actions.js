// ─── App actions ──────────────────────────────────────────────────────────────

export const AppActions = {
  setLoading: v => ({ type: 'SET_LOADING', payload: v }),
  setPermissions: v => ({ type: 'SET_PERMISSIONS', payload: v }),
  setError: v => ({ type: 'SET_ERROR', payload: v }),
  clearError: () => ({ type: 'CLEAR_ERROR' }),
  setTheme: v => ({ type: 'SET_THEME', payload: v }),
  setMediaFilter: v => ({ type: 'SET_MEDIA_FILTER', payload: v }),
  setShowHidden: v => ({ type: 'SET_SHOW_HIDDEN', payload: v }),
  setSortBy: v => ({ type: 'SET_SORT_BY', payload: v }),
  setAccentColor: v => ({ type: 'SET_ACCENT_COLOR', payload: v }),
  setNsfwFilterEnabled: v => ({ type: 'SET_NSFW_FILTER_ENABLED', payload: v }),
  setMlSceneEnabled: v => ({ type: 'SET_ML_SCENE_ENABLED', payload: v }),
  setMlFaceEnabled: v => ({ type: 'SET_ML_FACE_ENABLED', payload: v }),
};

// ─── Media actions ────────────────────────────────────────────────────────────

export const MediaActions = {
  setIndex: v => ({ type: 'SET_INDEX', payload: v }),
  setIsIndexing: v => ({ type: 'SET_IS_INDEXING', payload: v }),
  setSelectedFolder: v => ({ type: 'SET_SELECTED_FOLDER', payload: v }),
  setCurrentMedia: v => ({ type: 'SET_CURRENT_MEDIA', payload: v }),

  addFavorite: uri => ({ type: 'ADD_FAVORITE', payload: uri }),
  removeFavorite: uri => ({ type: 'REMOVE_FAVORITE', payload: uri }),
  toggleFavorite: uri => ({ type: 'TOGGLE_FAVORITE', payload: uri }),
  setFavorites: list => ({ type: 'SET_FAVORITES', payload: list }),

  /**
   * Remove a batch of URIs from favorites and tags (called after file deletion).
   * @param {string[]} uris
   */
  removeMediaItems: uris => ({ type: 'REMOVE_MEDIA_ITEMS', payload: uris }),

  addTag: (uri, tag) => ({ type: 'ADD_TAG', payload: { uri, tag } }),
  removeTag: (uri, tag) => ({ type: 'REMOVE_TAG', payload: { uri, tag } }),
  setTags: tags => ({ type: 'SET_TAGS', payload: tags }),
  clearUriTags: uri => ({ type: 'CLEAR_URI_TAGS', payload: uri }),
  clearUriBatchTags: uris => ({ type: 'CLEAR_URI_BATCH_TAGS', payload: uris }),

  setHiddenUris: list => ({ type: 'SET_HIDDEN_URIS', payload: list }),
  addHiddenUri: uri => ({ type: 'ADD_HIDDEN_URI', payload: uri }),
  removeHiddenUri: uri => ({ type: 'REMOVE_HIDDEN_URI', payload: uri }),
  batchSetHiddenUris: (uris, hidden) => ({
    type: 'BATCH_SET_HIDDEN_URIS',
    payload: { uris, hidden },
  }),
};
