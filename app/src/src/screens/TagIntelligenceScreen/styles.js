import { StyleSheet } from 'react-native';

export const createStyles = colors =>
  StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },

    // ─── Tag list ─────────────────────────────────────────────────────────────
    listContent: {
      paddingHorizontal: 14,
      paddingBottom: 32,
    },
    listHeader: {
      paddingVertical: 12,
      gap: 4,
    },
    listHeaderTitle: {
      fontSize: 11,
      fontWeight: '700',
      letterSpacing: 1.5,
      color: colors.textTertiary,
      textTransform: 'uppercase',
    },
    listHeaderSub: {
      fontSize: 12,
      color: colors.textSecondary,
    },
    searchInput: {
      height: 40,
      borderWidth: 1,
      borderRadius: 10,
      paddingHorizontal: 12,
      fontSize: 14,
    },

    tagRow: {
      flexDirection: 'row',
      alignItems: 'center',
      paddingVertical: 11,
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: colors.divider,
      gap: 10,
    },
    tagColorDot: {
      width: 10,
      height: 10,
      borderRadius: 5,
    },
    tagRowContent: {
      flex: 1,
    },
    tagRowName: {
      fontSize: 14,
      fontWeight: '600',
      color: colors.text,
    },
    tagRowMeta: {
      fontSize: 11,
      color: colors.textTertiary,
      marginTop: 1,
    },
    tagRowRight: {
      alignItems: 'flex-end',
      gap: 3,
    },
    tagReviewedBadge: {
      fontSize: 9,
      fontWeight: '700',
      letterSpacing: 1,
      color: colors.textTertiary,
      textTransform: 'uppercase',
    },
    tagPendingBadge: {
      fontSize: 9,
      fontWeight: '700',
      letterSpacing: 1,
      textTransform: 'uppercase',
    },
    chevron: {
      fontSize: 14,
      color: colors.textTertiary,
    },

    // ─── Loading ─────────────────────────────────────────────────────────────
    loadingWrap: {
      flex: 1,
      alignItems: 'center',
      justifyContent: 'center',
      gap: 12,
    },
    loadingLabel: {
      fontSize: 13,
      fontWeight: '500',
      color: colors.textSecondary,
    },

    // ─── Detail view ──────────────────────────────────────────────────────────
    detailScroll: {
      flex: 1,
    },
    detailScrollContent: {
      paddingHorizontal: 16,
      paddingBottom: 40,
    },

    // Tag header card
    tagCard: {
      borderWidth: 1,
      borderRadius: 14,
      padding: 16,
      marginBottom: 20,
      gap: 6,
    },
    tagCardName: {
      fontSize: 20,
      fontWeight: '700',
      color: colors.text,
    },
    tagCardMeta: {
      fontSize: 12,
      color: colors.textSecondary,
    },
    tagCardDesc: {
      fontSize: 13,
      color: colors.textSecondary,
      marginTop: 4,
      lineHeight: 19,
    },

    analyzeBtn: {
      height: 44,
      borderRadius: 12,
      alignItems: 'center',
      justifyContent: 'center',
      marginBottom: 20,
    },
    analyzeBtnLabel: {
      fontSize: 14,
      fontWeight: '700',
      color: '#fff',
      letterSpacing: 0.3,
    },

    // ─── Section header ───────────────────────────────────────────────────────
    sectionLabel: {
      fontSize: 9,
      fontWeight: '700',
      letterSpacing: 2,
      color: colors.textTertiary,
      textTransform: 'uppercase',
      marginBottom: 8,
    },

    // ─── Suggestion card ──────────────────────────────────────────────────────
    suggestionCard: {
      borderWidth: 1,
      borderRadius: 12,
      padding: 14,
      marginBottom: 10,
      gap: 6,
    },
    suggestionType: {
      fontSize: 9,
      fontWeight: '700',
      letterSpacing: 1.5,
      textTransform: 'uppercase',
    },
    suggestionValue: {
      fontSize: 15,
      fontWeight: '600',
      color: colors.text,
    },
    suggestionReasoning: {
      fontSize: 12,
      color: colors.textSecondary,
      lineHeight: 17,
    },
    editInput: {
      borderWidth: 1,
      borderRadius: 8,
      paddingHorizontal: 10,
      paddingVertical: 8,
      fontSize: 14,
      marginTop: 4,
    },
    suggestionActions: {
      flexDirection: 'row',
      gap: 8,
      marginTop: 4,
    },
    actionBtn: {
      flex: 1,
      height: 36,
      borderRadius: 8,
      borderWidth: 1,
      alignItems: 'center',
      justifyContent: 'center',
    },
    actionBtnLabel: {
      fontSize: 12,
      fontWeight: '700',
      letterSpacing: 0.3,
    },

    // ─── Aliases section ──────────────────────────────────────────────────────
    aliasesWrap: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: 6,
      marginBottom: 20,
    },
    aliasPill: {
      flexDirection: 'row',
      alignItems: 'center',
      height: 28,
      paddingHorizontal: 10,
      borderRadius: 14,
      borderWidth: 1,
      gap: 6,
    },
    aliasPillText: {
      fontSize: 12,
      fontWeight: '500',
      color: colors.textSecondary,
    },
    aliasPillRemove: {
      fontSize: 12,
      color: colors.textTertiary,
    },

    // ─── Empty suggestions ────────────────────────────────────────────────────
    emptyBox: {
      alignItems: 'center',
      justifyContent: 'center',
      paddingVertical: 32,
      gap: 8,
    },
    emptyIcon: {
      fontSize: 28,
      color: colors.textTertiary,
    },
    emptyLabel: {
      fontSize: 13,
      color: colors.textSecondary,
      textAlign: 'center',
    },
  });
