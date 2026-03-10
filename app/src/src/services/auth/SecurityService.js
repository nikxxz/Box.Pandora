/**
 * SecurityService
 *
 * Manages the passcode and biometric auth configuration for the "Show Hidden"
 * feature. Both values are stored in the existing `user_preferences` SQLite
 * table as JSON-encoded values.
 *
 * Keys used in user_preferences:
 *   auth_passcode          — string (4-digit PIN) or null when not set
 *   auth_biometric_enabled — boolean
 *
 * Biometric and passcode are MUTUALLY EXCLUSIVE by design:
 *   • Enabling biometric clears any stored passcode.
 *   • Disabling biometric re-exposes the passcode UI but does NOT restore the
 *     old passcode (user must set a new one if desired).
 *
 * Usage:
 *   import { SecurityService } from './SecurityService';
 *
 *   const code = await SecurityService.getPasscode();   // string | null
 *   await SecurityService.savePasscode('1234');
 *   const ok    = await SecurityService.verify('1234'); // boolean
 *   const auth  = await SecurityService.isAuthConfigured(); // boolean
 */

import { PreferenceService } from '../database/PreferenceService';

const PREF_PASSCODE          = 'auth_passcode';
const PREF_BIOMETRIC_ENABLED = 'auth_biometric_enabled';

// App-lock keys (separate from the hide-files auth)
const PREF_APP_LOCK_PASSCODE  = 'app_lock_passcode';
const PREF_APP_LOCK_BIOMETRIC = 'app_lock_biometric_enabled';

// ─── Passcode ─────────────────────────────────────────────────────────────────

/**
 * Returns the stored 4-digit passcode string, or null if not set.
 * @returns {Promise<string|null>}
 */
async function getPasscode() {
  return PreferenceService.get(PREF_PASSCODE); // already JSON.parse'd → string|null
}

/**
 * Persist a 4-digit passcode.
 * @param {string} code
 */
async function savePasscode(code) {
  await PreferenceService.set(PREF_PASSCODE, code);
}

/**
 * Remove the stored passcode.
 */
async function clearPasscode() {
  await PreferenceService.remove(PREF_PASSCODE);
}

/**
 * Verify a user-entered code against the stored passcode.
 * @param {string} code
 * @returns {Promise<boolean>}
 */
async function verify(code) {
  const stored = await getPasscode();
  if (!stored) return false;
  return stored === code;
}

// ─── Biometric ────────────────────────────────────────────────────────────────

/**
 * Returns true if biometric auth is enabled.
 * @returns {Promise<boolean>}
 */
async function isBiometricEnabled() {
  const v = await PreferenceService.get(PREF_BIOMETRIC_ENABLED);
  return v === true;
}

/**
 * Enable or disable biometric auth.
 * Enabling also clears any stored passcode (they are mutually exclusive).
 * @param {boolean} enabled
 */
async function saveBiometricEnabled(enabled) {
  await PreferenceService.set(PREF_BIOMETRIC_ENABLED, enabled);
  if (enabled) {
    // Biometric replaces passcode — clear stored PIN
    await clearPasscode();
  }
}

// ─── App Lock (whole-app gate) ────────────────────────────────────────────────
// Entirely separate from the hide-files auth above. Stored under different keys
// so both can coexist independently.

/** @returns {Promise<string|null>} */
async function getAppLockPasscode() {
  return PreferenceService.get(PREF_APP_LOCK_PASSCODE);
}

/** @param {string} code */
async function saveAppLockPasscode(code) {
  await PreferenceService.set(PREF_APP_LOCK_PASSCODE, code);
}

async function clearAppLockPasscode() {
  await PreferenceService.remove(PREF_APP_LOCK_PASSCODE);
}

/** @returns {Promise<boolean>} */
async function verifyAppLock(code) {
  const stored = await getAppLockPasscode();
  if (!stored) return false;
  return stored === code;
}

/** @returns {Promise<boolean>} */
async function isAppLockBiometricEnabled() {
  const v = await PreferenceService.get(PREF_APP_LOCK_BIOMETRIC);
  return v === true;
}

/**
 * Enable or disable biometric for the app lock.
 * Enabling clears any stored app-lock passcode (mutually exclusive).
 * @param {boolean} enabled
 */
async function saveAppLockBiometricEnabled(enabled) {
  await PreferenceService.set(PREF_APP_LOCK_BIOMETRIC, enabled);
  if (enabled) await clearAppLockPasscode();
}

/** @returns {Promise<boolean>} */
async function isAppLockConfigured() {
  const [passcode, biometric] = await Promise.all([
    getAppLockPasscode(),
    isAppLockBiometricEnabled(),
  ]);
  return biometric === true || passcode !== null;
}

// ─── Composite query ──────────────────────────────────────────────────────────

/**
 * Returns true if ANY auth method is configured (passcode or biometric).
 * Screens use this to decide whether the Show Hidden toggle requires auth.
 * @returns {Promise<boolean>}
 */
async function isAuthConfigured() {
  const [passcode, biometric] = await Promise.all([
    getPasscode(),
    isBiometricEnabled(),
  ]);
  return biometric === true || passcode !== null;
}

// ─── Export ───────────────────────────────────────────────────────────────────

export const SecurityService = {
  // Hide-files auth
  getPasscode,
  savePasscode,
  clearPasscode,
  verify,
  isBiometricEnabled,
  saveBiometricEnabled,
  isAuthConfigured,
  // App-lock auth
  getAppLockPasscode,
  saveAppLockPasscode,
  clearAppLockPasscode,
  verifyAppLock,
  isAppLockBiometricEnabled,
  saveAppLockBiometricEnabled,
  isAppLockConfigured,
};
