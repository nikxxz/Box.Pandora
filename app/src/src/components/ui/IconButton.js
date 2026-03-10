import React from 'react';
import { TouchableOpacity, StyleSheet } from 'react-native';
import { Icon } from './Icon';

/**
 * IconButton — pressable wrapper around Icon.
 *
 * Props:
 *   name      — icon key from the Icons registry
 *   onPress   — press handler
 *   size      — icon size in dp (default: 22)
 *   color     — tint color for non-colored icons (default: theme text color)
 *   style     — additional container style overrides
 *   disabled  — disables interaction and dims the icon
 */
export function IconButton({
  name,
  onPress,
  size = 22,
  color,
  style,
  disabled = false,
}) {
  return (
    <TouchableOpacity
      onPress={onPress}
      disabled={disabled}
      style={[styles.btn, disabled && styles.disabled, style]}
      hitSlop={{ top: 10, right: 10, bottom: 10, left: 10 }}
      activeOpacity={0.65}
    >
      <Icon name={name} size={size} color={color} />
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  btn: {
    padding: 4,
    alignItems: 'center',
    justifyContent: 'center',
  },
  disabled: {
    opacity: 0.4,
  },
});
