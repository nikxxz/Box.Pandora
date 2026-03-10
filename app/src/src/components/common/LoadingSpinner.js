import React, { useEffect, useRef } from 'react';
import { View, Text, Animated, Easing, StyleSheet } from 'react-native';
import { useTheme } from '../../providers/ThemeProvider';
import { useAppContext } from '../../store';

/**
 * DotsSpinner
 *
 * JS recreation of 3-dots-bounce.svg.
 * - Three dots bounce upward in sequence with a 100 ms stagger.
 * - Each cycle: 300 ms rise (ease-out cubic) + 300 ms fall (ease-in cubic) + 250 ms idle = 850 ms.
 * - Color defaults to the theme accent; pass `color` to override.
 */
export function DotsSpinner({ color, dotSize = 9, gap = 7 }) {
  const { colors } = useTheme();
  const { state: appState } = useAppContext();
  // Resolve: explicit prop > user-set accent > theme default accent
  const resolvedColor = color ?? appState.accentColor ?? colors.accent;
  const y0 = useRef(new Animated.Value(0)).current;
  const y1 = useRef(new Animated.Value(0)).current;
  const y2 = useRef(new Animated.Value(0)).current;

  const travel = dotSize * 1.5; // how far each dot rises

  useEffect(() => {
    // Build one full 850 ms loop for a given Animated.Value.
    function makeLoop(val) {
      return Animated.loop(
        Animated.sequence([
          Animated.timing(val, {
            toValue: -travel,
            duration: 300,
            easing: Easing.out(Easing.cubic),
            useNativeDriver: true,
          }),
          Animated.timing(val, {
            toValue: 0,
            duration: 300,
            easing: Easing.in(Easing.cubic),
            useNativeDriver: true,
          }),
          Animated.delay(250),
        ]),
      );
    }

    const a0 = makeLoop(y0);
    const a1 = makeLoop(y1);
    const a2 = makeLoop(y2);

    // Start all three with a 100 ms stagger — held by a one-shot delay wrapper.
    a0.start();
    setTimeout(() => a1.start(), 100);
    setTimeout(() => a2.start(), 200);

    return () => {
      a0.stop();
      a1.stop();
      a2.stop();
    };
  }, [y0, y1, y2, travel]);

  const dot = translateY => (
    <Animated.View
      style={[
        {
          width: dotSize,
          height: dotSize,
          borderRadius: dotSize / 2,
          backgroundColor: resolvedColor,
        },
        { transform: [{ translateY }] },
      ]}
    />
  );

  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap }}>
      {dot(y0)}
      {dot(y1)}
      {dot(y2)}
    </View>
  );
}

// ─── Full-screen loading spinner ─────────────────────────────────────────────
function FullSpinner({ message, accentColor, textColor }) {
  return (
    <View style={styles.container}>
      <DotsSpinner color={accentColor} dotSize={14} gap={10} />
      {message ? (
        <Text style={[styles.message, { color: textColor }]}>{message}</Text>
      ) : null}
    </View>
  );
}

// ─── Public component ─────────────────────────────────────────────────────────
export function LoadingSpinner({ message, size = 'large' }) {
  const { colors } = useTheme();
  const { state: appState } = useAppContext();
  const accentColor = appState.accentColor ?? colors.accent;

  if (size === 'small') {
    return (
      <View style={styles.smallContainer}>
        <DotsSpinner color={accentColor} dotSize={7} gap={5} />
      </View>
    );
  }

  return (
    <FullSpinner
      message={message}
      accentColor={accentColor}
      textColor={colors.textSecondary}
    />
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 24,
  },
  smallContainer: {
    width: '100%',
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 18,
  },
  message: {
    fontSize: 14,
    fontWeight: '500',
    letterSpacing: 0.2,
  },
});
