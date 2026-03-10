import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { useTheme } from '../../providers/ThemeProvider';

export function EmptyState({ icon = '☐', message = 'Nothing here yet', subMessage }) {
  const { colors } = useTheme();
  return (
    <View style={styles.container}>
      <Text style={[styles.icon, { color: colors.textTertiary }]}>{icon}</Text>
      <Text style={[styles.message, { color: colors.textSecondary }]}>{message}</Text>
      {subMessage ? (
        <Text style={[styles.sub, { color: colors.textTertiary }]}>{subMessage}</Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex:              1,
    alignItems:        'center',
    justifyContent:    'center',
    paddingHorizontal: 32,
    paddingTop:        80,
  },
  icon:    { fontSize: 48, marginBottom: 16 },
  message: { fontSize: 17, fontWeight: '500', textAlign: 'center' },
  sub:     { marginTop: 8, fontSize: 14, textAlign: 'center' },
});
