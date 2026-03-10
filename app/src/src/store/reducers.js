// ─── App state ────────────────────────────────────────────────────────────────

export const initialAppState = {
  isLoading: false,
  permissions: { read: false, write: false, manageStorage: false, all: false },
  error: null,
  theme: 'dark',
  mediaFilter: 'all', // 'all' | 'images' | 'videos'
  showHidden: false, // when true, hidden folders/files are visible
  sortBy: 'date', // 'date' | 'name' | 'count'
  accentColor: null, // string hex | null = use theme default
  nsfwFilterEnabled: false, // false = off by default; saved to DB when user turns it on
  mlSceneEnabled: true, // MobileNetV3 scene embedding model
  mlFaceEnabled: true, // MobileFaceNet face embedding model
};

export function appReducer(state, action) {
  switch (action.type) {
    case 'SET_LOADING':
      return { ...state, isLoading: action.payload };
    case 'SET_PERMISSIONS':
      return { ...state, permissions: action.payload };
    case 'SET_ERROR':
      return { ...state, error: action.payload };
    case 'CLEAR_ERROR':
      return { ...state, error: null };
    case 'SET_THEME':
      return { ...state, theme: action.payload };
    case 'SET_MEDIA_FILTER':
      return { ...state, mediaFilter: action.payload };
    case 'SET_SHOW_HIDDEN':
      return { ...state, showHidden: action.payload };
    case 'SET_SORT_BY':
      return { ...state, sortBy: action.payload };
    case 'SET_ACCENT_COLOR':
      return { ...state, accentColor: action.payload };
    case 'SET_NSFW_FILTER_ENABLED':
      return { ...state, nsfwFilterEnabled: action.payload };
    case 'SET_ML_SCENE_ENABLED':
      return { ...state, mlSceneEnabled: action.payload };
    case 'SET_ML_FACE_ENABLED':
      return { ...state, mlFaceEnabled: action.payload };
    default:
      return state;
  }
}

// ─── Media state ──────────────────────────────────────────────────────────────

export const initialMediaState = {
  index: null, // MediaIndexer index
  isIndexing: false,
  selectedFolder: null, // { name, count, coverUri }
  currentMedia: null, // MediaItem open in viewer
  favorites: [], // Array of URIs
  hiddenUris: [], // Array of URIs with hidden = 1
  tags: {}, // { [uri]: string[] }
};

export function mediaReducer(state, action) {
  switch (action.type) {
    case 'SET_INDEX':
      return { ...state, index: action.payload };
    case 'SET_IS_INDEXING':
      return { ...state, isIndexing: action.payload };
    case 'SET_SELECTED_FOLDER':
      return { ...state, selectedFolder: action.payload };
    case 'SET_CURRENT_MEDIA':
      return { ...state, currentMedia: action.payload };

    // Favorites
    case 'ADD_FAVORITE':
      return {
        ...state,
        favorites: state.favorites.includes(action.payload)
          ? state.favorites
          : [...state.favorites, action.payload],
      };
    case 'REMOVE_FAVORITE':
      return {
        ...state,
        favorites: state.favorites.filter(id => id !== action.payload),
      };
    case 'TOGGLE_FAVORITE': {
      const uri = action.payload;
      return {
        ...state,
        favorites: state.favorites.includes(uri)
          ? state.favorites.filter(id => id !== uri)
          : [...state.favorites, uri],
      };
    }
    case 'SET_FAVORITES':
      return { ...state, favorites: action.payload };

    // Hidden URIs
    case 'SET_HIDDEN_URIS':
      return { ...state, hiddenUris: action.payload };
    case 'ADD_HIDDEN_URI':
      return {
        ...state,
        hiddenUris: state.hiddenUris.includes(action.payload)
          ? state.hiddenUris
          : [...state.hiddenUris, action.payload],
      };
    case 'REMOVE_HIDDEN_URI':
      return {
        ...state,
        hiddenUris: state.hiddenUris.filter(uri => uri !== action.payload),
      };
    case 'BATCH_SET_HIDDEN_URIS': {
      const { uris, hidden } = action.payload;
      const uriSet = new Set(uris);
      if (hidden) {
        const merged = new Set(state.hiddenUris);
        uris.forEach(uri => merged.add(uri));
        return { ...state, hiddenUris: [...merged] };
      }
      return {
        ...state,
        hiddenUris: state.hiddenUris.filter(uri => !uriSet.has(uri)),
      };
    }

    // Batch-remove deleted media from favorites, hiddenUris + tags
    case 'REMOVE_MEDIA_ITEMS': {
      const removed = new Set(action.payload);
      const favorites = state.favorites.filter(uri => !removed.has(uri));
      const hiddenUris = state.hiddenUris.filter(uri => !removed.has(uri));
      const tags = Object.fromEntries(
        Object.entries(state.tags).filter(([uri]) => !removed.has(uri)),
      );
      return { ...state, favorites, hiddenUris, tags };
    }

    // Tags
    case 'ADD_TAG': {
      const { uri, tag } = action.payload;
      const existing = state.tags[uri] || [];
      if (existing.includes(tag)) return state;
      return { ...state, tags: { ...state.tags, [uri]: [...existing, tag] } };
    }
    case 'REMOVE_TAG': {
      const { uri, tag } = action.payload;
      const filtered = (state.tags[uri] || []).filter(t => t !== tag);
      return { ...state, tags: { ...state.tags, [uri]: filtered } };
    }
    case 'SET_TAGS':
      return { ...state, tags: action.payload };
    case 'CLEAR_URI_TAGS': {
      const uri = action.payload;
      return { ...state, tags: { ...state.tags, [uri]: [] } };
    }
    case 'CLEAR_URI_BATCH_TAGS': {
      const uris = action.payload;
      const next = { ...state.tags };
      for (const uri of uris) next[uri] = [];
      return { ...state, tags: next };
    }

    default:
      return state;
  }
}
