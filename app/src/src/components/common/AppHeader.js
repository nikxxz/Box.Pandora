import React, { useEffect, useRef, useMemo } from 'react';
import {
  Animated,
  Easing,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../../providers/ThemeProvider';
import { Icon } from '../ui/Icon';
import { CloseButton } from '../ui/CloseButton';

// ─── Character-cascade title animation ────────────────────────────────────────
//
// Each character fades in and slides up from ~12 px below, staggered by 28 ms.
// CascadeTitle is keyed by title in AnimatedTitle so it remounts (and therefore
// re-runs the entrance animation) whenever the title string changes.

function CascadeTitle({ title, textStyle }) {
  const chars = [...title];

  // Build one set of Animated.Values per character.  We intentionally do this
  // inside the ref initialiser so it only runs on mount (guaranteed fresh
  // because AnimatedTitle forces a remount on title change via key prop).
  const anims = useRef(
    chars.map(() => ({
      opacity: new Animated.Value(0),
      translateY: new Animated.Value(12),
    })),
  ).current;

  useEffect(() => {
    const staggered = chars.map((_, i) =>
      Animated.parallel([
        Animated.timing(anims[i].opacity, {
          toValue: 1,
          duration: 260,
          easing: Easing.out(Easing.quad),
          useNativeDriver: true,
        }),
        Animated.timing(anims[i].translateY, {
          toValue: 0,
          duration: 260,
          easing: Easing.out(Easing.cubic),
          useNativeDriver: true,
        }),
      ]),
    );
    // Each character starts 28 ms after the one before it
    Animated.stagger(28, staggered).start();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <View style={styles.titleRow}>
      {chars.map((char, i) => (
        <Animated.Text
          key={i}
          style={[
            textStyle,
            {
              opacity: anims[i].opacity,
              transform: [{ translateY: anims[i].translateY }],
            },
          ]}
        >
          {char}
        </Animated.Text>
      ))}
    </View>
  );
}

/** Remounts CascadeTitle (triggering a fresh animation) on every title change. */
function AnimatedTitle({ title, textStyle }) {
  return <CascadeTitle key={title} title={title} textStyle={textStyle} />;
}

// ─── AppHeader ────────────────────────────────────────────────────────────────
//
// Props
//   title           — string displayed with the cascade animation
//   showBack        — render a ‹ back button at the far left
//   onBackPress     — callback for the back button
//   onSearchPress   — callback to activate search mode
//   onSettingsPress — callback for the settings icon
//   rightElement    — completely override the right-side icons with custom JSX
//                     (used by FolderScreen / FavoritesScreen selection mode)
//   searchActive    — bool, whether the search bar is currently open
//   searchQuery     — current text in the search input
//   onSearchChange  — fn(text) called on every keystroke
//   onSearchClose   — fn() called when the close (×) button is pressed

export function AppHeader({
  title = 'pandora',
  showBack = false,
  onBackPress,
  onSearchPress,
  onSettingsPress,
  rightElement,
  // ── Search ──────────────────────────────────────────────────────────────
  searchActive = false,
  searchQuery = '',
  onSearchChange,
  onSearchClose,
}) {
  const insets = useSafeAreaInsets();
  const { colors } = useTheme();

  const titleStyle = [styles.titleText, { color: colors.text }];
  const inputRef = useRef(null);

  // When rightElement is provided (selection mode) we never enter visual search
  // mode even if searchActive is somehow still true.
  const isSearchMode = searchActive && !rightElement;

  // ── Master animation: 0 = normal, 1 = search ──────────────────────────────
  const anim = useRef(new Animated.Value(searchActive ? 1 : 0)).current;

  useEffect(() => {
    Animated.timing(anim, {
      toValue: searchActive ? 1 : 0,
      duration: 230,
      easing: Easing.out(Easing.cubic),
      useNativeDriver: true,
    }).start(({ finished }) => {
      // Auto-focus the text input once the slide-in animation completes
      if (finished && searchActive) {
        inputRef.current?.focus();
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchActive]);

  // ── Derived animated values ────────────────────────────────────────────────

  // Title slides left and fades out
  const titleOpacity = anim.interpolate({
    inputRange: [0, 0.45],
    outputRange: [1, 0],
    extrapolate: 'clamp',
  });
  const titleTranslateX = anim.interpolate({
    inputRange: [0, 1],
    outputRange: [0, -28],
    extrapolate: 'clamp',
  });

  // Normal right icons slide right and fade out
  const iconsOpacity = anim.interpolate({
    inputRange: [0, 0.45],
    outputRange: [1, 0],
    extrapolate: 'clamp',
  });
  const iconsTranslateX = anim.interpolate({
    inputRange: [0, 1],
    outputRange: [0, 28],
    extrapolate: 'clamp',
  });

  // Search input + close button slide in from right and fade in
  const searchOpacity = anim.interpolate({
    inputRange: [0.4, 1],
    outputRange: [0, 1],
    extrapolate: 'clamp',
  });
  const searchTranslateX = anim.interpolate({
    inputRange: [0, 1],
    outputRange: [22, 0],
    extrapolate: 'clamp',
  });

  return (
    <View
      style={[
        styles.container,
        {
          paddingTop: insets.top + 10,
          backgroundColor: colors.background,
        },
      ]}
    >
      {/* ── Far-left: back button (FolderScreen only) ─────────────────── */}
      {showBack && (
        <TouchableOpacity
          onPress={onBackPress}
          style={styles.backBtn}
          hitSlop={{ top: 12, right: 8, bottom: 12, left: 12 }}
          activeOpacity={0.6}
        >
          <Icon name="back" size={26} color={colors.text} />
        </TouchableOpacity>
      )}

      {/* ── Center: title slides out left / search input slides in ─────── */}
      <View style={styles.centerArea}>
        {/* Title stays in normal flow to define the container height */}
        <Animated.View
          style={{
            opacity: titleOpacity,
            transform: [{ translateX: titleTranslateX }],
          }}
          pointerEvents={isSearchMode ? 'none' : 'auto'}
        >
          <AnimatedTitle title={title} textStyle={titleStyle} />
        </Animated.View>

        {/* Search input is absolutely overlaid over the title */}
        <Animated.View
          style={[
            StyleSheet.absoluteFill,
            styles.searchInputWrapper,
            {
              opacity: searchOpacity,
              transform: [{ translateX: searchTranslateX }],
            },
          ]}
          pointerEvents={isSearchMode ? 'auto' : 'none'}
        >
          <TextInput
            ref={inputRef}
            style={[styles.searchInput, { color: colors.text }]}
            placeholder="Search files…"
            placeholderTextColor={colors.textTertiary}
            value={searchQuery}
            onChangeText={onSearchChange}
            autoCapitalize="none"
            autoCorrect={false}
            returnKeyType="search"
          />
        </Animated.View>
      </View>

      {/* ── Far-right: icons (or custom override) ─────────────────────── */}
      {rightElement ?? (
        <View style={styles.rightArea}>
          {/* Normal mode: search + settings — slide right on search open */}
          <Animated.View
            style={[
              styles.rightActions,
              {
                opacity: iconsOpacity,
                transform: [{ translateX: iconsTranslateX }],
              },
            ]}
            pointerEvents={isSearchMode ? 'none' : 'auto'}
          >
            {onSearchPress && (
              <TouchableOpacity
                onPress={onSearchPress}
                style={styles.iconBtn}
                hitSlop={{ top: 12, right: 8, bottom: 12, left: 8 }}
                activeOpacity={0.6}
              >
                <Icon name="search" size={22} color={colors.text} />
              </TouchableOpacity>
            )}
            <TouchableOpacity
              onPress={onSettingsPress}
              style={styles.iconBtn}
              hitSlop={{ top: 12, right: 12, bottom: 12, left: 8 }}
              activeOpacity={0.6}
            >
              <Icon name="sidebar" size={26} color={colors.text} />
            </TouchableOpacity>
          </Animated.View>

          {/* Search mode: static search icon + close button — slide in */}
          <Animated.View
            style={[
              StyleSheet.absoluteFill,
              styles.searchActions,
              {
                opacity: searchOpacity,
                transform: [{ translateX: searchTranslateX }],
              },
            ]}
            pointerEvents={isSearchMode ? 'auto' : 'none'}
          >
            <Icon name="search" size={20} color={colors.textSecondary} />
            <CloseButton
              onPress={() => {
                inputRef.current?.blur();
                onSearchClose?.();
              }}
              style={styles.iconBtn}
              size={14}
              color={colors.text}
            />
          </Animated.View>
        </View>
      )}
    </View>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    paddingBottom: 14,
  },
  // Back button — only present in FolderScreen
  backBtn: {
    marginRight: 14,
    paddingVertical: 2,
    alignItems: 'flex-start',
    justifyContent: 'center',
  },
  // Growing center area — holds title (flow) and search input (absolute overlay)
  centerArea: {
    flex: 1,
    overflow: 'hidden',
    justifyContent: 'flex-end',
  },
  // Inline flex row — one Animated.Text per character
  titleRow: {
    flexDirection: 'row',
    alignItems: 'flex-end',
  },
  titleText: {
    fontSize: 34,
    fontWeight: '200',
    letterSpacing: 4,
  },
  // Search input — absolutely fills centerArea, vertically centred
  searchInputWrapper: {
    justifyContent: 'center',
  },
  searchInput: {
    fontSize: 20,
    fontWeight: '300',
    letterSpacing: 0.3,
    paddingVertical: 2,
    paddingHorizontal: 0,
  },
  // Outer fixed-size container holding BOTH icon sets (stacked via absolute)
  rightArea: {
    position: 'relative',
    flexDirection: 'row',
    alignItems: 'center',
  },
  // Normal mode — search icon + settings icon
  rightActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  // Search mode — decorative search icon + close button; fills rightArea
  searchActions: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
    gap: 4,
  },
  iconBtn: {
    padding: 8,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
  },
  searchCloseText: {
    fontSize: 16,
    fontWeight: '700',
    lineHeight: 20,
  },
});
