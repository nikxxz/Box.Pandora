/**
 * PasscodeModal
 *
 * A full-screen 4-digit PIN pad modal, themed to match the app.
 *
 * Props:
 *   visible  — boolean
 *   mode     — 'verify' | 'setup' | 'change'
 *   onSuccess — () => void  called after successful auth / save
 *   onCancel  — () => void  called when the user presses ×
 *
 * mode behaviour:
 *   'verify'  — enter 4 digits → compare with stored passcode → onSuccess or shake
 *   'setup'   — enter 4 digits → confirm → save passcode → onSuccess
 *   'change'  — verify old passcode → enter new → confirm → save → onSuccess
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Animated,
  Easing,
  Modal,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../../providers/ThemeProvider';
import { useAppContext } from '../../store/AppContext';
import { SecurityService } from '../../services/auth/SecurityService';

// ─── Keypad layout ────────────────────────────────────────────────────────────

const ROWS = [
  ['1', '2', '3'],
  ['4', '5', '6'],
  ['7', '8', '9'],
  ['cancel', '0', 'del'],
];

// ─── Internal step machine ────────────────────────────────────────────────────

// step identifiers used in the multi-step flow
const STEP = {
  VERIFY_OLD: 'verify_old',   // change-mode: verify current passcode first
  ENTER:      'enter',        // verify-mode or first entry of setup/change
  CONFIRM:    'confirm',      // second entry for setup / change
};

function initialStep(mode) {
  if (mode === 'change') return STEP.VERIFY_OLD;
  return STEP.ENTER;
}

function stepTitle(mode, step) {
  if (mode === 'verify')  return 'Enter Passcode';
  if (mode === 'setup') {
    return step === STEP.CONFIRM ? 'Confirm Passcode' : 'Set Passcode';
  }
  // change
  if (step === STEP.VERIFY_OLD) return 'Enter Current Passcode';
  if (step === STEP.CONFIRM)    return 'Confirm New Passcode';
  return 'Enter New Passcode';
}

function stepSubtitle(mode, step) {
  if (mode === 'verify')  return 'Enter your 4-digit passcode to continue';
  if (mode === 'setup') {
    return step === STEP.CONFIRM
      ? 'Re-enter the passcode to confirm'
      : 'Create a 4-digit passcode';
  }
  // change
  if (step === STEP.VERIFY_OLD) return 'Authenticate with your current passcode';
  if (step === STEP.CONFIRM)    return 'Re-enter your new passcode to confirm';
  return 'Choose a new 4-digit passcode';
}

// ─── Component ────────────────────────────────────────────────────────────────

export function PasscodeModal({
  visible,
  mode = 'verify',
  onSuccess,
  onCancel,
  /** Override the verify function (defaults to SecurityService.verify) */
  verifyFn,
  /** Override the save function used in setup/change (defaults to SecurityService.savePasscode) */
  saveFn,
}) {
  const insets = useSafeAreaInsets();
  const { colors } = useTheme();
  const { state: appState } = useAppContext();
  const accentColor = appState.accentColor ?? colors.accent;

  // ── State ──────────────────────────────────────────────────────────────────
  const [digits, setDigits]     = useState('');
  const [step, setStep]         = useState(() => initialStep(mode));
  const [firstEntry, setFirst]  = useState(''); // for confirm step
  const [error, setError]       = useState('');
  const [busy, setBusy]         = useState(false);

  // ── Animations ─────────────────────────────────────────────────────────────
  const shakeAnim  = useRef(new Animated.Value(0)).current;
  const errorOpacity = useRef(new Animated.Value(0)).current;
  const scaleAnim  = useRef(new Animated.Value(0.92)).current;
  const opacityAnim = useRef(new Animated.Value(0)).current;

  // Reset all state when modal opens / mode changes
  useEffect(() => {
    if (visible) {
      setDigits('');
      setStep(initialStep(mode));
      setFirst('');
      setError('');
      setBusy(false);
      // Entrance animation
      scaleAnim.setValue(0.92);
      opacityAnim.setValue(0);
      Animated.parallel([
        Animated.spring(scaleAnim, {
          toValue: 1,
          tension: 200,
          friction: 22,
          useNativeDriver: true,
        }),
        Animated.timing(opacityAnim, {
          toValue: 1,
          duration: 180,
          easing: Easing.out(Easing.quad),
          useNativeDriver: true,
        }),
      ]).start();
    }
  }, [visible, mode]); // eslint-disable-line react-hooks/exhaustive-deps

  // Clear error when user starts typing
  useEffect(() => {
    if (digits.length > 0) {
      setError('');
      errorOpacity.setValue(0);
    }
  }, [digits]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Shake on error ─────────────────────────────────────────────────────────
  const shake = useCallback(() => {
    shakeAnim.setValue(0);
    Animated.sequence([
      Animated.timing(shakeAnim, { toValue: 10,  duration: 50, useNativeDriver: true }),
      Animated.timing(shakeAnim, { toValue: -10, duration: 50, useNativeDriver: true }),
      Animated.timing(shakeAnim, { toValue: 8,   duration: 50, useNativeDriver: true }),
      Animated.timing(shakeAnim, { toValue: -8,  duration: 50, useNativeDriver: true }),
      Animated.timing(shakeAnim, { toValue: 0,   duration: 40, useNativeDriver: true }),
    ]).start();
    Animated.sequence([
      Animated.timing(errorOpacity, { toValue: 1, duration: 120, useNativeDriver: true }),
    ]).start();
  }, [shakeAnim, errorOpacity]);

  // ── Core: process a complete 4-digit entry ─────────────────────────────────
  const processEntry = useCallback(async (code) => {
    if (busy) return;
    setBusy(true);

    const doVerify = verifyFn ?? SecurityService.verify;
    const doSave   = saveFn   ?? SecurityService.savePasscode;

    try {
      if (mode === 'verify') {
        const ok = await doVerify(code);
        if (ok) {
          onSuccess?.();
        } else {
          setError('Incorrect passcode');
          shake();
          setDigits('');
        }

      } else if (mode === 'setup') {
        if (step === STEP.ENTER) {
          setFirst(code);
          setDigits('');
          setStep(STEP.CONFIRM);
        } else {
          // CONFIRM step
          if (code === firstEntry) {
            await doSave(code);
            onSuccess?.();
          } else {
            setError('Passcodes do not match');
            shake();
            setDigits('');
            setStep(STEP.ENTER);
            setFirst('');
          }
        }

      } else if (mode === 'change') {
        if (step === STEP.VERIFY_OLD) {
          const ok = await doVerify(code);
          if (ok) {
            setDigits('');
            setStep(STEP.ENTER);
          } else {
            setError('Incorrect passcode');
            shake();
            setDigits('');
          }
        } else if (step === STEP.ENTER) {
          setFirst(code);
          setDigits('');
          setStep(STEP.CONFIRM);
        } else {
          // CONFIRM new
          if (code === firstEntry) {
            await doSave(code);
            onSuccess?.();
          } else {
            setError('Passcodes do not match');
            shake();
            setDigits('');
            setStep(STEP.ENTER);
            setFirst('');
          }
        }
      }
    } catch {
      setError('Something went wrong. Try again.');
      shake();
      setDigits('');
    } finally {
      setBusy(false);
    }
  }, [busy, mode, step, firstEntry, onSuccess, shake, verifyFn, saveFn]);

  // ── Key press handler ──────────────────────────────────────────────────────
  const handleKey = useCallback((key) => {
    if (busy) return;

    if (key === 'cancel') {
      onCancel?.();
      return;
    }

    if (key === 'del') {
      setDigits(d => d.slice(0, -1));
      return;
    }

    // digit
    const next = digits + key;
    setDigits(next);

    if (next.length === 4) {
      // Slight delay so user sees 4th dot filled before processing
      setTimeout(() => processEntry(next), 80);
    }
  }, [busy, digits, onCancel, processEntry]);

  // ── Render ─────────────────────────────────────────────────────────────────
  const styles = makeStyles(colors, accentColor, insets);

  return (
    <Modal
      visible={visible}
      transparent
      animationType="none"
      statusBarTranslucent
      onRequestClose={onCancel}
    >
      <View style={styles.backdrop}>
        <Animated.View
          style={[
            styles.panel,
            {
              transform: [
                { translateX: shakeAnim },
                { scale: scaleAnim },
              ],
              opacity: opacityAnim,
            },
          ]}
        >
          {/* ─ Title ─────────────────────────────────────────────── */}
          <Text style={styles.title}>{stepTitle(mode, step)}</Text>
          <Text style={styles.subtitle}>{stepSubtitle(mode, step)}</Text>

          {/* ─ Dot indicators ───────────────────────────────────── */}
          <View style={styles.dotsRow}>
            {[0, 1, 2, 3].map(i => (
              <View
                key={i}
                style={[
                  styles.dot,
                  i < digits.length
                    ? [styles.dotFilled, { backgroundColor: accentColor }]
                    : [styles.dotEmpty, { borderColor: accentColor }],
                ]}
              />
            ))}
          </View>

          {/* ─ Error text ────────────────────────────────────────── */}
          <Animated.Text
            style={[styles.errorText, { opacity: errorOpacity }]}
          >
            {error}
          </Animated.Text>

          {/* ─ Number pad ────────────────────────────────────────── */}
          <View style={styles.pad}>
            {ROWS.map((row, ri) => (
              <View key={ri} style={styles.padRow}>
                {row.map(key => (
                  <KeyButton
                    key={key}
                    keyVal={key}
                    onPress={handleKey}
                    colors={colors}
                    accentColor={accentColor}
                    disabled={
                      busy ||
                      (key !== 'del' && key !== 'cancel' && digits.length >= 4)
                    }
                  />
                ))}
              </View>
            ))}
          </View>
        </Animated.View>
      </View>
    </Modal>
  );
}

// ─── KeyButton ────────────────────────────────────────────────────────────────

function KeyButton({ keyVal, onPress, colors, accentColor, disabled }) {
  const scaleRef = useRef(new Animated.Value(1)).current;

  const handlePress = useCallback(() => {
    if (disabled) return;
    Animated.sequence([
      Animated.timing(scaleRef, { toValue: 0.88, duration: 60, useNativeDriver: true }),
      Animated.spring(scaleRef, { toValue: 1, tension: 300, friction: 18, useNativeDriver: true }),
    ]).start();
    onPress(keyVal);
  }, [disabled, keyVal, onPress, scaleRef]);

  const isCancel = keyVal === 'cancel';
  const isDel    = keyVal === 'del';
  const isSpecial = isCancel || isDel;

  const label = isCancel ? '×' : isDel ? '⌫' : keyVal;

  return (
    <TouchableOpacity
      activeOpacity={1}
      onPress={handlePress}
      disabled={disabled}
      style={keyStyles.wrap}
    >
      <Animated.View
        style={[
          keyStyles.btn,
          isSpecial ? { backgroundColor: 'transparent' } : { backgroundColor: colors.surface },
          disabled && !isSpecial && keyStyles.disabled,
          { transform: [{ scale: scaleRef }] },
        ]}
      >
        <Text
          style={[
            keyStyles.label,
            {
              color: isSpecial ? (disabled ? colors.textTertiary : colors.textSecondary) : colors.text,
              fontSize: isCancel ? 22 : isDel ? 20 : 26,
            },
          ]}
        >
          {label}
        </Text>
      </Animated.View>
    </TouchableOpacity>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const makeStyles = (colors, accentColor, insets) =>
  StyleSheet.create({
    backdrop: {
      flex: 1,
      backgroundColor: colors.overlayStrong,
      alignItems: 'center',
      justifyContent: 'center',
      paddingBottom: insets.bottom,
    },
    panel: {
      width: 320,
      backgroundColor: colors.card,
      borderRadius: 28,
      paddingTop: 36,
      paddingBottom: 28,
      paddingHorizontal: 24,
      alignItems: 'center',
      shadowColor: '#000',
      shadowOffset: { width: 0, height: 8 },
      shadowOpacity: 0.4,
      shadowRadius: 24,
      elevation: 20,
    },
    title: {
      fontSize: 18,
      fontWeight: '600',
      color: colors.text,
      marginBottom: 6,
      textAlign: 'center',
    },
    subtitle: {
      fontSize: 13,
      color: colors.textSecondary,
      marginBottom: 28,
      textAlign: 'center',
    },
    dotsRow: {
      flexDirection: 'row',
      gap: 20,
      marginBottom: 8,
    },
    dot: {
      width: 14,
      height: 14,
      borderRadius: 7,
    },
    dotFilled: {
      // backgroundColor set inline
    },
    dotEmpty: {
      backgroundColor: 'transparent',
      borderWidth: 1.5,
      // borderColor set inline
    },
    errorText: {
      fontSize: 12,
      color: '#EF4444',
      height: 20,
      marginBottom: 16,
      textAlign: 'center',
    },
    pad: {
      width: '100%',
      gap: 8,
      marginTop: 4,
    },
    padRow: {
      flexDirection: 'row',
      gap: 8,
      justifyContent: 'space-between',
    },
  });

// Static styles for KeyButton (not colour-dependent)
const keyStyles = StyleSheet.create({
  wrap: {
    flex: 1,
    aspectRatio: 1.3,
  },
  btn: {
    flex: 1,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  disabled: {
    opacity: 0.4,
  },
  label: {
    fontWeight: '400',
    letterSpacing: 0.5,
  },
});
