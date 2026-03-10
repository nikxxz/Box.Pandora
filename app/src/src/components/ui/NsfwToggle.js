/**
 * NsfwToggle
 *
 * An animated icon toggle for the global NSFW content filter.
 *
 *   value = true  → filter ON  (NSFW content hidden) — flat red icon, no glow
 *   value = false → filter OFF (NSFW content visible) — hot-pink icon with
 *                   halogen neon-on animation + sustained glow
 *
 * The "neon turning on" animation plays whenever the filter is disabled
 * (value transitions from true → false), mimicking the fluorescent-flicker
 * style used in the bottom navigation bar.
 */

import { useEffect, useRef } from 'react';
import { Animated, Image, StyleSheet, TouchableOpacity } from 'react-native';

// ─── Colours ──────────────────────────────────────────────────────────────────

const NSFW_RED = '#FF2D2D'; // filter ON  — NSFW hidden
const HOT_PINK = '#FF69B4'; // filter OFF — NSFW visible

// ─── Flicker frame data (val, duration ms) ───────────────────────────────────
// Mirrors the three-phase sequence from BottomTabNavigator.

const ANIM_SPEED = 0.5; // 50% duration

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
];

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

function buildFlickerSteps(anim, frames) {
  return frames.map(([val, dur]) =>
    Animated.timing(anim, {
      toValue: val,
      duration: Math.max(1, Math.round(dur * ANIM_SPEED)),
      useNativeDriver: false,
    }),
  );
}

// ─── Component ────────────────────────────────────────────────────────────────

export function NsfwToggle({ value, onToggle, size = 32 }) {
  // pulseAnim drives the glow intensity:
  //   0   = no glow (filter ON / red)
  //   0.5 = settled glow (filter OFF / hot-pink)
  //   1   = peak flicker brightness
  const pulseAnim = useRef(new Animated.Value(value ? 0 : 0.5)).current;

  const isFirst = useRef(true);

  useEffect(() => {
    // Skip animation on initial render — just set the resting value.
    if (isFirst.current) {
      isFirst.current = false;
      pulseAnim.setValue(value ? 0 : 0.5);
      return;
    }

    if (!value) {
      // Filter turned OFF → hot-pink neon-on animation
      pulseAnim.setValue(0);
      const steps = buildFlickerSteps(pulseAnim, [
        ...RAPID_FRAMES,
        ...WARM_FRAMES,
        ...BURST_FRAMES,
      ]);
      const settle = Animated.timing(pulseAnim, {
        toValue: 0.5,
        duration: Math.max(1, Math.round(450 * ANIM_SPEED)),
        useNativeDriver: false,
      });
      Animated.sequence([...steps, settle]).start();
    } else {
      // Filter turned ON → fade out glow, snap to red
      pulseAnim.stopAnimation();
      Animated.timing(pulseAnim, {
        toValue: 0,
        duration: Math.max(1, Math.round(200 * ANIM_SPEED)),
        useNativeDriver: false,
      }).start();
    }
  }, [value]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Derived animated values ───────────────────────────────────────────────

  // Outer halo — large, diffuse
  const outerOpacity = pulseAnim.interpolate({
    inputRange: [0, 0.5, 1],
    outputRange: [0, 0.18, 0.4],
  });
  // Inner halo — small, bright
  const innerOpacity = pulseAnim.interpolate({
    inputRange: [0, 0.5, 1],
    outputRange: [0, 0.3, 0.1],
  });

  // Shadow animation — mirrors BottomTabNavigator's textShadow interpolation
  const shadowRadius = pulseAnim.interpolate({
    inputRange: [0, 0.5, 1],
    outputRange: [0, 5, 12],
  });
  const shadowOpacity = pulseAnim.interpolate({
    inputRange: [0, 0.5, 1],
    outputRange: [0, 0.1, 0.22],
  });
  const elevation = pulseAnim.interpolate({
    inputRange: [0, 0.5, 1],
    outputRange: [0, 4, 10],
  });

  const containerSize = size + 20; // glow bleeds beyond icon bounds
  const outerR = containerSize / 2;
  const iconSize = Math.round(size + 2); // +20%
  const innerHaloSize = iconSize + 2;
  const innerR = innerHaloSize / 2;

  return (
    <TouchableOpacity
      onPress={onToggle}
      activeOpacity={0.8}
      hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
    >
      <Animated.View
        style={[
          styles.container,
          {
            width: containerSize,
            height: containerSize,
            shadowColor: HOT_PINK,
            shadowOffset: { width: 0, height: 0 },
            shadowOpacity,
            shadowRadius,
            elevation,
          },
        ]}
      >
        {/* Outer diffuse glow ring */}
        <Animated.View
          style={[
            styles.halo,
            {
              width: containerSize,
              height: containerSize,
              borderRadius: outerR,
              backgroundColor: HOT_PINK,
              opacity: outerOpacity,
            },
          ]}
        />
        {/* Inner bright core */}
        <Animated.View
          style={[
            styles.halo,
            {
              width: innerHaloSize,
              height: innerHaloSize,
              borderRadius: innerR,
              backgroundColor: HOT_PINK,
              opacity: innerOpacity,
            },
          ]}
        />
        {/* Legs icon */}
        <Image
          source={require('../../assets/icons/nsfw.png')}
          style={[
            styles.icon,
            {
              width: iconSize,
              height: iconSize,
              tintColor: value ? NSFW_RED : HOT_PINK,
            },
          ]}
          resizeMode="contain"
          fadeDuration={0}
        />
      </Animated.View>
    </TouchableOpacity>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  container: {
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'transparent',
  },
  halo: {
    position: 'absolute',
  },
  icon: {
    // positioned on top of the halo layers via stacking order
  },
});
