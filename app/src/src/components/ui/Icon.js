import React from 'react';
import { Image } from 'react-native';
import { Icons } from '../../theme/icons';
import { useTheme } from '../../providers/ThemeProvider';

/**
 * Icon
 *
 * Renders a PNG icon from the app-wide icon registry.
 *
 * Props:
 *   name    — key from Icons registry (e.g. 'heartRed', 'search', 'trash')
 *   size    — width & height in dp  (default: 24)
 *   color   — explicit tintColor for non-colored icons.
 *             When omitted, monochrome icons automatically inherit the
 *             current theme's text color — white in dark mode, dark in
 *             light mode — so no separate white/black PNG variants are needed.
 *             Colored icons (starYellow — Favourite star) always render in
 *             their original colour regardless of this prop.
 *   style   — additional ImageStyle overrides
 *
 * Example:
 *   <Icon name="heartRed" size={20} />               ← monochrome (tinted)
 *   <Icon name="search"   size={22} />               ← auto: theme text color
 *   <Icon name="search"   size={22} color="#fff" />  ← explicit override
 *   <Icon name="starYellow"  size={32} />            ← colored, no tint
 */
export function Icon({ name, size = 24, color, style }) {
  const { colors } = useTheme();
  const icon = Icons[name];

  if (!icon) {
    if (__DEV__) {
      console.warn(`[Icon] Unknown icon name: "${name}"`);
    }
    return null;
  }

  // Colored icons always render as-is; monochrome icons use the explicit
  // color prop or fall back to the current theme's primary text color.
  const resolvedColor = icon.colored ? undefined : color ?? colors.text;
  const tintStyle = resolvedColor ? { tintColor: resolvedColor } : null;

  return (
    <Image
      source={icon.png}
      style={[{ width: size, height: size }, tintStyle, style]}
      resizeMode="contain"
      fadeDuration={0}
    />
  );
}
