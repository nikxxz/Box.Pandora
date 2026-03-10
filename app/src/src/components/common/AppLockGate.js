/**
 * AppLockGate
 *
 * Full-screen lock screen that gates access to the entire app.
 * Activates on launch (if configured) and whenever the app returns from
 * the background.
 *
 * Supports two mutually exclusive auth methods (same as hide-files auth
 * but stored under separate DB keys so both features are independent):
 *   • Biometric  — shows Android BiometricPrompt automatically
 *   • Passcode   — shows PasscodeModal in verify mode
 *
 * Place this component as the direct child of your provider stack,
 * wrapping the NavigationContainer / AppNavigator.
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  AppState,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../../providers/ThemeProvider';
import { useAppContext } from '../../store/AppContext';
import { DatabaseService } from '../../services/database/DatabaseService';
import { SecurityService } from '../../services/auth/SecurityService';
import {
  BiometricService,
  BiometricError,
} from '../../services/auth/BiometricService';
import { PasscodeModal } from '../ui/PasscodeModal';
import { Icon } from '../ui/Icon';

export function AppLockGate({ children }) {
  const insets = useSafeAreaInsets();
  const { colors } = useTheme();
  const { state: appState } = useAppContext();
  const accentColor = appState.accentColor ?? colors.accent;

  // null = still reading config (avoids flash); false = unlocked; true = locked
  const [isLocked, setIsLocked] = useState(null);
  const [authMethod, setAuthMethod] = useState(null); // 'biometric' | 'passcode'

  const appStateRef = useRef(AppState.currentState);
  // Mirrors the latest config so the AppState listener can read it without stale closure
  const configRef = useRef({ biometric: false, hasPasscode: false });

  // ── Load config and apply initial lock ────────────────────────────────────
  const checkAndLock = useCallback(async () => {
    try {
      await DatabaseService.init(); // defensive — idempotent
      const [biometric, passcode] = await Promise.all([
        SecurityService.isAppLockBiometricEnabled(),
        SecurityService.getAppLockPasscode(),
      ]);
      const hasPasscode = passcode !== null;
      configRef.current = { biometric, hasPasscode };
      const method = biometric ? 'biometric' : hasPasscode ? 'passcode' : null;
      setAuthMethod(method);
      setIsLocked(method !== null);
    } catch {
      // DB unavailable — don't block the app
      setIsLocked(false);
    }
  }, []);

  useEffect(() => {
    checkAndLock();
  }, [checkAndLock]);

  // ── Re-lock when app returns from background ───────────────────────────────
  useEffect(() => {
    const sub = AppState.addEventListener('change', nextState => {
      if (
        appStateRef.current.match(/inactive|background/) &&
        nextState === 'active'
      ) {
        const { biometric, hasPasscode } = configRef.current;
        if (biometric || hasPasscode) {
          setIsLocked(true);
        }
      }
      appStateRef.current = nextState;
    });
    return () => sub.remove();
  }, []);

  const unlock = useCallback(() => setIsLocked(false), []);

  // ── Biometric prompt ───────────────────────────────────────────────────────
  const triggerBiometric = useCallback(async () => {
    try {
      await BiometricService.authenticate(
        "Open Pandora's Box",
        'Authenticate to open the app',
      );
      unlock();
    } catch (err) {
      // User cancelled or lockout — stay on lock screen, show retry button
      if (err?.code !== BiometricError.CANCELLED) {
        // Intentionally silent — user sees the retry button
      }
    }
  }, [unlock]);

  // Auto-trigger biometric prompt as soon as the lock screen appears
  useEffect(() => {
    if (isLocked && authMethod === 'biometric') {
      triggerBiometric();
    }
  }, [isLocked, authMethod, triggerBiometric]);

  // ── Render ─────────────────────────────────────────────────────────────────

  // Still resolving config — render nothing for a brief moment
  if (isLocked === null) return null;

  return (
    <>
      {children}

      {/* ── Passcode lock overlay ─────────────────────────────────────── */}
      {isLocked && authMethod === 'passcode' && (
        <PasscodeModal
          visible
          mode="verify"
          verifyFn={SecurityService.verifyAppLock}
          onSuccess={unlock}
          onCancel={() => {}} // cannot dismiss app lock
        />
      )}

      {/* ── Biometric lock overlay ────────────────────────────────────── */}
      {isLocked && authMethod === 'biometric' && (
        <View
          style={[
            StyleSheet.absoluteFill,
            styles.overlay,
            {
              backgroundColor: colors.background,
              paddingBottom: insets.bottom + 24,
            },
          ]}
        >
          <View style={styles.content}>
            <Icon name="padlock" size={52} color={colors.textSecondary} />
            <Text style={[styles.title, { color: colors.text }]}>
              Pandora's Box Locked
            </Text>
            <Text style={[styles.subtitle, { color: colors.textSecondary }]}>
              Authenticate to continue
            </Text>
          </View>

          <TouchableOpacity
            style={[styles.unlockBtn, { backgroundColor: accentColor }]}
            onPress={triggerBiometric}
            activeOpacity={0.8}
          >
            <Icon name="fingerprintScan" size={18} color="#fff" />
            <Text style={styles.unlockLabel}>Unlock with Fingerprint</Text>
          </TouchableOpacity>
        </View>
      )}
    </>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  overlay: {
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingTop: 100,
  },
  content: {
    alignItems: 'center',
    gap: 14,
  },
  title: {
    fontSize: 22,
    fontWeight: '600',
    letterSpacing: 0.3,
    marginTop: 8,
  },
  subtitle: {
    fontSize: 14,
    fontWeight: '400',
  },
  unlockBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 28,
    paddingVertical: 14,
    borderRadius: 30,
  },
  unlockLabel: {
    color: '#fff',
    fontSize: 15,
    fontWeight: '600',
    letterSpacing: 0.2,
  },
});
