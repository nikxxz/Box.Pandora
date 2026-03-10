/**
 * SettingsSidebar
 *
 * A slide-in panel from the right edge of the screen.
 * Contains quick-access toggles and actions for the gallery home screen.
 *
 * Props:
 *   visible          — boolean  (controlled show/hide)
 *   onClose          — () => void
 *   onNavigate       — (routeName: string) => void
 *   onCreateFolder   — () => void
 */

import React, {
  useEffect,
  useRef,
  useCallback,
  useMemo,
  useState,
} from 'react';
import {
  Alert,
  Animated,
  Dimensions,
  Easing,
  Modal,
  Pressable,
  StyleSheet,
  Switch,
  Text,
  TouchableOpacity,
  TouchableWithoutFeedback,
  View,
  ScrollView,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../../providers/ThemeProvider';
import { useToast } from '../../providers/ToastProvider';
import { useAppContext } from '../../store/AppContext';
import { useMediaContext } from '../../store/MediaContext';
import { AppActions, MediaActions } from '../../store/actions';
import { Icon } from '../ui/Icon';
import { CloseButton } from '../ui/CloseButton';
import { PasscodeModal } from '../ui/PasscodeModal';
import { useMediaLibrary } from '../../hooks/useMediaLibrary';
import { MediaIndexer } from '../../services/media/MediaIndexer';
import { CacheService } from '../../services/cache/CacheService';
import { PreferenceService } from '../../services/database/PreferenceService';
import { ImageCache } from '../../services/cache/ImageCache';
import { BackupService } from '../../services/backup';
import { SecurityService } from '../../services/auth/SecurityService';
import {
  BiometricService,
  BiometricError,
} from '../../services/auth/BiometricService';
import { ScanLogService } from '../../services/database/ScanLogService';
import { APP_VERSION, APP_BUILD } from '../../constants/version';
import { DB_VERSION } from '../../services/database/schema';

const SCREEN_WIDTH = Dimensions.get('window').width;
const PANEL_WIDTH = Math.min(SCREEN_WIDTH * 0.84, 340);

const ANIM_DURATION = 280;

// ─── Accent colour palette ────────────────────────────────────────────────────

const ACCENT_COLORS = [
  // Curated, high-contrast accents (Nothing OS-like “punch” on dark UI)
  // Keep these saturated and readable against near-black surfaces.
  '#EF4444', // red
  '#F43F5E', // rose
  '#EC4899', // pink
  '#A855F7', // purple
  '#6366F1', // indigo
  '#3B82F6', // blue
  '#0EA5E9', // sky
  '#06B6D4', // cyan
  '#14B8A6', // teal
  '#22C55E', // green
  '#84CC16', // lime
  '#EAB308', // yellow
  '#F59E0B', // amber
  '#F97316', // orange
];

// ─── Sort option config ────────────────────────────────────────────────────────

const SORT_OPTIONS = [
  { key: 'date', label: 'Date' },
  { key: 'name', label: 'Name' },
  { key: 'count', label: 'Count' },
];

// ─── Component ────────────────────────────────────────────────────────────────

export function SettingsSidebar({
  visible,
  onClose,
  onNavigate,
  onCreateFolder,
}) {
  const insets = useSafeAreaInsets();
  const { colors, isDark, toggleTheme } = useTheme();
  const toast = useToast();
  const { state: appState, dispatch } = useAppContext();
  const { dispatch: mediaDispatch } = useMediaContext();

  const showHidden = appState.showHidden;
  const sortBy = appState.sortBy ?? 'date';
  const accentColor = appState.accentColor ?? null;

  const [showColorPicker, setShowColorPicker] = useState(false);

  const { loadIndex } = useMediaLibrary();
  const [isReindexing, setIsReindexing] = useState(false);
  const [isClearingCache, setIsClearingCache] = useState(false);
  const [isExporting, setIsExporting] = useState(false);
  const [isImporting, setIsImporting] = useState(false);
  const [cacheEntries, setCacheEntries] = useState(null);
  const [lastScanTime, setLastScanTime] = useState(null);

  // ── Auth / security state (hide-files) ───────────────────────────────────
  // Loaded from SQLite every time the sidebar opens so it stays in sync.
  const [hasPasscode, setHasPasscode] = useState(false);
  const [biometricEnabled, setBiometricEnabled] = useState(false);
  const [passcodeModalVisible, setPasscodeModalVisible] = useState(false);
  const [passcodeMode, setPasscodeMode] = useState('verify');
  // Used to track what action should run after a successful "verify" auth
  // (e.g. opening Show Hidden vs something else).
  const pendingActionRef = useRef(null);

  // ── Auth / security state (app lock) ──────────────────────────────────────
  const [appLockHasPasscode, setAppLockHasPasscode] = useState(false);
  const [appLockBiometricEnabled, setAppLockBiometricEnabled] = useState(false);
  const [appLockModalVisible, setAppLockModalVisible] = useState(false);
  const [appLockModalMode, setAppLockModalMode] = useState('setup');

  // Load auth configs + cache/scan stats whenever the sidebar opens.
  // No deferral needed — the slide-in runs on the native thread (useNativeDriver),
  // so JS-thread setState calls during open don't affect animation frame rate.
  useEffect(() => {
    if (!visible) return;
    SecurityService.getPasscode()
      .then(p => setHasPasscode(p !== null))
      .catch(() => {});
    SecurityService.isBiometricEnabled()
      .then(b => setBiometricEnabled(b))
      .catch(() => {});
    SecurityService.getAppLockPasscode()
      .then(p => setAppLockHasPasscode(p !== null))
      .catch(() => {});
    SecurityService.isAppLockBiometricEnabled()
      .then(b => setAppLockBiometricEnabled(b))
      .catch(() => {});
    CacheService.getStats()
      .then(s => setCacheEntries(s?.totalEntries ?? null))
      .catch(() => {});
    ScanLogService.getLastFullScanTime()
      .then(t => setLastScanTime(t))
      .catch(() => {});
  }, [visible]);

  // ── Animation values ──────────────────────────────────────────────────────
  const panelAnim = useRef(new Animated.Value(PANEL_WIDTH)).current;
  const backdropAnim = useRef(new Animated.Value(0)).current;

  // `mountedVisible` keeps the Modal in the React tree during the slide-out
  // animation.  Without it, `visible=false` instantly unmounts the Modal and
  // the closing animation never plays.
  const [mountedVisible, setMountedVisible] = useState(false);

  useEffect(() => {
    if (visible) {
      // Reset anim values to start position, mount, then animate in.
      panelAnim.setValue(PANEL_WIDTH);
      backdropAnim.setValue(0);
      setMountedVisible(true);
      Animated.parallel([
        Animated.timing(panelAnim, {
          toValue: 0,
          duration: ANIM_DURATION,
          easing: Easing.out(Easing.cubic),
          useNativeDriver: true,
        }),
        Animated.timing(backdropAnim, {
          toValue: 1,
          duration: ANIM_DURATION,
          easing: Easing.out(Easing.quad),
          useNativeDriver: true,
        }),
      ]).start();
    } else {
      // Animate out first, then unmount so the slide-out is visible.
      Animated.parallel([
        Animated.timing(panelAnim, {
          toValue: PANEL_WIDTH,
          duration: ANIM_DURATION - 40,
          easing: Easing.in(Easing.cubic),
          useNativeDriver: true,
        }),
        Animated.timing(backdropAnim, {
          toValue: 0,
          duration: ANIM_DURATION - 40,
          easing: Easing.in(Easing.quad),
          useNativeDriver: true,
        }),
      ]).start(({ finished }) => {
        if (finished) setMountedVisible(false);
      });
    }
  }, [visible, panelAnim, backdropAnim]);

  // ── Handlers ──────────────────────────────────────────────────────────────

  // Called after PasscodeModal (verify mode) succeeds or biometric auth succeeds
  const onAuthSuccess = useCallback(() => {
    setPasscodeModalVisible(false);
    const action = pendingActionRef.current;
    pendingActionRef.current = null;
    action?.();
  }, []);

  // Handles press on the lock/unlock button for Show Hidden
  const handleLockButtonPress = useCallback(async () => {
    if (showHidden) {
      // Currently unlocked → lock immediately, no auth needed
      dispatch(AppActions.setShowHidden(false));
      return;
    }

    // Currently locked → need to unlock

    if (biometricEnabled) {
      // Biometric: show system prompt directly
      pendingActionRef.current = () => dispatch(AppActions.setShowHidden(true));
      try {
        await BiometricService.authenticate(
          'Show Hidden Items',
          'Authenticate to view hidden files',
        );
        onAuthSuccess();
      } catch (err) {
        if (err?.code !== BiometricError.CANCELLED) {
          Alert.alert(
            'Authentication Failed',
            err?.message ?? 'Could not authenticate.',
          );
        }
        pendingActionRef.current = null;
      }
      return;
    }

    if (hasPasscode) {
      // Passcode set → open verify modal
      pendingActionRef.current = () => dispatch(AppActions.setShowHidden(true));
      setPasscodeMode('verify');
      setPasscodeModalVisible(true);
      return;
    }

    // No auth configured → open setup modal so user can set a passcode
    // (items will show after setup completes)
    pendingActionRef.current = () => dispatch(AppActions.setShowHidden(true));
    setPasscodeMode('setup');
    setPasscodeModalVisible(true);
  }, [showHidden, dispatch, biometricEnabled, hasPasscode, onAuthSuccess]);

  // ── Security: biometric setup / removal ───────────────────────────────────

  const handleAddBiometric = useCallback(async () => {
    const available = await BiometricService.isAvailable();
    if (!available) {
      Alert.alert(
        'Not Available',
        'No biometric or device credential is enrolled on this device. Set up a fingerprint, face, or screen lock in your device settings first.',
        [{ text: 'OK' }],
      );
      return;
    }

    try {
      await BiometricService.authenticate(
        'Enable Biometric Auth',
        'Confirm your identity to enable biometric unlock',
      );
      await SecurityService.saveBiometricEnabled(true); // also clears stored passcode
      setBiometricEnabled(true);
      setHasPasscode(false);
    } catch (err) {
      if (err?.code !== BiometricError.CANCELLED) {
        Alert.alert(
          'Setup Failed',
          err?.message ?? 'Could not enable biometric auth.',
        );
      }
    }
  }, []);

  const handleRemoveBiometric = useCallback(async () => {
    try {
      await BiometricService.authenticate(
        'Remove Biometric Auth',
        'Confirm your identity to disable biometric unlock',
      );
      await SecurityService.saveBiometricEnabled(false);
      setBiometricEnabled(false);
    } catch (err) {
      if (err?.code !== BiometricError.CANCELLED) {
        Alert.alert(
          'Removal Failed',
          err?.message ?? 'Could not disable biometric auth.',
        );
      }
    }
  }, []);

  // ── App Lock: biometric ───────────────────────────────────────────────────

  const handleAppLockAddBiometric = useCallback(async () => {
    const available = await BiometricService.isAvailable();
    if (!available) {
      Alert.alert(
        'Not Available',
        'No biometric or device credential is enrolled. Set up a fingerprint or screen lock in device settings first.',
        [{ text: 'OK' }],
      );
      return;
    }
    try {
      await BiometricService.authenticate(
        'Enable App Lock',
        'Confirm your identity to enable biometric app lock',
      );
      await SecurityService.saveAppLockBiometricEnabled(true); // also clears app-lock passcode
      setAppLockBiometricEnabled(true);
      setAppLockHasPasscode(false);
    } catch (err) {
      if (err?.code !== BiometricError.CANCELLED) {
        Alert.alert(
          'Setup Failed',
          err?.message ?? 'Could not enable app lock.',
        );
      }
    }
  }, []);

  const handleAppLockRemoveBiometric = useCallback(async () => {
    try {
      await BiometricService.authenticate(
        'Remove App Lock',
        'Confirm your identity to disable biometric app lock',
      );
      await SecurityService.saveAppLockBiometricEnabled(false);
      setAppLockBiometricEnabled(false);
    } catch (err) {
      if (err?.code !== BiometricError.CANCELLED) {
        Alert.alert(
          'Removal Failed',
          err?.message ?? 'Could not disable app lock.',
        );
      }
    }
  }, []);

  // ── App Lock: passcode ────────────────────────────────────────────────────

  const handleAppLockSetPasscode = useCallback(() => {
    setAppLockModalMode('setup');
    setAppLockModalVisible(true);
  }, []);

  const handleAppLockChangePasscode = useCallback(() => {
    setAppLockModalMode('change');
    setAppLockModalVisible(true);
  }, []);

  const handleAppLockRemovePasscode = useCallback(() => {
    Alert.alert(
      'Remove App Lock',
      'The app will open without authentication.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Remove',
          style: 'destructive',
          onPress: async () => {
            await SecurityService.clearAppLockPasscode();
            setAppLockHasPasscode(false);
          },
        },
      ],
    );
  }, []);

  const handleAppLockPasscodeSuccess = useCallback(() => {
    setAppLockModalVisible(false);
    if (appLockModalMode === 'setup') {
      setAppLockHasPasscode(true);
    }
    // 'change' — passcode already updated inside PasscodeModal
  }, [appLockModalMode]);

  // ── Security: passcode setup / change / removal ───────────────────────────

  const handleSetPasscode = useCallback(() => {
    setPasscodeMode('setup');
    setPasscodeModalVisible(true);
  }, []);

  const handleChangePasscode = useCallback(() => {
    setPasscodeMode('change');
    setPasscodeModalVisible(true);
  }, []);

  const handleRemovePasscode = useCallback(() => {
    Alert.alert(
      'Remove Passcode',
      'Hidden items will be accessible without authentication.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Remove',
          style: 'destructive',
          onPress: async () => {
            await SecurityService.clearPasscode();
            setHasPasscode(false);
            // If hidden items are currently showing, hide them (no auth guard anymore
            // still means we re-hide for safety)
            dispatch(AppActions.setShowHidden(false));
          },
        },
      ],
    );
  }, [dispatch]);

  // Called when PasscodeModal completes setup or change
  const handlePasscodeSuccess = useCallback(() => {
    setPasscodeModalVisible(false);
    const action = pendingActionRef.current;
    pendingActionRef.current = null;

    if (passcodeMode === 'setup') {
      setHasPasscode(true);
      return;
    }
    if (passcodeMode === 'change') {
      // passcode already updated inside PasscodeModal
      return;
    }
    // verify mode — run the queued action
    action?.();
  }, [passcodeMode]);

  const handleSortBy = useCallback(
    key => dispatch(AppActions.setSortBy(key)),
    [dispatch],
  );

  const handleSettings = useCallback(() => {
    onClose();
    // Small delay so close animation doesn't fight navigation
    setTimeout(() => onNavigate?.('Settings'), 250);
  }, [onClose, onNavigate]);

  const handlePandoraMyth = useCallback(() => {
    onClose();
    setTimeout(() => onNavigate?.('PandoraMyth'), 250);
  }, [onClose, onNavigate]);

  const handleTagIntelligence = useCallback(() => {
    onClose();
    setTimeout(() => onNavigate?.('TagIntelligence'), 250);
  }, [onClose, onNavigate]);

  const handleCreateFolder = useCallback(() => {
    onClose();
    setTimeout(() => onCreateFolder?.(), 250);
  }, [onClose, onCreateFolder]);

  const handleReindex = useCallback(async () => {
    if (isReindexing) return;
    setIsReindexing(true);
    const toastId = toast.info(
      'Refreshing media index',
      'Checking for added, moved, updated, and deleted files...',
      { duration: 4500 },
    );
    try {
      // 1. Remove DB entries whose files/folders no longer exist on device.
      const prune = await MediaIndexer.pruneOrphans();
      // 2. Mark all albums stale so the next buildIndex forces a fresh scan.
      await MediaIndexer.invalidate();
      // 3. Re-scan CameraRoll (forced) and rebuild the index with hidden included.
      const freshIndex = await loadIndex(true, { includeHidden: true });
      const folderCount = Object.keys(freshIndex?.folders ?? {}).length;
      toast.dismiss(toastId);
      toast.success(
        'Media refresh complete',
        `${folderCount} folders indexed; removed ${prune.mediaRemoved} stale files and ${prune.albumsRemoved} stale folders`,
      );
    } catch (err) {
      console.warn('[SettingsSidebar] reindex error:', err);
      toast.dismiss(toastId);
      toast.error('Media refresh failed', err?.message ?? 'Re-index failed.');
    } finally {
      setIsReindexing(false);
    }
  }, [isReindexing, loadIndex, toast]);

  const handleAccentColor = useCallback(
    color => {
      dispatch(AppActions.setAccentColor(color));
      // Persist in the background — non-blocking, non-fatal
      PreferenceService.saveAccentColor(color).catch(() => {});
    },
    [dispatch],
  );

  const handleClearCache = useCallback(async () => {
    if (isClearingCache) return;
    setIsClearingCache(true);
    try {
      await CacheService.clearAll();
      ImageCache.clearAll();
    } catch (err) {
      console.warn('[SettingsSidebar] clearCache error:', err);
    } finally {
      setIsClearingCache(false);
    }
  }, [isClearingCache]);

  const handleExport = useCallback(async () => {
    if (isExporting) return;
    setIsExporting(true);
    try {
      await BackupService.exportBackup(appState);
    } catch (err) {
      console.warn('[SettingsSidebar] export error:', err);
      // User dismissed the SAF picker — not an error, just silently stop
      if (err?.message === 'Export cancelled.') return;
      Alert.alert('Export Failed', err?.message ?? 'Unable to export backup.', [
        { text: 'OK' },
      ]);
    } finally {
      setIsExporting(false);
    }
  }, [isExporting, appState]);

  const handleImport = useCallback(async () => {
    if (isImporting) return;
    setIsImporting(true);
    try {
      const result = await BackupService.importBackup();
      if (!result) return; // user cancelled the file picker

      const { appPrefs, favorites, hiddenUris, tagsMap } = result;

      // ── Apply app preferences to context ────────────────────────────
      if (appPrefs.theme) dispatch(AppActions.setTheme(appPrefs.theme));
      if (appPrefs.showHidden !== undefined)
        dispatch(AppActions.setShowHidden(appPrefs.showHidden));
      if (appPrefs.sortBy) dispatch(AppActions.setSortBy(appPrefs.sortBy));
      if (appPrefs.accentColor !== undefined)
        dispatch(AppActions.setAccentColor(appPrefs.accentColor));
      if (appPrefs.mediaFilter)
        dispatch(AppActions.setMediaFilter(appPrefs.mediaFilter));

      // ── Apply media state to context ─────────────────────────────────
      mediaDispatch(MediaActions.setFavorites(favorites));
      mediaDispatch(MediaActions.setHiddenUris(hiddenUris));
      mediaDispatch(MediaActions.setTags(tagsMap));

      const taggedCount = Object.keys(tagsMap).length;
      Alert.alert(
        'Import Successful',
        [
          'Preferences restored.',
          `• ${favorites.length} favourite(s)`,
          `• ${hiddenUris.length} hidden file(s)`,
          `• ${taggedCount} tagged file(s)`,
          '',
          'Run "Re-index Media" to fully apply changes on a fresh install or new device.',
        ].join('\n'),
        [{ text: 'OK' }],
      );
    } catch (err) {
      console.warn('[SettingsSidebar] import error:', err);
      Alert.alert(
        'Import Failed',
        err?.message ??
          'Unable to read backup. Make sure you selected a valid Pandora backup file.',
        [{ text: 'OK' }],
      );
    } finally {
      setIsImporting(false);
    }
  }, [isImporting, dispatch, mediaDispatch]);

  // ── Style helpers ─────────────────────────────────────────────────────────
  const styles = useMemo(() => makeStyles(colors, insets), [colors, insets]);

  return (
    <Modal
      visible={mountedVisible}
      transparent
      animationType="none"
      statusBarTranslucent
      onRequestClose={onClose}
    >
      {/* Full-screen root so we can position backdrop + panel */}
      <View style={styles.root}>
        {/* ── Backdrop (tappable to close) ─────────────────────────────── */}
        <Animated.View style={[styles.backdrop, { opacity: backdropAnim }]}>
          <TouchableWithoutFeedback onPress={onClose}>
            <View style={StyleSheet.absoluteFill} />
          </TouchableWithoutFeedback>
        </Animated.View>

        {/* ── Sliding panel ────────────────────────────────────────────── */}
        <Animated.View
          style={[styles.panel, { transform: [{ translateX: panelAnim }] }]}
        >
          <ScrollView
            showsVerticalScrollIndicator={false}
            contentContainerStyle={styles.scrollContent}
          >
            {/* ─ Header ─────────────────────────────────────────────── */}
            <View style={styles.header}>
              <View style={styles.headerLeft}>
                <Icon name="optionsCrystal" size={24} color={undefined} />
                <Text style={styles.headerTitle}>Pandora's Box</Text>
              </View>
              <CloseButton onPress={onClose} style={styles.closeBtn} />
            </View>

            {/* ─ Quick Actions ──────────────────────────────────────── */}
            <View style={styles.quickActions}>
              <TouchableOpacity
                onPress={handleReindex}
                disabled={isReindexing}
                activeOpacity={0.7}
                style={[
                  styles.quickBtn,
                  {
                    backgroundColor:
                      accentColor != null ? `${accentColor}22` : colors.surface,
                    borderColor: accentColor ?? colors.border,
                  },
                  isReindexing && styles.quickBtnDisabled,
                ]}
              >
                <Icon
                  name="refresh"
                  size={14}
                  color={accentColor ?? colors.textSecondary}
                />
                <Text
                  style={[
                    styles.quickBtnLabel,
                    { color: accentColor ?? colors.textSecondary },
                  ]}
                >
                  {isReindexing ? 'Scanning…' : 'Scan Media'}
                </Text>
              </TouchableOpacity>

              <TouchableOpacity
                onPress={handleLockButtonPress}
                activeOpacity={0.7}
                style={[
                  styles.quickBtn,
                  {
                    backgroundColor: showHidden
                      ? `${accentColor ?? colors.accent}22`
                      : colors.surface,
                    borderColor: showHidden
                      ? accentColor ?? colors.accent
                      : colors.border,
                  },
                ]}
              >
                <Icon
                  name={showHidden ? 'unlock' : 'padlock'}
                  size={14}
                  color={
                    showHidden
                      ? accentColor ?? colors.accent
                      : colors.textSecondary
                  }
                />
                <Text
                  style={[
                    styles.quickBtnLabel,
                    {
                      color: showHidden
                        ? accentColor ?? colors.accent
                        : colors.textSecondary,
                    },
                  ]}
                >
                  {showHidden ? 'Lock Files' : 'Hidden Locked'}
                </Text>
              </TouchableOpacity>
            </View>

            {/* ─ Section: Appearance ────────────────────────────────── */}
            <SectionLabel label="Appearance" colors={colors} />

            <RowToggle
              icon="setting"
              label="Dark Mode"
              value={isDark}
              onValueChange={toggleTheme}
              accentColor={accentColor}
              colors={colors}
            />

            <RowButton
              icon="optionsCrystal"
              label="Customise Colors"
              onPress={() => setShowColorPicker(true)}
              showChevron
              accentColor={accentColor}
              colors={colors}
            />

            {/* ─ Section: Library ───────────────────────────────────── */}
            <SectionLabel label="Library" colors={colors} />

            {/* Lock / unlock button for hidden items */}
            <LockRow
              unlocked={showHidden}
              onPress={handleLockButtonPress}
              accentColor={accentColor}
              colors={colors}
            />

            {/* ─ Section: Hidden Files Lock ─────────────────────────── */}
            <SectionLabel
              label="Hidden Files Lock"
              description="Fingerprint / passcode to reveal hidden items"
              colors={colors}
            />

            {/* Biometric row — always shown */}
            {biometricEnabled ? (
              <RowButton
                icon="fingerprintScan"
                label="Remove Fingerprint / Face ID"
                onPress={handleRemoveBiometric}
                accentColor={accentColor}
                colors={colors}
              />
            ) : (
              <RowButton
                icon="fingerprintScan"
                label="Add Fingerprint / Face ID"
                onPress={handleAddBiometric}
                accentColor={accentColor}
                colors={colors}
              />
            )}

            {/* Passcode rows — only when biometric is NOT active */}
            {!biometricEnabled &&
              (hasPasscode ? (
                <>
                  <RowButton
                    icon="padlock"
                    label="Change Passcode"
                    onPress={handleChangePasscode}
                    accentColor={accentColor}
                    colors={colors}
                  />
                  <RowButton
                    icon="unlock"
                    label="Remove Passcode"
                    onPress={handleRemovePasscode}
                    accentColor={accentColor}
                    colors={colors}
                  />
                </>
              ) : (
                <RowButton
                  icon="padlock"
                  label="Set Passcode"
                  onPress={handleSetPasscode}
                  accentColor={accentColor}
                  colors={colors}
                />
              ))}

            {/* ─ Section: App Lock ──────────────────────────────────── */}
            <SectionLabel
              label="App Lock"
              description="Fingerprint / passcode required to open the app"
              colors={colors}
            />

            {appLockBiometricEnabled ? (
              <RowButton
                icon="fingerprintScan"
                label="Remove App Lock (Fingerprint)"
                onPress={handleAppLockRemoveBiometric}
                accentColor={accentColor}
                colors={colors}
              />
            ) : (
              <RowButton
                icon="fingerprintScan"
                label="Lock App with Fingerprint"
                onPress={handleAppLockAddBiometric}
                accentColor={accentColor}
                colors={colors}
              />
            )}

            {!appLockBiometricEnabled &&
              (appLockHasPasscode ? (
                <>
                  <RowButton
                    icon="padlock"
                    label="Change App Lock Passcode"
                    onPress={handleAppLockChangePasscode}
                    accentColor={accentColor}
                    colors={colors}
                  />
                  <RowButton
                    icon="unlock"
                    label="Remove App Lock"
                    onPress={handleAppLockRemovePasscode}
                    accentColor={accentColor}
                    colors={colors}
                  />
                </>
              ) : (
                <RowButton
                  icon="padlock"
                  label="Lock App with Passcode"
                  onPress={handleAppLockSetPasscode}
                  accentColor={accentColor}
                  colors={colors}
                />
              ))}

            {/* Sort By pills */}
            <View style={styles.row}>
              <View style={styles.rowLeft}>
                <Icon
                  name="sort"
                  size={18}
                  color={accentColor ?? colors.textSecondary}
                />
                <Text style={[styles.rowLabel, { color: colors.text }]}>
                  Sort By
                </Text>
              </View>
              <View style={styles.pillGroup}>
                {SORT_OPTIONS.map(opt => {
                  const active = sortBy === opt.key;
                  const pillAccent = accentColor ?? colors.accent;
                  return (
                    <TouchableOpacity
                      key={opt.key}
                      onPress={() => handleSortBy(opt.key)}
                      activeOpacity={0.7}
                      style={[
                        styles.pill,
                        {
                          backgroundColor: active ? pillAccent : colors.surface,
                          borderColor: active ? pillAccent : colors.border,
                        },
                      ]}
                    >
                      <Text
                        style={[
                          styles.pillLabel,
                          {
                            color: active ? colors.white : colors.textSecondary,
                          },
                        ]}
                      >
                        {opt.label}
                      </Text>
                    </TouchableOpacity>
                  );
                })}
              </View>
            </View>

            {/* ─ Section: Files ───────────────────────────────────────
            <SectionLabel label="Files" colors={colors} />

            <RowButton
              icon="copy"
              label="Create New Folder"
              onPress={handleCreateFolder}
              accentColor={accentColor}
              colors={colors}
            /> */}

            {/* ─ Section: Maintenance ───────────────────────────────── */}
            <SectionLabel label="Maintenance" colors={colors} />

            <RowButton
              icon="refresh"
              label={isReindexing ? 'Scanning…' : 'Re-index Media'}
              onPress={handleReindex}
              disabled={isReindexing || isClearingCache}
              accentColor={accentColor}
              colors={colors}
            />

            {/* <RowButton
              icon="trash"
              label={
                isClearingCache
                  ? 'Clearing…'
                  : cacheEntries != null
                  ? `Clear Cache  ·  ${cacheEntries}`
                  : 'Clear Cache'
              }
              onPress={handleClearCache}
              disabled={isClearingCache || isReindexing}
              accentColor={accentColor}
              colors={colors}
            /> */}

            {/* ─ Section: Data ─────────────────────────────────────── */}
            <SectionLabel label="Data" colors={colors} />

            <RowButton
              icon="share1"
              label={isExporting ? 'Exporting…' : 'Export Backup'}
              onPress={handleExport}
              disabled={isExporting || isImporting || isReindexing}
              accentColor={accentColor}
              colors={colors}
            />

            <RowButton
              icon="copy"
              label={isImporting ? 'Importing…' : 'Import Backup'}
              onPress={handleImport}
              disabled={isImporting || isExporting || isReindexing}
              accentColor={accentColor}
              colors={colors}
            />

            {/* ─ Section: App ───────────────────────────────────────── */}
            <SectionLabel label="App" colors={colors} />

            <RowButton
              icon="options"
              label="Tag Intelligence"
              onPress={handleTagIntelligence}
              showChevron
              accentColor={accentColor}
              colors={colors}
            />

            <RowButton
              icon="setting"
              label="Settings"
              onPress={handleSettings}
              showChevron
              accentColor={accentColor}
              colors={colors}
            />

            <RowButton
              icon="optionsCrystal"
              label="The Myth of Pandora's Box"
              onPress={handlePandoraMyth}
              showChevron
              accentColor={accentColor}
              colors={colors}
            />

            {/* ─ Creator info ───────────────────────────────────────── */}
            <CreatorInfo
              colors={colors}
              accentColor={accentColor}
              lastScanTime={lastScanTime}
            />
          </ScrollView>
        </Animated.View>
      </View>

      {/* ── Accent colour picker ─────────────────────────────────────── */}
      <ColorPickerModal
        visible={showColorPicker}
        current={accentColor}
        onSelect={color => {
          handleAccentColor(color);
          setShowColorPicker(false);
        }}
        onClose={() => setShowColorPicker(false)}
        colors={colors}
      />

      {/* ── Passcode modal (hide-files auth) ─────────────────────────── */}
      <PasscodeModal
        visible={passcodeModalVisible}
        mode={passcodeMode}
        onSuccess={handlePasscodeSuccess}
        onCancel={() => {
          setPasscodeModalVisible(false);
          pendingActionRef.current = null;
        }}
      />

      {/* ── Passcode modal (app lock) ─────────────────────────────────── */}
      <PasscodeModal
        visible={appLockModalVisible}
        mode={appLockModalMode}
        verifyFn={SecurityService.verifyAppLock}
        saveFn={SecurityService.saveAppLockPasscode}
        onSuccess={handleAppLockPasscodeSuccess}
        onCancel={() => setAppLockModalVisible(false)}
      />
    </Modal>
  );
}

// ── Small sub-components ──────────────────────────────────────────────────────

/**
 * LockRow — replaces the Show Hidden toggle.
 * Shows a padlock (locked) or unlock icon (unlocked) as a tappable button.
 */
function LockRow({ unlocked, onPress, accentColor, colors }) {
  const iconName = unlocked ? 'unlock' : 'padlock';
  const label = unlocked ? 'Hidden Files Visible' : 'Hidden Files Locked';
  const accent = accentColor ?? colors.accent;
  const scale = useRef(new Animated.Value(1)).current;

  const handlePressIn = useCallback(() => {
    Animated.spring(scale, {
      toValue: 0.97,
      useNativeDriver: true,
      speed: 50,
      bounciness: 0,
    }).start();
  }, [scale]);

  const handlePressOut = useCallback(() => {
    Animated.spring(scale, {
      toValue: 1,
      useNativeDriver: true,
      speed: 30,
      bounciness: 4,
    }).start();
  }, [scale]);

  return (
    <Pressable
      onPress={onPress}
      onPressIn={handlePressIn}
      onPressOut={handlePressOut}
    >
      <Animated.View
        style={[
          rowStyles.row,
          {
            borderBottomColor: colors.divider,
            backgroundColor: colors.surface,
            transform: [{ scale }],
          },
        ]}
      >
        <View style={rowStyles.left}>
          <Icon name={iconName} size={17} color={colors.textSecondary} />
          <Text style={[rowStyles.label, { color: colors.text }]}>{label}</Text>
        </View>
        {/* Visual state indicator pill */}
        <View
          style={[
            lockStyles.pill,
            { backgroundColor: unlocked ? accent : colors.border },
          ]}
        >
          <Text
            style={[
              lockStyles.pillText,
              { color: unlocked ? colors.white : colors.textTertiary },
            ]}
          >
            {unlocked ? 'ON' : 'OFF'}
          </Text>
        </View>
      </Animated.View>
    </Pressable>
  );
}

function SectionLabel({ label, description, colors }) {
  return (
    <View>
      <Text style={[sectionStyles.label, { color: colors.textTertiary }]}>
        {label}
      </Text>
      {description ? (
        <Text
          style={[sectionStyles.description, { color: colors.textTertiary }]}
        >
          {description}
        </Text>
      ) : null}
    </View>
  );
}

function formatTimeAgo(ms) {
  if (!ms) return null;
  const diff = Date.now() - ms;
  const mins = Math.floor(diff / 60_000);
  if (mins < 1) return 'Just now';
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

function CreatorInfo({ colors, accentColor, lastScanTime }) {
  const accent = accentColor ?? colors.textTertiary;
  const scanLabel = formatTimeAgo(lastScanTime);
  return (
    <View style={creatorStyles.container}>
      <View
        style={[creatorStyles.divider, { backgroundColor: colors.divider }]}
      />
      <Text style={[creatorStyles.appName, { color: accent }]}>
        PANDORA'S BOX
      </Text>
      <Text style={[creatorStyles.version, { color: colors.textTertiary }]}>
        v{APP_VERSION} (build {APP_BUILD}) · DB v{DB_VERSION}
      </Text>
      {scanLabel && (
        <Text style={[creatorStyles.byLine, { color: colors.textTertiary }]}>
          Last scan: {scanLabel}
        </Text>
      )}
      <Text style={[creatorStyles.byLine, { color: colors.textTertiary }]}>
        @mothatapodiatrist
      </Text>
    </View>
  );
}

function RowToggle({ icon, label, value, onValueChange, accentColor, colors }) {
  return (
    <View
      style={[
        rowStyles.row,
        {
          borderBottomColor: colors.divider,
          backgroundColor: colors.surface,
        },
      ]}
    >
      <View style={rowStyles.left}>
        <Icon
          name={icon}
          size={17}
          color={accentColor ?? colors.textSecondary}
        />
        <Text style={[rowStyles.label, { color: colors.text }]}>{label}</Text>
      </View>
      <Switch
        value={value}
        onValueChange={onValueChange}
        trackColor={{
          false: colors.border,
          true: accentColor ?? colors.accent,
        }}
        thumbColor={colors.white}
        ios_backgroundColor={colors.border}
      />
    </View>
  );
}

function RowButton({
  icon,
  label,
  onPress,
  showChevron,
  disabled,
  accentColor,
  colors,
}) {
  return (
    <TouchableOpacity
      onPress={disabled ? undefined : onPress}
      activeOpacity={disabled ? 1 : 0.7}
      style={[
        rowStyles.row,
        {
          borderBottomColor: colors.divider,
          backgroundColor: colors.surface,
        },
        disabled && rowStyles.disabled,
      ]}
    >
      <View style={rowStyles.left}>
        <Icon
          name={icon}
          size={17}
          color={accentColor ?? colors.textSecondary}
        />
        <Text style={[rowStyles.label, { color: colors.text }]}>{label}</Text>
      </View>
      <View style={rowStyles.right}>
        {showChevron && (
          <Text style={[rowStyles.chevron, { color: colors.textTertiary }]}>
            ›
          </Text>
        )}
      </View>
    </TouchableOpacity>
  );
}

// ── Color Picker Modal ────────────────────────────────────────────────────────

const PICKER_SIZE = Math.min(Dimensions.get('window').width * 0.82, 320);
const SWATCH_SIZE = Math.floor((PICKER_SIZE - 36 - 4 * 10) / 5); // 5 per row

function ColorPickerModal({ visible, current, onSelect, onClose, colors }) {
  const defaultSwatchStyle = useMemo(
    () => ({
      backgroundColor: colors.surface,
      borderColor: current === null ? colors.text : colors.border,
      borderWidth: current === null ? 2.5 : 1,
    }),
    [colors.surface, colors.text, colors.border, current],
  );

  const accentSwatchBase = useMemo(() => {
    const map = {};
    for (const hex of ACCENT_COLORS) {
      map[hex] = { backgroundColor: hex, shadowColor: hex };
    }
    return map;
  }, []);

  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      statusBarTranslucent
      onRequestClose={onClose}
    >
      <TouchableWithoutFeedback onPress={onClose}>
        <View
          style={[
            pickerStyles.backdrop,
            { backgroundColor: colors.overlayStrong },
          ]}
        >
          <TouchableWithoutFeedback>
            {/* inner touch stops propagation to backdrop */}
            <View
              style={[
                pickerStyles.sheet,
                { backgroundColor: colors.card, borderColor: colors.border },
              ]}
            >
              <Text style={[pickerStyles.title, { color: colors.text }]}>
                Accent Colour
              </Text>

              {/* Swatches grid */}
              <View style={pickerStyles.grid}>
                {/* Default (theme) slot */}
                <TouchableOpacity
                  activeOpacity={0.75}
                  onPress={() => onSelect(null)}
                  style={[pickerStyles.swatch, defaultSwatchStyle]}
                >
                  <Text
                    style={[
                      pickerStyles.defaultLabel,
                      { color: colors.textSecondary },
                    ]}
                  >
                    ✕
                  </Text>
                </TouchableOpacity>

                {ACCENT_COLORS.map(hex => {
                  const isActive = current === hex;
                  return (
                    <TouchableOpacity
                      key={hex}
                      activeOpacity={0.75}
                      onPress={() => onSelect(hex)}
                      style={[
                        pickerStyles.swatch,
                        accentSwatchBase[hex],
                        isActive
                          ? pickerStyles.swatchActive
                          : pickerStyles.swatchInactive,
                      ]}
                    />
                  );
                })}
              </View>
            </View>
          </TouchableWithoutFeedback>
        </View>
      </TouchableWithoutFeedback>
    </Modal>
  );
}

const pickerStyles = StyleSheet.create({
  backdrop: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  sheet: {
    width: PICKER_SIZE,
    height: PICKER_SIZE,
    borderRadius: 28,
    borderWidth: 1,
    padding: 18,
    alignItems: 'center',
    justifyContent: 'center',
  },
  title: {
    fontSize: 12,
    fontWeight: '600',
    letterSpacing: 1.8,
    textTransform: 'uppercase',
    textAlign: 'center',
    marginBottom: 18,
  },
  grid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
    justifyContent: 'center',
  },
  swatch: {
    width: SWATCH_SIZE,
    height: SWATCH_SIZE,
    borderRadius: SWATCH_SIZE / 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  swatchActive: {
    borderColor: '#fff',
    borderWidth: 2.5,
    shadowOpacity: 0.9,
    shadowRadius: 10,
    shadowOffset: { width: 0, height: 0 },
    elevation: 10,
  },
  swatchInactive: {
    borderColor: 'transparent',
    borderWidth: 0,
    shadowOpacity: 0,
    shadowRadius: 0,
    elevation: 0,
  },
  defaultLabel: {
    fontSize: 15,
    fontWeight: '700',
  },
});

// ── Styles ────────────────────────────────────────────────────────────────────

const makeStyles = (colors, insets) =>
  StyleSheet.create({
    root: {
      flex: 1,
      flexDirection: 'row',
      alignItems: 'stretch',
    },
    backdrop: {
      ...StyleSheet.absoluteFillObject,
      backgroundColor: colors.overlayStrong,
    },
    panel: {
      position: 'absolute',
      right: 0,
      top: 0,
      bottom: 0,
      width: PANEL_WIDTH,
      backgroundColor: colors.card,
      borderTopLeftRadius: 24,
      borderBottomLeftRadius: 24,
      shadowColor: colors.black,
      shadowOffset: { width: -4, height: 0 },
      shadowOpacity: 0.35,
      shadowRadius: 20,
      elevation: 24,
    },
    scrollContent: {
      paddingTop: insets.top + 16,
      paddingBottom: insets.bottom + 32,
    },
    header: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: 20,
      paddingBottom: 20,
    },
    headerLeft: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 10,
    },
    headerTitle: {
      fontSize: 22,
      fontWeight: '300',
      letterSpacing: 3,
      color: colors.text,
    },
    closeBtn: {
      width: 32,
      height: 32,
      alignItems: 'center',
      justifyContent: 'center',
      borderRadius: 16,
    },
    quickActions: {
      flexDirection: 'row',
      gap: 8,
      paddingHorizontal: 20,
      paddingBottom: 4,
    },
    quickBtn: {
      flex: 1,
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 6,
      paddingVertical: 9,
      borderRadius: 10,
      borderWidth: 1,
    },
    quickBtnLabel: {
      fontSize: 12,
      fontWeight: '600',
      letterSpacing: 0.2,
    },
    quickBtnDisabled: {
      opacity: 0.45,
    },
    row: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: 20,
      paddingVertical: 14,
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: colors.divider,
    },
    rowLeft: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 10,
    },
    rowLabel: {
      fontSize: 15,
      fontWeight: '400',
    },
    pillGroup: {
      flexDirection: 'row',
      gap: 6,
    },
    pill: {
      paddingHorizontal: 11,
      paddingVertical: 5,
      borderRadius: 20,
      borderWidth: 1,
    },
    pillLabel: {
      fontSize: 12,
      fontWeight: '500',
    },
  });

const creatorStyles = StyleSheet.create({
  container: {
    alignItems: 'center',
    paddingTop: 28,
    paddingBottom: 10,
    gap: 3,
  },
  divider: {
    width: 40,
    height: 1,
    marginBottom: 14,
    borderRadius: 1,
  },
  appName: {
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 3,
  },
  version: {
    fontSize: 10,
    fontWeight: '400',
    letterSpacing: 1,
    marginBottom: 6,
  },
  byLine: {
    fontSize: 10,
    fontWeight: '300',
    letterSpacing: 0.5,
  },
  author: {
    fontSize: 12,
    fontWeight: '500',
    letterSpacing: 0.5,
  },
});

const lockStyles = StyleSheet.create({
  pill: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
    minWidth: 36,
    alignItems: 'center',
  },
  pillText: {
    fontSize: 11,
    fontWeight: '600',
    letterSpacing: 0.5,
  },
});

const sectionStyles = StyleSheet.create({
  label: {
    fontSize: 11,
    fontWeight: '600',
    letterSpacing: 1.2,
    textTransform: 'uppercase',
    paddingHorizontal: 20,
    paddingTop: 22,
    paddingBottom: 4,
  },
  description: {
    fontSize: 10,
    fontWeight: '400',
    letterSpacing: 0.2,
    paddingHorizontal: 20,
    paddingBottom: 6,
    opacity: 0.65,
  },
});

const rowStyles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    paddingVertical: 15,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  left: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  label: {
    fontSize: 15,
    fontWeight: '400',
  },
  chevron: {
    fontSize: 22,
    fontWeight: '300',
    lineHeight: 24,
  },
  right: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  colorDot: {
    width: 14,
    height: 14,
    borderRadius: 7,
  },
  disabled: {
    opacity: 0.4,
  },
});
