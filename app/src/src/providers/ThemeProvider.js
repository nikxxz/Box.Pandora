import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
} from 'react';
import { useColorScheme } from 'react-native';
import { DarkColors, LightColors } from '../theme/colors';
import { DatabaseService } from '../services/database/DatabaseService';
import { PreferenceService } from '../services/database/PreferenceService';

/**
 * ThemeProvider — Day / Night theme context with SQLite persistence.
 *
 * On mount:  loads the saved theme from SQLite (via PreferenceService).
 * On toggle: persists the new theme to SQLite before updating state.
 *
 * The DB init is called defensively here because ThemeProvider sits above
 * AppNavigator in the tree (where the main init call also lives). The init()
 * is idempotent and concurrent-safe so calling it from multiple places is fine.
 *
 * Usage:
 *   const { colors, isDark, toggleTheme } = useTheme();
 */

const THEME_PREF_KEY = 'app_theme';

const ThemeContext = createContext(null);

export function ThemeProvider({ children }) {
  // Seed from the OS colour scheme so the first render already matches the
  // device preference. This eliminates the dark→light flash that occurred when
  // a light-mode user's saved preference was loaded asynchronously from SQLite.
  const systemScheme = useColorScheme();
  const [theme, setTheme] = useState(
    systemScheme === 'light' ? 'light' : 'dark',
  );

  // ── Load persisted theme on mount ─────────────────────────────────────────
  useEffect(() => {
    async function loadTheme() {
      try {
        await DatabaseService.init();
        const saved = await PreferenceService.get(THEME_PREF_KEY);
        if (saved === 'light' || saved === 'dark') {
          setTheme(saved);
        }
      } catch {
        // DB unavailable on first launch — keep default 'dark'
      }
    }
    loadTheme();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Toggle with persistence ────────────────────────────────────────────────
  const toggleTheme = useCallback(() => {
    setTheme(t => {
      const next = t === 'dark' ? 'light' : 'dark';
      // Persist in background — non-blocking, non-fatal
      PreferenceService.set(THEME_PREF_KEY, next).catch(() => {});
      return next;
    });
  }, []);

  const isDark = theme === 'dark';

  return (
    <ThemeContext.Provider
      value={{
        theme,
        colors: isDark ? DarkColors : LightColors,
        isDark,
        toggleTheme,
        setTheme,
      }}
    >
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme() {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useTheme must be used inside ThemeProvider');
  return ctx;
}
