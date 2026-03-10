import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { View, TouchableOpacity, BackHandler } from 'react-native';
import { AppHeader } from '../../components/common/AppHeader';
import { MediaGrid } from '../../components/grid/MediaGrid';
import { MediaThumbnail } from '../../components/grid/MediaThumbnail';
import { MediaViewer } from '../../components/media/MediaViewer';
import { TagsModal } from '../../components/media/TagsModal';
import { LoadingSpinner } from '../../components/common/LoadingSpinner';
import { EmptyState } from '../../components/common/EmptyState';
import { ContextMenu } from '../../components/ui/ContextMenu';
import { RenameDialog } from '../../components/ui/RenameDialog';
import { PropertiesModal } from '../../components/ui/PropertiesModal';
import { Icon } from '../../components/ui/Icon';
import { useMediaLibrary } from '../../hooks/useMediaLibrary';
import { useMediaContext } from '../../store/MediaContext';
import { MediaActions } from '../../store/actions';
import { useAppContext } from '../../store/AppContext';
import { useTheme } from '../../providers/ThemeProvider';
import { useToast } from '../../providers/ToastProvider';
import { useDialog } from '../../providers/DialogProvider';
import { SettingsSidebar } from '../../components/common/SettingsSidebar';
import {
  getExtension,
  stripExtension,
  getMimeTypeForItem,
  getCommonMimeType,
} from '../../utils/fileUtils';
import { DIMENSIONS } from '../../constants/dimensions';
import { MediaStoreModule } from '../../services/media/MediaStoreModule';
import { SearchService } from '../../services/database/SearchService';
import { TagService } from '../../services/database/TagService';
import {
  streamResolveThumbnailUris,
  getCachedThumbUri,
  warmCacheFromDb,
} from '../../services/cache/ThumbnailCache';
import { createStyles } from './styles';

// ─── Base item definitions (onPress wired via useMemo inside component) ──────

const FILE_MENU_BASE = [
  { key: 'openWith', label: 'Open With', icon: 'openWith' },
  { key: 'share', label: 'Share', icon: 'share1' },
  { key: 'rename', label: 'Rename', icon: 'rename' },
  { key: 'copyTo', label: 'Copy To', icon: 'copy' },
  { key: 'moveTo', label: 'Move To', icon: 'moveRight' },
  { key: 'hide', label: 'Hide / Unhide', icon: 'hidden' },
  { key: 'fav', label: 'Favourite', icon: 'heartOutline' },
  { key: 'select', label: 'Multi Select', icon: 'test' },
  { key: 'info', label: 'Properties', icon: 'information' },
  {
    key: 'clearTags',
    label: 'Clear Tags',
    icon: 'tags',
    dividerBefore: true,
    destructive: true,
  },
  {
    key: 'delete',
    label: 'Delete',
    icon: 'trash',
    destructive: true,
  },
];

const SELECTION_MENU_BASE = [
  { key: 'selectAll', label: 'Select All', icon: 'checked' },
  { key: 'shareSel', label: 'Share Selected', icon: 'share1' },
  { key: 'tagSel', label: 'Tag Selected', icon: 'tags' },
  { key: 'copySel', label: 'Copy Selected', icon: 'copy' },
  { key: 'moveSel', label: 'Move Selected', icon: 'moveRight' },
  { key: 'hideSel', label: 'Hide Selected', icon: 'hidden' },
  { key: 'favSel', label: 'Add to Favourites', icon: 'heartOutline' },
  {
    key: 'clearTagsSel',
    label: 'Clear Tags',
    icon: 'tags',
    dividerBefore: true,
    destructive: true,
  },
  {
    key: 'delSel',
    label: 'Delete Selected',
    icon: 'trash',
    destructive: true,
  },
];

export function FolderScreen({ route, navigation }) {
  const folder = route.params?.folder ?? null;
  const { colors } = useTheme();
  const styles = useMemo(() => createStyles(colors), [colors]);
  const [sidebarVisible, setSidebarVisible] = useState(false);

  const {
    loadAlbumMedia,
    loadCachedItems,
    deleteMedia,
    toggleFavorite,
    toggleMediaHidden,
    batchToggleMediaHidden,
    batchToggleFavorite,
    renameMedia,
  } = useMediaLibrary();
  const { state, dispatch } = useMediaContext();
  const { state: appState } = useAppContext();
  // Pre-compute theme values passed to MediaThumbnail so thumbnails don't need
  // their own context subscriptions (which bypass React.memo).
  const thumbnailAccent = appState.accentColor ?? colors.accent;

  // Map global mediaFilter → CameraRoll assetType
  const assetType =
    appState.mediaFilter === 'images'
      ? 'Photos'
      : appState.mediaFilter === 'videos'
      ? 'Videos'
      : 'All';
  const toast = useToast();
  const dialog = useDialog();

  // Guard: if navigation params are missing, go back immediately
  useEffect(() => {
    if (!folder) {
      toast.error('Navigation Error', 'Could not open this folder.');
      navigation.goBack();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const showHidden = appState.showHidden;

  // NSFW filter
  const nsfwFilterEnabled = appState.nsfwFilterEnabled ?? false;
  const [nsfwUrisSet, setNsfwUrisSet] = useState(new Set());

  useEffect(() => {
    if (!nsfwFilterEnabled) {
      setNsfwUrisSet(new Set());
      return;
    }
    SearchService.getNsfwTagNames().then(tagNames =>
      SearchService.getNsfwTaggedUris(tagNames).then(setNsfwUrisSet),
    );
  }, [nsfwFilterEnabled]);

  // O(1) hidden-uri lookup
  const hiddenUrisSet = useMemo(
    () => new Set(state.hiddenUris),
    [state.hiddenUris],
  );

  // Maximum trailing placeholders after the last real item. 30 items = 10 rows
  // in a 3-column grid — enough to fill ~1.5 screens of buffer so the scroll
  // experience stays smooth, but short enough that FlatList's onEndReached
  // fires in time to trigger the next page load.
  const TRAILING_PLACEHOLDERS = 30;

  const [items, setItems] = useState(() => {
    // Pre-fill with a small batch of placeholder cells so the grid shows
    // instant visual feedback. Capped to TRAILING_PLACEHOLDERS so that
    // FlatList's onEndReached will fire when the user scrolls near the end,
    // allowing page 2+ to load.
    const preCount =
      assetType === 'Photos'
        ? folder.photoCount ?? folder.count ?? 0
        : assetType === 'Videos'
        ? folder.videoCount ?? folder.count ?? 0
        : folder.count ?? 0;
    const cappedCount = Math.min(preCount, TRAILING_PLACEHOLDERS);
    return Array.from({ length: cappedCount }, (_, i) => ({
      id: `__ph_${folder.name}_${i}`,
      uri: null,
      _isPlaceholder: true,
    }));
  });
  const [cursor, setCursor] = useState(null);
  const cursorRef = useRef(null);
  const [hasMore, setHasMore] = useState(true);
  const hasMoreRef = useRef(true);
  const [isLoading, setIsLoading] = useState(false);
  const [viewerIndex, setViewerIndex] = useState(-1);
  const [selected, setSelected] = useState(new Set());
  const isSelecting = selected.size > 0;

  // ─── Search state ───────────────────────────────────────────────────────────
  const [searchActive, setSearchActive] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [allLoaded, setAllLoaded] = useState(false);
  const isLoadingAllRef = useRef(false);
  const isLoadingPageRef = useRef(false);

  // ── Visibility guard (Simple Gallery pattern) ────────────────────────────────
  // Tracks URIs of items currently visible in the FlatList (≥50% visible, ≥100ms).
  // streamResolveThumbnailUris checks this set before dispatching resolution so
  // thumbnails are never loaded for items already scrolled off screen.
  const visibleUrisRef = useRef(new Set());

  // Mirror of `items` accessible in scroll callbacks without stale closures.
  const itemsRef = useRef([]);
  useEffect(() => {
    itemsRef.current = items;
  }, [items]);

  // Throttle timestamp for the scroll look-ahead so it doesn't spam.
  const scrollWarmLastRef = useRef(0);

  // Exit search if the user enters selection mode
  useEffect(() => {
    if (isSelecting && searchActive) {
      setSearchActive(false);
      setSearchQuery('');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isSelecting]);

  // ─── Android hardware back: exit multi-select before navigating back ────────
  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (selected.size > 0) {
        setSelected(new Set());
        return true; // consume — stay on the screen
      }
      return false; // let React Navigation handle it (go back to home)
    });
    return () => sub.remove();
  }, [selected.size]);

  // Derived selection flags (drive dynamic menu labels + operation direction)
  const allSelHidden = useMemo(
    () =>
      selected.size > 0 && [...selected].every(uri => hiddenUrisSet.has(uri)),
    [selected, hiddenUrisSet],
  );
  const allSelFav = useMemo(() => {
    if (selected.size === 0) return false;
    // Build a Set once so each .has() is O(1) instead of O(n) Array.includes.
    const favSet = new Set(state.favorites);
    return [...selected].every(uri => favSet.has(uri));
  }, [selected, state.favorites]);

  // Filter out hidden items unless showHidden is on
  const visibleItems = useMemo(() => {
    let list = showHidden
      ? items
      : items.filter(it => !hiddenUrisSet.has(it.uri));
    // NSFW filter: hide items tagged with NSFW tags
    if (nsfwFilterEnabled && nsfwUrisSet.size > 0) {
      list = list.filter(it => !it.uri || !nsfwUrisSet.has(it.uri));
    }
    return list;
  }, [items, showHidden, hiddenUrisSet, nsfwFilterEnabled, nsfwUrisSet]);

  // Apply search filter on top of visibility filter
  const displayItems = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return visibleItems;
    return visibleItems.filter(it => {
      const name = it.filename ?? it.uri?.split('/').pop() ?? '';
      return name.toLowerCase().includes(q);
    });
  }, [visibleItems, searchQuery]);

  // ─── Context menu state ────────────────────────────────────────────────
  // contextItem — the media item whose menu is open (null = closed)
  const [contextItem, setContextItem] = useState(null);
  // optionsMenuVisible — the multi-select Options sheet
  const [optionsMenuVisible, setOptionsMenuVisible] = useState(false);
  // batchTagsVisible — TagsModal opened for multi-selection
  const [batchTagsVisible, setBatchTagsVisible] = useState(false);
  const [batchTagsUris, setBatchTagsUris] = useState([]);
  // renameTarget — item currently being renamed
  const [renameTarget, setRenameTarget] = useState(null);
  // propertiesItem — item shown in Properties sheet
  const [propertiesItem, setPropertiesItem] = useState(null);

  // ─── Handle return from FolderPickerScreen ─────────────────────────────────────
  // React Navigation passes completedOperation as a route param when the picker
  // navigates back to this screen.  We consume it once and clear it.
  useEffect(() => {
    const op = route.params?.completedOperation;
    if (!op) return;

    if (op.mode === 'move' && op.movedUris?.length > 0) {
      // Remove successfully moved items from the local list
      setItems(prev => prev.filter(it => !op.movedUris.includes(it.uri)));
      setSelected(new Set());
    }

    // Clear param so re-focus doesn't re-run this effect
    navigation.setParams({ completedOperation: undefined });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [route.params?.completedOperation]);

  useEffect(() => {
    // Reset list whenever the folder or the global media-type filter changes
    isLoadingPageRef.current = false;
    isLoadingAllRef.current = false;

    // Rebuild placeholder grid for the new folder / filter (shown while DB query runs)
    const preCount =
      assetType === 'Photos'
        ? folder.photoCount ?? folder.count ?? 0
        : assetType === 'Videos'
        ? folder.videoCount ?? folder.count ?? 0
        : folder.count ?? 0;
    const cappedCount = Math.min(preCount, TRAILING_PLACEHOLDERS);
    setItems(
      Array.from({ length: cappedCount }, (_, i) => ({
        id: `__ph_${folder.name}_${i}`,
        uri: null,
        _isPlaceholder: true,
      })),
    );
    setCursor(null);
    cursorRef.current = null;
    setHasMore(true);
    hasMoreRef.current = true;
    setAllLoaded(false);
    setSearchActive(false);
    setSearchQuery('');

    // ── DB-first (Simple Gallery pattern) ──────────────────────────────────
    // Show cached SQLite items with pre-populated thumb URIs BEFORE CameraRoll
    // returns. Users see real thumbnails instantly instead of grey tiles.
    // CameraRoll (loadPage below) will overwrite with authoritative data.
    loadCachedItems(folder.name, assetType).then(cachedItems => {
      if (cachedItems.length > 0 && !isLoadingPageRef.current) {
        // Only apply if CameraRoll hasn't already returned its first page
        setItems(cachedItems);
      }
    });

    loadPage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [folder.name, assetType]);

  // Load ALL items when search is activated (for comprehensive search results)
  useEffect(() => {
    if (searchActive && !allLoaded && !isLoadingAllRef.current) {
      loadAllItems();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchActive]);

  const loadAllItems = useCallback(async () => {
    if (allLoaded || isLoadingAllRef.current) return;
    isLoadingAllRef.current = true;
    isLoadingPageRef.current = true; // block concurrent loadPage while loading all
    setIsLoading(true);
    let accumulated = [];
    let after = null;
    let moreAvailable = true;
    try {
      while (moreAvailable) {
        // eslint-disable-next-line no-await-in-loop
        const result = await loadAlbumMedia(folder.name, {
          first: 200,
          after,
          assetType,
        });
        if (!result) break;
        accumulated = [...accumulated, ...result.items];
        after = result.nextCursor;
        moreAvailable = result.hasMore;
      }
      setItems(accumulated);
      setCursor(null);
      setHasMore(false);
      setAllLoaded(true);
    } catch (err) {
      console.warn('[FolderScreen] loadAllItems error:', err);
    } finally {
      isLoadingAllRef.current = false;
      isLoadingPageRef.current = false;
      setIsLoading(false);
    }
  }, [allLoaded, folder.name, loadAlbumMedia, assetType]);

  const loadPage = useCallback(
    async (after = null) => {
      if (isLoadingPageRef.current) return;
      isLoadingPageRef.current = true;
      setIsLoading(true);
      try {
        // skipThumbs=true: items reach the grid immediately with their full-res
        // uri. Thumbnail resolution runs in a streaming background pass below,
        // patching thumbUri onto each item as workers complete.
        const result = await loadAlbumMedia(folder.name, {
          first: 60,
          after,
          assetType,
          skipThumbs: true,
        });
        if (!result) return;

        // ── Pre-warm L1 from SQLite ─────────────────────────────────────
        // After a cold start, L1 (JS Map) is empty even though many items
        // have a thumb_uri stored in SQLite (L2). Warming L1 here means
        // Fix A below finds more hits → fewer items fall through to the
        // native bridge call → faster first render.
        const allUris = result.items.map(it => it.uri).filter(Boolean);
        await warmCacheFromDb(allUris);

        // ── Fix A: Attach any already-cached thumbUri synchronously ──────
        // Items that were previously viewed have their thumbUri in the L1/L2
        // cache. By attaching it here, they render the correct thumbnail on
        // the FIRST paint with zero flash (the URI never changes later).
        const itemsWithCachedThumbs = result.items.map(it => {
          const cachedThumb = getCachedThumbUri(it.uri);
          return cachedThumb ? { ...it, thumbUri: cachedThumb } : it;
        });

        setItems(prev => {
          // Collect all real (non-placeholder) items from the previous state
          const prevReal = after ? prev.filter(it => !it._isPlaceholder) : []; // first page replaces everything
          const allReal = [...prevReal, ...itemsWithCachedThumbs];

          // Append a small trailing-placeholder buffer so onEndReached fires
          // in time. Skip placeholders entirely on the final page.
          if (!result.hasMore) return allReal;
          const trailing = Array.from(
            { length: TRAILING_PLACEHOLDERS },
            (_, i) => ({
              id: `__ph_${folder.name}_${allReal.length + i}`,
              uri: null,
              _isPlaceholder: true,
            }),
          );
          return [...allReal, ...trailing];
        });
        setCursor(result.nextCursor);
        cursorRef.current = result.nextCursor;
        setHasMore(result.hasMore);
        hasMoreRef.current = result.hasMore;

        // Proactively queue the next page as soon as this one is rendered,
        // so the user never waits at the end of a page while scrolling.
        // Uses setTimeout(0) to yield to the UI paint first.
        if (result.hasMore) {
          setTimeout(() => {
            if (hasMoreRef.current && !isLoadingPageRef.current) {
              loadPage(cursorRef.current);
            }
          }, 0);
        }

        // ── Fix F: Resolve remaining thumbnails in ONE batch setItems ────
        // Only resolve URIs that don't already have a cached thumbUri.
        // streamResolveThumbnailUris makes at most 2 onBatch calls (L1 hits
        // + native batch). We accumulate ALL results and flush once.
        const unresolvedUris = itemsWithCachedThumbs
          .filter(it => !it.thumbUri)
          .map(it => it.uri)
          .filter(uri => {
            const visible = visibleUrisRef.current;
            return visible.size === 0 || visible.has(uri);
          });

        if (unresolvedUris.length > 0) {
          const allResolved = new Map();
          await streamResolveThumbnailUris(unresolvedUris, thumbBatch => {
            for (const [uri, thumbUri] of thumbBatch) {
              allResolved.set(uri, thumbUri);
            }
          });
          // Single setItems call for all resolved thumbnails
          if (allResolved.size > 0) {
            setItems(prev => {
              let changed = false;
              const next = prev.map(it => {
                if (!it.uri || it.thumbUri || !allResolved.has(it.uri))
                  return it;
                const thumbUri = allResolved.get(it.uri);
                if (it.thumbUri === thumbUri) return it;
                changed = true;
                return { ...it, thumbUri };
              });
              return changed ? next : prev;
            });
          }
        }
      } catch (err) {
        console.warn('[FolderScreen] loadPage error:', err);
        toast.error('Load Error', err?.message ?? 'Could not load media.');
      } finally {
        isLoadingPageRef.current = false;
        setIsLoading(false);
      }
    },
    [folder.name, loadAlbumMedia, assetType, toast],
  );

  const toggleSelect = useCallback(item => {
    setSelected(prev => {
      const next = new Set(prev);
      next.has(item.uri) ? next.delete(item.uri) : next.add(item.uri);
      return next;
    });
  }, []);

  // Long-press — enters selection mode (or toggles when already selecting)
  const handleMediaLongPress = useCallback(
    item => {
      if (selected.size > 0) {
        toggleSelect(item);
      } else {
        setContextItem(item);
      }
    },
    [selected, toggleSelect],
  );
  const handleCloseFileMenu = useCallback(() => setContextItem(null), []);
  const handleCloseOptionsMenu = useCallback(
    () => setOptionsMenuVisible(false),
    [],
  );

  // ─── Clear Tags: single item ─────────────────────────────────────────────────

  const handleClearTagsSingle = useCallback(
    async item => {
      if (!item) return;
      setContextItem(null);
      try {
        await TagService.clearTagsForMedia(item.uri);
        dispatch(MediaActions.clearUriTags(item.uri));
        toast.success('Tags cleared', item.filename ?? 'File');
      } catch (err) {
        toast.error('Error', err?.message ?? 'Could not clear tags.');
      }
    },
    [dispatch, toast],
  );

  // ─── Clear Tags: selected items ───────────────────────────────────────────────

  const handleClearTagsSelected = useCallback(async () => {
    const uris = [...selected].map(uri => uri);
    if (!uris.length) return;
    setOptionsMenuVisible(false);
    try {
      await TagService.batchClearTagsForMedia(uris);
      dispatch(MediaActions.clearUriBatchTags(uris));
      toast.success('Tags cleared', `${uris.length} item${uris.length !== 1 ? 's' : ''}`);
    } catch (err) {
      toast.error('Error', err?.message ?? 'Could not clear tags.');
    }
  }, [selected, dispatch, toast]);

  // ─── Delete: single item (via context menu) ─────────────────────────────────
  const handleDeleteSingle = useCallback(
    async item => {
      if (!item) return;

      const confirmed = await dialog.destructive({
        title: 'Delete file?',
        body: item.filename
          ? `"${item.filename}" will be permanently deleted.`
          : 'This file will be permanently deleted.',
        icon: 'trash',
        deleteText: 'Delete',
        cancelText: 'Cancel',
      });
      if (!confirmed) return;

      try {
        const { results } = await deleteMedia([item.uri]);
        if (results[0]?.success) {
          setItems(prev => prev.filter(it => it.uri !== item.uri));
        } else if (results[0]?.code === 'DELETE_CANCELLED') {
          // User tapped "Don't allow" in the system consent dialog — silent
        } else {
          toast.error(
            'Delete Failed',
            results[0]?.error ?? 'Could not delete this file.',
          );
        }
      } catch (err) {
        toast.error(
          'Delete Failed',
          err?.message ?? 'Could not delete this file.',
        );
      }
    },
    [dialog, deleteMedia, toast],
  );

  // ─── Delete: selected items (via Options menu) ─────────────────────────────
  const handleDeleteSelected = useCallback(async () => {
    const count = selected.size;

    const confirmed = await dialog.destructive({
      title: `Delete ${count} item${count > 1 ? 's' : ''}?`,
      body: 'Deleted files cannot be recovered.',
      icon: 'trash',
      deleteText: 'Delete',
      cancelText: 'Cancel',
    });
    if (!confirmed) return;

    setOptionsMenuVisible(false);
    const uris = [...selected];
    try {
      const { results } = await deleteMedia(uris);
      const successUris = results.filter(r => r.success).map(r => r.uri);
      const isCancelled = results.some(r => r.code === 'DELETE_CANCELLED');
      const failCount = results.filter(
        r => !r.success && r.code !== 'DELETE_CANCELLED',
      ).length;
      if (successUris.length > 0) {
        setItems(prev => prev.filter(it => !successUris.includes(it.uri)));
        setSelected(new Set());
      }
      if (!isCancelled && failCount > 0) {
        toast.error(
          `${failCount} file${failCount > 1 ? 's' : ''} failed`,
          'Some files could not be deleted.',
        );
      } else if (successUris.length > 0) {
        toast.success(
          `${successUris.length} file${
            successUris.length > 1 ? 's' : ''
          } deleted`,
        );
      }
    } catch (err) {
      toast.error('Delete Failed', err?.message ?? 'Could not delete files.');
    }
  }, [dialog, selected, deleteMedia, toast]);

  // ─── Scroll look-ahead: warm thumbnails for items about to enter viewport ───
  // Fires every ~200 ms during scroll. Estimates which items will become
  // visible in the next 2 screen-heights and pre-resolves their thumbnails so
  // they are ready before Glide requests them. Also triggers the next page
  // load early when the user is scrolling fast towards the end.
  const handleScrollLookAhead = useCallback(
    ({ nativeEvent }) => {
      const now = Date.now();
      if (now - scrollWarmLastRef.current < 200) return;
      scrollWarmLastRef.current = now;

      const { contentOffset, layoutMeasurement } = nativeEvent;
      const scrollY = contentOffset.y;
      const viewHeight = layoutMeasurement.height;
      const cellSize = DIMENSIONS.thumbSize; // cells are square in 3-col grid
      const cols = 3;

      // Row index whose bottom edge is at the visible bottom
      const visibleBottomRow = Math.floor((scrollY + viewHeight) / cellSize);
      // Look-ahead: 2 more screen-heights ahead
      const lookAheadRow = Math.ceil((scrollY + viewHeight * 3) / cellSize);

      const current = itemsRef.current;
      const aheadItems = current.slice(
        visibleBottomRow * cols,
        Math.min(lookAheadRow * cols, current.length),
      );
      const aheadUris = aheadItems
        .filter(it => it.uri && !it._isPlaceholder && !it.thumbUri)
        .map(it => it.uri);

      if (aheadUris.length > 0) {
        warmCacheFromDb(aheadUris)
          .then(() =>
            streamResolveThumbnailUris(aheadUris, thumbBatch => {
              setItems(prev => {
                let changed = false;
                const next = prev.map(it => {
                  if (!it.uri || it.thumbUri || !thumbBatch.has(it.uri))
                    return it;
                  changed = true;
                  return { ...it, thumbUri: thumbBatch.get(it.uri) };
                });
                return changed ? next : prev;
              });
            }),
          )
          .catch(() => {});
      }

      // Early page trigger: if within 2 screen-heights of loaded content end
      const realCount = current.filter(it => !it._isPlaceholder).length;
      const totalContentH = Math.ceil(realCount / cols) * cellSize;
      if (
        totalContentH - scrollY - viewHeight < viewHeight * 2 &&
        hasMoreRef.current &&
        !isLoadingPageRef.current
      ) {
        loadPage(cursorRef.current);
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [loadPage],
  );

  // ─── MediaViewer near-end pre-fetch ─────────────────────────────────────────
  // Safety net for when the viewer is open and the user approaches the last
  // loaded item before the proactive chain has finished loading all pages.
  const handleViewerNearEnd = useCallback(() => {
    if (hasMoreRef.current && !isLoadingPageRef.current) {
      loadPage(cursorRef.current);
    }
  }, [loadPage]);

  // ─── Delete: from full-screen viewer ────────────────────────────────────────
  const handleViewerDelete = useCallback(
    async item => {
      const confirmed = await dialog.destructive({
        title: 'Delete this file?',
        body: 'This cannot be undone.',
        icon: 'trash',
        deleteText: 'Delete',
        cancelText: 'Cancel',
      });
      if (!confirmed) return;

      try {
        const { results } = await deleteMedia([item.uri]);
        if (results[0]?.success) {
          setItems(prev => prev.filter(it => it.uri !== item.uri));
          setViewerIndex(-1);
        } else if (results[0]?.code !== 'DELETE_CANCELLED') {
          toast.error(
            'Delete Failed',
            results[0]?.error ?? 'Could not delete this file.',
          );
        }
      } catch (err) {
        toast.error(
          'Delete Failed',
          err?.message ?? 'Could not delete this file.',
        );
      }
    },
    [dialog, deleteMedia, toast],
  );

  // ─── Context menu items with wired onPress ──────────────────────────────────

  const handleToggleFavorite = useCallback(
    item => toggleFavorite(item),
    [toggleFavorite],
  );

  const handleShareItem = useCallback(async item => {
    if (!item) return;
    setContextItem(null);
    try {
      const mimeType = getMimeTypeForItem(item);
      await MediaStoreModule.shareFile(item.uri, mimeType, item.filename ?? '');
    } catch {
      /* user cancelled or share sheet dismissed */
    }
  }, []);

  const handleHideItem = useCallback(
    async item => {
      if (!item) return;
      setContextItem(null);
      const isCurrentlyHidden = hiddenUrisSet.has(item.uri);
      try {
        await toggleMediaHidden(item);
        toast.success(isCurrentlyHidden ? 'File unhidden' : 'File hidden');
      } catch (err) {
        toast.error(
          'Error',
          err?.message ?? 'Could not update file visibility.',
        );
      }
    },
    [toggleMediaHidden, hiddenUrisSet, toast],
  );

  // ─── Rename file ─────────────────────────────────────────────────────────────

  const handleOpenRename = useCallback(item => {
    setContextItem(null);
    setTimeout(() => setRenameTarget(item), 180);
  }, []);

  const handleConfirmRename = useCallback(
    async newBaseName => {
      const item = renameTarget;
      setRenameTarget(null);
      if (!item) return;
      const ext = getExtension(item.filename);
      const fullName = ext
        ? `${newBaseName.trim()}.${ext}`
        : newBaseName.trim();
      try {
        const result = await renameMedia(item, fullName);
        if (result.success) {
          // Reflect new filename in local list
          setItems(prev =>
            prev.map(it =>
              it.uri === item.uri ? { ...it, filename: fullName } : it,
            ),
          );
          toast.success('File renamed', `"${item.filename}" → "${fullName}"`);
        } else {
          toast.error(
            'Rename failed',
            result.error ?? 'Could not rename file.',
          );
        }
      } catch (err) {
        toast.error('Rename failed', err?.message ?? 'Could not rename file.');
      }
    },
    [renameTarget, renameMedia, toast],
  );

  // ─── Properties ──────────────────────────────────────────────────────────────

  const handleOpenProperties = useCallback(item => {
    setContextItem(null);
    setTimeout(() => setPropertiesItem(item), 120);
  }, []);

  // ─── Copy To / Move To ─────────────────────────────────────────────────────

  /**
   * Open the FolderPickerScreen for a single-file copy/move.
   * Small delay so the context menu finishes its close animation first.
   */
  const handleOpenPicker = useCallback(
    (pickerMode, item) => {
      setContextItem(null);
      setTimeout(() => {
        navigation.navigate('FolderPicker', {
          mode: pickerMode,
          items: [item],
          sourceFolder: folder,
        });
      }, 180);
    },
    [folder, navigation],
  );

  /**
   * Open the FolderPickerScreen for the current multi-selection.
   */
  const handleOpenPickerForSelection = useCallback(
    pickerMode => {
      const selectedItems = [...selected]
        .map(uri => items.find(it => it.uri === uri))
        .filter(Boolean);
      if (!selectedItems.length) return;
      setOptionsMenuVisible(false);
      setTimeout(() => {
        navigation.navigate('FolderPicker', {
          mode: pickerMode,
          items: selectedItems,
          sourceFolder: folder,
        });
      }, 180);
    },
    [selected, items, folder, navigation],
  );

  const fileMenuItems = useMemo(() => {
    const isItemFav = contextItem
      ? state.favorites.includes(contextItem.uri)
      : false;
    const isItemHidden = contextItem
      ? hiddenUrisSet.has(contextItem.uri)
      : false;
    return FILE_MENU_BASE.map(item => {
      if (item.key === 'openWith')
        return {
          ...item,
          onPress: () => {
            setContextItem(null);
            if (contextItem) {
              const mimeType = getMimeTypeForItem(contextItem);
              MediaStoreModule.openWith(contextItem.uri, mimeType).catch(
                () => {},
              );
            }
          },
        };
      if (item.key === 'share')
        return { ...item, onPress: () => handleShareItem(contextItem) };
      if (item.key === 'hide')
        return {
          ...item,
          label: isItemHidden ? 'Unhide' : 'Hide',
          onPress: () => handleHideItem(contextItem),
        };
      if (item.key === 'fav')
        return {
          ...item,
          label: isItemFav ? 'Unfavourite' : 'Favourite',
          icon: isItemFav ? 'remove' : 'heartOutline',
          onPress: () => handleToggleFavorite(contextItem),
        };
      if (item.key === 'select')
        return {
          ...item,
          onPress: () => {
            setContextItem(null);
            if (contextItem) toggleSelect(contextItem);
          },
        };
      if (item.key === 'rename')
        return { ...item, onPress: () => handleOpenRename(contextItem) };
      if (item.key === 'copyTo')
        return {
          ...item,
          onPress: () => handleOpenPicker('copy', contextItem),
        };
      if (item.key === 'moveTo')
        return {
          ...item,
          onPress: () => handleOpenPicker('move', contextItem),
        };
      if (item.key === 'info')
        return { ...item, onPress: () => handleOpenProperties(contextItem) };
      if (item.key === 'clearTags')
        return { ...item, onPress: () => handleClearTagsSingle(contextItem) };
      if (item.key === 'delete')
        return { ...item, onPress: () => handleDeleteSingle(contextItem) };
      return item;
    });
  }, [
    contextItem,
    state.favorites,
    hiddenUrisSet,
    toggleSelect,
    handleToggleFavorite,
    handleShareItem,
    handleHideItem,
    handleOpenRename,
    handleOpenPicker,
    handleOpenProperties,
    handleClearTagsSingle,
    handleDeleteSingle,
  ]);

  const handleHideSelected = useCallback(async () => {
    const selectedItems = [...selected]
      .map(uri => items.find(it => it.uri === uri))
      .filter(Boolean);
    if (!selectedItems.length) return;

    // If ALL selected are hidden → unhide; otherwise hide
    const toHide = !allSelHidden;

    setOptionsMenuVisible(false);
    await batchToggleMediaHidden(selectedItems, toHide);
    setSelected(new Set());
    toast.success(
      toHide
        ? `${selectedItems.length} file${
            selectedItems.length > 1 ? 's' : ''
          } hidden`
        : `${selectedItems.length} file${
            selectedItems.length > 1 ? 's' : ''
          } unhidden`,
    );
  }, [selected, items, allSelHidden, batchToggleMediaHidden, toast]);

  const handleFavSelected = useCallback(async () => {
    const selectedItems = [...selected]
      .map(uri => items.find(it => it.uri === uri))
      .filter(Boolean);
    if (!selectedItems.length) return;

    // If ALL selected are already favourited → remove; otherwise add
    const toFavorite = !allSelFav;

    setOptionsMenuVisible(false);
    await batchToggleFavorite(selectedItems, toFavorite);
    setSelected(new Set());
    toast.success(
      toFavorite
        ? `${selectedItems.length} file${
            selectedItems.length > 1 ? 's' : ''
          } added to favourites`
        : `${selectedItems.length} file${
            selectedItems.length > 1 ? 's' : ''
          } removed from favourites`,
    );
  }, [selected, items, allSelFav, batchToggleFavorite, toast]);

  const handleShareSelected = useCallback(async () => {
    const selectedItems = [...selected]
      .map(uri => items.find(it => it.uri === uri))
      .filter(Boolean);
    if (!selectedItems.length) return;
    setOptionsMenuVisible(false);
    const uris = selectedItems.map(it => it.uri);
    const mimeType = getCommonMimeType(selectedItems);
    try {
      await MediaStoreModule.shareFiles(uris, mimeType);
    } catch {
      /* dismissed */
    }
  }, [selected, items]);

  const handleSelectAll = useCallback(() => {
    setOptionsMenuVisible(false);
    setSelected(new Set(displayItems.map(it => it.uri)));
  }, [displayItems]);

  const selectionMenuItems = useMemo(
    () =>
      SELECTION_MENU_BASE.map(item => {
        if (item.key === 'selectAll')
          return { ...item, onPress: handleSelectAll };
        if (item.key === 'shareSel')
          return { ...item, onPress: handleShareSelected };
        if (item.key === 'tagSel')
          return {
            ...item,
            onPress: () => {
              setOptionsMenuVisible(false);
              setBatchTagsUris([...selected]);
              setTimeout(() => setBatchTagsVisible(true), 120);
            },
          };
        if (item.key === 'copySel')
          return {
            ...item,
            onPress: () => handleOpenPickerForSelection('copy'),
          };
        if (item.key === 'moveSel')
          return {
            ...item,
            onPress: () => handleOpenPickerForSelection('move'),
          };
        if (item.key === 'hideSel')
          return {
            ...item,
            label: allSelHidden ? 'Unhide Selected' : 'Hide Selected',
            onPress: handleHideSelected,
          };
        if (item.key === 'favSel')
          return {
            ...item,
            label: allSelFav ? 'Remove from Favourites' : 'Add to Favourites',
            icon: allSelFav ? 'remove' : 'heartOutline',
            onPress: handleFavSelected,
          };
        if (item.key === 'clearTagsSel')
          return { ...item, onPress: handleClearTagsSelected };
        if (item.key === 'delSel')
          return { ...item, onPress: handleDeleteSelected };
        return item;
      }),
    [
      allSelHidden,
      allSelFav,
      selected,
      handleSelectAll,
      handleOpenPickerForSelection,
      handleHideSelected,
      handleFavSelected,
      handleShareSelected,
      handleClearTagsSelected,
      handleDeleteSelected,
    ],
  );

  // Placeholder-free list for MediaViewer — prefetch must never receive null URIs.
  // Computed here (not inside handleThumbnailPress) so the index lookup and the
  // items prop both operate on the exact same array.
  const viewerItems = useMemo(
    () => visibleItems.filter(it => !!it.uri),
    [visibleItems],
  );

  // Stable ref so renderItem doesn't need visibleItems as a dep (avoids
  // recreating renderItem — and re-rendering all cells — on every page append)
  const visibleItemsRef = useRef([]);
  visibleItemsRef.current = visibleItems;
  const viewerItemsRef = useRef([]);
  viewerItemsRef.current = viewerItems;

  // Refs for stable onPress/onLongPress — avoids recreating renderItem when
  // isSelecting, toggleSelect, or handleMediaLongPress identity changes.
  const isSelectingRef = useRef(isSelecting);
  isSelectingRef.current = isSelecting;
  const toggleSelectRef = useRef(toggleSelect);
  toggleSelectRef.current = toggleSelect;
  const handleMediaLongPressRef = useRef(handleMediaLongPress);
  handleMediaLongPressRef.current = handleMediaLongPress;

  // Refs for stable renderItem — selection/favorites/hidden are read from refs
  // so renderItem never needs to be recreated when these Sets change.
  // FlatList’s extraData prop already drives re-renders for affected cells.
  const selectedRef = useRef(selected);
  selectedRef.current = selected;
  // NOTE: favoritesSetRef is declared after the favoritesSet useMemo below
  // because favoritesSet must be computed first (var-hoisting would make it
  // undefined here if declared before the useMemo runs).
  const hiddenUrisSetRef = useRef(hiddenUrisSet);
  hiddenUrisSetRef.current = hiddenUrisSet;

  const handleThumbnailPress = useCallback(it => {
    if (!it.uri) return; // placeholder cell — not interactive yet
    if (isSelectingRef.current) {
      toggleSelectRef.current(it);
    } else {
      // Search in the placeholder-free list so the index matches what
      // MediaViewer receives (visibleItems has placeholders; viewerItems doesn't).
      const idx = viewerItemsRef.current.findIndex(v => v.uri === it.uri);
      setViewerIndex(idx >= 0 ? idx : 0);
    }
  }, []);

  const handleThumbnailLongPress = useCallback(it => {
    handleMediaLongPressRef.current(it);
  }, []);

  // Stable extraData — only changes when selection or favorites actually change
  const favoritesSet = useMemo(
    () => new Set(state.favorites),
    [state.favorites],
  );
  // Declared here (after favoritesSet useMemo) so the ref is never undefined.
  const favoritesSetRef = useRef(favoritesSet);
  favoritesSetRef.current = favoritesSet;
  const gridExtraData = useMemo(
    () => ({ selected, favoritesSet, hiddenUrisSet }),
    [selected, favoritesSet, hiddenUrisSet],
  );

  // Update the visible-URI set whenever FlatList's viewability changes.
  // Used by the streaming thumbnail resolver to skip off-screen items.
  const handleViewableItemsChanged = useCallback(({ viewableItems }) => {
    const next = new Set();
    for (const v of viewableItems) {
      if (v.item?.uri) next.add(v.item.uri);
    }
    visibleUrisRef.current = next;
  }, []);

  // Stable refs so renderItem doesn't need colors/accent as deps — these
  // values rarely change and are already captured in gridExtraData if they do.
  const colorsRef = useRef(colors);
  colorsRef.current = colors;
  const thumbnailAccentRef = useRef(thumbnailAccent);
  thumbnailAccentRef.current = thumbnailAccent;

  const renderItem = useCallback(
    ({ item }) => (
      <MediaThumbnail
        item={item}
        isSelected={selectedRef.current.has(item.uri)}
        isFavorite={favoritesSetRef.current.has(item.uri)}
        isHidden={hiddenUrisSetRef.current.has(item.uri)}
        onPress={handleThumbnailPress}
        onLongPress={handleThumbnailLongPress}
        colors={colorsRef.current}
        accent={thumbnailAccentRef.current}
      />
    ),
    [handleThumbnailPress, handleThumbnailLongPress],
  );

  // ─── Header props for selection vs normal mode ───────────────────────────

  const headerTitle = isSelecting ? `${selected.size} selected` : folder.name;

  const selectionRightElement = isSelecting ? (
    <TouchableOpacity
      onPress={() => setOptionsMenuVisible(true)}
      style={styles.deleteBtn}
      hitSlop={{ top: 12, right: 12, bottom: 12, left: 8 }}
      activeOpacity={0.7}
    >
      <Icon name="more" size={24} color={undefined} />
    </TouchableOpacity>
  ) : undefined;

  // If folder param is missing, render nothing while useEffect navigates back
  if (!folder) return null;

  return (
    <View style={styles.container}>
      <AppHeader
        title={headerTitle}
        showBack
        onBackPress={
          isSelecting ? () => setSelected(new Set()) : () => navigation.goBack()
        }
        rightElement={selectionRightElement}
        onSettingsPress={() => setSidebarVisible(true)}
        onSearchPress={!isSelecting ? () => setSearchActive(true) : undefined}
        searchActive={searchActive}
        searchQuery={searchQuery}
        onSearchChange={setSearchQuery}
        onSearchClose={() => {
          setSearchActive(false);
          setSearchQuery('');
        }}
      />
      {isLoading && !items.length ? (
        <LoadingSpinner message="Loading media…" />
      ) : (
        <MediaGrid
          data={displayItems}
          renderItem={renderItem}
          numColumns={3}
          extraData={gridExtraData}
          onViewableItemsChanged={handleViewableItemsChanged}
          onEndReached={() => hasMoreRef.current && loadPage(cursorRef.current)}
          onScroll={handleScrollLookAhead}
          ListEmptyComponent={
            <EmptyState
              message={
                searchQuery.trim()
                  ? `No results for "${searchQuery.trim()}"`
                  : 'No media in this folder'
              }
            />
          }
          ListFooterComponent={
            isLoading && items.length ? <LoadingSpinner size="small" /> : null
          }
        />
      )}

      <MediaViewer
        visible={viewerIndex >= 0}
        items={viewerItems}
        initialIndex={viewerIndex}
        initialMaximized
        onClose={() => setViewerIndex(-1)}
        onDelete={handleViewerDelete}
        onToggleFavorite={handleToggleFavorite}
        onRename={renameMedia}
        onNearEnd={handleViewerNearEnd}
        onOpenPicker={handleOpenPicker}
      />

      {/* ─── Per-item context menu ───────────────────────────────────────── */}
      <ContextMenu
        visible={!!contextItem}
        onClose={handleCloseFileMenu}
        title={contextItem?.filename ?? contextItem?.uri?.split('/').pop()}
        items={fileMenuItems}
      />

      {/* ─── Multi-select Options menu ─────────────────────────────────── */}
      <ContextMenu
        visible={optionsMenuVisible}
        onClose={handleCloseOptionsMenu}
        title={`${selected.size} item${
          selected.size !== 1 ? 's' : ''
        } selected`}
        items={selectionMenuItems}
      />

      {/* ─── Rename dialog ──────────────────────────────────────────────── */}
      <RenameDialog
        visible={!!renameTarget}
        onClose={() => setRenameTarget(null)}
        onConfirm={handleConfirmRename}
        initialName={stripExtension(renameTarget?.filename ?? '')}
        title="Rename File"
        placeholder="Enter file name"
      />

      {/* ─── Properties sheet ───────────────────────────────────────────── */}
      <PropertiesModal
        visible={!!propertiesItem}
        onClose={() => setPropertiesItem(null)}
        item={propertiesItem}
      />

      {/* ─── Batch tag modal (multi-select) ─────────────────────────────── */}
      <TagsModal
        visible={batchTagsVisible}
        onClose={() => setBatchTagsVisible(false)}
        mediaUris={batchTagsUris}
      />

      <SettingsSidebar
        visible={sidebarVisible}
        onClose={() => setSidebarVisible(false)}
        onNavigate={routeName => navigation.navigate(routeName)}
      />
    </View>
  );
}
