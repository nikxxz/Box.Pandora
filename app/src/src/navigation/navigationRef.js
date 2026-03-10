import { createNavigationContainerRef, CommonActions } from '@react-navigation/native';

export const navigationRef = createNavigationContainerRef();

/**
 * Reset the navigation stack to the Main (home) tab screen.
 * Safe to call from anywhere — no-ops if NavigationContainer isn't ready.
 */
export function resetToHome() {
  try {
    if (navigationRef.isReady()) {
      navigationRef.dispatch(
        CommonActions.reset({ index: 0, routes: [{ name: 'Main' }] }),
      );
    }
  } catch (err) {
    console.warn('[navigationRef] resetToHome error:', err);
  }
}

/**
 * Imperatively navigate to a named screen with optional params.
 * Safe to call from anywhere — no-ops if NavigationContainer isn't ready.
 *
 * @param {string} screenName — registered screen name (e.g. 'PersonDetail')
 * @param {object} [params]   — screen params
 */
export function navigate(screenName, params) {
  try {
    if (navigationRef.isReady()) {
      navigationRef.navigate(screenName, params);
    }
  } catch (err) {
    console.warn('[navigationRef] navigate error:', err);
  }
}
