import React, { memo, useCallback, useMemo, useRef } from 'react';
import {
  View,
  TouchableOpacity,
  Text,
  StyleSheet,
  Animated,
} from 'react-native';
import { Image } from 'expo-image';
import { DIMENSIONS } from '../../constants/dimensions';
import { formatDuration } from '../../utils/formatters';
import { Icon } from '../ui/Icon';

function hexToRgba(hex, alpha) {
  if (typeof hex !== 'string') return null;
  const normalized = hex.trim();
  if (!/^#[0-9a-fA-F]{6}$/.test(normalized)) return null;
  const r = parseInt(normalized.slice(1, 3), 16);
  const g = parseInt(normalized.slice(3, 5), 16);
  const b = parseInt(normalized.slice(5, 7), 16);
  const a = Math.max(0, Math.min(1, alpha));
  return `rgba(${r},${g},${b},${a})`;
}

// expo-image native fade-in transition.
// Only used for the initial image load (grey → image). Disabled when the
// source is a thumbUri swap (full-res → thumb) to avoid the visible flash.
const IMAGE_TRANSITION = {
  duration: 80,
  effect: 'cross-dissolve',
  timing: 'ease-in-out',
};

// No transition — used when the item already has a thumbUri on first render
// (cached from a previous visit). The image is already in the native cache so
// a cross-dissolve would only cause a pointless blink.
const NO_TRANSITION = null;

/**
 * MediaThumbnail
 *
 * A single image / video cell inside a media grid.
 *  • Supports long-press for multi-select
 *  • Shows a ▶ badge + duration for videos
 *  • Shows a ★ star badge at top-left for favorited items
 *
 * Loading placeholder strategy:
 *  • The container has backgroundColor = colors.surface (theme grey).
 *  • expo-image renders transparently while decoding, revealing that grey
 *    background — no JS state or shimmer overlay needed.
 *  • Once decoded, expo-image cross-dissolves the image in natively (200 ms).
 *  • recyclingKey={item.uri} resets expo-image on cell reuse so the transition
 *    always starts clean for the incoming image.
 */
/**
 * Inner component — pure props, no context subscriptions.
 * Wrapped by the exported MediaThumbnail which provides backwards-compat
 * context fallbacks for screens that don't pass colors/accent.
 */
const MediaThumbnailInner = memo(function MediaThumbnailInner({
  item,
  onPress,
  onLongPress,
  isSelected = false,
  isFavorite = false,
  isHidden = false,
  size = DIMENSIONS.thumbSize,
  colors,
  accent,
}) {
  const isVideo = item.type?.includes('video');

  const selectionOverlayColor = useMemo(
    () => hexToRgba(accent, 0.35) ?? colors.focused ?? colors.pressed,
    [accent, colors.focused, colors.pressed],
  );

  // Uses the pre-sized thumbnail URI on Android (compact JPEG from
  // cacheDir/thumbnails/ via MediaStoreModule.getThumbnailUri).
  // Falls back to the full-res content URI on iOS or before the thumb cache warms.
  const imageSource = useMemo(
    () => ({ uri: item.thumbUri || item.uri }),
    [item.thumbUri, item.uri],
  );

  // Track whether this cell has already rendered an image. If yes, use
  // NO_TRANSITION for subsequent URI changes (thumb swap) to avoid the
  // visible flash when full-res → thumbnail or vice-versa.
  const hasRenderedRef = useRef(false);
  const transition = useMemo(() => {
    if (hasRenderedRef.current) return NO_TRANSITION;
    // First render: if item already has a thumbUri, it's from cache and
    // will decode instantly — skip transition. Otherwise use a short
    // cross-dissolve for the grey → image reveal.
    if (item.thumbUri) {
      hasRenderedRef.current = true;
      return NO_TRANSITION;
    }
    hasRenderedRef.current = true;
    return IMAGE_TRANSITION;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const containerSizeStyle = useMemo(
    () => ({ width: size, height: size, backgroundColor: colors.surface }),
    [size, colors.surface],
  );

  const scale = useRef(new Animated.Value(1)).current;

  // Stable callbacks — avoid allocating a new closure on every render
  const handlePress = useCallback(() => onPress?.(item), [onPress, item]);
  const handleLongPress = useCallback(
    () => onLongPress?.(item),
    [onLongPress, item],
  );

  const handlePressIn = useCallback(() => {
    Animated.spring(scale, {
      toValue: 0.96,
      tension: 220,
      friction: 18,
      useNativeDriver: true,
    }).start();
  }, [scale]);

  const handlePressOut = useCallback(() => {
    Animated.spring(scale, {
      toValue: 1,
      tension: 220,
      friction: 18,
      useNativeDriver: true,
    }).start();
  }, [scale]);

  // ─── Null-URI placeholder cells ─────────────────────────────────────────────
  // Pre-filled by FolderScreen from the SQLite album count before CameraRoll
  // data arrives. Static grey tile — no animation needed since thumb resolution
  // happens in loadAlbumMedia before items reach the grid on subsequent pages.
  if (!item.uri) {
    return <View style={[styles.container, containerSizeStyle]} />;
  }

  return (
    <Animated.View
      style={[styles.container, containerSizeStyle, { transform: [{ scale }] }]}
    >
      <TouchableOpacity
        onPress={handlePress}
        onLongPress={handleLongPress}
        onPressIn={handlePressIn}
        onPressOut={handlePressOut}
        activeOpacity={0.85}
        style={StyleSheet.absoluteFill}
      >
        {/* expo-image decodes natively and cross-dissolves in via transition.
            While decoding, the container's grey backgroundColor shows through.
            recyclingKey resets expo-image on cell recycle so the transition
            never cross-fades a stale image into the new one. */}
        <Image
          source={imageSource}
          style={StyleSheet.absoluteFill}
          contentFit="cover"
          cachePolicy="memory-disk"
          recyclingKey={item.uri}
          transition={transition}
        />

        {/* Video indicator */}
        {isVideo && (
          <View
            style={[
              styles.videoBadge,
              { backgroundColor: colors.overlayStrong },
            ]}
          >
            <Text style={[styles.videoIcon, { color: colors.white }]}>▶</Text>
            {item.duration ? (
              <Text style={[styles.videoDuration, { color: colors.white }]}>
                {formatDuration(item.duration)}
              </Text>
            ) : null}
          </View>
        )}

        {/* Favourite star badge */}
        {isFavorite && (
          <View style={styles.starBadge}>
            <Icon name="starYellow" size={20} />
          </View>
        )}

        {/* HIDDEN badge */}
        {isHidden && (
          <View
            style={[
              styles.hiddenBadge,
              { backgroundColor: colors.overlayStrong },
            ]}
          >
            <Text style={[styles.hiddenBadgeText, { color: colors.white }]}>
              HIDDEN
            </Text>
          </View>
        )}

        {/* Multi-select overlay */}
        {isSelected && (
          <View
            style={[
              StyleSheet.absoluteFill,
              styles.selectedOverlay,
              { backgroundColor: selectionOverlayColor },
            ]}
          />
        )}
        {isSelected && (
          <Icon name="checked" size={26} color={colors.white} style={styles.checkmark} />
        )}
      </TouchableOpacity>
    </Animated.View>
  );
});

const styles = StyleSheet.create({
  container: {
    overflow: 'hidden',
  },
  videoBadge: {
    position: 'absolute',
    bottom: 5,
    right: 5,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 3,
    borderRadius: 4,
    paddingHorizontal: 5,
    paddingVertical: 2,
  },
  videoIcon: {
    fontSize: 9,
  },
  videoDuration: {
    fontSize: 10,
    fontWeight: '600',
  },
  starBadge: {
    position: 'absolute',
    top: 5,
    left: 5,
  },
  hiddenBadge: {
    position: 'absolute',
    top: 5,
    right: 5,
    paddingHorizontal: 5,
    paddingVertical: 2,
  },
  hiddenBadgeText: {
    fontSize: 8,
    fontWeight: '700',
    letterSpacing: 0.8,
  },
  selectedOverlay: {},
  checkmark: {
    position: 'absolute',
    top: 5,
    right: 5,
  },
});

// ─── Backwards-compat wrapper ────────────────────────────────────────────────
// Screens that don't pass `colors` / `accent` as props (e.g. TagGalleryScreen,
// FavoritesScreen) fall through to context here — but the context subscription
// lives in this thin wrapper, NOT inside the memoised inner component. When
// `colors` and `accent` are provided as props (FolderScreen), the context hooks
// are still called but their values are never forwarded, so changes to context
// won't cause the inner component to re-render thanks to `React.memo`.
import { useTheme } from '../../providers/ThemeProvider';
import { useAppContext } from '../../store/AppContext';

export const MediaThumbnail = memo(function MediaThumbnail(props) {
  const themeCtx = useTheme();
  const appCtx = useAppContext();
  const colors = props.colors ?? themeCtx.colors;
  const accent =
    props.accent ?? appCtx.state.accentColor ?? themeCtx.colors.accent;
  return <MediaThumbnailInner {...props} colors={colors} accent={accent} />;
});
