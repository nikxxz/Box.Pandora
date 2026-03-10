import { StyleSheet } from 'react-native';

/**
 * createStyles — theme-aware style factory.
 * Call inside a component with useMemo:
 *   const styles = useMemo(() => createStyles(colors), [colors]);
 */
export const createStyles = colors =>
  StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
      paddingHorizontal: 20,
    },
    title: {
      fontSize: 28,
      fontWeight: '300',
      color: colors.text,
      letterSpacing: 1,
      marginBottom: 24,
    },
    section: {
      marginBottom: 28,
    },
    sectionTitle: {
      fontSize: 12,
      fontWeight: '700',
      color: colors.textTertiary,
      letterSpacing: 1.4,
      textTransform: 'uppercase',
      marginBottom: 10,
    },
    row: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingVertical: 15,
      paddingHorizontal: 4,
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: colors.divider,
      backgroundColor: colors.surface,
    },
    rowLeft: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 10,
      flex: 1,
    },
    rowLabel: {
      color: colors.text,
      fontSize: 15,
    },
    rowValue: {
      color: colors.textSecondary,
      fontSize: 14,
    },
    rowChevron: {
      color: colors.textTertiary,
      fontSize: 22,
      fontWeight: '300',
      lineHeight: 24,
    },
    dangerBtn: {
      marginTop: 8,
      paddingVertical: 14,
      alignItems: 'center',
      borderRadius: 10,
      backgroundColor: colors.isDark ? '#2A1010' : '#FEF2F2',
      borderWidth: 1,
      borderColor: colors.isDark ? '#4A1818' : '#FECACA',
    },
    dangerBtnText: {
      color: colors.error,
      fontSize: 15,
      fontWeight: '600',
    },
  });
