/**
 * Theme — unified design-system export.
 *
 * Usage (named imports, recommended):
 *   import { Colors, Spacing, Typography, Radii, Icons } from '../theme';
 *
 * Usage (full theme object):
 *   import { Theme } from '../theme';
 *   Theme.colors.accent
 *   Theme.spacing.lg
 *   Theme.icons.heartRed
 */
export { Colors, DarkColors, LightColors } from './colors';
export { Spacing }    from './spacing';
export { Typography } from './typography';
export { Radii }      from './radii';
export { Icons }      from './icons';

import { DarkColors } from './colors';
import { Spacing }    from './spacing';
import { Typography } from './typography';
import { Radii }      from './radii';
import { Icons }      from './icons';

export const Theme = {
  colors:     DarkColors,
  spacing:    Spacing,
  typography: Typography,
  radii:      Radii,
  icons:      Icons,
};
