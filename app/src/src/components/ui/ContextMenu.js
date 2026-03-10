import React, { useEffect, useRef, useState } from 'react';
import {
  Animated,
  Easing,
  Modal,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  TouchableWithoutFeedback,
  View,
} from 'react-native';
import { useTheme } from '../../providers/ThemeProvider';
import { useAppContext } from '../../store/AppContext';
import { Icon } from './Icon';

/**
 * ContextMenu
 *
 * Centered popup menu — appears at the middle of the screen on long-press.
 * Theming-aware (dark / light). Backdrop tap dismisses.
 *
 * Props:
 *   visible       — boolean
 *   onClose       — () => void
 *   title         — optional string (file / folder name shown at top)
 *   items         — ContextMenuItem[]
 *
 * ContextMenuItem shape:
 *   {
 *     key           — string   — unique React key
 *     label         — string   — display text
 *     icon          — string   — Icon registry name (optional)
 *     destructive   — boolean  — renders in colors.error (red)
 *     dividerBefore — boolean  — hairline divider above this row
 *     onPress       — () => void
 *   }
 *
 * Tap backdrop or any item to close. No Cancel button needed.
 */
export function ContextMenu({ visible, onClose, title, items = [] }) {
  const { colors, isDark } = useTheme();
  const { state: appState } = useAppContext();
  const accentColor = appState.accentColor ?? null;

  // ── Enter / exit animation ────────────────────────────────────────────────
  const opacity = useRef(new Animated.Value(0)).current;
  const scale = useRef(new Animated.Value(0.88)).current;

  // `mountedVisible` keeps the Modal mounted during the slide-out animation.
  // Without it, `visible=false` instantly unmounts the Modal and the closing
  // animation never plays — the menu just snaps away.
  const [mountedVisible, setMountedVisible] = useState(false);

  useEffect(() => {
    if (visible) {
      // Reset to start position before mounting so interrupted closes never
      // leave stale animated values on the next open.
      opacity.setValue(0);
      scale.setValue(0.88);
      setMountedVisible(true);
      Animated.parallel([
        Animated.timing(opacity, {
          toValue: 1,
          duration: 160,
          easing: Easing.out(Easing.cubic),
          useNativeDriver: true,
        }),
        Animated.spring(scale, {
          toValue: 1,
          tension: 200,
          friction: 22,
          useNativeDriver: true,
        }),
      ]).start();
    } else {
      Animated.parallel([
        Animated.timing(opacity, {
          toValue: 0,
          duration: 110,
          easing: Easing.in(Easing.quad),
          useNativeDriver: true,
        }),
        Animated.timing(scale, {
          toValue: 0.88,
          duration: 110,
          easing: Easing.in(Easing.quad),
          useNativeDriver: true,
        }),
      ]).start(({ finished }) => { if (finished) setMountedVisible(false); });
    }
  }, [visible, opacity, scale]);

  const handleItemPress = item => {
    onClose();
    if (item.onPress) {
      setTimeout(item.onPress, 100);
    }
  };

  // Popup card: use themed card / border for day & night
  const cardBg = colors.card;
  const cardBorder = colors.border;
  const iconColor = item =>
    item.destructive ? colors.error : colors.textSecondary;
  const labelColor = item => (item.destructive ? colors.error : colors.text);

  return (
    <Modal
      visible={mountedVisible}
      transparent
      animationType="none"
      statusBarTranslucent
      onRequestClose={onClose}
    >
      {/* ── Full-screen tappable backdrop ──────────────────────────────── */}
      <TouchableWithoutFeedback onPress={onClose}>
        <Animated.View
          style={[
            styles.backdrop,
            { backgroundColor: colors.overlayStrong, opacity },
          ]}
        />
      </TouchableWithoutFeedback>

      {/* ── Centered popup card ────────────────────────────────────────── */}
      <View style={styles.centeredWrapper} pointerEvents="box-none">
        <Animated.View
          style={[
            styles.card,
            {
              backgroundColor: cardBg,
              borderColor: cardBorder,
              opacity,
              transform: [{ scale }],
              // Elevation / shadow
              shadowColor: colors.black,
              shadowOpacity: isDark ? 0.7 : 0.22,
              shadowRadius: 24,
              shadowOffset: { width: 0, height: 8 },
              elevation: 20,
            },
          ]}
        >
          {/* Optional title ─────────────────────────────────────────── */}
          {title ? (
            <>
              <View style={styles.titleRow}>
                <Text
                  style={[styles.titleText, { color: colors.textSecondary }]}
                  numberOfLines={1}
                >
                  {title}
                </Text>
              </View>
              <View style={[styles.divider, { backgroundColor: cardBorder }]} />
            </>
          ) : null}

          {/* Menu items ─────────────────────────────────────────────── */}
          <ScrollView bounces={false} showsVerticalScrollIndicator={false}>
            {items.map(item => (
              <React.Fragment key={item.key}>
                {item.dividerBefore && (
                  <View
                    style={[styles.divider, { backgroundColor: cardBorder }]}
                  />
                )}
                <TouchableOpacity
                  style={styles.row}
                  onPress={() => handleItemPress(item)}
                  activeOpacity={0.55}
                >
                  {item.icon ? (
                    <Icon
                      name={item.icon}
                      size={19}
                      color={iconColor(item)}
                      style={styles.rowIcon}
                    />
                  ) : (
                    <View style={styles.rowIconPlaceholder} />
                  )}
                  <Text style={[styles.rowLabel, { color: labelColor(item) }]}>
                    {item.label}
                  </Text>
                </TouchableOpacity>
              </React.Fragment>
            ))}
          </ScrollView>
        </Animated.View>
      </View>
    </Modal>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const CARD_WIDTH = 292;

const styles = StyleSheet.create({
  backdrop: {
    ...StyleSheet.absoluteFillObject,
  },
  centeredWrapper: {
    ...StyleSheet.absoluteFillObject,
    alignItems: 'center',
    justifyContent: 'center',
  },
  card: {
    width: CARD_WIDTH,
    borderRadius: 20,
    borderWidth: StyleSheet.hairlineWidth,
    overflow: 'hidden',
    maxHeight: '72%',
  },
  titleRow: {
    paddingHorizontal: 20,
    paddingVertical: 12,
  },
  titleText: {
    fontSize: 11,
    fontWeight: '600',
    letterSpacing: 0.5,
    textTransform: 'uppercase',
  },
  divider: {
    height: StyleSheet.hairlineWidth,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 20,
    height: 50,
  },
  rowIcon: {
    marginRight: 15,
    // opacity so monochrome icons don't compete with text weight
    opacity: 0.9,
  },
  rowIconPlaceholder: {
    width: 19,
    marginRight: 15,
  },
  rowLabel: {
    fontSize: 15,
    fontWeight: '400',
    letterSpacing: 0.1,
    flex: 1,
  },
});
