import { StyleSheet } from 'react-native';

export const createStyles = colors =>
  StyleSheet.create({
    // ─── Screen root ────────────────────────────────────────────────────────
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },

    // ─── ListHeaderComponent (tabs) ──────────────────────────────────────────
    listHeader: {
      paddingHorizontal: 20,
      paddingBottom: 16,
    },

    // ─── Tab row ─────────────────────────────────────────────────────────────
    tabRow: {
      flexDirection: 'row',
      alignItems: 'center',
    },

    tab: {
      paddingHorizontal: 16,
      height: 36,
      alignItems: 'center',
      justifyContent: 'center',
    },
    tabLabel: {
      fontSize: 13,
      fontWeight: '500',
      letterSpacing: 0.3,
    },
    tabLabelActive: {
      fontWeight: '700',
    },
  });
