/**
 * TagsScreen â€” redesigned
 *
 * Layout:
 *   AppHeader  (search + settings)
 *   â”€â”€â”€â”€â”€â”€â”€â”€â”€
 *   "Recently Active" horizontal strip  (top 4 tags by last activity)
 *   â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 *   Sort row  [ Count â–¾ ]  Â· N tags
 *   â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 *   Tag list rows  (category badge | name | count) â€” NO images, NO blur
 *
 * Performance wins vs the previous design:
 *   â€¢ Zero thumbnail fetching â€” no GPU blur, no ThumbnailCache calls
 *   â€¢ No InteractionManager stalls
 *   â€¢ Simple list rows are cheap to render/reconcile
 */

import React, {
  useEffect,
  useCallback,
  useState,
  useMemo,
  useRef,
} from 'react';
import {
  View,
  Text,
  Image,
  StyleSheet,
  FlatList,
  TouchableOpacity,
  RefreshControl,
  Dimensions,
  Animated,
  LayoutAnimation,
} from 'react-native';
import { Image as ExpoImage } from 'expo-image';
import { AppHeader } from '../../components/common/AppHeader';
import { EmptyState } from '../../components/common/EmptyState';
import { SettingsSidebar } from '../../components/common/SettingsSidebar';
import { useTheme } from '../../providers/ThemeProvider';
import { Spacing } from '../../theme';
import { useAppContext } from '../../store';
import { TagService } from '../../services/database/TagService';
import { SearchService } from '../../services/database/SearchService';
import { DatabaseService } from '../../services/database/DatabaseService';
import {
  TAG_CATEGORY_LIST,
  getCategoryForTag,
  getCategoryDef,
} from '../../constants/tagCategories';
import {
  warmCacheFromDb,
  batchResolveThumbnailUris,
} from '../../services/cache/ThumbnailCache';

const { width: SCREEN_W } = Dimensions.get('window');

// â”€â”€â”€ Colour palette for navigation (no longer used for card backgrounds) â”€â”€â”€â”€â”€â”€

const DEFAULT_TAG_COLORS = [
  '#e07a5f',
  '#3d405b',
  '#81b29a',
  '#f2cc8f',
  '#588157',
  '#f28482',
  '#4a4e69',
  '#a3b18a',
  '#f6bd60',
  '#344e41',
  '#9a8c98',
  '#3a5a40',
  '#84a59d',
  '#22223b',
];

function getTagColor(tag, index) {
  if (tag.color && tag.color !== '#888888') return tag.color;
  return DEFAULT_TAG_COLORS[(index ?? 0) % DEFAULT_TAG_COLORS.length];
}

// ─── Relative time formatter ─────────────────────────────────────────

function relativeTime(ts) {
  if (!ts) return null;
  const diff = Date.now() - ts;
  const mins = Math.floor(diff / 60_000);
  const hours = Math.floor(diff / 3_600_000);
  const days = Math.floor(diff / 86_400_000);
  if (mins < 1) return 'Just now';
  if (mins < 60) return `${mins}m ago`;
  if (hours < 24) return `${hours}h ago`;
  if (days === 1) return 'Yesterday';
  if (days < 7) return `${days}d ago`;
  if (days < 30) return `${Math.floor(days / 7)}w ago`;
  return `${Math.floor(days / 30)}mo ago`;
}

function formatShortDate(ts) {
  if (!ts) return null;
  const d = new Date(ts);
  const months = [
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
  return `${d.getDate()} ${months[d.getMonth()]}`;
}

// â”€â”€â”€ Sort options â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

const SORT_OPTIONS = [
  { key: 'Count', label: 'Count' },
  { key: 'A - Z', label: 'A - Z' },
  { key: 'Modified', label: 'Recently Modified' },
  { key: 'Created', label: 'Recently Created' },
  { key: 'Category', label: 'Category' },
];

// â”€â”€â”€ RecentTagCard â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

// ─── Cover-URI helper ──────────────────────────────────────────────────────────

/**
 * Fetches one representative image URI per tag (most recently tagged image).
 * Returns a map of { [tagId]: uri }.
 */
async function getTagCoverUris(tagIds) {
  if (!tagIds.length) return {};
  const db = DatabaseService.getDb();
  const placeholders = tagIds.map(() => '?').join(',');
  try {
    const { rows } = await db.execute(
      `SELECT mt.tag_id, mt.media_uri AS uri
       FROM   media_tags mt
       INNER  JOIN media_index mi ON mi.uri = mt.media_uri
       WHERE  mt.tag_id IN (${placeholders})
         AND  mi.hidden = 0
         AND  mi.media_type = 'image'
       ORDER  BY mt.tagged_at DESC`,
      tagIds,
    );
    // No GROUP BY — ORDER BY DESC + first-wins JS loop gives the most recent image per tag
    const map = {};
    for (const r of rows) {
      const key = String(r.tag_id);
      if (!map[key]) map[key] = r.uri;
    }
    return map;
  } catch {
    return {};
  }
}

function RecentTagCard({ tag, thumbUri, onPress, colors, accentColor }) {
  const count = tag.usage_count ?? 0;
  const timeStr = relativeTime(tag.last_active);
  const hasThumb = !!thumbUri;

  // Dynamic styles that depend on runtime color values.
  // Plain objects are used instead of StyleSheet.create because StyleSheet.create
  // registers new IDs with the native runtime on every invocation — calling it
  // inside useMemo would accumulate stale style entries on every theme/accent change.
  const dyn = useMemo(
    () => ({
      cardThumb: { borderColor: 'transparent' },
      cardNoThumb: {
        backgroundColor: colors.surface,
        borderColor: colors.border,
      },
      countThumb: { color: '#fff' },
      countNoThumb: { color: accentColor },
      countLabelThumb: { color: 'rgba(255,255,255,0.65)' },
      countLabelNoThumb: { color: colors.textTertiary },
      nameThumb: { color: '#fff' },
      nameNoThumb: { color: colors.text },
      timeThumb: { color: 'rgba(255,255,255,0.75)' },
      timeNoThumb: { color: colors.textTertiary },
    }),
    [
      accentColor,
      colors.border,
      colors.surface,
      colors.text,
      colors.textTertiary,
    ],
  );

  return (
    <TouchableOpacity
      style={[recentCard.card, hasThumb ? dyn.cardThumb : dyn.cardNoThumb]}
      onPress={() => onPress(tag)}
      activeOpacity={0.75}
      accessibilityLabel={`Tag ${tag.name}, ${count} items`}
    >
      {/* Blurred thumbnail background */}
      {hasThumb && (
        <ExpoImage
          source={{ uri: thumbUri }}
          style={StyleSheet.absoluteFill}
          contentFit="cover"
          blurRadius={3}
          cachePolicy="memory-disk"
          recyclingKey={`recent-cover-${tag.id}`}
        />
      )}
      {/* Scrim overlay for text legibility */}
      {hasThumb && <View style={recentCard.overlay} />}

      {/* Spacer pushes content to bottom */}
      <View style={recentCard.spacer} />
      <Text
        style={[recentCard.count, hasThumb ? dyn.countThumb : dyn.countNoThumb]}
        numberOfLines={1}
      >
        {count.toLocaleString()}
      </Text>
      <Text
        style={[
          recentCard.countLabel,
          hasThumb ? dyn.countLabelThumb : dyn.countLabelNoThumb,
        ]}
        numberOfLines={1}
      >
        {count === 1 ? 'item' : 'items'}
      </Text>
      <Text
        style={[recentCard.name, hasThumb ? dyn.nameThumb : dyn.nameNoThumb]}
        numberOfLines={2}
      >
        {tag.name}
      </Text>
      {timeStr ? (
        <Text
          style={[recentCard.time, hasThumb ? dyn.timeThumb : dyn.timeNoThumb]}
          numberOfLines={1}
        >
          {timeStr}
        </Text>
      ) : null}
    </TouchableOpacity>
  );
}

const RecentTagCardMemo = React.memo(RecentTagCard);

// â”€â”€â”€ TagRow â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

function TagRow({ tag, colorIndex, tagColor, onPress, colors, categoryDef }) {
  const count = tag.usage_count ?? 0;
  const updatedStr = tag.updated_at ? formatShortDate(tag.updated_at) : null;

  return (
    <TouchableOpacity
      style={[tagRow.row, { borderBottomColor: colors.divider }]}
      onPress={() => onPress(tag, colorIndex)}
      activeOpacity={0.6}
      accessibilityLabel={`Tag ${tag.name}, ${count} items`}
    >
      {/* Category icon — tinted to the tag's unique color, no background */}
      <View style={tagRow.catBadge}>
        <Image
          source={categoryDef?.icon}
          style={[tagRow.catIcon, { tintColor: tagColor }]}
          resizeMode="contain"
        />
      </View>

      {/* Name + secondary metadata */}
      <View style={tagRow.nameBlock}>
        <Text style={[tagRow.name, { color: colors.text }]} numberOfLines={1}>
          {tag.name}
        </Text>
        {updatedStr ? (
          <Text
            style={[tagRow.meta, { color: colors.textTertiary }]}
            numberOfLines={1}
          >
            {'Updated ' + updatedStr}
          </Text>
        ) : null}
      </View>

      {/* Count */}
      <Text style={[tagRow.count, { color: colors.textSecondary }]}>
        {count.toLocaleString()}
      </Text>
    </TouchableOpacity>
  );
}

const TagRowMemo = React.memo(TagRow);

// ─── CategorySectionHeader ───────────────────────────────────────────────────────

function CategorySectionHeader({ categoryDef, colors }) {
  return (
    <View
      style={[
        catHeader.row,
        { borderBottomColor: colors.divider, borderTopColor: colors.divider },
      ]}
    >
      <Image
        source={categoryDef.icon}
        style={[catHeader.icon, { tintColor: colors.textSecondary }]}
        resizeMode="contain"
      />
      <Text style={[catHeader.label, { color: colors.textTertiary }]}>
        {categoryDef.label.toUpperCase()}
      </Text>
      <Text style={[catHeader.count, { color: colors.textTertiary }]}>
        {categoryDef.count}
      </Text>
    </View>
  );
}

const CategorySectionHeaderMemo = React.memo(CategorySectionHeader);

// â”€â”€â”€ TagsScreen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

export function TagsScreen({ navigation }) {
  const { colors } = useTheme();
  const { state: appState } = useAppContext();
  const accentColor = appState.accentColor ?? colors.accent;
  const nsfwFilterEnabled = appState.nsfwFilterEnabled ?? false;

  const [tags, setTags] = useState([]);
  const [recentTags, setRecentTags] = useState([]);
  const [tagCoverThumbMap, setTagCoverThumbMap] = useState({});
  const [nsfwTagNames, setNsfwTagNames] = useState([]);
  const [fullyHiddenTagIds, setFullyHiddenTagIds] = useState(new Set());
  const [isLoading, setIsLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [searchActive, setSearchActive] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [sortMode, setSortMode] = useState('Count');
  const [sortMenuOpen, setSortMenuOpen] = useState(false);
  const [sidebarVisible, setSidebarVisible] = useState(false);
  const [recentCollapsed, setRecentCollapsed] = useState(false);

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

  // Animated chevron for the "RECENTLY ACTIVE" collapse toggle
  const recentChevronAnim = useRef(new Animated.Value(1)).current; // 1 = open (points up)
  useEffect(() => {
    Animated.timing(recentChevronAnim, {
      toValue: recentCollapsed ? 0 : 1,
      duration: 220,
      useNativeDriver: true,
    }).start();
  }, [recentCollapsed, recentChevronAnim]);
  const recentChevronRotate = recentChevronAnim.interpolate({
    inputRange: [0, 1],
    outputRange: ['0deg', '180deg'],
  });
  const sortMenuOpenRef = useRef(sortMenuOpen);
  sortMenuOpenRef.current = sortMenuOpen;

  // â”€â”€â”€ Data loading â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  const loadTagsData = useCallback(async () => {
    try {
      const [allTags, recent] = await Promise.all([
        TagService.getAllTags(),
        TagService.getRecentlyActiveTags(4),
      ]);
      setTags(allTags);
      setRecentTags(recent);

      // ── Prefetch thumbnail covers for recent tag cards ──────────────────
      if (recent.length > 0) {
        try {
          const coverMap = await getTagCoverUris(recent.map(t => t.id));
          const rawUris = recent
            .map(t => coverMap[String(t.id)])
            .filter(Boolean);
          if (rawUris.length) {
            await warmCacheFromDb(rawUris);
            const thumbUris = await batchResolveThumbnailUris(rawUris);
            const thumbMap = {};
            let uriIdx = 0;
            for (const t of recent) {
              const raw = coverMap[String(t.id)];
              if (raw) thumbMap[t.id] = thumbUris[uriIdx++];
            }
            setTagCoverThumbMap(thumbMap);
          }
        } catch {
          // Non-fatal — cards just show without background
        }
      }

      let loadedNsfwTagNames = [];
      if (nsfwFilterEnabled) {
        loadedNsfwTagNames = await SearchService.getNsfwTagNames();
      }
      setNsfwTagNames(loadedNsfwTagNames);

      const hiddenTagIds = new Set();
      if (nsfwFilterEnabled && loadedNsfwTagNames.length > 0) {
        await Promise.all(
          allTags.map(async tag => {
            try {
              const visible = await TagService.hasVisibleMedia(
                tag.id,
                loadedNsfwTagNames,
                { showHidden: true },
              );
              if (!visible) hiddenTagIds.add(tag.id);
            } catch {
              /* non-fatal */
            }
          }),
        );
      }
      setFullyHiddenTagIds(hiddenTagIds);
    } catch (err) {
      console.warn('[TagsScreen] loadTagsData error:', err);
    }
  }, [nsfwFilterEnabled]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setIsLoading(true);
      await loadTagsData();
      if (!cancelled) setIsLoading(false);
    })();
    return () => {
      cancelled = true;
    };
  }, [loadTagsData]);

  useEffect(() => {
    const unsubscribe = navigation.addListener('focus', loadTagsData);
    return unsubscribe;
  }, [navigation, loadTagsData]);

  const handleRefresh = useCallback(async () => {
    setRefreshing(true);
    await loadTagsData();
    setRefreshing(false);
  }, [loadTagsData]);

  // â”€â”€â”€ Sort + filter â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  const sortedTags = useMemo(() => {
    let list = [...tags];

    if (nsfwFilterEnabled && nsfwTagNames.length > 0) {
      const nsfwSet = new Set(nsfwTagNames.map(n => n.toLowerCase()));
      list = list.filter(
        t => !nsfwSet.has(t.name.toLowerCase()) && !fullyHiddenTagIds.has(t.id),
      );
    }

    switch (sortMode) {
      case 'A - Z':
        list.sort((a, b) => a.name.localeCompare(b.name));
        break;
      case 'Modified':
        list.sort(
          (a, b) =>
            (b.updated_at ?? b.created_at ?? 0) -
            (a.updated_at ?? a.created_at ?? 0),
        );
        break;
      case 'Created':
        list.sort((a, b) => (b.created_at ?? 0) - (a.created_at ?? 0));
        break;
      default: // Count
        list.sort((a, b) => {
          const diff = (b.usage_count ?? 0) - (a.usage_count ?? 0);
          return diff !== 0 ? diff : a.name.localeCompare(b.name);
        });
    }

    const q = searchQuery.trim().toLowerCase();
    if (q) list = list.filter(t => t.name.toLowerCase().includes(q));

    return list;
  }, [
    tags,
    sortMode,
    searchQuery,
    nsfwFilterEnabled,
    nsfwTagNames,
    fullyHiddenTagIds,
  ]);

  // Stable colour-index map for tag navigation
  const colorIndexMap = useMemo(() => {
    const m = new Map();
    const counted = [...tags].sort(
      (a, b) => (b.usage_count ?? 0) - (a.usage_count ?? 0),
    );
    counted.forEach((t, i) => m.set(t.id, i));
    return m;
  }, [tags]);

  // ─── Build the flat display list (tags + optional category section headers) ──────

  const displayItems = useMemo(() => {
    if (sortMode !== 'Category') {
      // Plain flat list — wrap each tag so renderItem can detect item type.
      return sortedTags.map(t => ({ _type: 'tag', ...t }));
    }
    // Group by category in canonical display order.
    const groups = {};
    for (const t of sortedTags) {
      const catKey =
        t.category && t.category !== 'misc'
          ? t.category
          : getCategoryForTag(t.name);
      if (!groups[catKey]) groups[catKey] = [];
      groups[catKey].push(t);
    }
    const items = [];
    for (const catDef of TAG_CATEGORY_LIST) {
      const catTags = groups[catDef.key];
      if (!catTags || catTags.length === 0) continue;
      // Within each section, sort by usage count descending.
      const sorted = [...catTags].sort(
        (a, b) => (b.usage_count ?? 0) - (a.usage_count ?? 0),
      );
      items.push({ _type: 'section', ...catDef, count: sorted.length });
      for (const t of sorted) {
        items.push({ _type: 'tag', ...t });
      }
    }
    return items;
  }, [sortedTags, sortMode]);

  // â”€â”€â”€ Navigation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  const handleTagPress = useCallback(
    (tag, colorIndex) => {
      navigation.navigate('TagGallery', {
        tagId: tag.id,
        tagName: tag.name,
        tagColor: getTagColor(
          tag,
          colorIndex ?? colorIndexMap.get(tag.id) ?? 0,
        ),
      });
    },
    [navigation, colorIndexMap],
  );

  const handleRecentPress = useCallback(
    tag => {
      navigation.navigate('TagGallery', {
        tagId: tag.id,
        tagName: tag.name,
        tagColor: getTagColor(tag, colorIndexMap.get(tag.id) ?? 0),
      });
    },
    [navigation, colorIndexMap],
  );

  // â”€â”€â”€ Render helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  const renderItem = useCallback(
    ({ item }) => {
      if (item._type === 'section') {
        return <CategorySectionHeaderMemo categoryDef={item} colors={colors} />;
      }
      const colorIdx = colorIndexMap.get(item.id) ?? 0;
      const catKey =
        item.category && item.category !== 'misc'
          ? item.category
          : getCategoryForTag(item.name);
      return (
        <TagRowMemo
          tag={item}
          colorIndex={colorIdx}
          tagColor={getTagColor(item, colorIdx)}
          onPress={handleTagPress}
          colors={colors}
          categoryDef={getCategoryDef(catKey)}
        />
      );
    },
    [handleTagPress, colorIndexMap, colors],
  );

  const keyExtractor = useCallback(
    item =>
      item._type === 'section' ? 'section-' + item.key : String(item.id),
    [],
  );

  // â”€â”€â”€ NSFW-filtered recent tags â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  const visibleRecentTags = useMemo(() => {
    if (!nsfwFilterEnabled || nsfwTagNames.length === 0) return recentTags;
    const nsfwSet = new Set(nsfwTagNames.map(n => n.toLowerCase()));
    return recentTags.filter(
      t => !nsfwSet.has(t.name.toLowerCase()) && !fullyHiddenTagIds.has(t.id),
    );
  }, [recentTags, nsfwFilterEnabled, nsfwTagNames, fullyHiddenTagIds]);

  // â”€â”€â”€ List header (recents + sort row) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  const currentSortLabel =
    SORT_OPTIONS.find(o => o.key === sortMode)?.label ?? sortMode;

  const ListHeader = useCallback(() => {
    if (tags.length === 0 && !isLoading) return null;
    return (
      <View>
        {/* â”€â”€ Recently Active â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */}
        {visibleRecentTags.length > 0 && (
          <View style={listHeaderStyles.section}>
            {/* Section header row with collapse toggle */}
            <View style={listHeaderStyles.sectionHeader}>
              <Text
                style={[
                  listHeaderStyles.sectionTitle,
                  { color: colors.textTertiary },
                ]}
              >
                RECENTLY ACTIVE
              </Text>
              <TouchableOpacity
                onPress={() => {
                  runNativeTransition();
                  setRecentCollapsed(c => !c);
                }}
                hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
                style={listHeaderStyles.collapseBtnRow}
              >
                <Text
                  style={[
                    listHeaderStyles.collapseBtn,
                    { color: colors.textTertiary },
                  ]}
                >
                  {recentCollapsed ? 'Show' : 'Hide'}
                </Text>
                <Animated.Text
                  style={[
                    listHeaderStyles.collapseChevron,
                    {
                      color: colors.textTertiary,
                      transform: [{ rotate: recentChevronRotate }],
                    },
                  ]}
                >
                  {'▾'}
                </Animated.Text>
              </TouchableOpacity>
            </View>
            {!recentCollapsed && (
              <View style={listHeaderStyles.recentGrid}>
                {visibleRecentTags.slice(0, 4).map(tag => (
                  <RecentTagCardMemo
                    key={tag.id}
                    tag={tag}
                    thumbUri={tagCoverThumbMap[tag.id]}
                    onPress={handleRecentPress}
                    colors={colors}
                    accentColor={accentColor}
                  />
                ))}
              </View>
            )}
          </View>
        )}

        {/* If no tags yet and still loading, show nothing */}
        {tags.length === 0 && isLoading && (
          <View style={listHeaderStyles.section} />
        )}

        {/* â”€â”€ Divider â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */}
        {tags.length > 0 && (
          <View
            style={[
              listHeaderStyles.divider,
              { backgroundColor: colors.divider },
            ]}
          />
        )}

        {/* â”€â”€ Sort row â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */}
        {tags.length > 0 && (
          <View style={listHeaderStyles.sortRow}>
            <TouchableOpacity
              style={[
                listHeaderStyles.sortBtn,
                {
                  backgroundColor: colors.surface,
                  borderColor: colors.border,
                },
              ]}
              onPress={() => {
                runNativeTransition();
                setSortMenuOpen(p => !p);
              }}
              hitSlop={{ top: 6, bottom: 6, left: 6, right: 6 }}
            >
              <Text
                style={[listHeaderStyles.sortBtnText, { color: colors.text }]}
              >
                {'Sort: '}
                {currentSortLabel}
                {'  ▾'}
              </Text>
            </TouchableOpacity>
            <Text
              style={[
                listHeaderStyles.tagCount,
                { color: colors.textTertiary },
              ]}
            >
              {sortedTags.length} {sortedTags.length === 1 ? 'tag' : 'tags'}
            </Text>
          </View>
        )}

        {/* â”€â”€ Sort dropdown â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ */}
        {sortMenuOpen && (
          <View
            style={[
              listHeaderStyles.sortMenu,
              {
                backgroundColor: colors.surface,
                borderColor: colors.border,
              },
            ]}
          >
            {SORT_OPTIONS.map(opt => {
              const active = opt.key === sortMode;
              return (
                <TouchableOpacity
                  key={opt.key}
                  style={[
                    listHeaderStyles.sortOption,
                    active && { backgroundColor: accentColor + '18' },
                  ]}
                  onPress={() => {
                    runNativeTransition();
                    setSortMode(opt.key);
                    setSortMenuOpen(false);
                  }}
                >
                  <Text
                    style={[
                      listHeaderStyles.sortOptionText,
                      { color: active ? accentColor : colors.text },
                      active && listHeaderStyles.sortOptionActive,
                    ]}
                  >
                    {opt.label}
                  </Text>
                  {active && (
                    <Text
                      style={[
                        listHeaderStyles.sortOptionCheck,
                        { color: accentColor },
                      ]}
                    >
                      âœ“
                    </Text>
                  )}
                </TouchableOpacity>
              );
            })}
          </View>
        )}
      </View>
    );
  }, [
    tags.length,
    isLoading,
    recentCollapsed,
    recentChevronRotate,
    visibleRecentTags,
    tagCoverThumbMap,
    colors,
    accentColor,
    sortMode,
    sortedTags.length,
    sortMenuOpen,
    currentSortLabel,
    handleRecentPress,
    runNativeTransition,
  ]);

  // â”€â”€â”€ Empty state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  const ListEmpty = useCallback(() => {
    if (isLoading) return null;
    if (searchQuery.trim()) {
      return (
        <View style={emptyStyles.wrap}>
          <Text style={[emptyStyles.title, { color: colors.text }]}>
            No results for "{searchQuery}"
          </Text>
          <Text style={[emptyStyles.subtitle, { color: colors.textSecondary }]}>
            Try a different keyword.
          </Text>
        </View>
      );
    }
    return (
      <View style={emptyStyles.wrap}>
        <Text style={emptyStyles.icon}>ðŸ·ï¸</Text>
        <Text style={[emptyStyles.title, { color: colors.text }]}>
          No Tags Yet
        </Text>
        <Text style={[emptyStyles.subtitle, { color: colors.textSecondary }]}>
          Tags will appear here when used.{'\n'}Open any media item, tap the tag
          icon, and start tagging.
        </Text>
      </View>
    );
  }, [isLoading, searchQuery, colors]);

  // â”€â”€â”€ Render â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  return (
    <View style={[screen.container, { backgroundColor: colors.background }]}>
      <AppHeader
        title="tags"
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

      {isLoading && tags.length === 0 ? (
        <EmptyState icon="â³" message="Loading tagsâ€¦" />
      ) : (
        <FlatList
          data={displayItems}
          renderItem={renderItem}
          keyExtractor={keyExtractor}
          ListHeaderComponent={ListHeader}
          ListEmptyComponent={ListEmpty}
          contentContainerStyle={[
            screen.listContent,
            sortedTags.length === 0 && screen.listContentEmpty,
          ]}
          showsVerticalScrollIndicator={false}
          refreshControl={
            <RefreshControl
              refreshing={refreshing}
              onRefresh={handleRefresh}
              tintColor={accentColor}
              colors={[accentColor]}
              progressBackgroundColor={colors.surface}
            />
          }
          keyboardShouldPersistTaps="handled"
          removeClippedSubviews
          initialNumToRender={25}
          maxToRenderPerBatch={20}
          windowSize={10}
          onScrollBeginDrag={() => {
            if (sortMenuOpenRef.current) {
              runNativeTransition();
              setSortMenuOpen(false);
            }
          }}
        />
      )}

      <SettingsSidebar
        visible={sidebarVisible}
        onClose={() => setSidebarVisible(false)}
        onNavigate={routeName => navigation.navigate(routeName)}
      />
    </View>
  );
}

// â”€â”€â”€ Styles â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

const RECENT_CARD_W = Math.floor((SCREEN_W - Spacing.lg * 2 - Spacing.sm) / 2); // 2-col grid

const recentCard = StyleSheet.create({
  card: {
    width: RECENT_CARD_W,
    height: RECENT_CARD_W, // square
    borderRadius: 14,
    borderWidth: StyleSheet.hairlineWidth,
    padding: Spacing.md,
    justifyContent: 'flex-end',
    overflow: 'hidden',
  },
  overlay: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(0,0,0,0.44)',
  },
  spacer: {
    flex: 1,
  },
  count: {
    fontSize: 26,
    fontWeight: '800',
    letterSpacing: -0.5,
    lineHeight: 30,
  },
  countLabel: {
    fontSize: 11,
    fontWeight: '500',
    letterSpacing: 0.2,
    marginBottom: 8,
  },
  name: {
    fontSize: 14,
    fontWeight: '700',
    letterSpacing: 0.1,
    lineHeight: 19,
  },
  time: {
    fontSize: 10,
    fontWeight: '500',
    letterSpacing: 0.1,
    marginTop: 3,
  },
});

const tagRow = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: Spacing.lg,
    paddingVertical: Spacing.md,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  catBadge: {
    width: 28,
    height: 28,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 12,
  },
  catIcon: {
    width: 32,
    height: 36,
  },
  nameBlock: {
    flex: 1,
    justifyContent: 'center',
  },
  name: {
    fontSize: 15,
    fontWeight: '600',
    letterSpacing: 0.1,
  },
  meta: {
    fontSize: 11,
    fontWeight: '400',
    marginTop: 2,
    letterSpacing: 0.1,
  },
  count: {
    fontSize: 14,
    fontWeight: '500',
    marginLeft: 8,
    minWidth: 36,
    textAlign: 'right',
  },
});

const catHeader = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: Spacing.lg,
    paddingTop: Spacing.xl,
    paddingBottom: Spacing.sm,
    gap: 7,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  icon: {
    width: 13,
    height: 13,
  },
  label: {
    flex: 1,
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 1.2,
  },
  count: {
    fontSize: 11,
    fontWeight: '500',
    letterSpacing: 0.2,
  },
});

const listHeaderStyles = StyleSheet.create({
  section: {
    paddingTop: Spacing.md,
    paddingBottom: 4,
  },
  sectionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: Spacing.lg,
    marginBottom: Spacing.sm,
  },
  sectionTitle: {
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 0.9,
    textTransform: 'uppercase',
  },
  collapseBtnRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 3,
  },
  collapseBtn: {
    fontSize: 12,
    fontWeight: '600',
    letterSpacing: 0.2,
  },
  collapseChevron: {
    fontSize: 14,
    fontWeight: '600',
    lineHeight: 16,
  },
  recentGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    paddingHorizontal: Spacing.lg,
    gap: Spacing.sm,
    paddingBottom: 8,
  },
  divider: {
    height: StyleSheet.hairlineWidth,
    marginTop: 6,
  },
  sortRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: Spacing.lg,
    paddingVertical: Spacing.md,
    gap: 8,
  },
  sortBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: Spacing.md,
    paddingVertical: Spacing.sm,
    borderRadius: 8,
    borderWidth: StyleSheet.hairlineWidth,
  },
  sortBtnText: {
    fontSize: 13,
    fontWeight: '600',
    letterSpacing: 0.1,
  },
  tagCount: {
    flex: 1,
    fontSize: 12,
    fontWeight: '500',
    textAlign: 'right',
    letterSpacing: 0.1,
  },
  sortMenu: {
    marginHorizontal: Spacing.lg,
    marginBottom: 4,
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
    overflow: 'hidden',
  },
  sortOption: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: Spacing.lg,
    paddingVertical: Spacing.md,
  },
  sortOptionText: {
    flex: 1,
    fontSize: 14,
    fontWeight: '500',
  },
  sortOptionActive: {
    fontWeight: '700',
  },
  sortOptionCheck: {
    fontSize: 14,
    fontWeight: '700',
    marginLeft: 8,
  },
});

const screen = StyleSheet.create({
  container: {
    flex: 1,
  },
  listContent: {
    paddingBottom: 40,
  },
  listContentEmpty: {
    flex: 1,
  },
});

const emptyStyles = StyleSheet.create({
  wrap: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 40,
    paddingBottom: 80,
    paddingTop: 40,
  },
  icon: {
    fontSize: 52,
    marginBottom: 18,
  },
  title: {
    fontSize: 18,
    fontWeight: '700',
    marginBottom: 10,
    textAlign: 'center',
  },
  subtitle: {
    fontSize: 14,
    lineHeight: 21,
    textAlign: 'center',
  },
});
