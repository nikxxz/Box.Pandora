import { NativeModules, Platform } from 'react-native';

const Native = NativeModules.SystemUIModule;

/**
 * SystemUIModule — controls Android system UI visibility from JS.
 * No-ops on iOS (no equivalent API needed).
 */
export const SystemUIModule = {
  /** Hide the bottom navigation bar (gesture pill / buttons). */
  hideNavigationBar() {
    if (Platform.OS === 'android') Native?.hideNavigationBar();
  },

  /** Restore the bottom navigation bar. */
  showNavigationBar() {
    if (Platform.OS === 'android') Native?.showNavigationBar();
  },

  /** Lock Activity orientation to landscape for video fullscreen. */
  lockLandscape() {
    if (Platform.OS === 'android') Native?.lockLandscape();
  },

  /** Restore free orientation (follows device sensor). */
  unlockOrientation() {
    if (Platform.OS === 'android') Native?.unlockOrientation();
  },
};
