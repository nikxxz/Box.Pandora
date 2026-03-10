/**
 * BiometricService
 *
 * JavaScript bridge for the native BiometricModule (BiometricModule.kt).
 *
 * Uses Android's BiometricPrompt with BIOMETRIC_STRONG | DEVICE_CREDENTIAL
 * authenticators — this shows the device lockscreen (fingerprint, face, or
 * PIN/pattern/password) rather than just fingerprint.
 *
 * Error codes from the native side:
 *   BIOMETRIC_CANCELLED — user pressed Back / dismissed the prompt
 *   BIOMETRIC_ERROR     — hardware error, lockout, or other OS error
 *   NO_ACTIVITY         — no foreground FragmentActivity (should not happen)
 *
 * Usage:
 *   import { BiometricService } from './BiometricService';
 *
 *   const available = await BiometricService.isAvailable();
 *
 *   try {
 *     await BiometricService.authenticate('Unlock', 'Authenticate to view hidden files');
 *     // success
 *   } catch (err) {
 *     if (err.code === 'BIOMETRIC_CANCELLED') { ... } // user cancelled
 *   }
 */

import { NativeModules, Platform } from 'react-native';

const { BiometricModule: Native } = NativeModules;

// ─── Error codes ──────────────────────────────────────────────────────────────

export const BiometricError = {
  CANCELLED:   'BIOMETRIC_CANCELLED',
  ERROR:       'BIOMETRIC_ERROR',
  NO_ACTIVITY: 'NO_ACTIVITY',
  UNSUPPORTED: 'UNSUPPORTED',
};

// ─── Helpers ──────────────────────────────────────────────────────────────────

function notAvailable(method) {
  return Promise.reject(
    Object.assign(
      new Error(`BiometricService.${method} is only available on Android`),
      { code: BiometricError.UNSUPPORTED },
    ),
  );
}

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Check whether device authentication (biometric or device credential) is
 * available and enrolled.
 *
 * @returns {Promise<boolean>}
 */
function isAvailable() {
  if (Platform.OS !== 'android' || !Native) return Promise.resolve(false);
  return Native.isBiometricAvailable();
}

/**
 * Show the Android device-authentication prompt (fingerprint / face / PIN).
 *
 * Resolves true on success.
 * Rejects with { code: 'BIOMETRIC_CANCELLED' } if the user cancels.
 * Rejects with { code: 'BIOMETRIC_ERROR' } on hardware/lockout error.
 *
 * @param {string} [title]    Dialog title shown to the user.
 * @param {string} [subtitle] Dialog subtitle (context hint).
 * @returns {Promise<boolean>}
 */
function authenticate(
  title = 'Authentication Required',
  subtitle = 'Authenticate to continue',
) {
  if (Platform.OS !== 'android' || !Native) return notAvailable('authenticate');
  return Native.authenticate(title, subtitle);
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const BiometricService = { isAvailable, authenticate };
