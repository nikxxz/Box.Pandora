import React from 'react';
import { TouchableOpacity, StyleSheet } from 'react-native';
import { Icon } from './Icon';
import { useTheme } from '../../providers/ThemeProvider';

const HIT_SLOP = { top: 10, right: 10, bottom: 10, left: 10 };

export function CloseButton({
  onPress,
  style,
  size = 18,
  color,
  hitSlop = HIT_SLOP,
  activeOpacity = 0.6,
}) {
  const { colors } = useTheme();
  const resolvedColor = color ?? colors.textSecondary;

  return (
    <TouchableOpacity
      onPress={onPress}
      style={[styles.button, style]}
      hitSlop={hitSlop}
      activeOpacity={activeOpacity}
    >
      <Icon name="remove" size={size} color={resolvedColor} />
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  button: {
    alignItems: 'center',
    justifyContent: 'center',
  },
});
