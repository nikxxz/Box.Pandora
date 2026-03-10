/* eslint-disable react-hooks/exhaustive-deps */
import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { Alert, Text, TouchableOpacity, View } from 'react-native';
import { AppHeader } from '../../components/common/AppHeader';
import { EmptyState } from '../../components/common/EmptyState';
import { LoadingSpinner } from '../../components/common/LoadingSpinner';
import { FolderCard } from '../../components/grid/FolderCard';
import { MediaGrid } from '../../components/grid/MediaGrid';
import { ContextMenu } from '../../components/ui/ContextMenu';
import { RenameDialog } from '../../components/ui/RenameDialog';
import { PropertiesModal } from '../../components/ui/PropertiesModal';
import { SettingsSidebar } from '../../components/common/SettingsSidebar';
import { AutoTagModal } from '../../components/media/AutoTagModal';
import { AlbumIndexService } from '../../services/database/AlbumIndexService';
import { MediaIndexService } from '../../services/database/MediaIndexService';
import { useMediaLibrary } from '../../hooks/useMediaLibrary';
import { usePermissions } from '../../hooks/usePermissions';
import { useTheme } from '../../providers/ThemeProvider';
import { useToast } from '../../providers/ToastProvider';
import { useAppContext } from '../../store/AppContext';
import { AppActions } from '../../store/actions';
import { SearchService } from '../../services/database/SearchService';
import {
  warmCacheFromDb,
  streamResolveThumbnailUris,
  getCachedThumbUri,
} from '../../services/cache/ThumbnailCache';
import { createStyles } from './styles';

// ─── Folder context menu base (hide label resolved per-folder at runtime) ────

const FOLDER_MENU_BASE = [
  { key: 'rename', label: 'Rename', icon: 'rename' },
  { key: 'copyTo', label: 'Copy To', icon: 'copy' },
  { key: 'moveTo', label: 'Move To', icon: 'moveRight' },
  { key: 'hide', label: 'Hide Folder', icon: 'hidden' }, // label replaced dynamically
  { key: 'select', label: 'Multi Select', icon: 'test' },
  { key: 'autoTag', label: 'Auto Tag', icon: 'tags' },
  { key: 'info', label: 'Properties', icon: 'information' },
  {
    key: 'delete',
    label: 'Delete Folder',
    icon: 'trash',
    dividerBefore: true,
    destructive: true,
  },
];

// ─── Tab config ───────────────────────────────────────────────────────────────

const TABS = [
  { id: 'all', label: 'All' },
  { id: 'images', label: 'Images' },
  { id: 'videos', label: 'Videos' },
];

// ─── Component ────────────────────────────────────────────────────────────────

export function HomeScreen({ navigation }) {
  const { colors } = useTheme();
  const styles = useMemo(() => createStyles(colors), [colors]);
  const toast = useToast();

  const {
    folders,
    isLoading,
    isIndexing,
    loadIndex,
    toggleAlbumHidden,
    renameAlbum,
  } = useMediaLibrary();
  const { hasReadPermission, requestReadPermissions } = usePermissions();

  const { state: appState, dispatch: appDispatch } = useAppContext();
  const activeTab = appState.mediaFilter;
  const showHidden = appState.showHidden;
  const sortBy = appState.sortBy ?? 'date';
  const nsfwFilterEnabled = appState.nsfwFilterEnabled ?? false;
  const accentColor = appState.accentColor ?? colors.accent;
  // Ref keeps renderFolder stable across accent changes; extraData={accentColor}
  // tells FlashList to re-render cells when the value actually changes.
  const accentColorRef = useRef(accentColor);
  accentColorRef.current = accentColor;

  // ─── NSFW cover URI set ──────────────────────────────────────────────────────
  const [nsfwUrisSet, setNsfwUrisSet] = useState(new Set());
  // Ref kept in sync every render so renderFolder can read it without
  // being listed as a dependency (avoids recreating renderFolder on every
  // NSFW state change which would trigger the FlashList render loop).
  const nsfwUrisSetRef = useRef(nsfwUrisSet);
  nsfwUrisSetRef.current = nsfwUrisSet;

  // ─── Resolved cover thumbnail map ─────────────────────────────────────────
  // Maps raw content:// cover URI → resolved file:// thumb URI.
  // Populated lazily when folders change.
  const [coverThumbMap, setCoverThumbMap] = useState(new Map());
  // Ref kept in sync every render so renderFolder can read the latest map
  // without depending on it — this is the core fix for the infinite loop:
  // renderFolder never changes identity when coverThumbMap updates.
  const coverThumbMapRef = useRef(coverThumbMap);
  coverThumbMapRef.current = coverThumbMap;

  useEffect(() => {
    if (!folders.length) return;
    let cancelled = false;
    async function warmCovers() {
      // Collect every unique cover URI across all folders
      const allCoverUris = [
        ...new Set(
          folders.flatMap(f =>
            [f.coverUri, f.photoCoverUri, f.videoCoverUri].filter(Boolean),
          ),
        ),
      ];
      if (!allCoverUris.length) return;

      // ── Split file:// from content:// URIs ─────────────────────────────
      // Android's ContentResolver.loadThumbnail (used by getThumbnailUriBatch)
      // only accepts content://media/... URIs.  Passing file:// URIs into the
      // native batch causes them to be silently skipped or, on some builds,
      // throws and aborts the whole batch.  For file:// URIs the original path
      // IS the displayable image — no thumbnail derivation needed — so we
      // self-map them directly and keep them out of the native call.
      const fileUris = allCoverUris.filter(u => u.startsWith('file://'));
      const contentUris = allCoverUris.filter(u => !u.startsWith('file://'));

      // Self-map file:// URIs immediately (no bridge round-trip required).
      // For these covers the filesystem path IS the displayable image.
      if (fileUris.length) {
        const fileMapped = new Map(fileUris.map(u => [u, u]));
        if (!cancelled)
          setCoverThumbMap(prev => new Map([...prev, ...fileMapped]));
      }

      if (!contentUris.length) return;

      // Pre-warm L1 from SQLite (zero bridge cost for previously-seen covers)
      await warmCacheFromDb(contentUris);
      if (cancelled) return;

      // Attach L1 hits immediately
      const resolved = new Map();
      const uncached = [];
      for (const uri of contentUris) {
        const thumb = getCachedThumbUri(uri);
        if (thumb) resolved.set(uri, thumb);
        else uncached.push(uri);
      }
      if (resolved.size)
        setCoverThumbMap(prev => new Map([...prev, ...resolved]));

      // Resolve remaining content:// URIs in one bridge batch
      if (uncached.length > 0) {
        await streamResolveThumbnailUris(uncached, thumbBatch => {
          if (cancelled) return;
          setCoverThumbMap(prev => new Map([...prev, ...thumbBatch]));
        });
      }
    }
    warmCovers().catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [folders]);

  useEffect(() => {
    if (!nsfwFilterEnabled) {
      setNsfwUrisSet(new Set());
      return;
    }
    SearchService.getNsfwTagNames().then(tagNames =>
      SearchService.getNsfwTaggedUris(tagNames).then(setNsfwUrisSet),
    );
  }, [nsfwFilterEnabled]);

  // ─── Sidebar visibility ──────────────────────────────────────────────────────
  const [sidebarVisible, setSidebarVisible] = useState(false);

  // ─── Folder context menu state ──────────────────────────────────────────────
  const [contextFolder, setContextFolder] = useState(null);

  // ─── Rename dialog state ────────────────────────────────────────────────────
  const [renameTarget, setRenameTarget] = useState(null); // folder being renamed

  // ─── Properties modal state ─────────────────────────────────────────────────
  const [propertiesFolder, setPropertiesFolder] = useState(null);

  // ─── Auto Tag modal state ────────────────────────────────────────────────────
  const [autoTagFolder, setAutoTagFolder] = useState(null);
  const [autoTagItems, setAutoTagItems] = useState([]);

  // ─── Permissions / initial load ─────────────────────────────────────────────

  useEffect(() => {
    async function boot() {
      if (hasReadPermission) {
        // Always load all folders (hidden included); showHidden is applied
        // client-side in filteredFolders so toggling the pref is instant.
        loadIndex(false, { includeHidden: true });
        return;
      }
      const granted = await requestReadPermissions();
      if (granted) {
        loadIndex(false, { includeHidden: true });
      } else {
        Alert.alert(
          'Permission Required',
          "Pandora's Box needs access to your photos and videos.",
          [{ text: 'OK' }],
        );
      }
    }
    boot();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasReadPermission]);

  // ─── Tab filtering ──────────────────────────────────────────────────────────

  // Null-out a URI if it is in the NSFW set (falls back to placeholder in FolderCard)
  const sanitizeCover = useCallback(
    uri => (nsfwFilterEnabled && uri && nsfwUrisSet.has(uri) ? null : uri),
    [nsfwFilterEnabled, nsfwUrisSet],
  );

  const filteredFolders = useMemo(() => {
    let list = folders;

    // ── Hidden filter (client-side — no DB round-trip on toggle) ───────────
    if (!showHidden) {
      list = list.filter(f => !f.hidden);
    }

    // Never render empty folders in the grid.
    list = list.filter(f => (f.count ?? 0) > 0);

    // ── Type filter ────────────────────────────────────────────────────────
    if (activeTab !== 'all') {
      list = list
        .filter(f =>
          activeTab === 'images'
            ? (f.photoCount ?? 0) > 0
            : (f.videoCount ?? 0) > 0,
        )
        .map(f => {
          const rawCover =
            activeTab === 'images'
              ? f.photoCoverUri ?? f.coverUri
              : f.videoCoverUri ?? f.coverUri;
          return {
            ...f,
            displayCount: activeTab === 'images' ? f.photoCount : f.videoCount,
            // coverUriKey is the stable original content:// URI used as
            // expo-image recyclingKey so the slot is reused when the thumb
            // is upgraded to a file:// path — prevents the fade-in flash.
            coverUriKey: rawCover,
            // NSFW guard only — thumb map lookup happens in renderFolder so
            // filteredFolders does NOT depend on coverThumbMap. Keeping
            // coverThumbMap out of this dep chain is what prevents the
            // FlashList commitLayout → setRenderId infinite render loop.
            coverUri: sanitizeCover(rawCover),
          };
        });
    } else if (nsfwFilterEnabled && nsfwUrisSet.size > 0) {
      // "All" tab + NSFW filter: null out flagged cover URIs.
      // Thumb resolution is deferred to renderFolder (see above comment).
      list = list.map(f => ({
        ...f,
        coverUriKey: f.coverUri, // stable recycling key
        coverUri: sanitizeCover(f.coverUri),
      }));
    }

    // ── Sort ───────────────────────────────────────────────────────────────
    return [...list].sort((a, b) => {
      switch (sortBy) {
        case 'name':
          return (a.name ?? '').localeCompare(b.name ?? '');
        case 'count':
          return (b.count ?? 0) - (a.count ?? 0);
        case 'date':
        default:
          // Fall back to natural index order (already newest-first from indexer)
          return 0;
      }
    });
    // sanitizeCover depends on nsfwFilterEnabled + nsfwUrisSet only —
    // NOT on coverThumbMap — so filteredFolders stays stable while
    // warmCovers is drip-filling coverThumbMap in the background.
  }, [folders, activeTab, sortBy, showHidden, sanitizeCover]);

  // ─── Handlers ───────────────────────────────────────────────────────────────

  const handleFolderPress = useCallback(
    folder => navigation.navigate('FolderDetail', { folder }),
    [navigation],
  );

  const handleFolderLongPress = useCallback(
    folder => setContextFolder(folder),
    [],
  );

  const handleCloseContextMenu = useCallback(() => setContextFolder(null), []);

  const handleRefresh = useCallback(
    () => loadIndex(true, { includeHidden: true }),
    [loadIndex],
  );

  // ─── Hide / Unhide folder ────────────────────────────────────────────────────

  const handleHideFolder = useCallback(
    async folder => {
      setContextFolder(null);
      try {
        await toggleAlbumHidden(folder);
        const isNowHidden = !folder.hidden;
        toast.success(
          isNowHidden ? 'Folder hidden' : 'Folder unhidden',
          isNowHidden
            ? 'Hidden folders are excluded from the grid unless "Show Hidden" is on.'
            : folder.name,
        );
      } catch (err) {
        toast.error(
          'Error',
          err?.message ?? 'Could not update folder visibility.',
        );
      }
    },
    [toggleAlbumHidden, toast],
  );

  // ─── Rename folder ────────────────────────────────────────────────────────────

  const handleOpenRename = useCallback(folder => {
    setContextFolder(null);
    // Brief delay so context menu fully closes before dialog opens
    setTimeout(() => setRenameTarget(folder), 180);
  }, []);

  const handleConfirmRename = useCallback(
    async newName => {
      const folder = renameTarget;
      setRenameTarget(null);
      if (!folder) return;

      try {
        const result = await renameAlbum(folder, newName);
        if (result.success) {
          toast.success('Folder renamed', `"${folder.name}" → "${newName}"`);
        } else {
          toast.error(
            'Rename failed',
            result.error ?? 'Could not rename folder.',
          );
        }
      } catch (err) {
        toast.error(
          'Rename failed',
          err?.message ?? 'Could not rename folder.',
        );
      }
    },
    [renameTarget, renameAlbum, toast],
  );

  // ─── Properties ──────────────────────────────────────────────────────────────

  const handleOpenProperties = useCallback(folder => {
    setContextFolder(null);
    setTimeout(() => setPropertiesFolder(folder), 120);
  }, []);

  // ─── Auto Tag ─────────────────────────────────────────────────────────────────

  const handleAutoTag = useCallback(
    async folder => {
      setContextFolder(null);
      try {
        const albumRow = await AlbumIndexService.getByName(folder.name);
        if (!albumRow?.id) {
          toast.error('Auto Tag', 'Folder not found in index.');
          return;
        }
        const items = await MediaIndexService.query(
          { albumId: albumRow.id, mediaType: 'image' },
          { by: 'date', order: 'DESC' },
          { limit: 0 }, // 0 = no limit
        );
        setAutoTagItems(items);
        setAutoTagFolder(folder);
      } catch (err) {
        toast.error('Auto Tag', err?.message ?? 'Could not load folder items.');
      }
    },
    [toast],
  );

  // ─── Dynamic context menu (hide/unhide label changes per folder) ─────────────

  const folderMenuItems = useMemo(() => {
    return FOLDER_MENU_BASE.map(item => {
      if (item.key === 'hide') {
        const isHidden = contextFolder?.hidden ?? false;
        return {
          ...item,
          label: isHidden ? 'Unhide Folder' : 'Hide Folder',
          onPress: () => handleHideFolder(contextFolder),
        };
      }
      if (item.key === 'rename') {
        return { ...item, onPress: () => handleOpenRename(contextFolder) };
      }
      if (item.key === 'info') {
        return { ...item, onPress: () => handleOpenProperties(contextFolder) };
      }
      if (item.key === 'autoTag') {
        return { ...item, onPress: () => handleAutoTag(contextFolder) };
      }
      return item;
    });
  }, [
    contextFolder,
    handleHideFolder,
    handleOpenRename,
    handleOpenProperties,
    handleAutoTag,
  ]);

  const renderFolder = useCallback(
    ({ item }) => {
      // Resolve cover URI here (not in filteredFolders) so that coverThumbMap
      // updates never change the data array — that's the path that causes
      // FlashList commitLayout → setRenderId infinite loops.
      //
      // coverThumbMapRef and nsfwUrisSetRef are updated synchronously every
      // render (above), so they're always current when this fn runs.
      // This callback therefore has a stable identity across coverThumbMap
      // and nsfwUrisSet changes, while still reading fresh values.
      const rawUri = item.coverUri;
      const nsfwUris = nsfwUrisSetRef.current;
      const thumbMap = coverThumbMapRef.current;
      const sanitized =
        nsfwFilterEnabled && rawUri && nsfwUris.has(rawUri) ? null : rawUri;
      const resolvedUri = sanitized
        ? thumbMap.get(sanitized) ?? sanitized
        : null;
      const folder =
        resolvedUri !== rawUri ? { ...item, coverUri: resolvedUri } : item;
      return (
        <FolderCard
          folder={folder}
          isHidden={item.hidden}
          onPress={handleFolderPress}
          onLongPress={handleFolderLongPress}
          accentColor={accentColorRef.current}
        />
      );
    },
    // nsfwFilterEnabled is a primitive (boolean) — safe to list.
    // Refs are NOT listed: they're always current without needing to be deps.
    // accentColor excluded — read from ref; extraData drives cell re-renders.
    [handleFolderPress, handleFolderLongPress, nsfwFilterEnabled],
  );

  // Stable object so FlashList only re-renders cells when something actually
  // changed — not on every parent render.
  const gridExtraData = useMemo(
    () => ({ accentColor, coverThumbMap, nsfwFilterEnabled, nsfwUrisSet }),
    [accentColor, coverThumbMap, nsfwFilterEnabled, nsfwUrisSet],
  );

  // ─── Header component (passed as ListHeaderComponent) ───────────────────────

  const ListHeader = useMemo(
    () => (
      <View style={styles.listHeader}>
        {/* Tabs row */}
        <View style={styles.tabRow}>
          {TABS.map(tab => {
            const focused = tab.id === activeTab;
            return (
              <TouchableOpacity
                key={tab.id}
                style={styles.tab}
                onPress={() => appDispatch(AppActions.setMediaFilter(tab.id))}
                activeOpacity={0.7}
              >
                <Text
                  style={[
                    styles.tabLabel,
                    { color: focused ? colors.text : colors.textTertiary },
                    focused && styles.tabLabelActive,
                  ]}
                >
                  {tab.label}
                </Text>
              </TouchableOpacity>
            );
          })}
        </View>
      </View>
    ),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [activeTab, styles, colors],
  );

  // ─── Loading state ──────────────────────────────────────────────────────────

  if (isLoading && !folders.length) {
    return (
      <View style={styles.container}>
        <AppHeader
          title="pandora"
          onSettingsPress={() => setSidebarVisible(true)}
          onSearchPress={() => navigation.navigate('Search')}
        />
        <LoadingSpinner message="Scanning your media…" />
      </View>
    );
  }

  // ─── Render ─────────────────────────────────────────────────────────────────

  return (
    <View style={styles.container}>
      {/* Persistent top bar */}
      <AppHeader
        title="pandora"
        onSettingsPress={() => setSidebarVisible(true)}
        onSearchPress={() => navigation.navigate('Search')}
      />

      <MediaGrid
        data={filteredFolders}
        renderItem={renderFolder}
        numColumns={2}
        extraData={gridExtraData}
        refreshing={isIndexing}
        onRefresh={handleRefresh}
        ListHeaderComponent={ListHeader}
        ListEmptyComponent={
          <EmptyState
            icon={activeTab === 'videos' ? '▶' : '☐'}
            message={
              activeTab === 'all'
                ? 'No media folders found'
                : `No ${activeTab} folders found`
            }
            subMessage="Pull down to refresh after granting media access."
          />
        }
      />

      {/* ─── Folder context menu ─────────────────────────────────────────── */}
      <ContextMenu
        visible={!!contextFolder}
        onClose={handleCloseContextMenu}
        title={contextFolder?.name}
        items={folderMenuItems}
      />

      {/* ─── Rename dialog ──────────────────────────────────────────────── */}
      <RenameDialog
        visible={!!renameTarget}
        onClose={() => setRenameTarget(null)}
        onConfirm={handleConfirmRename}
        initialName={renameTarget?.name ?? ''}
        title="Rename Folder"
        placeholder="Enter folder name"
      />

      {/* ─── Properties sheet ───────────────────────────────────────────── */}
      <PropertiesModal
        visible={!!propertiesFolder}
        onClose={() => setPropertiesFolder(null)}
        folder={propertiesFolder}
      />

      {/* ─── Auto Tag modal ──────────────────────────────────────────────── */}
      <AutoTagModal
        visible={!!autoTagFolder}
        folder={autoTagFolder}
        items={autoTagItems}
        onClose={() => {
          setAutoTagFolder(null);
          setAutoTagItems([]);
        }}
      />

      {/* ─── Settings sidebar ────────────────────────────────────────────── */}
      <SettingsSidebar
        visible={sidebarVisible}
        onClose={() => setSidebarVisible(false)}
        onNavigate={routeName => navigation.navigate(routeName)}
        onCreateFolder={() => {
          /* TODO: implement create folder dialog */
        }}
      />
    </View>
  );
}
