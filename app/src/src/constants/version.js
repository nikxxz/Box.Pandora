/**
 * Single source of truth for the app version used throughout the JS bundle.
 *
 * Keep in sync with:
 *   package.json                → "version"
 *   android/app/build.gradle   → versionName / versionCode
 *
 * DB_VERSION is imported from the schema directly (schema.js owns it)
 * so that incrementing the schema automatically surfaces the new number
 * everywhere — no double-update required.
 */

/** Semver string shown in-app (e.g. About screens, debug panels). */
export const APP_VERSION = '1.8.0';

/**
 * Short display name (major.minor) used where space is limited.
 * Matches android versionName.
 */
export const APP_VERSION_SHORT = '1.8';

/**
 * Android `versionCode` — must be incremented with every Play Store upload.
 * Kept here so the JS layer can surface it in debug/settings screens without
 * a native module call.
 */
export const APP_BUILD = 16;
