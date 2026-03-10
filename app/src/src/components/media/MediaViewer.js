/**
 * MediaViewer — Full-screen viewer with sliding info panel
 *
 * Layout:
 *   Media area — always full-screen (flex: 1), image/video uses contentFit='contain'
 *   Info panel — absolute overlay anchored to screen bottom; slides in/out via
 *                translateY spring animation (useNativeDriver: true, 60 fps).
 *
 * Behaviour:
 *   • Opens in fullscreen (initialMaximized=true default from all screens).
 *   • Drag handle at top of info panel → tap/tap to dismiss to fullscreen.
 *   • Maximize button (⤢, bottom-right) → tap to reveal info panel.
 *   • Panel slides with spring physics + simultaneous opacity fade for a
 *     hero-quality transition; entirely on the UI thread (no JS reflows).
 *   • Horizontal swipe navigation (FlatList, pagingEnabled) works in both modes.
 *   • For videos: seek-bar + play/pause/mute controls live inside the media
 *     area as an absolute overlay; auto-hide after 4 s; tap to toggle.
 *   • Back (hardware or header button) minimises fullscreen before closing.
 *
 * Props:
 *   visible            — boolean
 *   items              — MediaItem[]
 *   initialIndex       — number
 *   initialMaximized   — boolean (default false); when true the viewer opens
 *                        directly in fullscreen mode with the info panel hidden.
 *                        Pressing the minimize button slides the info panel up.
 *   onClose            — () => void
 *   onDelete           — (item) => void
 *   onToggleFavorite   — (item) => void
 *   onRename           — (item, newName) => Promise<{ success, error? }>
 *   onOpenPicker       — (mode: 'copy'|'move', item) => void  (opens FolderPicker)
 */

import React, {
  useState,
  useRef,
  useCallback,
  useEffect,
  useMemo,
} from 'react';
import {
  View,
  Text,
  FlatList,
  Modal,
  StyleSheet,
  Dimensions,
  StatusBar,
  TouchableOpacity,
  PanResponder,
  BackHandler,
  Animated,
  ScrollView,
  useWindowDimensions,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Image } from 'expo-image';
import { useTheme } from '../../providers/ThemeProvider';
import { Spacing } from '../../theme';
import { useMediaContext } from '../../store/MediaContext';
import { useAppContext } from '../../store/AppContext';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { ContextMenu } from '../ui/ContextMenu';
import { RenameDialog } from '../ui/RenameDialog';
import { PropertiesModal } from '../ui/PropertiesModal';
import { Icon } from '../ui/Icon';
import { VideoPlayer } from './VideoPlayer';
import { TagsModal } from './TagsModal';
import { ZoomableImage } from './ZoomableImage';
import {
  formatDuration,
  formatDateTimeDisplay,
  formatFileSize,
  formatDimensions,
} from '../../utils/formatters';
import {
  stripExtension,
  getExtension,
  getMimeTypeForItem,
} from '../../utils/fileUtils';
import { MediaStoreModule } from '../../services/media/MediaStoreModule';
import { SystemUIModule } from '../../services/media/SystemUIModule';
import { MediaIndexService } from '../../services/database/MediaIndexService';
import { TagService } from '../../services/database/TagService';
// import { FaceService } from '../../services/database/FaceService';
// import { navigate } from '../../navigation/navigationRef';
import { useToast } from '../../providers/ToastProvider';

// ─── Constants ───────────────────────────────────────────────────────────────

const { width: W, height: H } = Dimensions.get('window');
const HIT = { top: 10, right: 10, bottom: 10, left: 10 };
const MIN_PANEL = Math.round(H * 0.3);
const MAX_PANEL = Math.round(H * 0.65);
const DEFAULT_PANEL_H = Math.round(H * 0.35); // fallback for videos / unknown dims
const PEEK_H = 76; // height of the peek strip shown in fullscreen mode
// Session-level mute pref — resets on JS bundle reload.
let sessionMuted = true;

// ─── Helpers ─────────────────────────────────────────────────────────────────

function getTypeLabel(item) {
  if (!item) return '';
  const base = item.type?.includes('video') ? 'Video' : 'Image';
  const ext = item.extension?.toUpperCase();
  return ext ? `${base} · ${ext}` : base;
}

/**
 * Compute per-item layout for the split-panel viewer.
 *
 * Returns:
 *   panelH — info-panel height in px (clamped to [MIN_PANEL, MAX_PANEL])
 *
 * Panel height strategy:
 *   naturalH = how tall the media would be when scaled to screen width.
 *   idealPanelH = H - naturalH  (panel fills the space below the media's
 *   natural bottom edge).  Clamped so the panel is always readable and never
 *   swallows the whole screen.
 *
 * Content fill strategy (split view):
 *   Always "cover" — the media fills the entire media area with no black bars.
 *   Portrait items are cropped top/bottom; very wide items are cropped on the
 *   sides.  Full uncropped rendering is used in fullscreen mode (renderPage
 *   overrides to "contain" when isMaximized).
 */
function computeItemLayout(item) {
  if (!item || !item.width || !item.height) {
    return { panelH: DEFAULT_PANEL_H };
  }

  const aspect = item.width / item.height;
  const naturalH = W / aspect;
  const idealPanelH = H - naturalH;
  const panelH = Math.round(
    Math.max(MIN_PANEL, Math.min(MAX_PANEL, idealPanelH)),
  );

  return { panelH };
}

// ─── Panel helpers ────────────────────────────────────────────────────────────

function getDayName(ts) {
  if (!ts) return '';
  const d = new Date(ts < 1e10 ? ts * 1000 : ts);
  return d.toLocaleDateString('en-US', { weekday: 'long' });
}

function getRelativeDateLabel(ts) {
  if (!ts) return '';
  const d = new Date(ts < 1e10 ? ts * 1000 : ts);
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const itemDay = new Date(d);
  itemDay.setHours(0, 0, 0, 0);
  const diff = Math.round((today - itemDay) / 86400000);
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Yesterday';
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

function getTimeString(ts) {
  if (!ts) return '';
  const d = new Date(ts < 1e10 ? ts * 1000 : ts);
  const h = String(d.getHours()).padStart(2, '0');
  const m = String(d.getMinutes()).padStart(2, '0');
  return `${h}:${m}`;
}

function getMegapixels(w, h) {
  if (!w || !h) return null;
  return `${((w * h) / 1_000_000).toFixed(1)} MP`;
}

// ─── InfoCard ─────────────────────────────────────────────────────────────────

function InfoCard({ label, value, colors }) {
  if (!value) return null;
  return (
    <View style={[ic.card, { backgroundColor: colors.background }]}>
      <Text
        style={[ic.cardLabel, { color: colors.textSecondary }]}
        numberOfLines={1}
      >
        {label}
      </Text>
      <Text style={[ic.cardValue, { color: colors.text }]} numberOfLines={3}>
        {value}
      </Text>
    </View>
  );
}

const ic = StyleSheet.create({
  card: { flex: 1, borderRadius: 14, padding: 14, minHeight: 72 },
  cardLabel: { fontSize: 11, fontWeight: '500', marginBottom: 6, opacity: 0.7 },
  cardValue: { fontSize: 13, fontWeight: '500', lineHeight: 18 },
});

// ─── SeekBar (inside media area for videos) ──────────────────────────────────

const SeekBar = React.memo(function SeekBar({
  currentTime,
  duration,
  onSeek,
  accent,
}) {
  const trackLayout = useRef({ width: 1 });
  const startX = useRef(0);

  const seekTo = useCallback(
    pct => {
      onSeek(Math.max(0, Math.min(duration, pct * duration)));
    },
    [duration, onSeek],
  );

  const panResponder = useMemo(
    () =>
      PanResponder.create({
        onStartShouldSetPanResponder: () => true,
        onMoveShouldSetPanResponder: () => true,
        onPanResponderGrant: evt => {
          startX.current = evt.nativeEvent.locationX;
          seekTo(startX.current / trackLayout.current.width);
        },
        onPanResponderMove: (_, gs) => {
          seekTo((startX.current + gs.dx) / trackLayout.current.width);
        },
      }),
    [seekTo],
  );

  const progress = duration > 0 ? currentTime / duration : 0;
  const barColor = accent || '#FF3B30';

  return (
    <View style={sb.row}>
      <Text style={sb.time}>{formatDuration(Math.floor(currentTime))}</Text>
      <View
        style={sb.trackOuter}
        onLayout={e => {
          trackLayout.current.width = e.nativeEvent.layout.width;
        }}
        {...panResponder.panHandlers}
      >
        <View style={sb.trackBg}>
          <View
            style={[
              sb.trackFill,
              { width: `${progress * 100}%`, backgroundColor: barColor },
            ]}
          />
        </View>
        <View
          style={[
            sb.thumb,
            { left: `${progress * 100}%`, backgroundColor: barColor },
          ]}
        />
      </View>
      <Text style={sb.time}>{formatDuration(Math.floor(duration))}</Text>
    </View>
  );
});

const sb = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    marginBottom: 6,
    gap: 10,
  },
  time: {
    color: '#CCC',
    fontSize: 12,
    fontVariant: ['tabular-nums'],
    width: 48,
    textAlign: 'center',
  },
  trackOuter: { flex: 1, height: 28, justifyContent: 'center' },
  trackBg: {
    height: 3,
    borderRadius: 1.5,
    backgroundColor: 'rgba(255,255,255,0.25)',
    overflow: 'hidden',
  },
  trackFill: { height: '100%', borderRadius: 1.5 },
  thumb: {
    position: 'absolute',
    width: 14,
    height: 14,
    borderRadius: 7,
    marginLeft: -7,
    top: 7,
  },
});

// ─── Main component ───────────────────────────────────────────────────────────

export function MediaViewer({
  visible,
  items,
  initialIndex = 0,
  initialMaximized = false,
  onClose,
  onDelete,
  onToggleFavorite,
  onRename,
  onNearEnd,
  onOpenPicker,
}) {
  const insets = useSafeAreaInsets();
  // Dynamic screen width — updates when device rotates so FlatList page width
  // is always correct and swipe navigation works in both orientations.
  // eslint-disable-next-line no-shadow
  const { width: W } = useWindowDimensions();
  const { colors } = useTheme();
  const { state: mediaState } = useMediaContext();
  const { state: appState } = useAppContext();
  const accent = appState.accentColor ?? colors.accent;
  const toast = useToast();

  // ─── State ───────────────────────────────────────────────────────────────
  const [currentIndex, setCurrentIndex] = useState(initialIndex);
  const [isPaused, setIsPaused] = useState(true);
  const [isMuted, setIsMuted] = useState(sessionMuted);
  const [duration, setDuration] = useState(0);
  const [currentTime, setCurrentTime] = useState(0);
  const [videoReady, setVideoReady] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  // ─── Landscape video playlist ─────────────────────────────────────────────
  const [isShuffled, setIsShuffled] = useState(false);
  // shuffledVideoOrder: indices into `items` for video-only navigation
  const [shuffledVideoOrder, setShuffledVideoOrder] = useState([]);
  const [videoPlaylistPos, setVideoPlaylistPos] = useState(0);
  const [controlsVisible, setControlsVisible] = useState(true);
  const [isMaximized, setIsMaximized] = useState(false);
  // panelHeight is the CSS height of the info panel (per-item, computed from
  // aspect ratio). Drives style.height (non-animated). The actual show/hide
  // is done by translating the panel off-screen via panelAnim (native driver).
  const [panelHeight, setPanelHeight] = useState(DEFAULT_PANEL_H);
  const [optionsVisible, setOptionsVisible] = useState(false);
  const [renameTarget, setRenameTarget] = useState(null);
  const [tagsModalVisible, setTagsModalVisible] = useState(false);
  const [propertiesItem, setPropertiesItem] = useState(null);
  const [isZoomedIn, setIsZoomedIn] = useState(false);
  const [tagDescriptions, setTagDescriptions] = useState([]);

  const flatListRef = useRef(null);
  const tagsScrollRef = useRef(null);
  const videoRef = useRef(null);
  const controlsTimer = useRef(null);
  // Map of uri → ZoomableImage ref; used to reset zoom on page navigation.
  const zoomRefs = useRef({});
  // panelAnim drives the panel's translateY: 0 = panel visible (split view),
  // splitPanelH = panel slid off-screen (fullscreen). Uses useNativeDriver: true
  // so every frame is handled on the UI thread — no JS-driven layout reflows.
  const splitPanelHRef = useRef(DEFAULT_PANEL_H); // current item's computed split height
  const isMaximizedRef = useRef(false);  // mirror of isMaximized for onScroll
  const isFullscreenRef = useRef(false); // mirror of isFullscreen for scheduleHide
  isFullscreenRef.current = isFullscreen; // kept in sync every render
  // Guards stale ExoPlayer callbacks after a swipe: only accept callbacks whose
  // URI still matches the currently displayed item.
  const activeUriRef = useRef(null);
  // Tracks which video URIs have already fired onReadyForDisplay so that
  // pre-warmed adjacent videos show instantly when swiped into view.
  const readyUrisRef = useRef(new Set());
  // translateY: 0 = panel at rest (visible), splitPanelH = off-screen (hidden)
  const panelAnim = useRef(new Animated.Value(0)).current;
  // Opacity fades the panel in/out simultaneously with the translateY spring
  const panelOpacityAnim = useRef(new Animated.Value(1)).current;
  // mediaOffsetAnim shifts the image upward by panelH/2 when the panel is
  // visible so the image stays centred in the area above the panel.
  // When panel visible (panelAnim=0):    mediaOffsetAnim = -panelH/2
  // When panel hidden  (panelAnim=panelH): mediaOffsetAnim = 0
  const mediaOffsetAnim = useRef(new Animated.Value(0)).current;

  // ─── Derived ─────────────────────────────────────────────────────────────
  const currentItem = items?.[currentIndex] ?? null;
  const isVideo = currentItem?.type?.includes('video');

  // Indices of every video item in the list — used for landscape nav.
  const videoIndices = useMemo(
    () =>
      (items ?? []).reduce((acc, it, i) => {
        if (it.type?.includes('video')) acc.push(i);
        return acc;
      }, []),
    [items],
  );
  // Pre-build a Set so the per-render O(n) scan becomes O(1).
  const favoritesSet = useMemo(
    () => new Set(mediaState.favorites),
    [mediaState.favorites],
  );
  const isFav = currentItem ? favoritesSet.has(currentItem.uri) : false;
  const itemTags = currentItem ? mediaState.tags[currentItem.uri] ?? [] : [];
  const itemTagsKey = itemTags.join('|');

  useEffect(() => {
    let cancelled = false;

    async function loadTagDescriptions() {
      if (!currentItem?.uri || itemTags.length === 0) {
        setTagDescriptions([]);
        return;
      }
      try {
        const rows = await TagService.getTagsForMedia(currentItem.uri);
        const described = rows
          .filter(r => typeof r.description === 'string' && r.description.trim())
          .map(r => ({ name: r.name, description: r.description.trim() }));
        if (!cancelled) setTagDescriptions(described);
      } catch {
        if (!cancelled) setTagDescriptions([]);
      }
    }

    loadTagDescriptions();
    return () => {
      cancelled = true;
    };
  }, [currentItem?.uri, itemTagsKey]);

  // Reset tags horizontal scroll to the start whenever the viewed item changes
  useEffect(() => {
    tagsScrollRef.current?.scrollTo({ x: 0, animated: false });
  }, [currentIndex]);

  // ─── Hide/show Android navigation bar with the viewer ──────────────────
  useEffect(() => {
    if (visible) {
      SystemUIModule.hideNavigationBar();
    } else {
      SystemUIModule.showNavigationBar();
    }
    return () => SystemUIModule.showNavigationBar();
  }, [visible]);

  // ─── Reset on open / unlock orientation on close ─────────────────────────
  useEffect(() => {
    if (!visible) {
      // Always restore portrait when viewer is dismissed.
      SystemUIModule.unlockOrientation();
      return;
    }
    if (visible) {
      setCurrentIndex(initialIndex);
      setControlsVisible(true);
      setIsPaused(true);
      setIsMuted(sessionMuted);
      setDuration(0);
      setCurrentTime(0);
      setVideoReady(false);
      setIsFullscreen(false);
      setIsShuffled(false);
      setShuffledVideoOrder(videoIndices);
      setVideoPlaylistPos(videoIndices.indexOf(initialIndex) >= 0 ? videoIndices.indexOf(initialIndex) : 0);
      SystemUIModule.unlockOrientation();
      readyUrisRef.current.clear();
      setIsMaximized(initialMaximized);
      setIsZoomedIn(false);
      isMaximizedRef.current = initialMaximized;
      activeUriRef.current = items?.[initialIndex]?.uri ?? null;
      const initLayout = computeItemLayout(items?.[initialIndex]);
      splitPanelHRef.current = initLayout.panelH;
      setPanelHeight(initLayout.panelH);
      // translateY: 0 = panel visible, panelH = panel off-screen (fullscreen)
      panelAnim.setValue(initialMaximized ? initLayout.panelH : 0);
      panelOpacityAnim.setValue(initialMaximized ? 0 : 1);
      // Center image in visible media area (above the panel)
      mediaOffsetAnim.setValue(initialMaximized ? 0 : -(initLayout.panelH / 2));
      prefetchAdjacent(initialIndex);
      // Reset zoom state for all currently-rendered pages on (re-)open.
      Object.values(zoomRefs.current).forEach(r => r?.reset?.());
    }
    return () => clearTimeout(controlsTimer.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, initialIndex]);

  // ─── Guard if items shrink (e.g. deletion) ───────────────────────────────
  useEffect(() => {
    if (!visible) return;
    if (!items?.length) {
      onClose();
      return;
    }
    if (currentIndex >= items.length) {
      setVideoReady(false);
      setCurrentIndex(items.length - 1);
    }
  }, [items?.length, visible, currentIndex, onClose]);

  // ─── Android hardware back ───────────────────────────────────────────────
  useEffect(() => {
    if (!visible) return;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (isFullscreenRef.current) {
        // Exit landscape fullscreen before anything else.
        SystemUIModule.unlockOrientation();
        setIsFullscreen(false);
        scheduleHide();
        return true;
      }
      if (isMaximized) {
        doMinimize();
        return true;
      }
      onClose();
      return true;
    });
    return () => sub.remove();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, isMaximized, onClose, scheduleHide]);

  // ─── Video controls auto-hide ────────────────────────────────────────────
  // Defensive cleanup: clear any pending timer on unmount regardless of
  // what the visible/initialIndex effect cleanup has already done.
  useEffect(() => () => clearTimeout(controlsTimer.current), []);

  const scheduleHide = useCallback(() => {
    clearTimeout(controlsTimer.current);
    // In landscape fullscreen keep controls permanently visible.
    if (isFullscreenRef.current) return;
    controlsTimer.current = setTimeout(() => setControlsVisible(false), 4000);
  }, []);

  const toggleControls = useCallback(() => {
    setControlsVisible(v => {
      const next = !v;
      if (next) scheduleHide();
      return next;
    });
  }, [scheduleHide]);

  // ─── Landscape fullscreen toggle ─────────────────────────────────────────
  const handleLandscapeToggle = useCallback(() => {
    if (isFullscreenRef.current) {
      // Exit landscape — restore orientation and let controls auto-hide resume.
      SystemUIModule.unlockOrientation();
      setIsFullscreen(false);
      scheduleHide();
    } else {
      // Enter landscape — lock orientation and keep controls visible.
      SystemUIModule.lockLandscape();
      setIsFullscreen(true);
      setControlsVisible(true);
      clearTimeout(controlsTimer.current);
    }
  }, [scheduleHide]);

  // ─── Landscape video playlist: navigate to an item by its items[] index ──
  const navigateToVideo = useCallback(
    itemIndex => {
      if (itemIndex < 0 || itemIndex >= (items?.length ?? 0)) return;
      activeUriRef.current = items[itemIndex].uri;
      setVideoReady(readyUrisRef.current.has(items[itemIndex].uri));
      setIsFullscreen(false); // reset then re-apply after scroll
      setCurrentIndex(itemIndex);
      setIsPaused(true);
      setDuration(0);
      setCurrentTime(0);
      flatListRef.current?.scrollToIndex({ index: itemIndex, animated: false });
      setIsFullscreen(true);
    },
    [items],
  );

  const handleVideoNext = useCallback(() => {
    const nextPos = videoPlaylistPos + 1;
    if (nextPos >= shuffledVideoOrder.length) return;
    setVideoPlaylistPos(nextPos);
    navigateToVideo(shuffledVideoOrder[nextPos]);
  }, [videoPlaylistPos, shuffledVideoOrder, navigateToVideo]);

  const handleVideoPrev = useCallback(() => {
    const prevPos = videoPlaylistPos - 1;
    if (prevPos < 0) return;
    setVideoPlaylistPos(prevPos);
    navigateToVideo(shuffledVideoOrder[prevPos]);
  }, [videoPlaylistPos, shuffledVideoOrder, navigateToVideo]);

  const handleShuffleToggle = useCallback(() => {
    setIsShuffled(prev => {
      if (prev) {
        // Restore original order, keep current item in place
        const newOrder = [...videoIndices];
        const pos = newOrder.indexOf(currentIndex);
        setShuffledVideoOrder(newOrder);
        setVideoPlaylistPos(pos >= 0 ? pos : 0);
      } else {
        // Shuffle remaining videos, keep current at position 0
        const rest = videoIndices.filter(i => i !== currentIndex);
        for (let i = rest.length - 1; i > 0; i--) {
          const j = Math.floor(Math.random() * (i + 1));
          [rest[i], rest[j]] = [rest[j], rest[i]];
        }
        const newOrder = [currentIndex, ...rest];
        setShuffledVideoOrder(newOrder);
        setVideoPlaylistPos(0);
      }
      return !prev;
    });
  }, [videoIndices, currentIndex]);

  // ─── Prefetch adjacent images ────────────────────────────────────────────
  const prefetchAdjacent = useCallback(
    idx => {
      [idx - 1, idx + 1].forEach(i => {
        const it = items?.[i];
        if (it?.uri && !it.type?.includes('video')) {
          Image.prefetch(it.uri).catch(() => {});
        }
      });
    },
    [items],
  );

  // ─── Maximize / minimize ─────────────────────────────────────────────────
  // Both use Animated.spring with useNativeDriver: true so animations run
  // entirely on the UI thread — zero JS-thread jank.
  const doMaximize = useCallback(() => {
    isMaximizedRef.current = true;
    setIsMaximized(true);
    Animated.parallel([
      Animated.spring(panelAnim, {
        toValue: splitPanelHRef.current, // slide panel off-screen
        useNativeDriver: true,
        bounciness: 0,
        speed: 18,
      }),
      Animated.spring(panelOpacityAnim, {
        toValue: 0,
        useNativeDriver: true,
        bounciness: 0,
        speed: 24,
      }),
      // Image slides back to vertical centre as panel hides
      Animated.spring(mediaOffsetAnim, {
        toValue: 0,
        useNativeDriver: true,
        bounciness: 0,
        speed: 18,
      }),
    ]).start();
  }, [panelAnim, panelOpacityAnim, mediaOffsetAnim]);

  const doMinimize = useCallback(() => {
    isMaximizedRef.current = false;
    setIsMaximized(false);
    // Reset zoom on the current image so the panel doesn't slide in while
    // the image is still pinch-zoomed.
    const activeUri = activeUriRef.current;
    if (activeUri) zoomRefs.current[activeUri]?.reset?.();
    Animated.parallel([
      Animated.spring(panelAnim, {
        toValue: 0, // slide panel back into view
        useNativeDriver: true,
        bounciness: 6,
        speed: 14,
      }),
      Animated.spring(panelOpacityAnim, {
        toValue: 1,
        useNativeDriver: true,
        bounciness: 0,
        speed: 14,
      }),
      // Image shifts up so it centres in the visible area above the panel
      Animated.spring(mediaOffsetAnim, {
        toValue: -(splitPanelHRef.current / 2),
        useNativeDriver: true,
        bounciness: 6,
        speed: 14,
      }),
    ]).start();
  }, [panelAnim, panelOpacityAnim, mediaOffsetAnim]);

  const toggleMaximize = useCallback(() => {
    isMaximized ? doMinimize() : doMaximize();
  }, [isMaximized, doMaximize, doMinimize]);

  // ─── Gesture responders ───────────────────────────────────────────────────
  // Use refs so PanResponders are created once and never stale.
  const doMaximizeRef = useRef(doMaximize);
  doMaximizeRef.current = doMaximize;
  const doMinimizeRef = useRef(doMinimize);
  doMinimizeRef.current = doMinimize;
  const isZoomedInRef = useRef(isZoomedIn);
  isZoomedInRef.current = isZoomedIn;

  // Track panel scroll position — only intercept downward swipe when at top
  const panelScrollYRef = useRef(0);

  // Full panel: swipe down anywhere → collapse to fullscreen
  const panelPanResponder = useMemo(
    () =>
      PanResponder.create({
        onMoveShouldSetPanResponder: (_, gs) =>
          gs.dy > 8 && Math.abs(gs.dy) > Math.abs(gs.dx),
        onPanResponderRelease: (_, gs) => {
          if (gs.dy > 50) doMaximizeRef.current();
        },
      }),
    [],
  );

  // Peek strip: tap or upward drag → reveal info panel (fullscreen only)
  const peekStripResponder = useMemo(
    () =>
      PanResponder.create({
        onStartShouldSetPanResponder: () => true,
        onMoveShouldSetPanResponder: () => true,
        onPanResponderRelease: (_, gs) => {
          // Upward drag OR a simple tap (minimal movement)
          if (
            gs.dy < -20 ||
            gs.vy < -0.4 ||
            (Math.abs(gs.dy) < 8 && Math.abs(gs.dx) < 8)
          ) {
            doMinimizeRef.current();
          }
        },
      }),
    [],
  );

  const onScroll = useCallback(
    e => {
      const idx = Math.round(e.nativeEvent.contentOffset.x / W);
      if (idx !== currentIndex && idx >= 0 && idx < items.length) {
        // Reset zoom of the page being navigated away from.
        const leavingUri = items[currentIndex]?.uri;
        if (leavingUri) zoomRefs.current[leavingUri]?.reset?.();
        setIsZoomedIn(false);
        // Advance the active URI ref FIRST so any in-flight ExoPlayer callbacks
        // for the previous video are dropped before we update React state.
        activeUriRef.current = items[idx].uri;
        // If this video was pre-warmed and already decoded its first frame,
        // mark it ready immediately so the poster never flashes.
        setVideoReady(readyUrisRef.current.has(items[idx].uri));
        setIsFullscreen(false);
        setCurrentIndex(idx);
        // Keep playlist position in sync when user swipes to a video.
        if (items[idx].type?.includes('video')) {
          setShuffledVideoOrder(prev => {
            const pos = prev.indexOf(idx);
            if (pos >= 0) setVideoPlaylistPos(pos);
            return prev;
          });
        }
        setIsPaused(true);
        setDuration(0);
        setCurrentTime(0);
        prefetchAdjacent(idx);
        // Snap info panel to the new item's computed split height immediately.
        // Using setValue (not Animated.timing) avoids a 200ms JS-driven
        // animation running concurrently with the post-swipe re-renders.
        const newLayout = computeItemLayout(items[idx]);
        splitPanelHRef.current = newLayout.panelH;
        setPanelHeight(newLayout.panelH);
        if (isMaximizedRef.current) {
          // Panel is off-screen; snap both animations to their "hidden" values
          // for the new item so doMinimize springs back correctly.
          panelAnim.setValue(newLayout.panelH);
          mediaOffsetAnim.setValue(0);
        } else {
          // Panel is visible; snap the image offset to centre in the new
          // visible area immediately (no animation — same as a layout snap).
          mediaOffsetAnim.setValue(-(newLayout.panelH / 2));
        }
        // Signal the parent when approaching the last loaded item so it can
        // pre-fetch the next page before the user hits the boundary.
        if (onNearEnd && idx >= items.length - 5) {
          onNearEnd();
        }
      }
    },
    [
      W,
      currentIndex,
      items,
      prefetchAdjacent,
      panelAnim,
      mediaOffsetAnim,
      onNearEnd,
    ],
  );

  // ─── Action handlers ─────────────────────────────────────────────────────
  const handleShare = useCallback(async () => {
    if (!currentItem) return;
    try {
      const mimeType = getMimeTypeForItem(currentItem);
      await MediaStoreModule.shareFile(
        currentItem.uri,
        mimeType,
        currentItem.filename ?? '',
      );
    } catch {
      /* dismissed */
    }
  }, [currentItem]);

  const handleSeek = useCallback(time => {
    setCurrentTime(time);
    videoRef.current?.seek?.(time);
  }, []);

  // onReadyForDisplay and onError are defined inline in renderPage so they can
  // be guarded by activeUriRef — see the VideoPlayer block in renderPage.

  const handleDeleteCurrent = useCallback(() => {
    if (currentItem) onDelete?.(currentItem);
  }, [currentItem, onDelete]);

  const handleToggleFav = useCallback(() => {
    if (currentItem) onToggleFavorite?.(currentItem);
  }, [currentItem, onToggleFavorite]);

  const handleOpenRename = useCallback(item => {
    setOptionsVisible(false);
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
      const result = await (onRename
        ? onRename(item, fullName)
        : { success: false, error: 'Rename not available.' });
      if (result.success) {
        toast.success('File renamed', `"${item.filename}" → "${fullName}"`);
      } else {
        toast.error('Rename failed', result.error ?? 'Could not rename file.');
      }
    },
    [renameTarget, onRename, toast],
  );

  // ─── Find face handler (disabled — uncomment menu item to re-enable) ──────
  // const handleFindFaces = useCallback(async () => {
  //   if (!currentItem) return;
  //   setOptionsVisible(false);
  //   try {
  //     const persons = await FaceService.getPersonsForAsset(currentItem.uri);
  //     if (persons.length === 0) {
  //       Alert.alert(
  //         'No faces found',
  //         'No indexed faces were found in this image.',
  //       );
  //       return;
  //     }
  //     if (persons.length === 1) {
  //       navigate('PersonDetail', { personId: persons[0].person_id });
  //       return;
  //     }
  //     Alert.alert(
  //       'Multiple people found',
  //       'Which person do you want to see?',
  //       persons
  //         .map(p => ({
  //           text: p.name ?? `Unknown #${p.person_id}`,
  //           onPress: () => navigate('PersonDetail', { personId: p.person_id }),
  //         }))
  //         .concat([{ text: 'Cancel', style: 'cancel' }]),
  //     );
  //   } catch (err) {
  //     console.warn('[MediaViewer] handleFindFaces error:', err);
  //   }
  // }, [currentItem]);

  // ─── Options menu ─────────────────────────────────────────────────────────
  const menuItems = useMemo(() => {
    if (!currentItem) return [];
    return [
      {
        key: 'openWith',
        label: 'Open With',
        icon: 'openWith',
        onPress: () => {
          setOptionsVisible(false);
          const mimeType = getMimeTypeForItem(currentItem);
          MediaStoreModule.openWith(currentItem.uri, mimeType).catch(() => {});
        },
      },
      { key: 'share', label: 'Share', icon: 'share1', onPress: handleShare },
      {
        key: 'rename',
        label: 'Rename',
        icon: 'rename',
        onPress: () => handleOpenRename(currentItem),
      },
      {
        key: 'copyTo',
        label: 'Copy To',
        icon: 'copy',
        onPress: () => {
          setOptionsVisible(false);
          setTimeout(() => onOpenPicker?.('copy', currentItem), 120);
        },
      },
      {
        key: 'moveTo',
        label: 'Move To',
        icon: 'moveRight',
        onPress: () => {
          setOptionsVisible(false);
          setTimeout(() => onOpenPicker?.('move', currentItem), 120);
        },
      },
      { key: 'hide', label: 'Hide', icon: 'hidden' },
      {
        key: 'fav',
        label: isFav ? 'Unfavourite' : 'Favourite',
        icon: isFav ? 'remove' : 'heartOutline',
        onPress: handleToggleFav,
      },
      {
        key: 'info',
        label: 'Properties',
        icon: 'information',
        onPress: () => {
          setOptionsVisible(false);
          setTimeout(() => setPropertiesItem(currentItem), 180);
        },
      },
      // ...(isImage
      //   ? [
      //       {
      //         key: 'findFace',
      //         label: "Find this person's photos",
      //         icon: 'facialRecognition',
      //         onPress: handleFindFaces,
      //       },
      //     ]
      //   : [])

      {
        key: 'delete',
        label: 'Delete',
        icon: 'trash',
        dividerBefore: true,
        destructive: true,
        onPress: handleDeleteCurrent,
      },
    ];
  }, [
    currentItem,
    isFav,
    handleShare,
    handleToggleFav,
    handleDeleteCurrent,
    handleOpenRename,
    onOpenPicker,
  ]);

  // ─── Render page ──────────────────────────────────────────────────────────
  const renderPage = useCallback(
    ({ item, index }) => {
      const itemIsVideo = item.type?.includes('video');
      const isActive = index === currentIndex;
      // The panel is an absolute overlay — the image is always full-height.
      // Always use 'contain' so the full image is visible without cropping.
      const contentFit = 'contain';
      const contentPosition = 'center';
      const videoResizeMode = 'contain';
      return itemIsVideo ? (
        <TouchableOpacity
          activeOpacity={1}
          onPress={toggleControls}
          style={[s.page, { width: W }]}
        >
          <>
            {/* Poster thumbnail while ExoPlayer initialises */}
            {(!isActive || !videoReady) && (
              <Image
                source={{ uri: item.uri }}
                style={StyleSheet.absoluteFill}
                contentFit={contentFit}
                contentPosition={contentPosition}
                cachePolicy="memory-disk"
                recyclingKey={item.uri}
              />
            )}
            {/* Play-icon overlay for off-screen video pages */}
            {!isActive && (
              <View style={[StyleSheet.absoluteFill, s.videoThumbOverlay]}>
                <Icon name="play" size={48} color="#FFF" />
              </View>
            )}
            {/* VideoPlayer — active item + ±1 adjacent video items.
                  Adjacent items mount with paused=true and opacity:0 so
                  ExoPlayer pre-initialises in the background.  When the
                  user swipes to them their first frame is already decoded
                  and readyUrisRef.has(uri) lets us skip the poster.
                  key={item.uri} still guards against stale surface calls. */}
            {(isActive || Math.abs(index - currentIndex) <= 1) && (
              <VideoPlayer
                key={item.uri}
                ref={isActive ? videoRef : undefined}
                uri={item.uri}
                paused={isActive ? isPaused : true}
                muted={isActive ? isMuted : true}
                resizeMode={videoResizeMode}
                style={[
                  StyleSheet.absoluteFill,
                  (!isActive || !videoReady) && s.videoHidden,
                ]}
                onReadyForDisplay={() => {
                  // Always record readiness so the swipe-in path is instant.
                  readyUrisRef.current.add(item.uri);
                  if (item.uri !== activeUriRef.current) return;
                  setVideoReady(true);
                }}
                onError={() => {
                  if (item.uri !== activeUriRef.current) return;
                  setVideoReady(true);
                }}
                onLoad={({ duration: d }) => {
                  if (item.uri !== activeUriRef.current) return;
                  setDuration(d);
                  // Persist duration to SQLite in background so subsequent
                  // opens and the info panel show it without a fresh scan.
                  if (d > 0) {
                    MediaIndexService.syncMetrics(item.uri, {
                      duration: d,
                    }).catch(() => {});
                  }
                }}
                onProgress={({ currentTime: t }) => {
                  if (item.uri !== activeUriRef.current) return;
                  setCurrentTime(t);
                }}
                onEnd={() => {
                  if (item.uri !== activeUriRef.current) return;
                  setIsPaused(true);
                }}
              />
            )}
          </>
        </TouchableOpacity>
      ) : (
        <View style={[s.page, { width: W }]}>
          <ZoomableImage
            ref={r => {
              if (r) {
                zoomRefs.current[item.uri] = r;
              } else {
                delete zoomRefs.current[item.uri];
              }
            }}
            uri={item.uri}
            contentFit={contentFit}
            recyclingKey={item.uri}
            transition={0}
            zoomEnabled={index === currentIndex}
            onScaleChange={setIsZoomedIn}
          />
        </View>
      );
    },
    [
      W,
      currentIndex,
      isPaused,
      isMuted,
      videoReady,
      toggleControls,
      setIsZoomedIn,
    ],
  );

  const getItemLayout = useCallback(
    (_, i) => ({ length: W, offset: W * i, index: i }),
    [W],
  );

  // ─── Guard ───────────────────────────────────────────────────────────────
  if (!visible || !items?.length) return null;

  // ─── Info panel data ──────────────────────────────────────────────────────
  const typeLabel = getTypeLabel(currentItem);
  const sizeLabel = currentItem?.fileSize
    ? formatFileSize(currentItem.fileSize)
    : null;
  const dimsLabel =
    currentItem?.width && currentItem?.height
      ? formatDimensions(currentItem.width, currentItem.height)
      : null;
  // Duration: populated asynchronously from VideoPlayer's onLoad
  const durationLabel =
    isVideo && duration > 0 ? formatDuration(duration) : null;
  const createdLabel = formatDateTimeDisplay(currentItem?.timestamp);
  const albumLabel = currentItem?.albumName || null;
  const dayName = getDayName(currentItem?.timestamp);
  const relativeDateLabel = getRelativeDateLabel(currentItem?.timestamp);
  const timeStr = getTimeString(currentItem?.timestamp);
  const megapixels = !isVideo
    ? getMegapixels(currentItem?.width, currentItem?.height)
    : null;
  const extLabel =
    (currentItem?.extension || getExtension(currentItem?.filename ?? '')).toUpperCase() || null;
  const filenameBase = currentItem?.filename
    ? stripExtension(currentItem.filename)
    : null;
  const typeOnly = typeLabel ? typeLabel.split('·')[0].trim() : null;

  return (
    <Modal
      visible
      transparent={false}
      animationType="slide"
      statusBarTranslucent
      onRequestClose={onClose}
    >
      {/*
       * GestureHandlerRootView is required inside the Modal because on Android
       * Modal renders in a separate native window, outside the app-level
       * GestureHandlerRootView defined in App.js. Without this, RNGH gesture
       * handlers (pinch, pan, double-tap) silently fail.
       */}
      <GestureHandlerRootView style={s.ghRoot}>
        <StatusBar hidden />

        {/*
         * Root column: media area (flex: 1) + info panel (Animated height).
         * Total height = H (exact screen height, matching StatusBar hidden).
         * As panelAnim decreases to 0 the media area fills the freed space.
         */}
        <View style={s.root}>
          {/* ── MEDIA AREA ─────────────────────────────────────────────────── */}
          <View style={s.mediaArea}>
            {/* Swipeable pages — wrapped in Animated.View so mediaOffsetAnim can
                shift the image up/down with the native driver while the panel
                slides in/out, keeping the image centred in the visible area. */}
            <Animated.View
              style={[
                StyleSheet.absoluteFill,
                { transform: [{ translateY: mediaOffsetAnim }] },
              ]}
            >
              <FlatList
                ref={flatListRef}
                data={items}
                horizontal
                pagingEnabled
                showsHorizontalScrollIndicator={false}
                // Disable swipe when zoomed in (pan gesture takes over) or when
                // the video is in native landscape fullscreen mode.
                scrollEnabled={!isZoomedIn && !isFullscreen}
                keyExtractor={it => it.uri}
                renderItem={renderPage}
                getItemLayout={getItemLayout}
                initialScrollIndex={Math.min(initialIndex, items.length - 1)}
                onMomentumScrollEnd={onScroll}
                extraData={currentIndex}
                windowSize={3}
                maxToRenderPerBatch={3}
                removeClippedSubviews={false}
                initialNumToRender={1}
                style={s.flatList}
              />
            </Animated.View>

            {/* Header overlay: back (left) + options (right) */}
            <View
              style={[s.headerOverlay, { paddingTop: insets.top + 10 }]}
              pointerEvents="box-none"
            >
              <TouchableOpacity
                onPress={onClose}
                hitSlop={HIT}
                style={s.headerBtn}
                activeOpacity={0.75}
              >
                <Icon name="back" size={18} color="#FFF" />
              </TouchableOpacity>
              <View style={s.headerSpacer} />
              <TouchableOpacity
                onPress={() => setOptionsVisible(true)}
                hitSlop={HIT}
                style={s.headerBtn}
                activeOpacity={0.75}
              >
                <Icon name="more" size={18} color="#FFF" />
              </TouchableOpacity>
            </View>

            {/* Video controls overlay — floats above the active bottom overlay */}
            {isVideo && controlsVisible && (
              <View
                style={[
                  s.videoControls,
                  {
                    bottom: isFullscreen
                      ? insets.bottom
                      : isMaximized
                      ? PEEK_H
                      : panelHeight,
                  },
                ]}
              >
                <SeekBar
                  currentTime={currentTime}
                  duration={duration}
                  onSeek={handleSeek}
                  accent={accent}
                />
                <View style={s.videoActionsRow}>
                  <TouchableOpacity
                    onPress={() => setIsPaused(v => !v)}
                    style={s.videoBtn}
                    hitSlop={HIT}
                  >
                    <Icon
                      name={isPaused ? 'play' : 'pause'}
                      size={26}
                      color="#FFF"
                    />
                  </TouchableOpacity>
                  <TouchableOpacity
                    onPress={() =>
                      setIsMuted(v => {
                        sessionMuted = !v;
                        return !v;
                      })
                    }
                    style={s.videoBtn}
                    hitSlop={HIT}
                  >
                    <Icon
                      name={isMuted ? 'volumeMute' : 'volumeOn'}
                      size={22}
                      color="#FFF"
                    />
                  </TouchableOpacity>
                  {/* Prev / Shuffle / Next — landscape mode only */}
                  {isFullscreen && (
                    <>
                      <TouchableOpacity
                        onPress={handleVideoPrev}
                        style={[s.videoBtn, videoPlaylistPos <= 0 && s.videoBtnDisabled]}
                        hitSlop={HIT}
                        disabled={videoPlaylistPos <= 0}
                      >
                        <Icon name="previous" size={20} color="#FFF" />
                      </TouchableOpacity>
                      <TouchableOpacity
                        onPress={handleShuffleToggle}
                        style={s.videoBtn}
                        hitSlop={HIT}
                      >
                        <Icon
                          name="refresh"
                          size={18}
                          color={isShuffled ? accent : '#FFF'}
                        />
                      </TouchableOpacity>
                      <TouchableOpacity
                        onPress={handleVideoNext}
                        style={[
                          s.videoBtn,
                          videoPlaylistPos >= shuffledVideoOrder.length - 1 && s.videoBtnDisabled,
                        ]}
                        hitSlop={HIT}
                        disabled={videoPlaylistPos >= shuffledVideoOrder.length - 1}
                      >
                        {/* Flip previous icon to use as "next" */}
                        <Icon
                          name="previous"
                          size={20}
                          color="#FFF"
                          style={{ transform: [{ scaleX: -1 }] }}
                        />
                      </TouchableOpacity>
                    </>
                  )}
                  <View style={s.videoBtnSpacer} />
                  <TouchableOpacity
                    onPress={handleLandscapeToggle}
                    style={s.videoBtn}
                    hitSlop={HIT}
                  >
                    <Icon name="maximize" size={20} color="#FFF" />
                  </TouchableOpacity>
                </View>
              </View>
            )}

            {/* Maximize — bottom-right of media area, only in split view (not in landscape) */}
            {!isMaximized && !isFullscreen && (
              <TouchableOpacity
                onPress={doMaximize}
                style={s.maximizeBtn}
                hitSlop={HIT}
                activeOpacity={0.75}
              >
                <Icon name="maximize" size={18} color="#FFF" />
              </TouchableOpacity>
            )}
          </View>

          {/* ── INFO PANEL — hidden in landscape fullscreen ──────────────── */}
          {!isFullscreen && <Animated.View
            pointerEvents="box-none"
            style={[
              s.infoPanel,
              {
                height: panelHeight,
                backgroundColor: colors.card,
                transform: [{ translateY: panelAnim }],
                opacity: panelOpacityAnim,
              },
            ]}
          >
            {/* Inner wrapper captures swipe-down to dismiss; pointerEvents="box-none" on
                the outer Animated.View ensures touches outside the panel (on the media
                area) are never blocked by the animated container. */}
            <View style={s.panelInner} {...panelPanResponder.panHandlers}>
              {/* Drag handle — tap to collapse */}
              <View style={s.dragHandle}>
                <TouchableOpacity
                  onPress={doMaximize}
                  activeOpacity={0.5}
                  hitSlop={HIT}
                  style={s.pillWrap}
                >
                  <View style={[s.pill, { backgroundColor: colors.border }]} />
                </TouchableOpacity>
              </View>

              <ScrollView
                style={s.panelScroll}
                contentContainerStyle={[
                  s.panelContent,
                  { paddingBottom: insets.bottom + 16 },
                ]}
                showsVerticalScrollIndicator={false}
                bounces={false}
                onScroll={e => {
                  panelScrollYRef.current = e.nativeEvent.contentOffset.y;
                }}
                scrollEventThrottle={16}
              >
                {/* Header: Date (left) + tags (right) — NOT in cards */}
                <View style={s.topHeader}>
                  <View style={s.topDateCol}>
                    <Text style={[s.topDay, { color: colors.text }]}>
                      {dayName || ''}
                    </Text>
                    <Text style={[s.topSub, { color: colors.textSecondary }]}>
                      {(relativeDateLabel || '—') +
                        (timeStr ? `  |  ${timeStr}` : '')}
                    </Text>
                  </View>

                  <View style={s.topTagsCol}>
                    <TouchableOpacity
                      onPress={() => setTagsModalVisible(true)}
                      style={[s.tagsBtn, { borderColor: accent }]}
                      activeOpacity={0.7}
                    >
                      <Icon
                        name="tags"
                        size={13}
                        color={colors.textSecondary}
                      />
                      <Text style={[s.tagsBtnText, { color: accent }]}>
                        {itemTags.length ? 'Edit' : '+Tags'}
                      </Text>
                    </TouchableOpacity>

                    <ScrollView
                      ref={tagsScrollRef}
                      horizontal
                      showsHorizontalScrollIndicator={false}
                      contentContainerStyle={s.topTagsRow}
                      style={s.topTagsScroll}
                    >
                      {itemTags.length ? (
                        itemTags.map(tag => (
                          <View
                            key={tag}
                            style={[
                              s.tagChip,
                              { backgroundColor: colors.surface },
                            ]}
                          >
                            <Text
                              style={[s.tagChipText, { color: colors.text }]}
                              numberOfLines={1}
                            >
                              {tag}
                            </Text>
                          </View>
                        ))
                      ) : (
                        <Text style={[s.topTagsEmpty, { color: colors.textTertiary }]}>
                          No tags
                        </Text>
                      )}
                    </ScrollView>
                  </View>
                </View>

                {/* Row 2: Filename (no extension) | Extension */}
                <View style={s.metaRowTwo}>
                  <View style={[s.metaCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
                    <Text style={[s.metaLabel, { color: colors.textSecondary }]}>
                      Filename
                    </Text>
                    <Text style={[s.metaValue, { color: colors.text }]}>
                      {filenameBase || '-'}
                    </Text>
                  </View>
                  <View style={[s.metaCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
                    <Text style={[s.metaLabel, { color: colors.textSecondary }]}>
                      Extension
                    </Text>
                    <Text style={[s.metaValue, { color: colors.text }]}>
                      {extLabel || '-'}
                    </Text>
                  </View>
                </View>

                {/* Row 3: Type | Dimensions | File Size */}
                <View style={s.metaRowThree}>
                  <View style={[s.metaCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
                    <Text style={[s.metaLabel, { color: colors.textSecondary }]}>
                      Type
                    </Text>
                    <Text style={[s.metaValue, { color: colors.text }]}>
                      {typeOnly || '-'}
                    </Text>
                  </View>
                  <View style={[s.metaCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
                    <Text style={[s.metaLabel, { color: colors.textSecondary }]}>
                      Dimensions
                    </Text>
                    <Text style={[s.metaValue, { color: colors.text }]}>
                      {dimsLabel || '-'}
                    </Text>
                  </View>
                  <View style={[s.metaCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
                    <Text style={[s.metaLabel, { color: colors.textSecondary }]}>
                      File Size
                    </Text>
                    <Text style={[s.metaValue, { color: colors.text }]}>
                      {sizeLabel || '-'}
                    </Text>
                  </View>
                </View>

                {tagDescriptions.length > 0 ? (
                  <View
                    style={[
                      s.tagDescSection,
                      { backgroundColor: colors.surface },
                    ]}
                  >
                    <Text
                      style={[s.tagDescTitle, { color: colors.textSecondary }]}
                    >
                      Tag Descriptions
                    </Text>
                    <ScrollView
                      style={s.tagDescScroll}
                      contentContainerStyle={s.tagDescContent}
                      showsVerticalScrollIndicator
                      nestedScrollEnabled
                    >
                      {tagDescriptions.map(tag => (
                        <View
                          key={tag.name}
                          style={[
                            s.tagDescItem,
                            { borderBottomColor: colors.border },
                          ]}
                        >
                          <Text style={[s.tagDescName, { color: accent }]}>
                            {tag.name}
                          </Text>
                          <Text style={[s.tagDescText, { color: colors.text }]}>
                            {tag.description}
                          </Text>
                        </View>
                      ))}
                    </ScrollView>
                  </View>
                ) : null}
              </ScrollView>
            </View>
            {/* /panelInner */}
          </Animated.View>}

          {/* ── PEEK STRIP — shown in fullscreen (not in landscape mode) ─── */}
          {isMaximized && !isFullscreen && (
            <View
              style={[s.peekStrip, { backgroundColor: colors.card }]}
              {...peekStripResponder.panHandlers}
            >
              <View style={[s.pill, { backgroundColor: colors.border }]} />
              <Text
                numberOfLines={1}
                style={[s.peekFilename, { color: colors.text }]}
              >
                {currentItem?.filename ?? ''}
              </Text>
            </View>
          )}
        </View>

        {/* ─── Options context menu ─────────────────────────────────────────── */}
        <ContextMenu
          visible={optionsVisible}
          onClose={() => setOptionsVisible(false)}
          title={currentItem?.filename ?? ''}
          items={menuItems}
        />

        {/* ─── Tags modal ───────────────────────────────────────────────────── */}
        <TagsModal
          visible={tagsModalVisible}
          onClose={() => setTagsModalVisible(false)}
          mediaUri={currentItem?.uri}
          mediaRecord={currentItem}
        />

        {/* ─── Rename dialog ────────────────────────────────────────────────── */}
        <RenameDialog
          visible={!!renameTarget}
          onClose={() => setRenameTarget(null)}
          onConfirm={handleConfirmRename}
          initialName={stripExtension(renameTarget?.filename ?? '')}
          title="Rename File"
          placeholder="Enter file name"
        />

        {/* ─── Properties modal ─────────────────────────────────────────────── */}
        <PropertiesModal
          visible={!!propertiesItem}
          onClose={() => setPropertiesItem(null)}
          item={propertiesItem}
        />
      </GestureHandlerRootView>
    </Modal>
  );
}

// ─── Styles ──────────────────────────────────────────────────────────────────

const s = StyleSheet.create({
  // GestureHandlerRootView — fills the Modal window
  ghRoot: { flex: 1 },

  // Root column container — full screen height
  root: {
    flex: 1,
    flexDirection: 'column',
    backgroundColor: '#000',
  },

  // Media area — always full height (panel is an absolute overlay)
  mediaArea: {
    flex: 1,
    backgroundColor: '#000',
    overflow: 'hidden',
  },

  // FlatList — fills its Animated.View wrapper
  flatList: {
    ...StyleSheet.absoluteFillObject,
  },

  // FlatList pages — width: W, flex height via StyleSheet.absoluteFill images
  page: {
    width: W,
    flex: 1,
    backgroundColor: '#000',
    justifyContent: 'center',
  },

  videoThumbOverlay: {
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: 'rgba(0,0,0,0.30)',
  },
  videoHidden: { opacity: 0 },

  // Header overlay (top of media area)
  headerOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingBottom: 14,
  },
  headerBtn: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: 'rgba(0,0,0,0.38)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  headerSpacer: { flex: 1 },

  // Video controls overlay (bottom of media area, above info panel)
  videoControls: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    paddingTop: 10,
    paddingBottom: 10,
    backgroundColor: 'rgba(0,0,0,0.55)',
  },
  videoActionsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 20,
    gap: 20,
    paddingBottom: 4,
  },
  videoBtn: { padding: 6 },
  videoBtnDisabled: { opacity: 0.3 },
  videoBtnSpacer: { flex: 1 },

  // Maximize / minimize button — bottom-right corner of media area
  maximizeBtn: {
    position: 'absolute',
    bottom: 12,
    right: 12,
    width: 34,
    height: 34,
    borderRadius: 17,
    backgroundColor: 'rgba(0,0,0,0.40)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  // Invisible swipe-up capture zone at bottom of media area
  swipeUpZone: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    height: 90,
  },

  // Peek strip — visible in fullscreen; tap or drag-up to reveal the info panel
  peekStrip: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    height: PEEK_H,
    alignItems: 'center',
    paddingTop: 8,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: -4 },
    shadowOpacity: 0.28,
    shadowRadius: 14,
    elevation: 16,
  },
  peekFilename: {
    fontSize: 13,
    fontWeight: '600',
    marginTop: 5,
    paddingHorizontal: 20,
    textAlign: 'center',
  },
  peekSize: {
    fontSize: 11,
    marginTop: 2,
    opacity: 0.7,
  },

  // Info panel — absolute overlay at screen bottom
  infoPanel: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    overflow: 'hidden',
    borderTopLeftRadius: 0,
    borderTopRightRadius: 0,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: -4 },
    shadowOpacity: 0.28,
    shadowRadius: 14,
    elevation: 16,
  },
  // Panel inner wrapper — receives swipe-down panHandlers
  panelInner: { flex: 1 },
  // Drag handle
  dragHandle: { alignItems: 'center', paddingTop: 10, paddingBottom: 6 },
  pillWrap: { paddingVertical: 6, paddingHorizontal: 40 },
  pill: { width: 38, height: 4, borderRadius: 2 },
  // Panel scroll
  panelScroll: { flex: 1 },
  panelContent: { paddingTop: 4, paddingHorizontal: 16, gap: 10 },
  topHeader: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    justifyContent: 'space-between',
    gap: 12,
    paddingHorizontal: 4,
    paddingBottom: 8,
  },
  topDateCol: { flexShrink: 0 },
  topDay: { fontSize: 34, fontWeight: '500', letterSpacing: -0.3 },
  topSub: { fontSize: 14, fontWeight: '500', marginTop: 6, opacity: 0.92 },
  topTagsCol: { flex: 1, alignItems: 'flex-end' },
  topTagsScroll: { marginTop: 8, alignSelf: 'stretch' },
  topTagsRow: { gap: 6, justifyContent: 'flex-end' },
  topTagsEmpty: { fontSize: 12, fontWeight: '500', paddingVertical: 6 },
  metaRowTwo: { flexDirection: 'row', gap: 12 },
  metaRowThree: { flexDirection: 'row', gap: 12 },
  metaCard: {
    flex: 1,
    borderRadius: 22,
    padding: 16,
    minHeight: 86,
    borderWidth: StyleSheet.hairlineWidth,
  },
  metaLabel: {
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 1.6,
    textTransform: 'uppercase',
    marginBottom: 8,
    opacity: 0.7,
  },
  metaValue: { fontSize: 13, fontWeight: '600', lineHeight: 18 },
  metaTagsHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
  },
  metaTagsRow: { gap: 6, alignItems: 'center', paddingTop: 2 },
  metaEmpty: { fontSize: 12, fontWeight: '500', paddingVertical: 6 },
  dateHeroTitle: { fontSize: 22, fontWeight: '700', letterSpacing: -0.2 },
  dateHeroSub: { fontSize: 13, fontWeight: '500', marginTop: 6, opacity: 0.9 },
  // Date / time header (row: left=date col, right=tags scroll)
  dateHeader: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 10,
    paddingHorizontal: 4,
    paddingBottom: 2,
  },
  dateTextCol: { flexShrink: 0 },
  dateTagsScroll: { flex: 1, alignSelf: 'center' },
  dateTagsRow: { gap: 6, alignItems: 'center', paddingVertical: 2 },
  dayName: { fontSize: 26, fontWeight: '700', letterSpacing: -0.3 },
  dateSubRow: { flexDirection: 'row', alignItems: 'center', marginTop: 3 },
  dateSubText: { fontSize: 14 },
  dateSubSep: { fontSize: 14, opacity: 0.4 },
  // Specs card
  specsCard: { borderRadius: 14, padding: 16 },
  specsTopRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  specsText: { fontSize: 15, fontWeight: '600' },
  specsDivider: {
    height: StyleSheet.hairlineWidth,
    marginTop: 12,
    marginBottom: 10,
  },
  specsBadges: { flexDirection: 'row', gap: 8 },
  badge: { paddingHorizontal: 12, paddingVertical: 5, borderRadius: 999 },
  badgeText: { fontSize: 12, fontWeight: '600' },
  // 2-column info grid
  infoGrid: { flexDirection: 'row', gap: 10 },
  // Tags strip
  tagsRow: { paddingTop: 2, paddingBottom: 4, gap: 6, alignItems: 'center' },
  tagsBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    paddingHorizontal: 12,
    paddingVertical: 7,
    borderRadius: 999,
    borderWidth: 1,
  },
  tagsBtnText: { fontSize: 12, fontWeight: '600' },
  tagChip: { paddingHorizontal: 12, paddingVertical: 6, borderRadius: 999 },
  tagChipText: { fontSize: 12, fontWeight: '500' },
  tagDescSection: {
    marginTop: 4,
    borderRadius: 14,
    padding: 12,
    borderWidth: StyleSheet.hairlineWidth,
  },
  tagDescTitle: {
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 1.1,
    textTransform: 'uppercase',
    marginBottom: 8,
  },
  tagDescScroll: {
    maxHeight: 180,
  },
  tagDescContent: {
    paddingBottom: 2,
  },
  tagDescItem: {
    borderBottomWidth: StyleSheet.hairlineWidth,
    paddingVertical: 8,
  },
  tagDescName: {
    fontSize: 12,
    fontWeight: '700',
    marginBottom: 2,
  },
  tagDescText: {
    fontSize: 12,
    lineHeight: 17,
  },
});
