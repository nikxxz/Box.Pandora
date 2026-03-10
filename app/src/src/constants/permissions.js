import { Platform } from 'react-native';

// Android API levels referenced throughout the permission service
export const ANDROID_API = {
  SCOPED_STORAGE: 29,       // Android 10 — scoped storage starts
  MANAGE_STORAGE: 30,       // Android 11 — MANAGE_EXTERNAL_STORAGE introduced
  GRANULAR_MEDIA: 33,       // Android 13 — per-media-type permissions introduced
};

// Raw Android permission strings
export const ANDROID_PERMISSIONS = {
  // Android 13+ (API 33+)
  READ_MEDIA_IMAGES: 'android.permission.READ_MEDIA_IMAGES',
  READ_MEDIA_VIDEO: 'android.permission.READ_MEDIA_VIDEO',
  READ_MEDIA_AUDIO: 'android.permission.READ_MEDIA_AUDIO',
  READ_MEDIA_VISUAL_USER_SELECTED: 'android.permission.READ_MEDIA_VISUAL_USER_SELECTED', // API 34+

  // Android 10–12 (API 29–32)
  READ_EXTERNAL_STORAGE: 'android.permission.READ_EXTERNAL_STORAGE',

  // Android 9 and below (API ≤ 28)
  WRITE_EXTERNAL_STORAGE: 'android.permission.WRITE_EXTERNAL_STORAGE',

  // Android 11+ (API 30+) — Full filesystem access (needs Settings intent)
  MANAGE_EXTERNAL_STORAGE: 'android.permission.MANAGE_EXTERNAL_STORAGE',

  // All API levels — GPS coordinates embedded in media
  ACCESS_MEDIA_LOCATION: 'android.permission.ACCESS_MEDIA_LOCATION',
};

export const IS_ANDROID = Platform.OS === 'android';
export const ANDROID_SDK = IS_ANDROID ? (Platform.Version ?? 0) : 0;

// Rationale strings shown in the permission dialogs
export const PERMISSION_RATIONALE = {
  readMedia: {
    title: 'Media Access Required',
    message:
      'Pandora needs access to your photos, videos, and audio files to display your gallery.',
    buttonNeutral: 'Ask Later',
    buttonNegative: 'Deny',
    buttonPositive: 'Allow',
  },
  writeMedia: {
    title: 'Storage Write Access',
    message:
      'Pandora needs write access to delete and move your media files.',
    buttonNeutral: 'Ask Later',
    buttonNegative: 'Deny',
    buttonPositive: 'Allow',
  },
};
