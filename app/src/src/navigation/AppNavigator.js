import React, { useEffect, useRef } from 'react';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { BottomTabNavigator } from './BottomTabNavigator';
import { FolderScreen } from '../screens/FolderScreen';
import { FolderPickerScreen } from '../screens/FolderPickerScreen';
import { TagGalleryScreen } from '../screens/TagGalleryScreen';
import { SandboxNavigator } from '../screens/SandboxScreen/SandboxNavigator';
import { PandoraMythScreen } from '../screens/PandoraMythScreen';
import { SearchScreen } from '../screens/SearchScreen';
import { TagIntelligenceScreen } from '../screens/TagIntelligenceScreen';
import { useTheme } from '../providers/ThemeProvider';
import { DatabaseService } from '../services/database/DatabaseService';
import { TagService } from '../services/database/TagService';
import { useMediaLibrary } from '../hooks/useMediaLibrary';
import { useBackgroundSync } from '../hooks/useBackgroundSync';
import { useStandbyMode } from '../hooks/useStandbyMode';
import { navigationRef } from './navigationRef';
import { useAppContext } from '../store/AppContext';
import { AppActions } from '../store/actions';

const Stack = createNativeStackNavigator();

/**
 * AppNavigator — root navigation tree.
 *
 * Also owns the app-startup sequence:
 *   1. DatabaseService.init() — opens SQLite, creates schema, sets PRAGMAs
 *   2. loadUserData()         — populates state.favorites + state.tags from SQLite
 *
 * Both steps are fire-and-forget (useEffect, non-blocking render).
 * DatabaseService.init() is also called defensively in ThemeProvider and
 * MediaIndexer so no service can use the DB before it is open.
 *
 * Stack:
 *   Main (BottomTabNavigator)
 *     └── FolderDetail (FolderScreen) — pushed on folder tap
 */
const AUTO_HIDE_MS = 2 * 60 * 1000; // 2 minutes

export default function AppNavigator() {
  const { colors } = useTheme();
  const { loadUserData } = useMediaLibrary();
  const { state: appState, dispatch } = useAppContext();

  // Start the lightweight background sync (new-file detection + thumb warming).
  // It self-cancels on user activity, app-backgrounded, or time budget exceeded.
  useBackgroundSync();

  // Power-saver: cancel ML jobs and flush image LRU when the app is backgrounded.
  useStandbyMode();

  // ── Boot: init DB then load user data ─────────────────────────────────────
  useEffect(() => {
    async function boot() {
      try {
        await DatabaseService.init();
        await loadUserData();
        // Fire-and-forget: re-classify existing tags into the correct category.
        // Only updates rows still marked 'misc' (i.e. created before v9).
        TagService.backfillTagCategories().catch(() => {});
      } catch (err) {
        console.warn('[AppNavigator] boot error:', err);
      }
    }
    boot();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── Auto-hide: when showHidden turns on, hide again after 2 minutes ────────
  const autoHideRef = useRef(null);
  useEffect(() => {
    if (appState.showHidden) {
      autoHideRef.current = setTimeout(() => {
        dispatch(AppActions.setShowHidden(false));
      }, AUTO_HIDE_MS);
    } else {
      if (autoHideRef.current) {
        clearTimeout(autoHideRef.current);
        autoHideRef.current = null;
      }
    }
    return () => {
      if (autoHideRef.current) {
        clearTimeout(autoHideRef.current);
      }
    };
  }, [appState.showHidden, dispatch]);

  return (
    <NavigationContainer ref={navigationRef}>
      <Stack.Navigator
        screenOptions={{
          headerShown: false,
          contentStyle: { backgroundColor: colors.background },
          animation: 'slide_from_right',
        }}
      >
        <Stack.Screen name="Main" component={BottomTabNavigator} />
        <Stack.Screen
          name="FolderDetail"
          component={FolderScreen}
          options={{ animation: 'slide_from_right' }}
        />
        <Stack.Screen
          name="FolderPicker"
          component={FolderPickerScreen}
          options={{ animation: 'slide_from_bottom' }}
        />
        <Stack.Screen
          name="TagGallery"
          component={TagGalleryScreen}
          options={{ animation: 'slide_from_right' }}
        />
        <Stack.Screen
          name="Settings"
          component={SandboxNavigator}
          options={{ animation: 'slide_from_right' }}
        />
        <Stack.Screen
          name="PandoraMyth"
          component={PandoraMythScreen}
          options={{ animation: 'slide_from_bottom' }}
        />
        <Stack.Screen
          name="Search"
          component={SearchScreen}
          options={{ animation: 'slide_from_bottom' }}
        />
        <Stack.Screen
          name="TagIntelligence"
          component={TagIntelligenceScreen}
          options={{ animation: 'slide_from_right' }}
        />
      </Stack.Navigator>
    </NavigationContainer>
  );
}
