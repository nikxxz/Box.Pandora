import { StyleSheet } from 'react-native';

export const createStyles = colors =>
  StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },
    deleteBtn: {
      padding: 8,
      borderRadius: 20,
      alignItems: 'center',
      justifyContent: 'center',
    },
    deleteText: {
      fontSize: 15,
      fontWeight: '600',
    },
  });
