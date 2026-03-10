import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { useTheme } from '../../providers/ThemeProvider';

export function Badge({ count, style }) {
  const { colors } = useTheme();
  if (!count && count !== 0) return null;
  return (
    <View style={[styles.badge, { backgroundColor: colors.accent }, style]}>
      <Text style={styles.text}>{count > 999 ? '999+' : count}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  badge: {
    minWidth:          20,
    height:            20,
    borderRadius:      10,
    alignItems:        'center',
    justifyContent:    'center',
    paddingHorizontal:  5,
  },
  text: { color: '#FFFFFF', fontSize: 11, fontWeight: '700' },
});
