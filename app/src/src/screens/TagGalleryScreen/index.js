/**
 * TagGalleryScreen — redesigned
 *
 * Tag Detail header (scrollable with the grid):
 *   Tag name · description · item count · % of library · last updated
 *   First tagged · related tag chips (co-occurrence)
 *
 * Below the header: standard 3-column media grid.
 * Empty state offers a library-scan prompt when the tag has 0 items.
 *
 * Uses SQLite via TagService for all data operations.
 */

import React, {
  useEffect,
  useState,
  useCallback,
  useMemo,
  useRef,
} from 'react';
import {
  View,
  Text,
  Image,
  StyleSheet,
  FlatList,
  Dimensions,
  TouchableOpacity,
  ActivityIndicator,
  StatusBar,
  Modal,
  TextInput,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  Alert,
  Animated,
  LayoutAnimation,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../../providers/ThemeProvider';
import { useAppContext } from '../../store/AppContext';
import { useMediaContext } from '../../store/MediaContext';
import { MediaActions } from '../../store/actions';
import { MediaThumbnail } from '../../components/grid/MediaThumbnail';
import { MediaViewer } from '../../components/media/MediaViewer';
import { EmptyState } from '../../components/common/EmptyState';
import { LoadingSpinner } from '../../components/common/LoadingSpinner';
import { ContextMenu } from '../../components/ui/ContextMenu';
import { TagService } from '../../services/database/TagService';
import { SearchService } from '../../services/database/SearchService';
import {
  getCategoryDef,
  TAG_CATEGORY_LIST,
} from '../../constants/tagCategories';
import { useMediaLibrary } from '../../hooks/useMediaLibrary';
import { useToast } from '../../providers/ToastProvider';
import { getMimeTypeForItem } from '../../utils/fileUtils';
import { MediaStoreModule } from '../../services/media/MediaStoreModule';
import {
  streamResolveThumbnailUris,
  getCachedThumbUri,
  warmCacheFromDb,
} from '../../services/cache/ThumbnailCache';

const { width: SCREEN_WIDTH } = Dimensions.get('window');
const COLUMN_COUNT = 3;
const ITEM_SPACING = 2;
const THUMBNAIL_SIZE =
  (SCREEN_WIDTH - ITEM_SPACING * (COLUMN_COUNT + 1)) / COLUMN_COUNT;

const PAGE_SIZE = 300;

const GALLERY_SORT_OPTIONS = [
  { key: 'date_desc', label: 'Newest' },
  { key: 'date_asc', label: 'Oldest' },
];

// ─── Date helpers ──────────────────────────────────────────────────────────────────

const MONTH_LONG = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
];
const MONTH_SHORT = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
];

function formatLongDate(ts) {
  if (!ts) return null;
  const d = new Date(ts);
  return `${d.getDate()} ${MONTH_LONG[d.getMonth()]} ${d.getFullYear()}`;
}

function formatMonthYear(ts) {
  if (!ts) return null;
  const d = new Date(ts);
  return `${MONTH_SHORT[d.getMonth()]} ${d.getFullYear()}`;
}

function formatShortDate(ts) {
  if (!ts) return null;
  const d = new Date(ts);
  return `${d.getDate()} ${MONTH_SHORT[d.getMonth()]} ${d.getFullYear()}`;
}

// ─── Tag color palette ────────────────────────────────────────────────────────
// Vibrant hues that read clearly as badge backgrounds (white text) on both
// light and dark app backgrounds.
const TAG_COLOR_PALETTE = [
  '#E53935', // Red
  '#F4511E', // Deep Orange
  '#FB8C00', // Orange
  '#F9A825', // Amber
  '#43A047', // Green
  '#00897B', // Teal
  '#00ACC1', // Cyan
  '#039BE5', // Light Blue
  '#1E88E5', // Blue
  '#3949AB', // Indigo
  '#5E35B1', // Deep Purple
  '#8E24AA', // Purple
  '#D81B60', // Pink
  '#F06292', // Soft Pink
  '#FF7043', // Deep Orange Light
  '#546E7A', // Blue Grey
  '#7CB342', // Light Green
  '#888888', // Default grey
];

// ─── TagGalleryScreen ────────────────────────────────────────────────────────

export function TagGalleryScreen({ route, navigation }) {
  const { colors, isDark } = useTheme();
  const { state: mediaState, dispatch } = useMediaContext();
  const { state: appState } = useAppContext();
  const nsfwFilterEnabled = appState.nsfwFilterEnabled ?? false;
  const showHidden = appState.showHidden ?? false;
  const toast = useToast();
  const { toggleFavorite, renameMedia } = useMediaLibrary();
  const insets = useSafeAreaInsets();

  const { tagId, tagColor = '#54A0FF' } = route.params ?? {};

  // Live tag data from SQLite
  const [tagData, setTagData] = useState(null);
  const tagName = tagData?.name ?? route.params?.tagName;

  // Extended stats
  const [relatedTags, setRelatedTags] = useState([]);
  const [totalMediaCount, setTotalMediaCount] = useState(0);

  // Edit modal state
  const [editVisible, setEditVisible] = useState(false);
  const [editName, setEditName] = useState('');
  const [editDescription, setEditDescription] = useState('');
  const [editColor, setEditColor] = useState('#888888');

  // Media state
  const [mediaItems, setMediaItems] = useState([]);
  const [nsfwUrisSet, setNsfwUrisSet] = useState(new Set());
  const [isLoading, setIsLoading] = useState(true);
  const [viewerIndex, setViewerIndex] = useState(-1);
  const [contextItem, setContextItem] = useState(null);

  // Sort / filter for gallery grid
  const [gallerySort, setGallerySort] = useState('date_desc');
  const [gallerySortOpen, setGallerySortOpen] = useState(false);

  // Info header expandable section
  const [moreDetailsOpen, setMoreDetailsOpen] = useState(false);

  const runNativeTransition = useCallback(() => {
    LayoutAnimation.configureNext({
      duration: 220,
      create: {
        type: LayoutAnimation.Types.easeInEaseOut,
        property: LayoutAnimation.Properties.opacity,
      },
      update: {
        type: LayoutAnimation.Types.easeInEaseOut,
      },
      delete: {
        type: LayoutAnimation.Types.easeInEaseOut,
        property: LayoutAnimation.Properties.opacity,
      },
    });
  }, []);

  // Animated chevron for the "More details" toggle
  const moreDetailsChevronAnim = useRef(new Animated.Value(0)).current; // 0 = closed
  useEffect(() => {
    Animated.timing(moreDetailsChevronAnim, {
      toValue: moreDetailsOpen ? 1 : 0,
      duration: 220,
      useNativeDriver: true,
    }).start();
  }, [moreDetailsOpen, moreDetailsChevronAnim]);
  const moreDetailsChevronRotate = moreDetailsChevronAnim.interpolate({
    inputRange: [0, 1],
    outputRange: ['0deg', '180deg'],
  });

  // Overflow menu (⋮)
  const [overflowVisible, setOverflowVisible] = useState(false);

  // Merge tag modal
  const [mergeVisible, setMergeVisible] = useState(false);
  const [allTags, setAllTags] = useState([]);
  const [mergeQuery, setMergeQuery] = useState('');
  const [mergeTarget, setMergeTarget] = useState(null);
  const [mergeConfirmVisible, setMergeConfirmVisible] = useState(false);
  const [merging, setMerging] = useState(false);

  // Change category modal
  const [changeCatVisible, setChangeCatVisible] = useState(false);

  // Delete confirm modal
  const [deleteConfirmVisible, setDeleteConfirmVisible] = useState(false);

  // Pagination
  const [hasMore, setHasMore] = useState(false);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const mediaPageRef = useRef(0);

  // Guard: tagId is required to load any data
  useEffect(() => {
    if (!tagId) {
      toast.error('Navigation Error', 'Could not load this tag.');
      navigation.goBack();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ─── Load tag data (now uses getTagStats for first_tagged_at) ──────────────

  const loadTag = useCallback(async () => {
    try {
      const tag = await TagService.getTagStats(tagId);
      if (tag) setTagData(tag);
    } catch (err) {
      console.warn('[TagGalleryScreen] loadTag error:', err);
    }
  }, [tagId]);

  // ─── Load extended stats (related tags + total media count) ─────────────────

  const loadExtendedStats = useCallback(async () => {
    try {
      const [related, total] = await Promise.all([
        TagService.getRelatedTags(tagId, 5),
        TagService.getTotalMediaCount(),
      ]);
      setRelatedTags(related);
      setTotalMediaCount(total);
    } catch (err) {
      console.warn('[TagGalleryScreen] loadExtendedStats error:', err);
    }
  }, [tagId]);

  // ─── Load media items for this tag ──────────────────────────────────────────

  const loadMedia = useCallback(async () => {
    try {
      setIsLoading(true);
      mediaPageRef.current = 0;
      const items = await TagService.getMediaForTag(tagId, {
        limit: PAGE_SIZE,
        offset: 0,
        showHidden,
      });
      // Pre-warm L1 from SQLite so getCachedThumbUri finds previously-resolved thumbs
      const allUris = items.map(r => r.uri).filter(Boolean);
      await warmCacheFromDb(allUris);
      // Attach any already-cached thumb URIs synchronously
      const mapped = items.map(row => {
        const cachedThumb = getCachedThumbUri(row.uri);
        return {
          id: row.uri,
          uri: row.uri,
          filename: row.filename ?? row.uri.split('/').pop(),
          type: row.media_type ?? 'image',
          duration: row.duration ?? null,
          width: row.width ?? 0,
          height: row.height ?? 0,
          size: row.file_size ?? 0,
          ...(cachedThumb ? { thumbUri: cachedThumb } : {}),
        };
      });
      setMediaItems(mapped);
      setHasMore(items.length === PAGE_SIZE);
      // Show the grid immediately — don't block on thumbnail resolution
      setIsLoading(false);

      // Resolve thumbnails for items not yet in cache.
      // streamResolveThumbnailUris fires onBatch up to twice:
      //   1. synchronously for L1 cache hits (zero latency)
      //   2. after a single native batch call for misses
      // Update state on each batch so thumbnails appear progressively.
      const unresolvedUris = mapped
        .filter(it => !it.thumbUri)
        .map(it => it.uri);
      if (unresolvedUris.length > 0) {
        await streamResolveThumbnailUris(unresolvedUris, thumbBatch => {
          setMediaItems(prev => {
            let changed = false;
            const next = prev.map(it => {
              if (!it.uri || it.thumbUri || !thumbBatch.has(it.uri)) return it;
              changed = true;
              return { ...it, thumbUri: thumbBatch.get(it.uri) };
            });
            return changed ? next : prev;
          });
        });
      }
    } catch (err) {
      console.warn('[TagGalleryScreen] loadMedia error:', err);
      setIsLoading(false);
    }
  }, [tagId]);

  // ─── Load additional pages of media ──────────────────────────────────────────

  const loadMoreMedia = useCallback(async () => {
    if (!hasMore || isLoadingMore) return;
    try {
      setIsLoadingMore(true);
      const nextPage = mediaPageRef.current + 1;
      const items = await TagService.getMediaForTag(tagId, {
        limit: PAGE_SIZE,
        offset: nextPage * PAGE_SIZE,
        showHidden,
      });
      if (items.length === 0) {
        setHasMore(false);
        setIsLoadingMore(false);
        return;
      }
      mediaPageRef.current = nextPage;
      const allUris = items.map(r => r.uri).filter(Boolean);
      await warmCacheFromDb(allUris);
      const mapped = items.map(row => {
        const cachedThumb = getCachedThumbUri(row.uri);
        return {
          id: row.uri,
          uri: row.uri,
          filename: row.filename ?? row.uri.split('/').pop(),
          type: row.media_type ?? 'image',
          duration: row.duration ?? null,
          width: row.width ?? 0,
          height: row.height ?? 0,
          size: row.file_size ?? 0,
          ...(cachedThumb ? { thumbUri: cachedThumb } : {}),
        };
      });
      setMediaItems(prev => [...prev, ...mapped]);
      setHasMore(items.length === PAGE_SIZE);
      const unresolvedUris = mapped
        .filter(it => !it.thumbUri)
        .map(it => it.uri);
      if (unresolvedUris.length > 0) {
        await streamResolveThumbnailUris(unresolvedUris, thumbBatch => {
          setMediaItems(prev => {
            let changed = false;
            const next = prev.map(it => {
              if (!it.uri || it.thumbUri || !thumbBatch.has(it.uri)) return it;
              changed = true;
              return { ...it, thumbUri: thumbBatch.get(it.uri) };
            });
            return changed ? next : prev;
          });
        });
      }
    } catch (err) {
      console.warn('[TagGalleryScreen] loadMoreMedia error:', err);
    } finally {
      setIsLoadingMore(false);
    }
  }, [tagId, hasMore, isLoadingMore]);

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

  useEffect(() => {
    loadTag();
    loadMedia();
    loadExtendedStats();
  }, [loadTag, loadMedia, loadExtendedStats]);

  // Reload on focus (in case tags were modified in MediaViewer)
  useEffect(() => {
    const unsubscribe = navigation.addListener('focus', () => {
      loadTag();
      loadMedia();
      loadExtendedStats();
    });
    return unsubscribe;
  }, [navigation, loadTag, loadMedia, loadExtendedStats]);

  // ─── Edit modal ─────────────────────────────────────────────────────────────

  const openEdit = useCallback(() => {
    setEditName(tagData?.name ?? tagName);
    setEditDescription(tagData?.description ?? '');
    setEditColor(tagData?.color ?? tagColor);
    setEditVisible(true);
  }, [tagData, tagName, tagColor]);

  const saveEdit = useCallback(async () => {
    const trimmed = editName.trim();
    if (!trimmed) return;
    try {
      await TagService.updateTag(tagId, {
        name: trimmed,
        description: editDescription.trim() || null,
        color: editColor,
      });

      // Also update the in-memory tag map in MediaContext
      // so TagsScreen reflects the new name when navigating back
      const mediaForTag = await TagService.getMediaForTag(tagId, {
        limit: 5000,
        showHidden: true,
      });
      mediaForTag.forEach(row => {
        // Remove old tag name, add new one
        dispatch(MediaActions.removeTag(row.uri, tagName));
        dispatch(MediaActions.addTag(row.uri, trimmed));
      });

      setTagData(prev =>
        prev
          ? {
              ...prev,
              name: trimmed,
              description: editDescription.trim() || null,
              color: editColor,
            }
          : prev,
      );
      setEditVisible(false);
      toast.success('Tag updated');
    } catch (err) {
      console.warn('[TagGalleryScreen] saveEdit error:', err);
      toast.error('Failed to update tag');
    }
  }, [editName, editDescription, editColor, tagId, tagName, dispatch, toast]);

  // ─── Delete tag ─────────────────────────────────────────────────────────────

  const handleDeleteTag = useCallback(() => {
    setDeleteConfirmVisible(true);
  }, []);

  const confirmDeleteTag = useCallback(async () => {
    setDeleteConfirmVisible(false);
    try {
      const mediaForTag = await TagService.getMediaForTag(tagId, {
        limit: 5000,
        showHidden: true,
      });
      mediaForTag.forEach(row => {
        dispatch(MediaActions.removeTag(row.uri, tagName));
      });
      await TagService.deleteTag(tagId);
      toast.success('Tag deleted');
      navigation.goBack();
    } catch (err) {
      console.warn('[TagGalleryScreen] deleteTag error:', err);
      toast.error('Failed to delete tag');
    }
  }, [tagId, tagName, dispatch, toast, navigation]);

  // ─── Merge tag ───────────────────────────────────────────────────────────────

  const openMerge = useCallback(async () => {
    try {
      const tags = await TagService.getAllTags();
      setAllTags(tags.filter(t => t.id !== tagId));
      setMergeQuery('');
      setMergeTarget(null);
      setMergeVisible(true);
    } catch (err) {
      console.warn('[TagGalleryScreen] openMerge error:', err);
    }
  }, [tagId]);

  const handleMerge = useCallback(async () => {
    if (!mergeTarget) return;
    setMerging(true);
    try {
      await TagService.mergeTag(tagId, mergeTarget.id);
      toast.success(`Merged into "${mergeTarget.name}"`);
      setMergeConfirmVisible(false);
      setMergeVisible(false);
      navigation.goBack();
    } catch (err) {
      console.warn('[TagGalleryScreen] mergeTag error:', err);
      toast.error('Failed to merge tag');
    } finally {
      setMerging(false);
    }
  }, [tagId, mergeTarget, toast, navigation]);

  // ─── Change category ─────────────────────────────────────────────────────────

  const handleChangeCategory = useCallback(
    async newCategory => {
      try {
        await TagService.updateTag(tagId, { category: newCategory });
        setTagData(prev => (prev ? { ...prev, category: newCategory } : prev));
        setChangeCatVisible(false);
        toast.success('Category updated');
      } catch (err) {
        console.warn('[TagGalleryScreen] changeCategory error:', err);
        toast.error('Failed to update category');
      }
    },
    [tagId, toast],
  );

  // ─── Favourites handling ────────────────────────────────────────────────────

  const handleToggleFavorite = useCallback(
    async item => {
      await toggleFavorite(item);
    },
    [toggleFavorite],
  );

  // ─── Render helpers ─────────────────────────────────────────────────────────

  const handleItemPress = useCallback((item, index) => {
    setViewerIndex(index);
  }, []);

  const handleItemLongPress = useCallback(item => {
    setContextItem(item);
  }, []);

  const contextMenuItems = useMemo(() => {
    if (!contextItem) return [];
    return [
      {
        key: 'openWith',
        label: 'Open With',
        icon: 'openWith',
        onPress: () => {
          setContextItem(null);
          const mimeType = getMimeTypeForItem(contextItem);
          MediaStoreModule.openWith(contextItem.uri, mimeType).catch(() => {});
        },
      },
      {
        key: 'share',
        label: 'Share',
        icon: 'share1',
        onPress: () => {
          setContextItem(null);
          const mimeType = getMimeTypeForItem(contextItem);
          MediaStoreModule.shareFile(
            contextItem.uri,
            mimeType,
            contextItem.filename ?? '',
          ).catch(() => {});
        },
      },
    ];
  }, [contextItem]);

  // Convert favorites array to a Set for O(1) lookup.
  // Stored in a ref so renderItem stays stable while still reading current values
  // when FlatList calls it (triggered by extraData change on the FlatList).
  const favoritesSet = useMemo(
    () => new Set(mediaState.favorites ?? []),
    [mediaState.favorites],
  );
  const favoritesSetRef = useRef(favoritesSet);
  favoritesSetRef.current = favoritesSet;

  // ─── NSFW-filtered display items ────────────────────────────────────────────
  const displayItems = useMemo(() => {
    if (nsfwFilterEnabled && nsfwUrisSet.size > 0) {
      return mediaItems.filter(it => !nsfwUrisSet.has(it.uri));
    }
    return mediaItems;
  }, [mediaItems, nsfwFilterEnabled, nsfwUrisSet]);

  const sortedDisplayItems = useMemo(() => {
    if (gallerySort === 'date_asc') return [...displayItems].reverse();
    return displayItems;
  }, [displayItems, gallerySort]);

  const renderItem = useCallback(
    ({ item, index }) => {
      const isFav = favoritesSetRef.current.has(item.uri);
      return (
        <View
          style={{
            width: THUMBNAIL_SIZE,
            height: THUMBNAIL_SIZE,
            padding: ITEM_SPACING,
          }}
        >
          <MediaThumbnail
            item={item}
            size={THUMBNAIL_SIZE - ITEM_SPACING * 2}
            isFavorite={isFav}
            onPress={() => handleItemPress(item, index)}
            onLongPress={() => handleItemLongPress(item)}
          />
        </View>
      );
    },
    [handleItemPress, handleItemLongPress],
  );

  const keyExtractor = useCallback(item => item.uri, []);

  // ─── Related-tag navigation ──────────────────────────────────────────────────

  const handleRelatedTagPress = useCallback(
    tag => {
      navigation.push('TagGallery', {
        tagId: tag.id,
        tagName: tag.name,
        tagColor: tag.color && tag.color !== '#888888' ? tag.color : tagColor,
      });
    },
    [navigation, tagColor],
  );

  // ─── Info header (scrolls with the grid) ─────────────────────────────────────

  const InfoHeader = useCallback(
    () => {
      const count = sortedDisplayItems.length;
      const rawCount = mediaItems.length;
      const pct =
        totalMediaCount > 0
          ? Math.round((rawCount / totalMediaCount) * 100)
          : 0;
      const lastUpdated = tagData?.updated_at ?? tagData?.created_at;
      const firstTagged = tagData?.first_tagged_at;

      return (
        <View>
          {/* ── Top info block ─────────────────────────────────── */}
          <View style={infoStyles.block}>
            {/* Category badge — icon + label */}
            {(() => {
              const catDef = getCategoryDef(tagData?.category);
              return catDef ? (
                <View style={infoStyles.catRow}>
                  {catDef.icon && (
                    <Image
                      source={catDef.icon}
                      style={[infoStyles.catIcon, { tintColor: tagColor }]}
                      resizeMode="contain"
                    />
                  )}
                  <Text style={[infoStyles.catLabel, { color: tagColor }]}>
                    {catDef.label}
                  </Text>
                </View>
              ) : null;
            })()}

            {/* Tag name */}
            <Text style={[infoStyles.tagName, { color: colors.text }]}>
              {tagName ?? '—'}
            </Text>

            {/* Description */}
            {tagData?.description ? (
              <Text
                style={[
                  infoStyles.description,
                  { color: colors.textSecondary },
                ]}
              >
                {tagData.description}
              </Text>
            ) : null}

            {/* Combined stat: "N items • Updated 2 Mar 2026" */}
            <Text style={[infoStyles.statMain, { color: colors.text }]}>
              {rawCount.toLocaleString()} {rawCount === 1 ? 'item' : 'items'}
              {lastUpdated ? ` • Updated ${formatShortDate(lastUpdated)}` : ''}
            </Text>

            {/* Library distribution bar */}
            {totalMediaCount > 0 && (
              <View style={infoStyles.barContainer}>
                <View
                  style={[
                    infoStyles.barTrack,
                    { backgroundColor: colors.border },
                  ]}
                >
                  <View
                    style={[
                      infoStyles.barFill,
                      {
                        width: `${Math.max(pct, 1)}%`,
                        backgroundColor: tagColor,
                      },
                    ]}
                  />
                </View>
                <Text
                  style={[infoStyles.barLabel, { color: colors.textTertiary }]}
                >
                  {pct}
                  {'% of library'}
                </Text>
              </View>
            )}
          </View>

          {/* ── Divider ─────────────────────────────────────────── */}
          <View
            style={[infoStyles.divider, { backgroundColor: colors.divider }]}
          />

          {/* ── Related tags + expandable more details ────────────── */}
          <View style={infoStyles.miniBlock}>
            {/* Related tags */}
            {relatedTags.length > 0 && (
              <View style={infoStyles.relatedRow}>
                <Text
                  style={[infoStyles.miniStat, { color: colors.textTertiary }]}
                >
                  {'Related: '}
                </Text>
                <ScrollView
                  horizontal
                  showsHorizontalScrollIndicator={false}
                  contentContainerStyle={infoStyles.relatedScroll}
                >
                  {relatedTags.map(rt => (
                    <TouchableOpacity
                      key={rt.id}
                      style={[
                        infoStyles.relatedChip,
                        {
                          backgroundColor: tagColor + '22',
                          borderColor: tagColor + '55',
                        },
                      ]}
                      onPress={() => handleRelatedTagPress(rt)}
                      activeOpacity={0.7}
                    >
                      <Text
                        style={[
                          infoStyles.relatedChipText,
                          { color: tagColor },
                        ]}
                        numberOfLines={1}
                      >
                        {rt.name}
                      </Text>
                    </TouchableOpacity>
                  ))}
                </ScrollView>
              </View>
            )}

            {/* More details toggle */}
            <TouchableOpacity
              style={infoStyles.moreDetailsBtn}
              onPress={() => {
                runNativeTransition();
                setMoreDetailsOpen(p => !p);
              }}
              activeOpacity={0.7}
            >
              <Text
                style={[
                  infoStyles.moreDetailsBtnText,
                  { color: colors.textSecondary },
                ]}
              >
                {moreDetailsOpen ? 'Less details' : 'More details'}
              </Text>
              <Animated.Text
                style={[
                  infoStyles.moreDetailsChevron,
                  {
                    color: colors.textSecondary,
                    transform: [{ rotate: moreDetailsChevronRotate }],
                  },
                ]}
              >
                {'▾'}
              </Animated.Text>
            </TouchableOpacity>

            {/* Expandable: firstTagged */}
            {moreDetailsOpen && firstTagged ? (
              <Text
                style={[infoStyles.miniStat, { color: colors.textTertiary }]}
              >
                {'First tagged: '}
                <Text style={{ color: colors.textSecondary }}>
                  {formatMonthYear(firstTagged)}
                </Text>
              </Text>
            ) : null}
          </View>

          {/* ── Divider ─────────────────────────────────────────── */}
          <View
            style={[infoStyles.divider, { backgroundColor: colors.divider }]}
          />

          {/* ── Grid toolbar ──────────────────────────────────────── */}
          <View style={infoStyles.gridToolbar}>
            <TouchableOpacity
              style={infoStyles.gridSortBtn}
              onPress={() => {
                runNativeTransition();
                setGallerySortOpen(p => !p);
              }}
              activeOpacity={0.7}
            >
              <Text style={[infoStyles.gridSortText, { color: colors.text }]}>
                {'Sort: '}
                {GALLERY_SORT_OPTIONS.find(o => o.key === gallerySort)?.label ??
                  'Newest'}
                {'  ▾'}
              </Text>
            </TouchableOpacity>
            <Text
              style={[infoStyles.gridCountText, { color: colors.textTertiary }]}
            >
              {count.toLocaleString()} {count === 1 ? 'item' : 'items'}
            </Text>
          </View>

          {/* Sort dropdown */}
          {gallerySortOpen && (
            <View
              style={[
                infoStyles.sortDropdown,
                { backgroundColor: colors.card, borderColor: colors.border },
              ]}
            >
              {GALLERY_SORT_OPTIONS.map(opt => (
                <TouchableOpacity
                  key={opt.key}
                  style={infoStyles.sortOption}
                  onPress={() => {
                    runNativeTransition();
                    setGallerySort(opt.key);
                    setGallerySortOpen(false);
                  }}
                >
                  <Text
                    style={[
                      infoStyles.sortOptionText,
                      {
                        color: gallerySort === opt.key ? tagColor : colors.text,
                      },
                    ]}
                  >
                    {gallerySort === opt.key ? '✓  ' : '    '}
                    {opt.label}
                  </Text>
                </TouchableOpacity>
              ))}
            </View>
          )}

          {/* ── Divider before grid ──────────────────────────────── */}
          <View
            style={[infoStyles.divider, { backgroundColor: colors.divider }]}
          />
        </View>
      );
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [
      tagName,
      tagData,
      tagColor,
      sortedDisplayItems.length,
      mediaItems.length,
      totalMediaCount,
      relatedTags,
      colors,
      handleRelatedTagPress,
      moreDetailsOpen,
      gallerySort,
      gallerySortOpen,
    ],
  );

  // ─── Render ─────────────────────────────────────────────────────────────────

  const bgColor = isDark ? colors.background : colors.background;

  return (
    <View style={[styles.container, { backgroundColor: bgColor }]}>
      <StatusBar
        barStyle={isDark ? 'light-content' : 'dark-content'}
        backgroundColor={bgColor}
      />
      {/* ── Minimal nav bar ──────────────────────────────────────── */}
      <View
        style={[
          navBar.container,
          {
            borderBottomColor: colors.divider,
            paddingTop: insets.top || 8,
          },
        ]}
      >
        <TouchableOpacity
          style={navBar.backBtn}
          onPress={() => navigation.goBack()}
          accessibilityLabel="Go back"
        >
          <Text style={[navBar.backText, { color: colors.text }]}>{'‹'}</Text>
        </TouchableOpacity>

        {/* Category icon (falls back to color dot if category not loaded yet) */}
        {(() => {
          const catDef = getCategoryDef(tagData?.category);
          return catDef?.icon ? (
            <Image
              source={catDef.icon}
              style={[navBar.catIcon, { tintColor: tagColor }]}
              resizeMode="contain"
            />
          ) : (
            <View style={[navBar.colorDot, { backgroundColor: tagColor }]} />
          );
        })()}

        <Text
          style={[navBar.title, { color: colors.textSecondary }]}
          numberOfLines={1}
        >
          {tagName ?? ''}
        </Text>

        <TouchableOpacity
          style={navBar.editBtn}
          onPress={() => setOverflowVisible(true)}
          accessibilityLabel="Tag options"
          hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
        >
          <Text
            style={[navBar.editIcon, { color: isDark ? '#8E8E93' : '#636366' }]}
          >
            {'⋮'}
          </Text>
        </TouchableOpacity>
      </View>
      {/* Overflow menu modal */}
      <Modal
        visible={overflowVisible}
        transparent
        animationType="fade"
        statusBarTranslucent
        onRequestClose={() => setOverflowVisible(false)}
      >
        <Pressable
          style={overflowStyles.backdrop}
          onPress={() => setOverflowVisible(false)}
        >
          <Pressable
            style={[
              overflowStyles.menu,
              { backgroundColor: isDark ? '#2C2C2E' : '#FFF' },
            ]}
            onPress={e => e.stopPropagation()}
          >
            <TouchableOpacity
              style={overflowStyles.menuItem}
              onPress={() => {
                setOverflowVisible(false);
                openEdit();
              }}
            >
              <Text
                style={[
                  overflowStyles.menuText,
                  { color: isDark ? '#FFF' : '#000' },
                ]}
              >
                Rename / Edit
              </Text>
            </TouchableOpacity>
            <View
              style={[
                overflowStyles.menuDivider,
                { backgroundColor: isDark ? '#3A3A3C' : '#E5E5EA' },
              ]}
            />
            <TouchableOpacity
              style={overflowStyles.menuItem}
              onPress={() => {
                setOverflowVisible(false);
                setTimeout(openMerge, 200);
              }}
            >
              <Text
                style={[
                  overflowStyles.menuText,
                  { color: isDark ? '#FFF' : '#000' },
                ]}
              >
                Merge tag
              </Text>
            </TouchableOpacity>
            <View
              style={[
                overflowStyles.menuDivider,
                { backgroundColor: isDark ? '#3A3A3C' : '#E5E5EA' },
              ]}
            />
            <TouchableOpacity
              style={overflowStyles.menuItem}
              onPress={() => {
                setOverflowVisible(false);
                setChangeCatVisible(true);
              }}
            >
              <Text
                style={[
                  overflowStyles.menuText,
                  { color: isDark ? '#FFF' : '#000' },
                ]}
              >
                Change category
              </Text>
            </TouchableOpacity>
            <View
              style={[
                overflowStyles.menuDivider,
                { backgroundColor: isDark ? '#3A3A3C' : '#E5E5EA' },
              ]}
            />
            <TouchableOpacity
              style={overflowStyles.menuItem}
              onPress={() => {
                setOverflowVisible(false);
                setTimeout(handleDeleteTag, 200);
              }}
            >
              <Text style={[overflowStyles.menuText, { color: colors.error }]}>
                Delete tag
              </Text>
            </TouchableOpacity>
          </Pressable>
        </Pressable>
      </Modal>
      {/* Edit tag modal */}
      <Modal
        visible={editVisible}
        animationType={Platform.OS === 'ios' ? 'slide' : 'fade'}
        presentationStyle={Platform.OS === 'ios' ? 'pageSheet' : 'fullScreen'}
        statusBarTranslucent
        onRequestClose={() => setEditVisible(false)}
      >
        <KeyboardAvoidingView
          style={{ flex: 1, backgroundColor: isDark ? '#1C1C1E' : '#F2F2F7' }}
          behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
        >
          <View
            style={[
              editStyles.container,
              {
                backgroundColor: isDark ? '#1C1C1E' : '#F2F2F7',
                paddingTop: insets.top,
                paddingBottom: insets.bottom,
              },
            ]}
          >
            {/* Modal header */}
            <View style={editStyles.modalHeader}>
              <TouchableOpacity
                onPress={() => setEditVisible(false)}
                hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
              >
                <Text style={[editStyles.cancelBtn, { color: colors.error }]}>
                  Cancel
                </Text>
              </TouchableOpacity>
              <Text
                style={[
                  editStyles.modalTitle,
                  { color: isDark ? '#FFF' : '#000' },
                ]}
              >
                Edit Tag
              </Text>
              <TouchableOpacity
                onPress={saveEdit}
                disabled={!editName.trim()}
                hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
              >
                <Text
                  style={[
                    editStyles.saveBtn,
                    {
                      color: editName.trim()
                        ? tagColor
                        : isDark
                        ? '#555'
                        : '#CCC',
                    },
                  ]}
                >
                  Save
                </Text>
              </TouchableOpacity>
            </View>

            <ScrollView
              style={editStyles.formScroll}
              keyboardShouldPersistTaps="handled"
            >
              {/* Name field */}
              <Text
                style={[
                  editStyles.fieldLabel,
                  { color: isDark ? '#9A9A9A' : '#666' },
                ]}
              >
                TAG NAME
              </Text>
              <TextInput
                style={[
                  editStyles.fieldInput,
                  {
                    backgroundColor: isDark ? '#2C2C2E' : '#FFF',
                    color: isDark ? '#FFF' : '#000',
                    borderColor: isDark ? '#3A3A3C' : '#E0E0E0',
                  },
                ]}
                value={editName}
                onChangeText={setEditName}
                placeholder="Tag name"
                placeholderTextColor={isDark ? '#555' : '#BBB'}
                autoCapitalize="none"
                autoCorrect={false}
                returnKeyType="done"
                onSubmitEditing={saveEdit}
              />

              <Text
                style={[editStyles.hint, { color: isDark ? '#555' : '#BBB' }]}
              >
                Renaming a tag updates it everywhere automatically — all media
                associated with this tag will reflect the new name instantly.
              </Text>

              {/* Description field */}
              <Text
                style={[
                  editStyles.fieldLabel,
                  { color: isDark ? '#9A9A9A' : '#666', marginTop: 20 },
                ]}
              >
                DESCRIPTION
              </Text>
              <TextInput
                style={[
                  editStyles.fieldInput,
                  editStyles.fieldInputMultiline,
                  {
                    backgroundColor: isDark ? '#2C2C2E' : '#FFF',
                    color: isDark ? '#FFF' : '#000',
                    borderColor: isDark ? '#3A3A3C' : '#E0E0E0',
                  },
                ]}
                value={editDescription}
                onChangeText={setEditDescription}
                placeholder="Optional description for this tag…"
                placeholderTextColor={isDark ? '#555' : '#BBB'}
                multiline
                numberOfLines={3}
                maxLength={300}
                returnKeyType="default"
                textAlignVertical="top"
              />
              <Text
                style={[editStyles.hint, { color: isDark ? '#555' : '#BBB' }]}
              >
                Shown on the tag card in the Tags screen.
              </Text>

              {/* Color picker */}
              <Text
                style={[
                  editStyles.fieldLabel,
                  { color: isDark ? '#9A9A9A' : '#666', marginTop: 20 },
                ]}
              >
                COLOR
              </Text>
              <View style={editStyles.colorGrid}>
                {TAG_COLOR_PALETTE.map(c => (
                  <TouchableOpacity
                    key={c}
                    style={[
                      editStyles.colorSwatch,
                      { backgroundColor: c },
                      editColor === c && editStyles.colorSwatchSelected,
                    ]}
                    onPress={() => setEditColor(c)}
                    activeOpacity={0.75}
                  >
                    {editColor === c && (
                      <Text style={editStyles.colorCheck}>✓</Text>
                    )}
                  </TouchableOpacity>
                ))}
              </View>

              {/* Delete Tag button */}
              <TouchableOpacity
                style={[
                  editStyles.deleteBtn,
                  { backgroundColor: colors.error },
                ]}
                onPress={() => {
                  setEditVisible(false);
                  setTimeout(() => setDeleteConfirmVisible(true), 300);
                }}
              >
                <Text style={editStyles.deleteBtnText}>Delete Tag</Text>
              </TouchableOpacity>
            </ScrollView>
          </View>
        </KeyboardAvoidingView>
      </Modal>
      {/* ── Media grid (with detail header as ListHeaderComponent) ─── */}
      {isLoading && mediaItems.length === 0 ? (
        <View style={{ flex: 1 }}>
          {tagData ? <InfoHeader /> : null}
          <LoadingSpinner />
        </View>
      ) : (
        <FlatList
          data={sortedDisplayItems}
          renderItem={renderItem}
          keyExtractor={keyExtractor}
          numColumns={COLUMN_COUNT}
          ListHeaderComponent={InfoHeader}
          ListEmptyComponent={
            <View style={emptyGridStyles.wrap}>
              {mediaItems.length > 0 ? (
                <>
                  <Text style={emptyGridStyles.icon}>🚫</Text>
                  <Text style={[emptyGridStyles.title, { color: colors.text }]}>
                    All items hidden
                  </Text>
                  <Text
                    style={[
                      emptyGridStyles.subtitle,
                      { color: colors.textSecondary },
                    ]}
                  >
                    Disable the content filter to view this tag.
                  </Text>
                </>
              ) : (
                <>
                  <Text style={emptyGridStyles.icon}>🏷️</Text>
                  <Text style={[emptyGridStyles.title, { color: colors.text }]}>
                    No media tagged
                  </Text>
                  <Text
                    style={[
                      emptyGridStyles.subtitle,
                      { color: colors.textSecondary },
                    ]}
                  >
                    Tag media items from the viewer to see them here.
                  </Text>
                </>
              )}
            </View>
          }
          ListFooterComponent={
            isLoadingMore ? (
              <View style={{ paddingVertical: 20, alignItems: 'center' }}>
                <ActivityIndicator size="small" color={tagColor} />
              </View>
            ) : null
          }
          contentContainerStyle={styles.listContent}
          showsVerticalScrollIndicator={true}
          initialNumToRender={18}
          maxToRenderPerBatch={12}
          windowSize={7}
          removeClippedSubviews={true}
          extraData={favoritesSet}
          onEndReached={loadMoreMedia}
          onEndReachedThreshold={0.8}
        />
      )}
      {/* Media Viewer */}
      <MediaViewer
        visible={viewerIndex >= 0}
        items={displayItems}
        initialIndex={viewerIndex}
        initialMaximized
        onClose={() => setViewerIndex(-1)}
        onToggleFavorite={handleToggleFavorite}
        onRename={renameMedia}
        onOpenPicker={(mode, item) =>
          navigation.navigate('FolderPicker', {
            mode,
            items: [item],
            sourceFolder: null,
            returnRoute: 'Main',
          })
        }
      />
      {/* Long-press context menu */}
      <ContextMenu
        visible={!!contextItem}
        onClose={() => setContextItem(null)}
        title={contextItem?.filename ?? ''}
        items={contextMenuItems}
      />

      {/* ── Delete confirm dialog ────────────────────────────────────────── */}
      <Modal
        visible={deleteConfirmVisible}
        transparent
        animationType="fade"
        statusBarTranslucent
        onRequestClose={() => setDeleteConfirmVisible(false)}
      >
        <Pressable
          style={dialogStyles.backdrop}
          onPress={() => setDeleteConfirmVisible(false)}
        >
          <Pressable
            style={[dialogStyles.dialog, { backgroundColor: colors.card }]}
            onPress={e => e.stopPropagation()}
          >
            <Text style={[dialogStyles.title, { color: colors.text }]}>
              Delete Tag
            </Text>
            <Text style={[dialogStyles.body, { color: colors.textSecondary }]}>
              {`Delete “${tagName}”? This removes the tag from all media. Your files will not be deleted.`}
            </Text>
            <View style={dialogStyles.actions}>
              <TouchableOpacity
                style={[dialogStyles.btn, { borderColor: colors.border }]}
                onPress={() => setDeleteConfirmVisible(false)}
              >
                <Text style={[dialogStyles.btnText, { color: colors.text }]}>
                  Cancel
                </Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[
                  dialogStyles.btn,
                  { backgroundColor: colors.error, borderColor: colors.error },
                ]}
                onPress={confirmDeleteTag}
              >
                <Text style={[dialogStyles.btnText, { color: '#FFF' }]}>
                  Delete
                </Text>
              </TouchableOpacity>
            </View>
          </Pressable>
        </Pressable>
      </Modal>

      {/* ── Merge confirm dialog ─────────────────────────────────────────── */}
      <Modal
        visible={mergeConfirmVisible}
        transparent
        animationType="fade"
        statusBarTranslucent
        onRequestClose={() => setMergeConfirmVisible(false)}
      >
        <Pressable
          style={dialogStyles.backdrop}
          onPress={() => setMergeConfirmVisible(false)}
        >
          <Pressable
            style={[dialogStyles.dialog, { backgroundColor: colors.card }]}
            onPress={e => e.stopPropagation()}
          >
            <Text style={[dialogStyles.title, { color: colors.text }]}>
              Confirm Merge
            </Text>
            <Text style={[dialogStyles.body, { color: colors.textSecondary }]}>
              {`Merge “${tagName}” into “${mergeTarget?.name}”?\n\n“${tagName}” will be permanently deleted and all its media will carry “${mergeTarget?.name}” instead.`}
            </Text>
            <View style={dialogStyles.actions}>
              <TouchableOpacity
                style={[dialogStyles.btn, { borderColor: colors.border }]}
                onPress={() => setMergeConfirmVisible(false)}
                disabled={merging}
              >
                <Text style={[dialogStyles.btnText, { color: colors.text }]}>
                  Cancel
                </Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[
                  dialogStyles.btn,
                  {
                    backgroundColor: colors.accent,
                    borderColor: colors.accent,
                    opacity: merging ? 0.6 : 1,
                  },
                ]}
                onPress={handleMerge}
                disabled={merging}
              >
                <Text style={[dialogStyles.btnText, { color: '#FFF' }]}>
                  {merging ? 'Merging…' : 'Merge'}
                </Text>
              </TouchableOpacity>
            </View>
          </Pressable>
        </Pressable>
      </Modal>

      {/* ── Change category bottom sheet ────────────────────────────────── */}
      <Modal
        visible={changeCatVisible}
        transparent
        animationType="slide"
        statusBarTranslucent
        onRequestClose={() => setChangeCatVisible(false)}
      >
        <Pressable
          style={catSheetStyles.backdrop}
          onPress={() => setChangeCatVisible(false)}
        >
          <Pressable
            style={[catSheetStyles.sheet, { backgroundColor: colors.card }]}
            onPress={e => e.stopPropagation()}
          >
            <View
              style={[
                catSheetStyles.handle,
                { backgroundColor: colors.border },
              ]}
            />
            <Text style={[catSheetStyles.title, { color: colors.text }]}>
              Change Category
            </Text>
            {TAG_CATEGORY_LIST.map((cat, idx) => {
              const isActive = (tagData?.category ?? 'misc') === cat.key;
              return (
                <TouchableOpacity
                  key={cat.key}
                  style={[
                    catSheetStyles.row,
                    {
                      borderBottomColor:
                        idx < TAG_CATEGORY_LIST.length - 1
                          ? colors.divider
                          : 'transparent',
                    },
                  ]}
                  onPress={() => handleChangeCategory(cat.key)}
                  activeOpacity={0.7}
                >
                  <Image
                    source={cat.icon}
                    style={[
                      catSheetStyles.catIcon,
                      {
                        tintColor: isActive ? tagColor : colors.textSecondary,
                      },
                    ]}
                    resizeMode="contain"
                  />
                  <Text
                    style={[
                      catSheetStyles.catLabel,
                      {
                        color: isActive ? tagColor : colors.text,
                        fontWeight: isActive ? '700' : '400',
                      },
                    ]}
                  >
                    {cat.label}
                  </Text>
                  {isActive && (
                    <Text style={{ color: tagColor, fontSize: 16 }}>✓</Text>
                  )}
                </TouchableOpacity>
              );
            })}
          </Pressable>
        </Pressable>
      </Modal>

      {/* ── Merge tag full-page sheet ──────────────────────────────────────── */}
      <Modal
        visible={mergeVisible}
        animationType={Platform.OS === 'ios' ? 'slide' : 'fade'}
        presentationStyle={Platform.OS === 'ios' ? 'pageSheet' : 'fullScreen'}
        statusBarTranslucent
        onRequestClose={() => setMergeVisible(false)}
      >
        <View
          style={[
            mergeSheetStyles.container,
            {
              backgroundColor: colors.background,
              paddingTop: insets.top,
              paddingBottom: insets.bottom,
            },
          ]}
        >
          {/* Header */}
          <View
            style={[
              mergeSheetStyles.header,
              { borderBottomColor: colors.divider },
            ]}
          >
            <TouchableOpacity
              onPress={() => setMergeVisible(false)}
              hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
            >
              <Text
                style={[
                  mergeSheetStyles.cancelBtn,
                  { color: colors.textSecondary },
                ]}
              >
                Cancel
              </Text>
            </TouchableOpacity>
            <Text style={[mergeSheetStyles.title, { color: colors.text }]}>
              Merge Tag
            </Text>
            <View style={{ width: 64 }} />
          </View>

          {/* Description */}
          <Text
            style={[
              mergeSheetStyles.description,
              { color: colors.textSecondary },
            ]}
          >
            {`Select a tag to merge “${tagName}” into. All media tagged with “${tagName}” will be re-tagged, then “${tagName}” will be deleted.`}
          </Text>

          {/* Search bar */}
          <View
            style={[
              mergeSheetStyles.searchWrap,
              { backgroundColor: colors.surface, borderColor: colors.border },
            ]}
          >
            <TextInput
              style={[mergeSheetStyles.searchInput, { color: colors.text }]}
              value={mergeQuery}
              onChangeText={setMergeQuery}
              placeholder="Search tags…"
              placeholderTextColor={colors.textTertiary}
              autoCorrect={false}
              autoCapitalize="none"
              clearButtonMode="while-editing"
            />
          </View>

          {/* Tag list */}
          <FlatList
            data={allTags.filter(
              t =>
                !mergeQuery ||
                t.name.toLowerCase().includes(mergeQuery.toLowerCase()),
            )}
            keyExtractor={t => String(t.id)}
            renderItem={({ item }) => {
              const isSelected = mergeTarget?.id === item.id;
              const dotColor =
                item.color && item.color !== '#888888'
                  ? item.color
                  : colors.textTertiary;
              return (
                <TouchableOpacity
                  style={[
                    mergeSheetStyles.tagRow,
                    {
                      borderBottomColor: colors.divider,
                      backgroundColor: isSelected
                        ? tagColor + '18'
                        : 'transparent',
                    },
                  ]}
                  onPress={() => setMergeTarget(isSelected ? null : item)}
                  activeOpacity={0.7}
                >
                  <View
                    style={[
                      mergeSheetStyles.tagDot,
                      { backgroundColor: dotColor },
                    ]}
                  />
                  <Text
                    style={[
                      mergeSheetStyles.tagName,
                      { color: isSelected ? tagColor : colors.text },
                    ]}
                    numberOfLines={1}
                  >
                    {item.name}
                  </Text>
                  <Text
                    style={[
                      mergeSheetStyles.tagCount,
                      { color: colors.textTertiary },
                    ]}
                  >
                    {(item.usage_count ?? 0).toLocaleString()}
                  </Text>
                  {isSelected && (
                    <Text
                      style={{ color: tagColor, fontSize: 16, marginLeft: 8 }}
                    >
                      ✓
                    </Text>
                  )}
                </TouchableOpacity>
              );
            }}
            showsVerticalScrollIndicator={false}
            keyboardShouldPersistTaps="handled"
          />

          {/* Footer confirm button */}
          {mergeTarget && (
            <View
              style={[
                mergeSheetStyles.footer,
                {
                  borderTopColor: colors.divider,
                  backgroundColor: colors.background,
                  paddingBottom: insets.bottom + 16,
                },
              ]}
            >
              <TouchableOpacity
                style={[
                  mergeSheetStyles.mergeBtn,
                  {
                    backgroundColor: colors.accent,
                    opacity: merging ? 0.6 : 1,
                  },
                ]}
                onPress={() => setMergeConfirmVisible(true)}
                disabled={merging}
              >
                <Text style={mergeSheetStyles.mergeBtnText}>
                  {`Merge into “${mergeTarget.name}”`}
                </Text>
              </TouchableOpacity>
            </View>
          )}
        </View>
      </Modal>
    </View>
  );
}

// ─── Header styles ────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 8,
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  backButton: {
    width: 44,
    height: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  backText: {
    fontSize: 34,
    lineHeight: 38,
    fontWeight: '300',
  },
  titleArea: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginLeft: 4,
  },
  colorDot: {
    width: 14,
    height: 14,
    borderRadius: 7,
  },
  title: {
    flex: 1,
    fontSize: 18,
    fontWeight: '700',
    letterSpacing: 0.2,
  },
  countChip: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 20,
    marginRight: 4,
  },
  countText: {
    fontSize: 13,
    fontWeight: '700',
  },
  editButton: {
    width: 40,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 4,
  },
  editIcon: {
    fontSize: 22,
  },
  listContent: {
    padding: ITEM_SPACING,
  },
});

// ─── Edit Modal styles ────────────────────────────────────────────────────────

const editStyles = StyleSheet.create({
  container: {
    flex: 1,
  },
  modalHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    paddingVertical: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: 'rgba(128,128,128,0.25)',
  },
  cancelBtn: {
    fontSize: 16,
    fontWeight: '500',
  },
  modalTitle: {
    fontSize: 17,
    fontWeight: '700',
    letterSpacing: 0.1,
  },
  saveBtn: {
    fontSize: 16,
    fontWeight: '700',
  },
  formScroll: {
    flex: 1,
    paddingHorizontal: 20,
    paddingTop: 24,
  },
  fieldLabel: {
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 0.8,
    textTransform: 'uppercase',
    marginBottom: 8,
  },
  fieldInput: {
    borderRadius: 12,
    borderWidth: 1,
    paddingHorizontal: 16,
    paddingVertical: 14,
    fontSize: 16,
  },
  fieldInputMultiline: {
    minHeight: 90,
    paddingTop: 12,
  },
  colorGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
    marginBottom: 24,
  },
  colorSwatch: {
    width: 36,
    height: 36,
    borderRadius: 18,
    alignItems: 'center',
    justifyContent: 'center',
  },
  colorSwatchSelected: {
    borderWidth: 3,
    borderColor: '#fff',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.4,
    shadowRadius: 4,
    elevation: 6,
  },
  colorCheck: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '700',
  },
  hint: {
    fontSize: 13,
    lineHeight: 19,
    marginTop: 10,
    marginBottom: 10,
  },
  deleteBtn: {
    alignSelf: 'center',
    paddingHorizontal: 24,
    paddingVertical: 12,
    borderRadius: 12,
    marginTop: 20,
    marginBottom: 40,
  },
  deleteBtnText: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '700',
  },
});
// ─── Minimal nav bar styles ───────────────────────────────────────────

const navBar = StyleSheet.create({
  container: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 8,
    paddingVertical: 8,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  backBtn: {
    width: 44,
    height: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  backText: {
    fontSize: 34,
    lineHeight: 38,
    fontWeight: '300',
  },
  colorDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
    marginRight: 6,
  },
  catIcon: {
    width: 18,
    height: 18,
    marginRight: 6,
  },
  title: {
    flex: 1,
    fontSize: 14,
    fontWeight: '600',
    letterSpacing: 0.1,
  },
  editBtn: {
    width: 40,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 4,
  },
  editIcon: {
    fontSize: 22,
  },
});

// ─── Detail info header styles ────────────────────────────────────────

const infoStyles = StyleSheet.create({
  block: {
    paddingHorizontal: 20,
    paddingTop: 22,
    paddingBottom: 16,
  },
  tagName: {
    fontSize: 30,
    fontWeight: '800',
    letterSpacing: -0.5,
    marginBottom: 6,
  },
  catRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 8,
  },
  catIcon: {
    width: 15,
    height: 15,
    marginRight: 5,
  },
  catLabel: {
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 1.1,
    textTransform: 'uppercase',
  },
  description: {
    fontSize: 14,
    lineHeight: 20,
    marginBottom: 14,
  },
  statsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 6,
  },
  statMain: {
    fontSize: 16,
    fontWeight: '700',
    letterSpacing: 0.1,
  },
  statSep: {
    fontSize: 14,
  },
  statSub: {
    fontSize: 14,
    fontWeight: '500',
  },
  lastUpdated: {
    fontSize: 12,
    fontWeight: '500',
    letterSpacing: 0.1,
    marginTop: 2,
  },
  divider: {
    height: StyleSheet.hairlineWidth,
    marginHorizontal: 0,
  },
  miniBlock: {
    paddingHorizontal: 20,
    paddingVertical: 14,
    gap: 10,
  },
  miniStat: {
    fontSize: 13,
    fontWeight: '500',
    lineHeight: 18,
  },
  relatedRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  relatedScroll: {
    gap: 6,
    paddingRight: 8,
  },
  relatedChip: {
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: 20,
    borderWidth: StyleSheet.hairlineWidth,
  },
  relatedChipText: {
    fontSize: 12,
    fontWeight: '600',
  },
  barContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 12,
    gap: 10,
  },
  barTrack: {
    flex: 1,
    height: 14,
    borderRadius: 7,
    overflow: 'hidden',
  },
  barFill: {
    height: '100%',
    borderRadius: 7,
  },
  barLabel: {
    fontSize: 12,
    fontWeight: '600',
    minWidth: 52,
    textAlign: 'right',
  },
  moreDetailsBtn: {
    alignSelf: 'flex-start',
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    paddingVertical: 2,
  },
  moreDetailsChevron: {
    fontSize: 14,
    fontWeight: '600',
    lineHeight: 18,
  },
  moreDetailsBtnText: {
    fontSize: 13,
    fontWeight: '500',
  },
  gridToolbar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 10,
  },
  gridSortBtn: {
    paddingVertical: 4,
    paddingHorizontal: 2,
  },
  gridSortText: {
    fontSize: 13,
    fontWeight: '600',
  },
  gridCountText: {
    fontSize: 12,
    fontWeight: '500',
  },
  sortDropdown: {
    marginHorizontal: 16,
    marginBottom: 4,
    borderRadius: 10,
    borderWidth: StyleSheet.hairlineWidth,
    overflow: 'hidden',
  },
  sortOption: {
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  sortOptionText: {
    fontSize: 14,
    fontWeight: '500',
  },
});

// ─── Empty grid state styles ──────────────────────────────────────────

const emptyGridStyles = StyleSheet.create({
  wrap: {
    alignItems: 'center',
    paddingTop: 48,
    paddingHorizontal: 40,
    paddingBottom: 60,
  },
  icon: {
    fontSize: 44,
    marginBottom: 16,
  },
  title: {
    fontSize: 17,
    fontWeight: '700',
    textAlign: 'center',
    marginBottom: 8,
  },
  subtitle: {
    fontSize: 13,
    lineHeight: 20,
    textAlign: 'center',
  },
});

// ─── Overflow menu styles ─────────────────────────────────────────────

const overflowStyles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.35)',
    justifyContent: 'flex-start',
    alignItems: 'flex-end',
    paddingTop: 56,
    paddingRight: 8,
  },
  menu: {
    minWidth: 200,
    borderRadius: 14,
    overflow: 'hidden',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.18,
    shadowRadius: 12,
    elevation: 8,
  },
  menuItem: {
    paddingHorizontal: 16,
    paddingVertical: 14,
  },
  menuText: {
    fontSize: 15,
    fontWeight: '500',
  },
  menuDivider: {
    height: StyleSheet.hairlineWidth,
  },
});

// ─── Shared dialog styles ───────────────────────────────────────────────────

const dialogStyles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.55)',
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 28,
  },
  dialog: {
    width: '100%',
    borderRadius: 20,
    padding: 24,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 8 },
    shadowOpacity: 0.22,
    shadowRadius: 24,
    elevation: 12,
  },
  title: {
    fontSize: 17,
    fontWeight: '700',
    marginBottom: 10,
    letterSpacing: 0.1,
  },
  body: {
    fontSize: 14,
    lineHeight: 21,
    marginBottom: 22,
  },
  actions: {
    flexDirection: 'row',
    gap: 10,
  },
  btn: {
    flex: 1,
    paddingVertical: 13,
    borderRadius: 12,
    borderWidth: 1,
    alignItems: 'center',
  },
  btnText: {
    fontSize: 15,
    fontWeight: '600',
  },
});

// ─── Change-category bottom-sheet styles ──────────────────────────────

const catSheetStyles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.50)',
    justifyContent: 'flex-end',
  },
  sheet: {
    borderTopLeftRadius: 22,
    borderTopRightRadius: 22,
    paddingTop: 12,
    paddingBottom: 32,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: -4 },
    shadowOpacity: 0.15,
    shadowRadius: 16,
    elevation: 12,
  },
  handle: {
    width: 38,
    height: 4,
    borderRadius: 2,
    alignSelf: 'center',
    marginBottom: 16,
  },
  title: {
    fontSize: 16,
    fontWeight: '700',
    letterSpacing: 0.1,
    paddingHorizontal: 20,
    marginBottom: 8,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 14,
    borderBottomWidth: StyleSheet.hairlineWidth,
    gap: 12,
  },
  catIcon: {
    width: 20,
    height: 20,
  },
  catLabel: {
    flex: 1,
    fontSize: 15,
  },
});

// ─── Merge-tag sheet styles ────────────────────────────────────────────────

const mergeSheetStyles = StyleSheet.create({
  container: {
    flex: 1,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    paddingVertical: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  cancelBtn: {
    fontSize: 16,
    fontWeight: '500',
    width: 64,
  },
  title: {
    fontSize: 17,
    fontWeight: '700',
    letterSpacing: 0.1,
  },
  description: {
    fontSize: 13,
    lineHeight: 19,
    paddingHorizontal: 20,
    paddingTop: 14,
    paddingBottom: 6,
  },
  searchWrap: {
    marginHorizontal: 16,
    marginVertical: 10,
    borderRadius: 12,
    borderWidth: 1,
    paddingHorizontal: 14,
    paddingVertical: 10,
  },
  searchInput: {
    fontSize: 15,
  },
  tagRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 14,
    borderBottomWidth: StyleSheet.hairlineWidth,
    gap: 12,
  },
  tagDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
  },
  tagName: {
    flex: 1,
    fontSize: 15,
    fontWeight: '500',
  },
  tagCount: {
    fontSize: 12,
    fontWeight: '500',
  },
  footer: {
    paddingHorizontal: 20,
    paddingTop: 16,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  mergeBtn: {
    borderRadius: 14,
    paddingVertical: 15,
    alignItems: 'center',
  },
  mergeBtnText: {
    color: '#FFF',
    fontSize: 16,
    fontWeight: '700',
  },
});
