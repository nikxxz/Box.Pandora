import React, { useEffect, useRef, useState } from 'react';
import { View, Animated, StyleSheet } from 'react-native';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { HomeScreen } from '../screens/HomeScreen';
import { FavoritesScreen } from '../screens/FavoritesScreen';
import { TagIntelligenceScreen } from '../screens/TagIntelligenceScreen';
import { TagsScreen } from '../screens/TagsScreen';
import { useTheme } from '../providers/ThemeProvider';
import { useAppContext } from '../store/AppContext';
import { DIMENSIONS } from '../constants/dimensions';

const Tab = createBottomTabNavigator();

// ─── Staggered startup delay per tab (async flickering) ───────────────────────

const STARTUP_DELAYS = {
  Folders: 0,
  AIGallery: 380,
  Tags: 760,
};

// ─── Shared flicker frame data ────────────────────────────────────────────────

// Phase 1: rapid chaotic (~1.4 s)
const RAPID_FRAMES = [
  [1, 30],
  [0, 25],
  [1, 40],
  [0, 20],
  [1, 25],
  [0, 35],
  [1, 30],
  [0, 20],
  [1, 45],
  [0, 30],
  [1, 20],
  [0, 25],
  [1, 35],
  [0, 40],
  [1, 25],
  [0, 20],
  [1, 30],
  [0, 25],
  [1, 40],
  [0, 30],
  [1, 25],
  [0, 20],
  [1, 35],
  [0, 25],
  [1, 30],
  [0, 20],
  [1, 40],
  [0, 25],
  [1, 30],
  [0, 20],
];

// Phase 2: warming up — durations lengthen (~1.2 s)
const WARM_FRAMES = [
  [1, 80],
  [0, 55],
  [1, 110],
  [0, 45],
  [1, 130],
  [0, 65],
  [1, 160],
  [0, 80],
  [1, 200],
  [0, 90],
];

// Phase 3: burst — speeds up again nearing the end of the cycle
const BURST_FRAMES = [
  [0, 18],
  [1, 14],
  [0, 10],
  [1, 12],
  [0, 8],
  [1, 14],
  [0, 10],
  [1, 10],
  [0, 6],
  [1, 8],
];

// Active tab full sequence:  RAPID(~860ms) + WARM(~1015ms) + BURST(~110ms) + settle(450ms) ≈ 2435ms
// Inactive tab short sequence: RAPID + first-8 WARM frames (~1585ms) + settle(300ms) ≈ 1885ms
// → inactive tabs go dark ~550ms before the active tab settles
const INACTIVE_FLICKER_FRAMES = [...RAPID_FRAMES, ...WARM_FRAMES.slice(0, 8)];

function buildFlickerSteps(anim, frames) {
  return frames.map(([val, dur]) =>
    Animated.timing(anim, {
      toValue: val,
      duration: dur,
      useNativeDriver: true, // opacity is native-driveable; shadowRadius removed
    }),
  );
}

// ─── Tab text label ────────────────────────────────────────────────────────────

const TAB_LABELS = {
  Folders: 'FOLDERS',
  AIGallery: 'AI GALLERY',
  Tags: 'TAGS',
};

// accentColor is now passed as a prop from BottomTabNavigator so each
// TabLabel no longer holds its own AppContext subscription. Three independent
// subscriptions were causing all three tabs to re-render on every global state
// change (mediaFilter toggle, sort change, favorites update, etc.).
function TabLabel({ name, focused, accentColor }) {
  const { colors } = useTheme();

  // Active colour: custom accent overrides theme token
  const activeColor = accentColor ?? colors.tabActive;

  const label = TAB_LABELS[name] ?? name.toUpperCase();
  const pulseAnim = useRef(new Animated.Value(0)).current;

  // Guards: track whether startup already ran so focus-change effect only
  // fires on actual navigation, not during the initial startup animation.
  const hasRunStartup = useRef(false);
  // When false, all tabs (including unfocused) render with the animated style
  // so the startup flicker is visible on every tab.
  const [startupDone, setStartupDone] = useState(false);

  // Keep a ref to the current focused value so the startup timer can read
  // it at the moment it fires (not stale closure).
  const focusedRef = useRef(focused);
  useEffect(() => {
    focusedRef.current = focused;
  }, [focused]);

  // ── Startup: all tabs flicker once on mount, staggered by tab name ───────
  useEffect(() => {
    const delay = STARTUP_DELAYS[name] ?? 0;
    const timer = setTimeout(() => {
      hasRunStartup.current = true;
      pulseAnim.setValue(0);

      const isFocused = focusedRef.current;
      const frames = isFocused
        ? [...RAPID_FRAMES, ...WARM_FRAMES, ...BURST_FRAMES]
        : INACTIVE_FLICKER_FRAMES;
      const flickerSteps = buildFlickerSteps(pulseAnim, frames);

      const settle = Animated.timing(pulseAnim, {
        // Inactive tabs settle at 1.0 → interpolates to opacity 1.0 (fully
        // visible). Active tabs settle at 0.5 → opacity 0.7 (glow dim).
        // Using 1.0 for inactive avoids the JS-thread style-object switch
        // from `opacity: glowOpacity` → no opacity that caused a visible
        // flash when startupDone flipped.
        toValue: isFocused ? 0.5 : 1,
        duration: isFocused ? 450 : 300,
        useNativeDriver: true,
      });

      Animated.sequence([...flickerSteps, settle]).start(result => {
        if (result?.finished) setStartupDone(true);
      });
    }, delay);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── Focus-change animation (user navigating between tabs) ────────────────
  useEffect(() => {
    // Skip during startup — the timer above owns the animation at that point
    if (!hasRunStartup.current) return;

    if (focused) {
      pulseAnim.setValue(0);
      const flickerSteps = buildFlickerSteps(pulseAnim, [
        ...RAPID_FRAMES,
        ...WARM_FRAMES,
        ...BURST_FRAMES,
      ]);
      const settle = Animated.timing(pulseAnim, {
        toValue: 0.5,
        duration: 450,
        useNativeDriver: true,
      });
      Animated.sequence([...flickerSteps, settle]).start();
    } else {
      pulseAnim.stopAnimation();
      Animated.timing(pulseAnim, {
        toValue: 1, // 1.0 → opacity 1.0 (full visibility, no flash)
        duration: 200,
        useNativeDriver: true,
      }).start();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focused]);

  // opacity: fully off → near-invisible, settled → 70%, fully on → 100%
  // textShadowRadius is kept static (7) so the entire animation runs on the
  // native thread — the Animated API cannot drive text-shadow natively.
  const glowOpacity = pulseAnim.interpolate({
    inputRange: [0, 0.5, 1],
    outputRange: [0.05, 0.7, 1],
  });

  return (
    <View style={tabStyles.labelContainer}>
      <Animated.Text
        numberOfLines={1}
        style={[
          tabStyles.label,
          // opacity is ALWAYS driven by glowOpacity (native thread) so the
          // style object never switches between animated and non-animated —
          // that switch was the root cause of the visible flash when
          // startupDone flipped on unfocused tabs.
          // Inactive tabs settle at pulseAnim=1 → opacity 1.0 (full).
          // Active tabs settle at pulseAnim=0.5 → opacity 0.7 (glow dim).
          {
            opacity: glowOpacity,
            color: !startupDone || focused ? activeColor : colors.tabInactive,
            textShadowColor: activeColor,
            textShadowOffset: { width: 0, height: 0 },
            textShadowRadius: !startupDone || focused ? 7 : 0,
          },
        ]}
      >
        {label}
      </Animated.Text>
    </View>
  );
}

const tabStyles = StyleSheet.create({
  labelContainer: {
    minWidth: 90,
    alignItems: 'center',
    justifyContent: 'center',
  },
  label: {
    fontSize: 14,
    fontWeight: '800',
    letterSpacing: 2,
  },
});

// ─── Navigator ────────────────────────────────────────────────────────────────

export function BottomTabNavigator() {
  const { colors } = useTheme();
  const { state: appState } = useAppContext();
  const accentColor = appState.accentColor ?? null;
  const insets = useSafeAreaInsets();
  // Adds safe-area bottom so icons clear the Android gesture / button nav bar
  const extraBottom = insets.bottom;

  return (
    <Tab.Navigator
      screenOptions={{
        headerShown: false,
        tabBarShowLabel: false,

        tabBarStyle: {
          backgroundColor: colors.background,
          borderTopColor: colors.border,
          borderTopWidth: 0.5,
          height: DIMENSIONS.tabBarHeight + extraBottom,
          paddingBottom: 8 + extraBottom,
          paddingTop: 6,
        },

        tabBarItemStyle: {
          alignItems: 'center',
          justifyContent: 'center',
        },

        tabBarActiveTintColor: colors.tabActive,
        tabBarInactiveTintColor: colors.tabInactive,
      }}
    >
      <Tab.Screen
        name="Folders"
        component={HomeScreen}
        options={{
          tabBarIcon: ({ focused }) => (
            <TabLabel
              name="Folders"
              focused={focused}
              accentColor={accentColor}
            />
          ),
        }}
      />
      <Tab.Screen
        name="Favorites"
        component={FavoritesScreen}
        options={{
          tabBarIcon: ({ focused }) => (
            <TabLabel
              name="Favorites"
              focused={focused}
              accentColor={accentColor}
            />
          ),
        }}
      />
      <Tab.Screen
        name="Tags"
        component={TagsScreen}
        options={{
          tabBarIcon: ({ focused }) => (
            <TabLabel name="Tags" focused={focused} accentColor={accentColor} />
          ),
        }}
      />
    </Tab.Navigator>
  );
}
