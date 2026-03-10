/**
 * PermissionService
 *
 * Handles all Android media permission flows for:
 *  - READ  (view images / videos / audio)
 *  - WRITE / DELETE (modify or remove media files)
 *
 * Android version matrix:
 *  API ≤ 28  → READ_EXTERNAL_STORAGE + WRITE_EXTERNAL_STORAGE
 *  API 29–32 → READ_EXTERNAL_STORAGE (scoped storage; WRITE blocked by default)
 *  API 30+   → MediaStore.createDeleteRequest for delete (no runtime perm needed)
 *              MediaStore ContentResolver.insert for saving (no runtime perm needed)
 *              MANAGE_EXTERNAL_STORAGE only if arbitrary FS access is required
 *  API 33+   → READ_MEDIA_IMAGES / READ_MEDIA_VIDEO / READ_MEDIA_AUDIO
 *
 * Key insight (Android 10+ / API 29+):
 *  • Deleting shared media files → use MediaStore.createDeleteRequest (system dialog)
 *    — does NOT require MANAGE_EXTERNAL_STORAGE or any runtime write permission.
 *    Handled transparently by MediaStoreModule.deleteMediaFiles().
 *  • Creating / saving files to gallery → use ContentResolver.insert + write
 *    — does NOT require WRITE_EXTERNAL_STORAGE on API 29+.
 *    Handled transparently by MediaStoreModule.saveToGallery().
 *  • Arbitrary filesystem access (RNFS on shared paths) → MANAGE_EXTERNAL_STORAGE.
 *    Use requestFullFileAccess() only when truly needed.
 */

import { PermissionsAndroid, Platform, Linking, Alert } from 'react-native';
import { MediaStoreModule } from '../media/MediaStoreModule';
import {
  ANDROID_SDK,
  ANDROID_API,
  ANDROID_PERMISSIONS,
  PERMISSION_RATIONALE,
  IS_ANDROID,
} from '../../constants/permissions';

const { RESULTS } = PermissionsAndroid;

// ─── Helpers ────────────────────────────────────────────────────────────────

function isGranted(result) {
  return result === RESULTS.GRANTED;
}

// ─── Read permissions ────────────────────────────────────────────────────────

/**
 * Request the correct read permissions for the running Android version.
 * Returns true if all required read permissions are granted.
 */
async function requestMediaReadPermissions() {
  if (!IS_ANDROID) return true;

  try {
    if (ANDROID_SDK >= ANDROID_API.GRANULAR_MEDIA) {
      // Android 13+ — per-type granular permissions
      const results = await PermissionsAndroid.requestMultiple([
        ANDROID_PERMISSIONS.READ_MEDIA_IMAGES,
        ANDROID_PERMISSIONS.READ_MEDIA_VIDEO,
        ANDROID_PERMISSIONS.READ_MEDIA_AUDIO,
      ]);
      return Object.values(results).every(isGranted);
    }

    // Android 10–12
    const result = await PermissionsAndroid.request(
      ANDROID_PERMISSIONS.READ_EXTERNAL_STORAGE,
      PERMISSION_RATIONALE.readMedia,
    );
    return isGranted(result);
  } catch (err) {
    console.error('[PermissionService] requestMediaReadPermissions:', err);
    return false;
  }
}

/**
 * Check whether read permissions are currently granted without prompting.
 */
async function checkMediaReadPermissions() {
  if (!IS_ANDROID) return true;
  try {
    if (ANDROID_SDK >= ANDROID_API.GRANULAR_MEDIA) {
      const image = await PermissionsAndroid.check(
        ANDROID_PERMISSIONS.READ_MEDIA_IMAGES,
      );
      const video = await PermissionsAndroid.check(
        ANDROID_PERMISSIONS.READ_MEDIA_VIDEO,
      );
      return image && video;
    }
    return PermissionsAndroid.check(ANDROID_PERMISSIONS.READ_EXTERNAL_STORAGE);
  } catch (err) {
    console.error('[PermissionService] checkMediaReadPermissions:', err);
    return false;
  }
}

// ─── Write / Delete permissions ──────────────────────────────────────────────

/**
 * Request write permissions.
 *
 * With MediaStore-based delete (createDeleteRequest) and gallery-save
 * (ContentResolver.insert), no explicit write runtime permission is needed
 * on Android 10+ (API 29+). The OS shows its own consent dialog when needed.
 *
 * On Android 9 and below (API ≤ 28) we still request WRITE_EXTERNAL_STORAGE.
 *
 * If your use-case requires arbitrary filesystem access via RNFS on shared
 * storage paths, call requestFullFileAccess() instead.
 */
async function requestMediaWritePermissions() {
  if (!IS_ANDROID) return true;

  // API 29+ — MediaStore handles write consent; no runtime permission needed
  if (ANDROID_SDK >= ANDROID_API.SCOPED_STORAGE) return true;

  // API ≤ 28 — legacy WRITE permission
  try {
    const result = await PermissionsAndroid.request(
      ANDROID_PERMISSIONS.WRITE_EXTERNAL_STORAGE,
      PERMISSION_RATIONALE.writeMedia,
    );
    return isGranted(result);
  } catch (err) {
    console.error('[PermissionService] requestMediaWritePermissions:', err);
    return false;
  }
}

/**
 * Request MANAGE_EXTERNAL_STORAGE (Android 11+ / API 30+).
 *
 * This is a special-app-access permission NOT needed for normal gallery
 * delete/save (those use MediaStore). Only request it when the app needs
 * direct filesystem access via RNFS for paths outside its own private dirs.
 *
 * On Android 11+ MANAGE_EXTERNAL_STORAGE cannot be requested programmatically —
 * the user must enable it in  Settings → Apps → Special app access → All files access.
 * This function prompts the user to navigate there.
 *
 * Returns true if the permission is already / subsequently granted.
 */
async function requestFullFileAccess() {
  if (!IS_ANDROID) return true;
  if (ANDROID_SDK < ANDROID_API.MANAGE_STORAGE) {
    // Below API 30 WRITE_EXTERNAL_STORAGE covers this
    return requestMediaWritePermissions();
  }

  const already = await checkManageExternalStorage();
  if (already) return true;

  // MANAGE_EXTERNAL_STORAGE cannot be requested programmatically —
  // the user must enable it in Settings → Apps → Special app access → All files access.
  // Navigate directly to the per-app settings screen.
  await new Promise(resolve =>
    Alert.alert(
      'All Files Access Required',
      'To hide folders from other gallery apps, Pandora\'s Box needs "All files access".\n\nTap Open Settings, then enable the toggle for Pandora\'s Box.',
      [
        { text: 'Not now', style: 'cancel', onPress: resolve },
        {
          text: 'Open Settings',
          onPress: async () => {
            try {
              await MediaStoreModule.openAllFilesAccessSettings();
            } catch {
              // Last resort — general settings (better than nothing)
              Linking.openSettings();
            }
            resolve();
          },
        },
      ],
    ),
  );
  return checkManageExternalStorage();
}

/**
 * Check MANAGE_EXTERNAL_STORAGE status (Android 11+).
 *
 * Uses the native MediaStoreModule.isExternalStorageManager() bridge which
 * calls Environment.isExternalStorageManager() — the only reliable way to
 * check this special-app-access permission.
 *
 * react-native-permissions v5.x does NOT export MANAGE_EXTERNAL_STORAGE,
 * so rnpCheck() cannot be used for this permission.
 * PermissionsAndroid.check() also always returns false for it.
 */
async function checkManageExternalStorage() {
  if (!IS_ANDROID || ANDROID_SDK < ANDROID_API.MANAGE_STORAGE) return true;
  try {
    return await MediaStoreModule.isExternalStorageManager();
  } catch (err) {
    console.warn('[PermissionService] checkManageExternalStorage:', err);
    return false;
  }
}

/**
 * Check write permission status without prompting.
 * Returns true on API 29+ (MediaStore handles its own consent).
 */
async function checkMediaWritePermissions() {
  if (!IS_ANDROID) return true;
  // API 29+ — no runtime write permission needed for MediaStore operations
  if (ANDROID_SDK >= ANDROID_API.SCOPED_STORAGE) return true;
  try {
    return PermissionsAndroid.check(ANDROID_PERMISSIONS.WRITE_EXTERNAL_STORAGE);
  } catch (err) {
    return false;
  }
}

// ─── Combined ────────────────────────────────────────────────────────────────

/**
 * Request all permissions needed for full gallery functionality.
 * Returns { read, write, manageStorage, all }.
 */
async function requestAllPermissions() {
  const read = await requestMediaReadPermissions();
  const write = await requestMediaWritePermissions();
  const manageStorage = await checkManageExternalStorage();
  return { read, write, manageStorage, all: read && write };
}

/**
 * Check current status of all permissions without triggering prompts.
 * Returns { read, write, manageStorage, all }.
 */
async function checkPermissions() {
  if (!IS_ANDROID)
    return { read: true, write: true, manageStorage: true, all: true };
  const [read, write, manageStorage] = await Promise.all([
    checkMediaReadPermissions(),
    checkMediaWritePermissions(),
    checkManageExternalStorage(),
  ]);
  return { read, write, manageStorage, all: read && write };
}

// ─── Optional: ACCESS_MEDIA_LOCATION (GPS in photos) ────────────────────────

async function requestMediaLocationPermission() {
  if (!IS_ANDROID) return true;
  try {
    const result = await PermissionsAndroid.request(
      ANDROID_PERMISSIONS.ACCESS_MEDIA_LOCATION,
    );
    return isGranted(result);
  } catch {
    return false;
  }
}

// ─── Export ──────────────────────────────────────────────────────────────────

export const PermissionService = {
  requestMediaReadPermissions,
  requestMediaWritePermissions,
  requestFullFileAccess,
  requestAllPermissions,
  checkPermissions,
  checkMediaReadPermissions,
  checkMediaWritePermissions,
  checkManageExternalStorage,
  requestMediaLocationPermission,
};
