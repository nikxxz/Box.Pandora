import React, { useEffect, useRef } from 'react';
import { Animated, View, Text, StyleSheet } from 'react-native';
import { useTheme } from '../../providers/ThemeProvider';
import { Icon } from './Icon';
import { CloseButton } from './CloseButton';

/**
 * Toast — animated notification strip.
 *
 * Rendered by ToastProvider; do not use directly.
 * Slides up + fades in, then auto-dismisses.
 *
 * Extra props:
 *   duration={0}   — pinned; never auto-dismisses (use toast.dismiss(id))
 *   progress={0.4} — renders a thin live progress bar at the bottom (0–1)
 */
export function Toast({
  id,
  title,
  subtitle,
  type = 'info',
  duration = 3000,
  progress = null,
  onDismiss,
}) {
  const { colors, isDark } = useTheme();

  // Type → icon + accent colour mapping.
  // success / error use colored icons (no tint applied).
  // info / warning use the `information` icon tinted to the type colour.
  const TYPE = {
    success: { icon: 'success', color: colors.success },
    error: { icon: 'error', color: colors.error },
    warning: { icon: 'information', color: colors.warning },
    info: { icon: 'information', color: colors.info },
  };
  const cfg = TYPE[type] ?? TYPE.info;

  const opacity = useRef(new Animated.Value(0)).current;
  const translateY = useRef(new Animated.Value(16)).current;

  useEffect(() => {
    // Enter animation
    Animated.parallel([
      Animated.timing(opacity, {
        toValue: 1,
        duration: 220,
        useNativeDriver: true,
      }),
      Animated.timing(translateY, {
        toValue: 0,
        duration: 220,
        useNativeDriver: true,
      }),
    ]).start();

    // duration === 0 means pinned — caller must dismiss() manually
    if (duration > 0) {
      const timer = setTimeout(dismiss, duration);
      return () => clearTimeout(timer);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function dismiss() {
    Animated.parallel([
      Animated.timing(opacity, {
        toValue: 0,
        duration: 160,
        useNativeDriver: true,
      }),
      Animated.timing(translateY, {
        toValue: 8,
        duration: 160,
        useNativeDriver: true,
      }),
    ]).start(() => onDismiss(id));
  }

  const bg = colors.card;
  const shadow = isDark
    ? {}
    : {
        shadowColor: colors.black,
        shadowOffset: { width: 0, height: 4 },
        shadowOpacity: 0.1,
        shadowRadius: 12,
        elevation: 6,
      };

  const hasProgress = progress !== null && progress !== undefined;
  const progressPct = hasProgress ? Math.max(0, Math.min(1, progress)) : 0;

  return (
    <Animated.View
      style={[
        styles.toast,
        shadow,
        {
          backgroundColor: bg,
          opacity,
          transform: [{ translateY }],
        },
      ]}
    >
      {/* Main row: icon + text + dismiss */}
      <View style={styles.row}>
        <View style={styles.iconWrap}>
          <Icon name={cfg.icon} size={20} color={cfg.color} />
        </View>

        <View style={styles.body}>
          <Text
            style={[styles.title, { color: colors.text }]}
            numberOfLines={1}
          >
            {title}
          </Text>
          {subtitle ? (
            <Text
              style={[styles.subtitle, { color: colors.textSecondary }]}
              numberOfLines={2}
            >
              {subtitle}
            </Text>
          ) : null}
        </View>

        <CloseButton onPress={dismiss} style={styles.close} />
      </View>

      {/* Progress bar — only shown when progress prop is provided */}
      {hasProgress && (
        <View
          style={[styles.progressTrack, { backgroundColor: colors.pressed }]}
        >
          <View
            style={[
              styles.progressFill,
              {
                width: `${Math.round(progressPct * 100)}%`,
                backgroundColor: cfg.color,
              },
            ]}
          />
        </View>
      )}
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  toast: {
    borderRadius: 18,
    overflow: 'hidden',
    minHeight: 60,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  iconWrap: {
    paddingRight: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  body: {
    flex: 1,
    paddingVertical: 12,
    paddingRight: 4,
  },
  title: {
    fontSize: 14,
    fontWeight: '600',
    lineHeight: 18,
  },
  subtitle: {
    fontSize: 12,
    marginTop: 2,
    lineHeight: 16,
  },
  close: {
    paddingHorizontal: 14,
    paddingVertical: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  progressTrack: {
    height: 3,
    marginHorizontal: 16,
    marginBottom: 10,
    borderRadius: 1.5,
    overflow: 'hidden',
  },
  progressFill: {
    height: '100%',
    borderRadius: 1.5,
  },
});
