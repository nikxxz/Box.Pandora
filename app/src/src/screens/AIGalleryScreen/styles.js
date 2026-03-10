import { StyleSheet } from 'react-native';

export const createStyles = colors =>
  StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },

    // ─── Search bar ───────────────────────────────────────────────────────────
    searchBar: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 10,
      paddingHorizontal: 14,
      paddingVertical: 10,
      borderBottomWidth: 0.5,
    },
    searchInput: {
      flex: 1,
      height: 44,
      borderRadius: 12,
      borderWidth: 1,
      paddingHorizontal: 14,
      fontSize: 14,
    },
    sendBtn: {
      width: 44,
      height: 44,
      borderRadius: 12,
      alignItems: 'center',
      justifyContent: 'center',
    },
    sendBtnDisabled: { opacity: 0.4 },
    sendBtnLabel: {
      fontSize: 20,
      fontWeight: '700',
      color: '#FFFFFF',
      lineHeight: 24,
    },

    // ─── Loading ──────────────────────────────────────────────────────────────
    loadingWrap: {
      flex: 1,
      alignItems: 'center',
      justifyContent: 'center',
      gap: 14,
    },
    loadingLabel: {
      fontSize: 13,
      fontWeight: '500',
      letterSpacing: 0.5,
    },

    // ─── List header ──────────────────────────────────────────────────────────
    listHeader: {
      paddingHorizontal: 14,
      paddingTop: 12,
      paddingBottom: 6,
      gap: 10,
    },

    // ─── Resolution sections ──────────────────────────────────────────────────
    resolveSection: {
      gap: 6,
    },
    resolveLabel: {
      fontSize: 9,
      fontWeight: '700',
      letterSpacing: 2,
      color: colors.textTertiary,
    },

    // ─── Chips ────────────────────────────────────────────────────────────────
    chipsRow: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: 6,
    },
    chip: {
      height: 28,
      paddingHorizontal: 10,
      borderRadius: 14,
      borderWidth: 1,
      borderColor: colors.border,
      alignItems: 'center',
      justifyContent: 'center',
    },
    chipResolved: {
      borderWidth: 1.5,
    },
    chipUnmatched: {
      borderStyle: 'dashed',
      opacity: 0.6,
    },
    chipText: {
      fontSize: 12,
      fontWeight: '500',
      color: colors.textSecondary,
    },

    // ─── Result meta ──────────────────────────────────────────────────────────
    resultMeta: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
    },
    resultCount: {
      fontSize: 11,
      fontWeight: '600',
      letterSpacing: 1,
      color: colors.textTertiary,
      textTransform: 'uppercase',
    },
    matchNote: {
      fontSize: 11,
      color: colors.textTertiary,
    },

    // ─── Debug panel ──────────────────────────────────────────────────────────
    debugToggle: {
      alignSelf: 'flex-start',
      paddingVertical: 2,
    },
    debugToggleLabel: {
      fontSize: 12,
      fontWeight: '600',
      letterSpacing: 0.3,
    },
    debugPanel: {
      borderWidth: 0.5,
      borderRadius: 12,
      padding: 14,
      gap: 2,
      backgroundColor: colors.surface,
    },
    debugJsonWrap: {
      marginTop: 6,
      gap: 4,
    },
    debugKey: {
      fontSize: 9,
      fontWeight: '700',
      letterSpacing: 1.5,
      color: colors.textTertiary,
    },
    debugJson: {
      fontSize: 11,
      fontFamily: 'monospace',
      color: colors.textSecondary,
      lineHeight: 17,
    },
  });
