import React, { memo, useRef, useCallback } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  Animated,
  Platform,
} from 'react-native';
import { Image } from 'expo-image';
import Svg, { Path } from 'react-native-svg';
import { DIMENSIONS } from '../../constants/dimensions';
import { formatCount, formatShortDateParts } from '../../utils/formatters';
import { useTheme } from '../../providers/ThemeProvider';

/**
 * FolderCard
 *
 * Displays a single album/folder tile in the home grid:
 *  • Full-bleed cover thumbnail
 *  • SVG folder-tab panel — shape derived 1-to-1 from folder.svg
 *    (Adobe Illustrator export, 2200×2200 viewBox)
 *
 * How the notch was extracted from folder.svg (inner flap path):
 *   Card width  = 1431.875 SVG units  (x 384.063 → 1815.938)
 *   Tab top     = y 844.289           (front flap top edge)
 *   Panel top   = y 1042.044          (lower body top edge)
 *   TAB_LIFT    = 197.755 = 20.4 % of panel height
 *
 *   Notch runs LTR — a cubic + diagonal line + cubic:
 *     Start  (tab top-right)  → 36.2 % of card width
 *     Cubic A  C1=(38.3%, 0 %)  C2=(40.2%, 6.3 %)  end=(41.6 %, 17.4 %)
 *     Line                   → (49.8 %, 82.6 %)
 *     Cubic B  C1=(51.2%,93.7%) C2=(53.2%,100%)   end=(55.2%, 100 %)
 *   After the notch the panel top is flat to the right edge (100 %).
 *
 * Wrapped in React.memo to avoid re-renders when sibling cards update.
 */

const { cardWidth, cardHeight, cardBorderRadius, gridRowGap } = DIMENSIONS;

// ── Panel geometry ──────────────────────────────────────────────────────────
const PANEL_H = Math.round(cardHeight * 0.52); // ~52 % of card
const TAB_LIFT = Math.max(16, Math.round(PANEL_H * 0.204)); // 20.4 % of panel (from SVG)
const CONTAINER_H = PANEL_H + TAB_LIFT;
const TAB_R = 10; // top-left corner radius

// Pre-compute notch control points from folder.svg proportions
const W = cardWidth;
const T = TAB_LIFT;

const buildPath = () =>
  [
    `M ${TAB_R} 0`,
    `L ${W * 0.362} 0`,
    // Cubic A — gentle departure rightward and downward (matches SVG inner-path)
    `C ${W * 0.383} 0 ${W * 0.402} ${T * 0.063} ${W * 0.416} ${T * 0.174}`,
    // Diagonal line — the straight middle section of the folder notch
    `L ${W * 0.498} ${T * 0.826}`,
    // Cubic B — smooth arrival at panel-top level
    `C ${W * 0.512} ${T * 0.937} ${W * 0.532} ${T} ${W * 0.552} ${T}`,
    // Panel flat top continues to right edge
    `L ${W} ${T}`,
    `L ${W} ${CONTAINER_H}`,
    `L 0 ${CONTAINER_H}`,
    `L 0 ${TAB_R}`,
    // Rounded top-left corner on the tab
    `A ${TAB_R} ${TAB_R} 0 0 1 ${TAB_R} 0`,
    'Z',
  ].join(' ');

const PANEL_PATH = buildPath();
// ────────────────────────────────────────────────────────────────────────────

// On Android, elevation-based shadows don't need overflow:visible, so we can
// safely clip the wrapper to hide the image bleed at rounded corners.
// On iOS, overflow must stay 'visible' so shadow* props render outside the bounds.
const WRAPPER_OVERFLOW = Platform.OS === 'android' ? 'hidden' : 'visible';

// ── Text & layout metrics — all proportional to card width ──────────────────
// Reference device: cardWidth ≈ 179 px on a 390-pt screen.
// Ratios are W-relative so every device gets the same visual proportion.
const INSET_SIDE = Math.round(W * 0.078); // left/right body padding  (~14 px)
const INSET_NOTCH = Math.round(W * 0.089); // notch-text left offset   (~16 px)
const BOTTOM_PAD = Math.round(W * 0.045); // bottom-row bottom gap    ( ~8 px)
const FS_NAME = Math.max(10, Math.round(W * 0.067)); // notch date label    (~12 px)
const LH_NAME = Math.max(13, Math.round(W * 0.089)); // name line-height    (~16 px)
const FS_FOLDER = Math.max(14, Math.round(W * 0.1)); // folder name label   (~18 px)
const FS_COUNT = Math.max(8, Math.round(W * 0.056)); // item-count badge    (~10 px)
const FS_COUNT_SUB = Math.max(7, Math.round(W * 0.05)); // sub-count text      ( ~9 px)
const LH_COUNT_SUB = Math.max(10, Math.round(W * 0.078)); // sub-count line-h    (~14 px)
const FS_BADGE = Math.max(7, Math.round(W * 0.05)); // HIDDEN badge text   ( ~9 px)
const BADGE_INSET = Math.round(W * 0.045); // badge top/right offset   ( ~8 px)
const BADGE_PAD_H = Math.round(W * 0.034); // badge horizontal padding ( ~6 px)
const BADGE_PAD_V = Math.round(W * 0.017); // badge vertical padding   ( ~3 px)
// ────────────────────────────────────────────────────────────────────────────

export const FolderCard = memo(function FolderCard({
  folder,
  onPress,
  onLongPress,
  isHidden = false,
  accentColor: accentColorProp,
}) {
  const { colors, isDark } = useTheme();
  // Accept accentColor as a prop so this component does not subscribe to
  // AppContext. With hook-based context reads, React.memo has no effect on
  // preventing re-renders triggered by context changes.
  const accentColor = accentColorProp ?? null;
  const { name, count, displayCount, coverUri, coverUriKey, lastModified } =
    folder;
  const shownCount = displayCount ?? count;

  // Card colors — sourced from theme constants (theme/colors.js)
  const panelColor = colors.cardPanel;
  const borderColor = colors.cardBorder;
  const textPrimary = colors.cardPanelText;
  const textDim = colors.cardPanelTextDim;
  // Accent-aware dim: uses accent when set, otherwise falls back to theme dim
  const accentDim = accentColor ?? textDim;

  // Notch date label — month+day for current year, relative label for older
  const dateParts = formatShortDateParts(lastModified);
  const currentYear = new Date().getFullYear();
  const itemYear = dateParts.year ? parseInt(dateParts.year, 10) : currentYear;
  const yearDiff = currentYear - itemYear;
  const isSameYear = yearDiff === 0;
  // Relative label for past years: "last year" / "2 years ago" / …
  const relativeYearLabel =
    yearDiff === 1 ? 'last year' : `${yearDiff} years ago`;

  const shadowColor = colors.black;
  const shadowOpacity = isDark ? 0.55 : 0.18;
  const shadowRadius = isDark ? 8 : 14;
  const elevation = isDark ? 5 : 10;

  const scale = useRef(new Animated.Value(1)).current;

  const handlePressIn = useCallback(() => {
    Animated.spring(scale, {
      toValue: 0.97,
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

  return (
    // Outer wrapper carries the shadow + border (overflow visible so shadow renders on iOS)
    <Animated.View
      style={[
        styles.shadowWrapper,
        {
          borderColor,
          backgroundColor: colors.card,
          shadowColor,
          shadowOpacity,
          shadowRadius,
          elevation,
        },
        { transform: [{ scale }] },
      ]}
    >
      {/* Inner touchable clips the image/SVG content */}
      <TouchableOpacity
        style={[styles.card, { backgroundColor: colors.card }]}
        onPress={() => onPress?.(folder)}
        onLongPress={() => onLongPress?.(folder)}
        onPressIn={handlePressIn}
        onPressOut={handlePressOut}
        delayLongPress={350}
        activeOpacity={0.88}
      >
        {coverUri ? (
          <Image
            source={{ uri: coverUri }}
            style={[StyleSheet.absoluteFill, styles.coverImage]}
            contentFit="cover"
            blurRadius={5.4}
            cachePolicy="memory-disk"
            recyclingKey={coverUriKey ?? coverUri}
            transition={200}
          />
        ) : (
          <View
            style={[
              StyleSheet.absoluteFill,
              styles.placeholder,
              { backgroundColor: colors.surface },
            ]}
          />
        )}

        {/* SVG folder-tab shape — theme-aware fill, no touch events */}
        <View style={styles.svgContainer} pointerEvents="none">
          <Svg width={cardWidth} height={CONTAINER_H}>
            <Path d={PANEL_PATH} fill={panelColor} />
          </Svg>
        </View>

        {/* Name + count subtitle — inside the raised tab/notch, left side */}
        <View style={styles.notchText} pointerEvents="none">
          {isSameYear ? (
            <Text
              style={[styles.name, { color: textPrimary }]}
              numberOfLines={1}
              allowFontScaling={false}
            >
              <Text style={{ color: accentDim }} allowFontScaling={false}>
                {dateParts.month}
              </Text>
              {' ' + dateParts.day}
            </Text>
          ) : (
            <Text
              style={[styles.name, { color: textDim }]}
              numberOfLines={1}
              allowFontScaling={false}
            >
              {relativeYearLabel}
            </Text>
          )}
        </View>

        {/* Bottom row: "Feb 25" bottom-left, "2020" bottom-right */}
        <View style={styles.bottomRow} pointerEvents="none">
          <View style={styles.dateBlock}>
            <Text style={[styles.dateMonth, { color: textPrimary }]}>
              {name}
            </Text>
            <Text style={[styles.dateDay, { color: textPrimary }]} />
          </View>
          <Text style={[styles.dateYear, { color: accentDim }]}>
            {formatCount(shownCount)}
          </Text>
        </View>

        {/* HIDDEN badge — only visible when folder is hidden and showHidden is on */}
        {isHidden && (
          <View style={styles.hiddenBadge} pointerEvents="none">
            <Text style={styles.hiddenBadgeText}>HIDDEN</Text>
          </View>
        )}
      </TouchableOpacity>
    </Animated.View>
  );
});

const styles = StyleSheet.create({
  // Shadow wrapper — overflow visible only on iOS (needed for shadow* props).
  // On Android elevation handles the shadow and overflow:hidden clips image bleed.
  shadowWrapper: {
    width: cardWidth,
    height: cardHeight,
    borderRadius: cardBorderRadius,
    borderWidth: 5,
    overflow: WRAPPER_OVERFLOW,
    shadowOffset: { width: 0, height: 4 },
    // Screen-height-derived row gap so cards breathe on every device
    marginBottom: gridRowGap,
  },
  // Cover image — explicit borderRadius so Android clips corners without relying on parent overflow
  coverImage: {
    borderRadius: cardBorderRadius - 5,
  },
  // Inner card — overflow hidden to clip image + SVG
  card: {
    flex: 1,
    borderRadius: cardBorderRadius - 5, // slightly tighter than wrapper
    overflow: 'hidden',
    backgroundColor: '#1C1C1C',
  },
  placeholder: {
    backgroundColor: '#1A1A1A',
  },
  // Anchors the SVG to the bottom of the card
  svgContainer: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    width: cardWidth,
    height: CONTAINER_H,
  },
  // Name + count subtitle — position freely adjustable
  notchText: {
    position: 'absolute',
    bottom: PANEL_H,
    left: INSET_NOTCH,
    width: Math.round(W * 0.34),
    height: TAB_LIFT,
    justifyContent: 'center',
  },
  name: {
    color: '#FFFFFF',
    fontSize: FS_NAME,
    fontWeight: '400',
    lineHeight: LH_NAME,
    includeFontPadding: false,
  },
  countSub: {
    color: 'rgba(255,255,255,0.40)',
    fontSize: FS_COUNT_SUB,
    fontWeight: '400',
    lineHeight: LH_COUNT_SUB,
  },
  // Bottom row — folder name left, item count right
  bottomRow: {
    position: 'absolute',
    bottom: BOTTOM_PAD,
    left: INSET_SIDE,
    right: INSET_SIDE,
    flexDirection: 'row',
    alignItems: 'baseline',
    justifyContent: 'space-between',
  },
  // Left side of bottom row: folder name
  dateBlock: {
    flexDirection: 'row',
    alignItems: 'baseline',
    gap: 2,
  },
  dateMonth: {
    color: '#FFFFFF',
    fontSize: FS_FOLDER,
    fontWeight: '400',
  },
  dateDay: {
    color: '#FFFFFF',
    fontSize: FS_FOLDER,
    fontWeight: '600',
  },
  dateYear: {
    color: 'rgba(255,255,255,0.40)',
    fontSize: FS_COUNT,
    fontWeight: '400',
  },
  hiddenBadge: {
    position: 'absolute',
    top: BADGE_INSET,
    right: BADGE_INSET,
    backgroundColor: 'rgba(0,0,0,0.72)',
    paddingHorizontal: BADGE_PAD_H,
    paddingVertical: BADGE_PAD_V,
  },
  hiddenBadgeText: {
    color: '#FFFFFF',
    fontSize: FS_BADGE,
    fontWeight: '700',
    letterSpacing: 0.8,
  },
});
