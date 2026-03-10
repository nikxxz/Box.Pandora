/**
 * SearchScreen
 *
 * Global media search — searches filenames and tag names across the full
 * media index.  Results open in the normal MediaViewer.
 *
 * Filtering:
 *   • Type pills: All / Images / Videos
 *   • NSFW shield: when active (default) NSFW-tagged items are excluded
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../../providers/ThemeProvider';
import { useAppContext } from '../../store/AppContext';
import { useMediaContext } from '../../store/MediaContext';
import { MediaActions } from '../../store/actions';
import { Icon } from '../../components/ui/Icon';
import { MediaThumbnail } from '../../components/grid/MediaThumbnail';
import { MediaViewer } from '../../components/media/MediaViewer';
import { SearchService } from '../../services/database/SearchService';
import { useMediaLibrary } from '../../hooks/useMediaLibrary';
import {
  warmCacheFromDb,
  streamResolveThumbnailUris,
  getCachedThumbUri,
} from '../../services/cache/ThumbnailCache';

// ─── Constants ────────────────────────────────────────────────────────────────

const NUM_COLUMNS = 3;
const DEBOUNCE_MS = 250;

const TYPE_FILTERS = [
  { id: 'all', label: 'All' },
  { id: 'images', label: 'Images' },
  { id: 'videos', label: 'Videos' },
];

// ─── Screen ───────────────────────────────────────────────────────────────────

export function SearchScreen({ navigation }) {
  const insets = useSafeAreaInsets();
  const { colors, isDark } = useTheme();
  const { state: appState } = useAppContext();
  const { state: mediaState, dispatch: mediaDispatch } = useMediaContext();
  const { toggleFavorite } = useMediaLibrary();
  const accent = appState.accentColor ?? colors.accent;
  const nsfwFilterEnabled = appState.nsfwFilterEnabled ?? false;
  const showHidden = appState.showHidden ?? false;

  // ── State ─────────────────────────────────────────────────────────────────
  const [query, setQuery] = useState('');
  const [mediaType, setMediaType] = useState('all');
  const [nsfwTagNames, setNsfwTagNames] = useState([]);
  const [results, setResults] = useState([]);
  const [isSearching, setIsSearching] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);
  const [viewerIndex, setViewerIndex] = useState(-1);

  const inputRef = useRef(null);
  const debounceRef = useRef(null);

  // ── Load NSFW tag list on mount ───────────────────────────────────────────
  useEffect(() => {
    SearchService.getNsfwTagNames().then(tags => setNsfwTagNames(tags));
    // Auto-focus the input
    const t = setTimeout(() => inputRef.current?.focus(), 150);
    return () => clearTimeout(t);
  }, []);

  // ── Derived: favourites set (O(1) lookup) ─────────────────────────────────
  const favSet = useMemo(
    () => new Set(mediaState.favorites),
    [mediaState.favorites],
  );

  // ── Search ────────────────────────────────────────────────────────────────
  const runSearch = useCallback(
    async (q, type, hideNsfw, nsfwTags) => {
      const trimmed = (q ?? '').trim();
      if (!trimmed) {
        setResults([]);
        setHasSearched(false);
        setIsSearching(false);
        return;
      }
      setIsSearching(true);
      setHasSearched(true);
      try {
        const rows = await SearchService.searchMedia(trimmed, {
          mediaType: type,
          excludeNsfw: hideNsfw,
          nsfwTagNames: nsfwTags,
          showHidden,
        });

        // Pre-warm L1 from SQLite so previously-resolved thumbs show immediately
        const allUris = rows.map(r => r.uri).filter(Boolean);
        await warmCacheFromDb(allUris);

        // Attach cached thumbUris synchronously on first paint
        const withThumbs = rows.map(r => {
          const cached = getCachedThumbUri(r.uri);
          return cached ? { ...r, thumbUri: cached } : r;
        });
        setResults(withThumbs);

        // Resolve remaining uncached URIs in one batch
        const unresolvedUris = withThumbs
          .filter(r => !r.thumbUri)
          .map(r => r.uri);
        if (unresolvedUris.length > 0) {
          const allResolved = new Map();
          await streamResolveThumbnailUris(unresolvedUris, thumbBatch => {
            for (const [uri, thumbUri] of thumbBatch) {
              allResolved.set(uri, thumbUri);
            }
          });
          if (allResolved.size > 0) {
            setResults(prev => {
              let changed = false;
              const next = prev.map(r => {
                if (!r.uri || r.thumbUri || !allResolved.has(r.uri)) return r;
                changed = true;
                return { ...r, thumbUri: allResolved.get(r.uri) };
              });
              return changed ? next : prev;
            });
          }
        }
      } catch (err) {
        console.warn('[SearchScreen] search error:', err);
        setResults([]);
      } finally {
        setIsSearching(false);
      }
    },
    [showHidden],
  );

  // Debounce whenever query / filters change
  useEffect(() => {
    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(
      () => runSearch(query, mediaType, nsfwFilterEnabled, nsfwTagNames),
      DEBOUNCE_MS,
    );
    return () => clearTimeout(debounceRef.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, mediaType, nsfwFilterEnabled, nsfwTagNames, showHidden]);

  // ── Viewer callbacks ──────────────────────────────────────────────────────
  const handleItemPress = useCallback((item, index) => {
    setViewerIndex(index);
  }, []);

  const handleViewerClose = useCallback(() => setViewerIndex(-1), []);

  const handleToggleFavorite = useCallback(
    async item => {
      try {
        await toggleFavorite(item);
        // Reflect in local results so the star badge updates without a re-search
        if (favSet.has(item.uri)) {
          mediaDispatch(MediaActions.removeFavorite(item.uri));
        } else {
          mediaDispatch(MediaActions.addFavorite(item.uri));
        }
      } catch {}
    },
    [toggleFavorite, favSet, mediaDispatch],
  );

  // ── Thumbnail renderer ───────────────────────────────────────────────────
  const THUMB_SIZE = useMemo(() => {
    // FlatList with numColumns=3 doesn't expose container width, so we
    // calculate based on screen width minus hairline gaps.
    const { width } = require('react-native').Dimensions.get('window');
    return Math.floor(width / NUM_COLUMNS);
  }, []);

  const renderItem = useCallback(
    ({ item, index }) => {
      if (!item.uri) return null;
      return (
        <MediaThumbnail
          item={item}
          onPress={() => handleItemPress(item, index)}
          onLongPress={() => {}}
          isSelected={false}
          isFavorite={favSet.has(item.uri)}
          isHidden={false}
          size={THUMB_SIZE}
          colors={colors}
          accent={accent}
        />
      );
    },
    [handleItemPress, favSet, THUMB_SIZE, colors, accent],
  );

  const keyExtractor = useCallback(item => item.uri ?? item.id, []);

  // ── Styles ────────────────────────────────────────────────────────────────
  const s = useMemo(
    () => makeStyles(colors, accent, insets, isDark),
    [colors, accent, insets, isDark],
  );

  // ── Render ────────────────────────────────────────────────────────────────
  return (
    <View style={s.root}>
      {/* ── Header: back + search bar ──────────────────────────────────── */}
      <View style={s.headerBar}>
        <TouchableOpacity
          onPress={() => navigation.goBack()}
          style={s.backBtn}
          hitSlop={{ top: 12, bottom: 12, left: 12, right: 12 }}
          activeOpacity={0.7}
        >
          <Icon name="back" size={20} color={colors.text} />
        </TouchableOpacity>

        <View style={s.inputWrap}>
          <Icon
            name="search"
            size={16}
            color={colors.textTertiary}
            style={s.inputIcon}
          />
          <TextInput
            ref={inputRef}
            style={s.input}
            value={query}
            onChangeText={setQuery}
            placeholder="Search images, videos, tags…"
            placeholderTextColor={colors.textTertiary}
            returnKeyType="search"
            autoCapitalize="none"
            autoCorrect={false}
            clearButtonMode="while-editing"
          />
          {query.length > 0 && (
            <TouchableOpacity
              onPress={() => setQuery('')}
              hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
            >
              <Icon name="close" size={16} color={colors.textTertiary} />
            </TouchableOpacity>
          )}
        </View>
      </View>

      {/* ── Filter bar: type pills ──────────────────────────────────────── */}
      <View style={s.filterBar}>
        <View style={s.typePills}>
          {TYPE_FILTERS.map(f => {
            const active = mediaType === f.id;
            return (
              <TouchableOpacity
                key={f.id}
                onPress={() => setMediaType(f.id)}
                activeOpacity={0.7}
                style={[
                  s.pill,
                  {
                    backgroundColor: active ? accent : colors.surface,
                    borderColor: active ? accent : colors.border,
                  },
                ]}
              >
                <Text
                  style={[
                    s.pillLabel,
                    { color: active ? colors.white : colors.textSecondary },
                  ]}
                >
                  {f.label}
                </Text>
              </TouchableOpacity>
            );
          })}
        </View>
      </View>

      {/* ── Results grid ───────────────────────────────────────────────── */}
      {isSearching ? (
        <View style={s.centred}>
          <ActivityIndicator color={accent} size="large" />
        </View>
      ) : !hasSearched ? (
        <EmptyHint
          icon="search"
          message="Search your media"
          sub="Find by filename or tag — try 'beach', 'sunset', 'screenshot'…"
          colors={colors}
          accent={accent}
        />
      ) : results.length === 0 ? (
        <EmptyHint
          icon="search"
          message={`No results for "${query.trim()}"`}
          sub={
            nsfwFilterEnabled && nsfwTagNames.length > 0
              ? 'NSFW content is filtered. Toggle the shield in Settings to include it.'
              : 'Try a different keyword or tag name.'
          }
          colors={colors}
          accent={accent}
        />
      ) : (
        <FlatList
          data={results}
          renderItem={renderItem}
          keyExtractor={keyExtractor}
          numColumns={NUM_COLUMNS}
          showsVerticalScrollIndicator={false}
          contentContainerStyle={{ paddingBottom: insets.bottom + 24 }}
          keyboardShouldPersistTaps="handled"
          keyboardDismissMode="on-drag"
          ListHeaderComponent={
            <Text style={[s.resultCount, { color: colors.textTertiary }]}>
              {results.length} result{results.length !== 1 ? 's' : ''}
            </Text>
          }
          initialNumToRender={24}
          maxToRenderPerBatch={12}
          windowSize={7}
        />
      )}

      {/* ── MediaViewer ────────────────────────────────────────────────── */}
      <MediaViewer
        visible={viewerIndex >= 0}
        items={results}
        initialIndex={Math.max(0, viewerIndex)}
        initialMaximized
        onClose={handleViewerClose}
        onToggleFavorite={handleToggleFavorite}
        onDelete={null}
        onRename={null}
        onOpenPicker={(mode, item) =>
          navigation.navigate('FolderPicker', {
            mode,
            items: [item],
            sourceFolder: null,
            returnRoute: 'Main',
          })
        }
      />
    </View>
  );
}

// ─── Empty / hint state ───────────────────────────────────────────────────────

function EmptyHint({ message, sub, colors, accent }) {
  return (
    <View style={hintStyles.wrap}>
      <Text style={[hintStyles.title, { color: colors.textSecondary }]}>
        {message}
      </Text>
      <Text style={[hintStyles.sub, { color: colors.textTertiary }]}>
        {sub}
      </Text>
      <View style={[hintStyles.rule, { backgroundColor: accent }]} />
    </View>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

function makeStyles(colors, accent, insets, isDark) {
  return StyleSheet.create({
    root: {
      flex: 1,
      backgroundColor: colors.background,
    },

    // Header
    headerBar: {
      flexDirection: 'row',
      alignItems: 'center',
      paddingTop: insets.top + 8,
      paddingBottom: 10,
      paddingHorizontal: 14,
      gap: 10,
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: colors.border,
    },
    backBtn: {
      width: 36,
      height: 36,
      alignItems: 'center',
      justifyContent: 'center',
    },
    inputWrap: {
      flex: 1,
      flexDirection: 'row',
      alignItems: 'center',
      backgroundColor: isDark ? colors.surface : colors.card,
      borderRadius: 12,
      paddingHorizontal: 12,
      height: 42,
      gap: 8,
      borderWidth: StyleSheet.hairlineWidth,
      borderColor: colors.border,
    },
    inputIcon: {
      // aligns with text baseline
    },
    input: {
      flex: 1,
      fontSize: 15,
      color: colors.text,
      paddingVertical: 0,
    },

    // Filter bar
    filterBar: {
      flexDirection: 'row',
      alignItems: 'center',
      paddingHorizontal: 14,
      paddingVertical: 10,
      gap: 8,
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: colors.border,
    },
    typePills: {
      flexDirection: 'row',
      gap: 6,
      flex: 1,
    },
    pill: {
      paddingHorizontal: 13,
      paddingVertical: 6,
      borderRadius: 20,
      borderWidth: 1,
    },
    pillLabel: {
      fontSize: 12,
      fontWeight: '600',
    },
    // States
    centred: {
      flex: 1,
      alignItems: 'center',
      justifyContent: 'center',
    },
    resultCount: {
      fontSize: 11,
      fontWeight: '500',
      letterSpacing: 0.4,
      paddingHorizontal: 14,
      paddingVertical: 8,
    },
  });
}

const hintStyles = StyleSheet.create({
  wrap: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 40,
    gap: 10,
  },
  title: {
    fontSize: 17,
    fontWeight: '500',
    textAlign: 'center',
  },
  sub: {
    fontSize: 13,
    lineHeight: 19,
    textAlign: 'center',
  },
  rule: {
    width: 32,
    height: 1.5,
    borderRadius: 1,
    marginTop: 6,
  },
});
