import React, { useEffect, useRef, useState } from 'react';
import {
  Animated,
  Modal,
  StyleSheet,
  Text,
  TouchableOpacity,
  TouchableWithoutFeedback,
  View,
} from 'react-native';
import { useTheme } from '../../providers/ThemeProvider';
import { Icon } from './Icon';
import { CloseButton } from './CloseButton';

/**
 * Dialog — modal dialog component.
 *
 * Rendered by DialogProvider; do not use directly.
 * Supports alert, confirm, and destructive variants.
 *
 * Button style tokens:
 *   primary     — filled accent blue
 *   destructive — filled red
 *   ghost       — outlined, no fill
 *   cancel      — grey, subdued
 */

// ─── Button ────────────────────────────────────────────────────────────────────
function DialogButton({ label, btnStyle = 'primary', onPress, colors }) {
  const map = {
    primary: { bg: colors.accent, text: colors.white, border: colors.accent },
    destructive: {
      bg: colors.error,
      text: colors.white,
      border: colors.error,
    },
    ghost: {
      bg: colors.surface,
      text: colors.text,
      border: colors.border,
    },
    cancel: {
      bg: colors.surface,
      text: colors.textSecondary,
      border: colors.border,
    },
  };
  const cfg = map[btnStyle] ?? map.primary;

  return (
    <TouchableOpacity
      onPress={onPress}
      activeOpacity={0.72}
      style={[styles.btn, { backgroundColor: cfg.bg, borderColor: cfg.border }]}
    >
      <Text style={[styles.btnText, { color: cfg.text }]}>{label}</Text>
    </TouchableOpacity>
  );
}

// ─── Dialog ────────────────────────────────────────────────────────────────────
export function Dialog({ visible, config, onClose }) {
  const { colors, isDark } = useTheme();
  const scale = useRef(new Animated.Value(0.92)).current;
  const opacity = useRef(new Animated.Value(0)).current;

  // Keep the Modal mounted during the exit animation so the scale+fade
  // plays fully rather than snapping away on `visible=false`.
  const [mountedVisible, setMountedVisible] = useState(false);

  useEffect(() => {
    if (visible) {
      scale.setValue(0.92);
      opacity.setValue(0);
      setMountedVisible(true);
      Animated.parallel([
        Animated.spring(scale, {
          toValue: 1,
          useNativeDriver: true,
          tension: 160,
          friction: 10,
        }),
        Animated.timing(opacity, {
          toValue: 1,
          duration: 200,
          useNativeDriver: true,
        }),
      ]).start();
    } else {
      Animated.parallel([
        Animated.timing(scale, {
          toValue: 0.92,
          duration: 120,
          useNativeDriver: true,
        }),
        Animated.timing(opacity, {
          toValue: 0,
          duration: 120,
          useNativeDriver: true,
        }),
      ]).start(({ finished }) => { if (finished) setMountedVisible(false); });
    }
  }, [visible, opacity, scale]);

  if (!config) return null;

  const {
    icon,
    iconColor,
    title,
    body,
    layout = 'centered', // 'centered' | 'inline'
    buttons = [],
    closable = false,
    onDismiss,
  } = config;

  // Use the theme's card surface — automatically correct in day and night mode.
  const cardBg = colors.card;
  const shadow = isDark
    ? { elevation: 6 }
    : {
        shadowColor: colors.black,
        shadowOffset: { width: 0, height: 8 },
        shadowOpacity: 0.12,
        shadowRadius: 24,
        elevation: 10,
      };

  const isInline = layout === 'inline';

  return (
    <Modal
      visible={mountedVisible}
      transparent
      animationType="none"
      statusBarTranslucent
      onRequestClose={onDismiss ?? onClose}
    >
      {/* Backdrop */}
      <TouchableWithoutFeedback onPress={onDismiss}>
        <Animated.View
          style={[
            styles.backdrop,
            { backgroundColor: colors.overlayStrong, opacity },
          ]}
        />
      </TouchableWithoutFeedback>

      {/* Card */}
      <View style={styles.centerer} pointerEvents="box-none">
        <Animated.View
          style={[
            styles.card,
            shadow,
            { backgroundColor: cardBg, transform: [{ scale }] },
          ]}
        >
          {/* Close button */}
          {closable && (
            <CloseButton
              onPress={onDismiss ?? onClose}
              style={styles.closeBtn}
              size={16}
              color={colors.textTertiary}
            />
          )}

          {/* Inline layout: icon + title side by side */}
          {isInline && (
            <View style={styles.inlineHeader}>
              {icon ? (
                <Icon
                  name={icon}
                  size={28}
                  color={iconColor ?? colors.textSecondary}
                  style={styles.inlineIcon}
                />
              ) : null}
              <Text
                style={[
                  styles.title,
                  { color: colors.text, textAlign: 'left', flex: 1 },
                ]}
              >
                {title}
              </Text>
            </View>
          )}

          {/* Centered layout: icon stacked above title */}
          {!isInline && (
            <>
              {icon ? (
                <View style={styles.iconWrap}>
                  <Icon
                    name={icon}
                    size={40}
                    color={iconColor ?? colors.textSecondary}
                  />
                </View>
              ) : null}
              <Text style={[styles.title, { color: colors.text }]}>
                {title}
              </Text>
            </>
          )}

          {/* Body */}
          {body ? (
            <Text
              style={[
                styles.body,
                {
                  color: colors.textSecondary,
                  textAlign: isInline ? 'left' : 'center',
                },
              ]}
            >
              {body}
            </Text>
          ) : null}

          {/* Divider */}
          <View
            style={[
              styles.divider,
              { backgroundColor: colors.divider ?? colors.border },
            ]}
          />

          {/* Buttons — always row layout; a single flex:1 button fills the width */}
          <View style={styles.btnRow}>
            {buttons.map((btn, i) => (
              <DialogButton key={i} {...btn} colors={colors} />
            ))}
          </View>
        </Animated.View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    ...StyleSheet.absoluteFillObject,
  },
  centerer: {
    ...StyleSheet.absoluteFillObject,
    alignItems: 'center',
    justifyContent: 'center',
    pointerEvents: 'box-none',
  },
  card: {
    width: '86%',
    maxWidth: 360,
    borderRadius: 20,
    paddingTop: 28,
    paddingBottom: 20,
    paddingHorizontal: 22,
  },
  closeBtn: {
    position: 'absolute',
    top: 14,
    right: 16,
    zIndex: 1,
  },
  // Centered layout
  iconWrap: {
    alignSelf: 'center',
    marginBottom: 16,
  },
  title: {
    fontSize: 20,
    fontWeight: '700',
    textAlign: 'center',
    lineHeight: 26,
    letterSpacing: -0.3,
  },
  // Inline layout
  inlineHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 4,
  },
  inlineIcon: {
    marginRight: 12,
  },
  body: {
    fontSize: 14,
    lineHeight: 20,
    marginTop: 10,
    fontWeight: '400',
  },
  divider: {
    height: 1,
    marginTop: 20,
    marginBottom: 16,
  },
  btnRow: {
    flexDirection: 'row',
    gap: 10,
  },
  btn: {
    flex: 1,
    paddingVertical: 13,
    borderRadius: 999,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1.5,
  },
  btnText: {
    fontSize: 14,
    fontWeight: '600',
    letterSpacing: 0.2,
  },
});
