/**
 * Color tokens — Day and Night palettes.
 *
 * Use `useTheme().colors` in components for theme-aware colors.
 * `Colors` is kept as a backward-compat alias for the dark palette.
 */

// ─── Night (Dark) ──────────────────────────────────────────────────────────────
export const DarkColors = {
  // Nothing OS-inspired: higher contrast, stricter monochrome, clear surface steps
  background: '#0A0A0A',
  surface: '#141414',
  card: '#1A1A1A',
  border: '#2E2E2E',
  divider: '#242424',

  text: '#FFFFFF',
  textSecondary: '#B6B6B6',
  textTertiary: '#7A7A7A',

  // Default accent aims for Nothing-like "alert" highlight; user can override.
  accent: '#EF4444',
  accentDim: '#B91C1C',

  tabActive: '#FFFFFF',
  tabInactive: '#7A7A7A',

  // ── FolderCard-specific tokens ──────────────────────────────────────────
  cardPanel: '#121212', // SVG folder-tab fill
  cardBorder: '#2B2B2B', // card outline
  cardPanelText: '#FFFFFF', // primary text on panel
  cardPanelTextDim: 'rgba(255,255,255,0.40)', // secondary/dim text on panel

  overlay: 'rgba(0, 0, 0, 0.62)',
  overlayLight: 'rgba(0, 0, 0, 0.34)',
  overlayStrong: 'rgba(0, 0, 0, 0.88)',

  error: '#EF4444',
  success: '#22C55E',
  warning: '#EAB308',
  info: '#3B82F6',

  pressed: 'rgba(255, 255, 255, 0.08)',
  focused: 'rgba(91, 155, 213, 0.15)',
  ripple: 'rgba(255, 255, 255, 0.10)',

  transparent: 'transparent',
  white: '#FFFFFF',
  black: '#000000',
};

// ─── Day (Light) ───────────────────────────────────────────────────────────────
export const LightColors = {
  // Nothing-ish "light" (still neutral, not overly blue)
  background: '#F4F4F5',
  surface: '#FFFFFF',
  card: '#FFFFFF',
  border: '#E5E5E5',
  divider: '#EEEEEE',

  text: '#111111',
  textSecondary: '#5F5F5F',
  textTertiary: '#8B8B8B',

  accent: '#DC2626',
  accentDim: '#B91C1C',

  tabActive: '#111111',
  tabInactive: '#8B8B8B',

  // ── FolderCard-specific tokens ──────────────────────────────────────────
  cardPanel: '#EFEFEF', // SVG folder-tab fill (near-white)
  cardBorder: '#D6D6D6', // card outline
  cardPanelText: '#1A1A1A', // primary text on panel
  cardPanelTextDim: 'rgba(0,0,0,0.45)', // secondary/dim text on panel

  overlay: 'rgba(0, 0, 0, 0.42)',
  overlayLight: 'rgba(0, 0, 0, 0.14)',
  overlayStrong: 'rgba(0, 0, 0, 0.62)',

  error: '#EF4444',
  success: '#22C55E',
  warning: '#EAB308',
  info: '#2563EB',

  pressed: 'rgba(0, 0, 0, 0.06)',
  focused: 'rgba(37, 99, 235, 0.12)',
  ripple: 'rgba(0, 0, 0, 0.08)',

  transparent: 'transparent',
  white: '#FFFFFF',
  black: '#000000',
};

// Backward-compat — existing components that import `Colors` always get dark.
export const Colors = DarkColors;
