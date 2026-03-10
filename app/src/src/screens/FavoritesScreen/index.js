import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { TouchableOpacity, View } from 'react-native';
import { AppHeader } from '../../components/common/AppHeader';
import { MediaGrid } from '../../components/grid/MediaGrid';
import { MediaThumbnail } from '../../components/grid/MediaThumbnail';
import { MediaViewer } from '../../components/media/MediaViewer';
import { TagsModal } from '../../components/media/TagsModal';
import { EmptyState } from '../../components/common/EmptyState';
import { LoadingSpinner } from '../../components/common/LoadingSpinner';
import { SettingsSidebar } from '../../components/common/SettingsSidebar';
import { ContextMenu } from '../../components/ui/ContextMenu';
import { RenameDialog } from '../../components/ui/RenameDialog';
import { PropertiesModal } from '../../components/ui/PropertiesModal';
import { Icon } from '../../components/ui/Icon';
import { useMediaContext } from '../../store/MediaContext';
import { useAppContext } from '../../store/AppContext';
import { useMediaLibrary } from '../../hooks/useMediaLibrary';
import { MediaIndexService } from '../../services/database/MediaIndexService';
import { SearchService } from '../../services/database/SearchService';
import { useTheme } from '../../providers/ThemeProvider';
import { useToast } from '../../providers/ToastProvider';
import { useDialog } from '../../providers/DialogProvider';
import {
  streamResolveThumbnailUris,
  getCachedThumbUri,
  warmCacheFromDb,
} from '../../services/cache/ThumbnailCache';
import {
  getExtension,
  stripExtension,
  getMimeTypeForItem,
  getCommonMimeType,
} from '../../utils/fileUtils';
import { MediaStoreModule } from '../../services/media/MediaStoreModule';
import { createStyles } from './styles';

// ─── Context menu definition ──────────────────────────────────────────────────

const FAV_FILE_MENU_BASE = [
  { key: 'openWith', label: 'Open With', icon: 'openWith' },
  { key: 'share', label: 'Share', icon: 'share1' },
  { key: 'fav', label: 'Unfavourite', icon: 'remove' },
  { key: 'info', label: 'Properties', icon: 'information' },
];

const FAV_SELECTION_MENU_BASE = [
  { key: 'selectAll', label: 'Select All', icon: 'checked' },
  { key: 'shareSel', label: 'Share Selected', icon: 'share1' },
  { key: 'tagSel', label: 'Tag Selected', icon: 'tags' },
  { key: 'copySel', label: 'Copy Selected', icon: 'copy' },
  { key: 'moveSel', label: 'Move Selected', icon: 'moveRight' },
  { key: 'hideSel', label: 'Hide Selected', icon: 'hidden' },
  { key: 'favSel', label: 'Remove from Favourites', icon: 'remove' },
  {
    key: 'delSel',
    label: 'Delete Selected',
    icon: 'trash',
    dividerBefore: true,
    destructive: true,
  },
];

export function FavoritesScreen({ navigation }) {
  const { colors } = useTheme();
  const styles = useMemo(() => createStyles(colors), [colors]);
  const toast = useToast();
  const dialog = useDialog();

  const { state } = useMediaContext();
  const { state: appState } = useAppContext();
  const nsfwFilterEnabled = appState.nsfwFilterEnabled ?? false;
  const {
    toggleFavorite,
    toggleMediaHidden,
    batchToggleMediaHidden,
    batchToggleFavorite,
    deleteMedia,
    renameMedia,
  } = useMediaLibrary();

  const hiddenUrisSet = useMemo(
    () => new Set(state.hiddenUris ?? []),
    [state.hiddenUris],
  );

  const [items, setItems] = useState([]);
  const [isLoading, setIsLoading] = useState(false);
  const [viewerIndex, setViewerIndex] = useState(-1);
  const [sidebarVisible, setSidebarVisible] = useState(false);
  // ─── NSFW filter state ──────────────────────────────────────────────────────
  const [nsfwUrisSet, setNsfwUrisSet] = useState(new Set());
  // ─── Search state ───────────────────────────────────────────────────────────
  const [searchActive, setSearchActive] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  // ─── Multi-select state ─────────────────────────────────────────────────────
  const [selected, setSelected] = useState(new Set());
  const isSelecting = selected.size > 0;
  const [optionsMenuVisible, setOptionsMenuVisible] = useState(false);
  const [batchTagsVisible, setBatchTagsVisible] = useState(false);
  const [batchTagsUris, setBatchTagsUris] = useState([]);

  // Exit search when selection mode is activated
  useEffect(() => {
    if (isSelecting && searchActive) {
      setSearchActive(false);
      setSearchQuery('');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isSelecting]);

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
  // ─── Context menu / rename / properties state ────────────────────────────────
  const [contextItem, setContextItem] = useState(null);
  const [renameTarget, setRenameTarget] = useState(null);
  const [propertiesItem, setPropertiesItem] = useState(null);

  // ─── Load NSFW tagged URIs whenever filter setting changes ────────────────
  useEffect(() => {
    if (!nsfwFilterEnabled) {
      setNsfwUrisSet(new Set());
      return;
    }
    SearchService.getNsfwTagNames().then(tagNames =>
      SearchService.getNsfwTaggedUris(tagNames).then(setNsfwUrisSet),
    );
  }, [nsfwFilterEnabled]);

  // ─── Load full metadata from SQLite whenever favorites list changes ─────────
  //
  // state.favorites (URI array) is the authoritative source of truth.
  // Any in-session toggle or delete dispatches to state first (optimistic),
  // which triggers this effect — a fast SQLite SELECT to fetch full rows
  // (type, filename, duration, etc.) needed for proper rendering.
  useEffect(() => {
    let cancelled = false;

    async function load() {
      if (items.length === 0) setIsLoading(true);
      try {
        const rows = await MediaIndexService.query(
          { favorite: true },
          { by: 'date', order: 'DESC' },
          { limit: 5000 },
        );
        if (!cancelled) {
          // Pre-warm L1 cache from SQLite so getCachedThumbUri finds
          // previously-resolved thumbs after a cold start.
          const allUris = rows.map(r => r.uri).filter(Boolean);
          await warmCacheFromDb(allUris);

          // Attach any already-cached thumb URIs synchronously
          const mapped = rows.map(r => {
            const cachedThumb = getCachedThumbUri(r.uri);
            return {
              id: r.uri,
              uri: r.uri,
              filename: r.filename ?? '',
              type: r.media_type ?? 'image',
              duration: r.duration ?? null,
              fileSize: r.file_size ?? 0,
              width: r.width ?? 0,
              height: r.height ?? 0,
              timestamp: r.device_created_at
                ? Math.floor(r.device_created_at / 1000)
                : null,
              albumName: r.album_name ?? '',
              ...(cachedThumb ? { thumbUri: cachedThumb } : {}),
            };
          });
          setItems(mapped);

          // Resolve thumbnails for items not yet in cache
          const unresolvedUris = mapped
            .filter(it => !it.thumbUri)
            .map(it => it.uri);
          if (unresolvedUris.length > 0 && !cancelled) {
            const allResolved = new Map();
            await streamResolveThumbnailUris(unresolvedUris, thumbBatch => {
              for (const [uri, thumbUri] of thumbBatch) {
                allResolved.set(uri, thumbUri);
              }
            });
            if (allResolved.size > 0 && !cancelled) {
              setItems(prev => {
                let changed = false;
                const next = prev.map(it => {
                  if (!it.uri || it.thumbUri || !allResolved.has(it.uri))
                    return it;
                  changed = true;
                  return { ...it, thumbUri: allResolved.get(it.uri) };
                });
                return changed ? next : prev;
              });
            }
          }
        }
      } catch (err) {
        console.warn('[FavoritesScreen] load error:', err);
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    }

    load();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.favorites]);

  // ─── Auto-close viewer if the open item gets unfavorited ────────────────────
  useEffect(() => {
    if (
      viewerIndex >= 0 &&
      items[viewerIndex] &&
      !state.favorites.includes(items[viewerIndex].uri)
    ) {
      setViewerIndex(-1);
    }
  }, [state.favorites, viewerIndex, items]);

  // ─── Handlers ──────────────────────────────────────────────────────────

  const handleToggleFavorite = useCallback(
    item => toggleFavorite(item),
    [toggleFavorite],
  );

  const toggleSelect = useCallback(item => {
    setSelected(prev => {
      const next = new Set(prev);
      next.has(item.uri) ? next.delete(item.uri) : next.add(item.uri);
      return next;
    });
  }, []);

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
  const handleCloseContextMenu = useCallback(() => setContextItem(null), []);
  const handleCloseOptionsMenu = useCallback(
    () => setOptionsMenuVisible(false),
    [],
  );

  // Rename
  const handleOpenRename = useCallback(item => {
    setContextItem(null);
    setTimeout(() => setRenameTarget(item), 180);
  }, []);

  const handleConfirmRename = useCallback(
    async newBaseName => {
      const item = renameTarget;
      setRenameTarget(null);
      if (!item) return;
      const ext = getExtension(item.filename ?? item.uri ?? '');
      const fullName = ext
        ? `${newBaseName.trim()}.${ext}`
        : newBaseName.trim();
      try {
        const result = await renameMedia(item, fullName);
        if (result.success) {
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

  // Properties
  const handleOpenProperties = useCallback(item => {
    setContextItem(null);
    setTimeout(() => setPropertiesItem(item), 120);
  }, []);

  // Hide/unhide
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

  // Delete single
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
          toast.success('File deleted');
        } else if (results[0]?.code !== 'DELETE_CANCELLED') {
          toast.error(
            'Delete failed',
            results[0]?.error ?? 'Could not delete file.',
          );
        }
      } catch (err) {
        toast.error('Delete failed', err?.message ?? 'Could not delete file.');
      }
    },
    [dialog, deleteMedia, toast],
  );

  // Delete selected
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
      toast.error('Delete failed', err?.message ?? 'Could not delete files.');
    }
  }, [dialog, selected, deleteMedia, toast]);

  // Hide/unhide selected
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

  // Favourite/unfavourite selected
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

  // Copy/move selected — open FolderPickerScreen
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
          sourceFolder: null,
          returnRoute: 'Favorites',
        });
      }, 180);
    },
    [selected, items, navigation],
  );

  // Copy/move single item
  const handleOpenPicker = useCallback(
    (pickerMode, item) => {
      setContextItem(null);
      setTimeout(() => {
        navigation.navigate('FolderPicker', {
          mode: pickerMode,
          items: [item],
          sourceFolder: null,
          returnRoute: 'Favorites',
        });
      }, 180);
    },
    [navigation],
  );

  // Context menu items
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

  const fileMenuItems = useMemo(() => {
    const isItemHidden = contextItem
      ? hiddenUrisSet.has(contextItem.uri)
      : false;
    return FAV_FILE_MENU_BASE.map(item => {
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
      if (item.key === 'fav')
        return { ...item, onPress: () => handleToggleFavorite(contextItem) };
      if (item.key === 'hide')
        return {
          ...item,
          label: isItemHidden ? 'Unhide' : 'Hide',
          onPress: () => handleHideItem(contextItem),
        };
      if (item.key === 'info')
        return { ...item, onPress: () => handleOpenProperties(contextItem) };
      if (item.key === 'delete')
        return { ...item, onPress: () => handleDeleteSingle(contextItem) };
      return item;
    });
  }, [
    contextItem,
    hiddenUrisSet,
    toggleSelect,
    handleShareItem,
    handleOpenRename,
    handleOpenPicker,
    handleToggleFavorite,
    handleHideItem,
    handleOpenProperties,
    handleDeleteSingle,
  ]);

  const handleSelectAll = useCallback(() => {
    setOptionsMenuVisible(false);
    setSelected(new Set(displayItems.map(it => it.uri)));
  }, [displayItems]);

  const selectionMenuItems = useMemo(
    () =>
      FAV_SELECTION_MENU_BASE.map(item => {
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
      handleDeleteSelected,
    ],
  );

  // Refs for stable onPress/onLongPress — avoids recreating renderItem when
  // isSelecting, toggleSelect, or handleMediaLongPress identity changes.
  const itemsRef = useRef(items);
  itemsRef.current = items;
  const isSelectingRef = useRef(isSelecting);
  isSelectingRef.current = isSelecting;
  const toggleSelectRef = useRef(toggleSelect);
  toggleSelectRef.current = toggleSelect;
  const handleMediaLongPressRef = useRef(handleMediaLongPress);
  handleMediaLongPressRef.current = handleMediaLongPress;

  // Refs for stable renderItem — selection/hidden are read from refs so
  // renderItem identity is stable across these changes.
  // FlatList’s extraData prop drives re-renders for affected cells.
  const selectedRef = useRef(selected);
  selectedRef.current = selected;
  const hiddenUrisSetRef = useRef(hiddenUrisSet);
  hiddenUrisSetRef.current = hiddenUrisSet;

  const handleThumbnailPress = useCallback(it => {
    if (isSelectingRef.current) {
      toggleSelectRef.current(it);
    } else {
      const idx = itemsRef.current.findIndex(v => v.uri === it.uri);
      setViewerIndex(idx >= 0 ? idx : 0);
    }
  }, []);

  const handleThumbnailLongPress = useCallback(it => {
    handleMediaLongPressRef.current(it);
  }, []);

  const renderItem = useCallback(
    ({ item }) => (
      <MediaThumbnail
        item={item}
        isFavorite={true}
        isSelected={selectedRef.current.has(item.uri)}
        isHidden={hiddenUrisSetRef.current.has(item.uri)}
        onPress={handleThumbnailPress}
        onLongPress={handleThumbnailLongPress}
      />
    ),
    [handleThumbnailPress, handleThumbnailLongPress],
  );

  // ─── Header props for selection vs normal mode ────────────────────────────────────────────
  const headerTitle = isSelecting ? `${selected.size} selected` : 'favorites';

  // Search + NSFW filter applied on top of the full favorites list
  const displayItems = useMemo(() => {
    let list = items;
    // NSFW filter: hide items tagged with NSFW tags
    if (nsfwFilterEnabled && nsfwUrisSet.size > 0) {
      list = list.filter(it => !nsfwUrisSet.has(it.uri));
    }
    const q = searchQuery.trim().toLowerCase();
    if (!q) return list;
    return list.filter(it => {
      const name = it.filename ?? it.uri?.split('/').pop() ?? '';
      return name.toLowerCase().includes(q);
    });
  }, [items, searchQuery, nsfwFilterEnabled, nsfwUrisSet]);

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

  // ─── Render ──────────────────────────────────────────────────────────

  if (isLoading && !items.length) {
    return (
      <View style={styles.container}>
        <AppHeader
          title="favorites"
          onSettingsPress={() => setSidebarVisible(true)}
          onSearchPress={() => setSearchActive(true)}
          searchActive={searchActive}
          searchQuery={searchQuery}
          onSearchChange={setSearchQuery}
          onSearchClose={() => {
            setSearchActive(false);
            setSearchQuery('');
          }}
        />
        <LoadingSpinner message="Loading favorites…" />
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <AppHeader
        title={headerTitle}
        showBack={isSelecting}
        onBackPress={isSelecting ? () => setSelected(new Set()) : undefined}
        rightElement={selectionRightElement}
        onSettingsPress={
          isSelecting ? undefined : () => setSidebarVisible(true)
        }
        onSearchPress={!isSelecting ? () => setSearchActive(true) : undefined}
        searchActive={searchActive}
        searchQuery={searchQuery}
        onSearchChange={setSearchQuery}
        onSearchClose={() => {
          setSearchActive(false);
          setSearchQuery('');
        }}
      />

      <MediaGrid
        data={displayItems}
        renderItem={renderItem}
        numColumns={3}
        extraData={selected}
        ListEmptyComponent={
          <EmptyState
            icon="♡"
            message={
              searchQuery.trim()
                ? `No results for "${searchQuery.trim()}"`
                : 'No favorites yet'
            }
            subMessage={
              searchQuery.trim()
                ? undefined
                : 'Long press any photo or video and tap Favourite.'
            }
          />
        }
      />

      <MediaViewer
        visible={viewerIndex >= 0}
        items={items}
        initialIndex={viewerIndex}
        initialMaximized
        onClose={() => setViewerIndex(-1)}
        onToggleFavorite={handleToggleFavorite}
        onRename={renameMedia}
        onOpenPicker={handleOpenPicker}
      />

      {/* ─── Per-item context menu ───────────────────────────────────────── */}
      <ContextMenu
        visible={!!contextItem}
        onClose={handleCloseContextMenu}
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
