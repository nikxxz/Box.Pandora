import { StyleSheet } from 'react-native';

export const createStyles = colors =>
  StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },

    // ─── Mode banner (shown below the header) ────────────────────────────────
    banner: {
      flexDirection: 'row',
      alignItems: 'center',
      paddingHorizontal: 20,
      paddingVertical: 14,
      backgroundColor: colors.surface,
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: colors.border,
      gap: 10,
    },
    bannerText: {
      flex: 1,
      fontSize: 13,
      color: colors.textSecondary,
      lineHeight: 18,
    },
    bannerCount: {
      fontWeight: '700',
      color: colors.text,
    },

    // ─── Loading overlay ─────────────────────────────────────────────────────
    loadingOverlay: {
      ...StyleSheet.absoluteFillObject,
      backgroundColor: colors.overlay ?? 'rgba(0,0,0,0.45)',
      alignItems: 'center',
      justifyContent: 'center',
      zIndex: 10,
    },
    loadingCard: {
      backgroundColor: colors.surface,
      borderRadius: 16,
      paddingHorizontal: 32,
      paddingVertical: 24,
      alignItems: 'center',
      gap: 14,
      minWidth: 180,
    },
    loadingText: {
      fontSize: 14,
      color: colors.textSecondary,
      textAlign: 'center',
    },
  });
