import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Animated,
  Image,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../../providers/ThemeProvider';
import { useAppContext } from '../../store/AppContext';
import { useMediaContext } from '../../store/MediaContext';
import { AppActions, MediaActions } from '../../store/actions';
import { Icon } from '../../components/ui/Icon';
import { CacheService } from '../../services/cache/CacheService';
import { ImageCache } from '../../services/cache/ImageCache';
import { MediaIndexer } from '../../services/media/MediaIndexer';
import { DatabaseService } from '../../services/database/DatabaseService';
import { PreferenceService } from '../../services/database/PreferenceService';
import { ScanLogService } from '../../services/database/ScanLogService';
import { BackupService } from '../../services/backup';
import { SecurityService } from '../../services/auth/SecurityService';
import { useMediaLibrary } from '../../hooks/useMediaLibrary';
import { SearchService } from '../../services/database/SearchService';
import { usePermissions } from '../../hooks/usePermissions';
import { useDialog } from '../../providers/DialogProvider';
import { useToast } from '../../providers/ToastProvider';
import { ModelManager, MODEL_IDS } from '../../services/ml/ModelManager';
import { EmbeddingIndexer } from '../../services/ml/EmbeddingIndexer';
import { FaceIndexer } from '../../services/ml/FaceIndexer';
import { APP_VERSION, APP_BUILD } from '../../constants/version';
import { DB_VERSION } from '../../services/database/schema';
import { createStyles } from './styles';

// ─── Constants ────────────────────────────────────────────────────────────────

// APP_VERSION, APP_BUILD, DB_VERSION imported above — do not hardcode here.
const MODEL_VERSION = 'ML Kit 17.0.9';

const NSFW_RED = '#FF2D2D';
const HOT_PINK = '#FF69B4';

const SORT_OPTIONS = [
  { key: 'date', label: 'Date' },
  { key: 'name', label: 'Name' },
  { key: 'count', label: 'Count' },
];

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatBytes(bytes) {
  if (!bytes || bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.min(
    Math.floor(Math.log(bytes) / Math.log(k)),
    sizes.length - 1,
  );
  return `${(bytes / Math.pow(k, i)).toFixed(1)} ${sizes[i]}`;
}

function formatTimeAgo(ms) {
  if (!ms) return 'Never';
  const diff = Date.now() - ms;
  const mins = Math.floor(diff / 60_000);
  if (mins < 1) return 'Just now';
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

// ─── Screen ───────────────────────────────────────────────────────────────────

export function SandboxScreen({ navigation }) {
  const insets = useSafeAreaInsets();
  const { colors, isDark, toggleTheme } = useTheme();
  const { state: appState, dispatch } = useAppContext();
  const { dispatch: mediaDispatch } = useMediaContext();
  const accentColor = appState.accentColor ?? null;
  const iconColor = accentColor ?? colors.textSecondary;
  const accent = accentColor ?? colors.accent;
  const sortBy = appState.sortBy ?? 'date';

  const styles = useMemo(
    () => createStyles({ ...colors, isDark }),
    [colors, isDark],
  );

  const dialog = useDialog();
  const toast = useToast();
  const { loadIndex } = useMediaLibrary();
  const { permissions, requestAllPermissions, requestFullFileAccess } =
    usePermissions();

  // ── State ──────────────────────────────────────────────────────────────────
  const [cacheStats, setCacheStats] = useState(null); // { totalEntries }
  const [memStats, setMemStats] = useState(null); // { size, capacity, utilization }
  const [mediaStats, setMediaStats] = useState(null); // { images, videos, totalSize, largestFolder, largestCount }
  const [dbSizeBytes, setDbSizeBytes] = useState(null);
  const [lastScanTime, setLastScanTime] = useState(null);
  const [tagCount, setTagCount] = useState(null);
  const [aiTagCount, setAiTagCount] = useState(null);
  const [securityStatus, setSecurityStatus] = useState({
    hasPasscode: false,
    biometric: false,
    appLock: false,
  });
  const [isReindexing, setIsReindexing] = useState(false);
  const [isResettingTags, setIsResettingTags] = useState(false);
  const [isExporting, setIsExporting] = useState(false);
  const [isImporting, setIsImporting] = useState(false);
  const [devTapCount, setDevTapCount] = useState(0);
  const devTapTimer = useRef(null);

  // ── Smart Tagging Models ───────────────────────────────────────────────────
  const [modelInfos, setModelInfos] = useState({}); // modelId → ModelInfo
  const [downloading, setDownloading] = useState({}); // modelId → 0-1 progress
  const [downloadErr, setDownloadErr] = useState({}); // modelId → string
  const [deleting, setDeleting] = useState({}); // modelId → boolean

  // ── NSFW tag management ────────────────────────────────────────────────────
  const [nsfwTags, setNsfwTags] = useState([]);
  const [nsfwInput, setNsfwInput] = useState('');
  const nsfwInputRef = useRef(null);

  // ── Load all stats on mount ────────────────────────────────────────────────
  useEffect(() => {
    loadAllStats();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadAllStats = useCallback(async () => {
    try {
      // Cache stats
      const disk = await CacheService.getStats();
      const mem = ImageCache.memStats();
      setCacheStats(disk);
      setMemStats(mem);

      // DB-backed stats
      const db = DatabaseService.getDb();
      if (db) {
        // DB file size
        const pcResult = db.executeSync('PRAGMA page_count');
        const psResult = db.executeSync('PRAGMA page_size');
        const dbSize =
          (pcResult.rows[0]?.page_count ?? 0) *
          (psResult.rows[0]?.page_size ?? 0);
        setDbSizeBytes(dbSize);

        // Media counts + total size
        const { rows: typeRows } = await db.execute(
          `SELECT media_type, COUNT(*) as cnt, SUM(file_size) as total
           FROM media_index GROUP BY media_type`,
        );
        const images = typeRows.find(r => r.media_type === 'image')?.cnt ?? 0;
        const videos = typeRows.find(r => r.media_type === 'video')?.cnt ?? 0;
        const totalSize = typeRows.reduce((s, r) => s + (r.total ?? 0), 0);

        // Largest album
        const { rows: albumRows } = await db.execute(
          `SELECT a.name, COUNT(*) as cnt
           FROM media_index m
           LEFT JOIN albums a ON m.album_id = a.id
           GROUP BY m.album_id ORDER BY cnt DESC LIMIT 1`,
        );
        setMediaStats({
          images,
          videos,
          totalSize,
          largestFolder: albumRows[0]?.name ?? null,
          largestCount: albumRows[0]?.cnt ?? 0,
        });

        // Tag counts
        const { rows: tagRows } = await db.execute(
          'SELECT COUNT(*) as cnt FROM tags',
        );
        const { rows: aiRows } = await db.execute(
          'SELECT COUNT(DISTINCT asset_id) as cnt FROM tag_suggestions',
        );
        setTagCount(tagRows[0]?.cnt ?? 0);
        setAiTagCount(aiRows[0]?.cnt ?? 0);
      }

      // Smart tagging models
      try {
        await ModelManager.init();
        const infos = {};
        for (const id of Object.values(MODEL_IDS)) {
          infos[id] = ModelManager.getModelInfo(id);
        }
        setModelInfos(infos);
      } catch {}

      // NSFW tag list
      const nsfwList = await SearchService.getNsfwTagNames();
      setNsfwTags(nsfwList);

      // Last full scan time
      const scanTime = await ScanLogService.getLastFullScanTime();
      setLastScanTime(scanTime);

      // Security status (read-only display)
      const [passcode, biometric, appLockPasscode, appLockBio] =
        await Promise.all([
          SecurityService.getPasscode(),
          SecurityService.isBiometricEnabled(),
          SecurityService.getAppLockPasscode(),
          SecurityService.isAppLockBiometricEnabled(),
        ]);
      setSecurityStatus({
        hasPasscode: passcode !== null,
        biometric,
        appLock: appLockPasscode !== null || appLockBio,
      });
    } catch (err) {
      console.warn('[Settings] loadAllStats error:', err);
    }
  }, []);

  // ── Download / install a smart tagging model ──────────────────────────────
  const handleModelInstall = useCallback(
    async modelId => {
      if (downloading[modelId] != null) return; // already in progress
      setDownloadErr(prev => ({ ...prev, [modelId]: null }));
      setDownloading(prev => ({ ...prev, [modelId]: 0 }));
      try {
        await ModelManager.downloadAndInstall(modelId, progress => {
          setDownloading(prev => ({ ...prev, [modelId]: progress }));
        });
        toast.success('Model installed', `${modelId} is ready.`);
      } catch (err) {
        setDownloadErr(prev => ({
          ...prev,
          [modelId]: err?.message ?? 'Download failed',
        }));
      } finally {
        setDownloading(prev => {
          const next = { ...prev };
          delete next[modelId];
          return next;
        });
        // Refresh model info
        setModelInfos(prev => ({
          ...prev,
          [modelId]: ModelManager.getModelInfo(modelId),
        }));
      }
    },
    [downloading, toast],
  );

  // ── Delete (uninstall) a model ───────────────────────────────────────────
  const handleModelDelete = useCallback(
    async modelId => {
      const confirmed = await dialog.destructive({
        title: 'Delete model?',
        body: `This removes the downloaded model file for “${modelId}” from device storage. You can re-download it at any time.`,
        confirmLabel: 'Delete',
      });
      if (!confirmed) return;
      setDeleting(prev => ({ ...prev, [modelId]: true }));
      try {
        await ModelManager.uninstall(modelId);
        setModelInfos(prev => ({
          ...prev,
          [modelId]: ModelManager.getModelInfo(modelId),
        }));
        toast.success('Model deleted', `${modelId} removed from device.`);
      } catch (err) {
        toast.error('Delete failed', err?.message ?? 'Could not remove model.');
      } finally {
        setDeleting(prev => ({ ...prev, [modelId]: false }));
      }
    },
    [dialog, toast],
  );

  // ── Toggle ML model on / off ───────────────────────────────────────────────
  const handleToggleModelEnabled = useCallback(
    async (modelId, value) => {
      try {
        if (modelId === MODEL_IDS.SCENE) {
          EmbeddingIndexer.setSceneIndexingEnabled(value);
          await PreferenceService.saveMlSceneEnabled(value);
          dispatch(AppActions.setMlSceneEnabled(value));
        } else if (modelId === MODEL_IDS.FACE) {
          FaceIndexer.setFaceIndexingEnabled(value);
          await PreferenceService.saveMlFaceEnabled(value);
          dispatch(AppActions.setMlFaceEnabled(value));
        }
      } catch (err) {
        toast.error('Toggle failed', err?.message ?? 'Could not save setting.');
      }
    },
    [dispatch, toast],
  );

  // ── Rebuild media index ────────────────────────────────────────────────────
  const handleRebuildIndex = useCallback(async () => {
    if (isReindexing) return;
    setIsReindexing(true);
    try {
      await MediaIndexer.invalidate();
      const index = await loadIndex(true);
      const folderCount = Object.keys(index?.folders ?? {}).length;
      await loadAllStats();
      toast.success(
        'Index rebuilt',
        `${folderCount} folder${folderCount !== 1 ? 's' : ''} indexed.`,
      );
    } catch (err) {
      await dialog.alert({
        title: 'Rebuild Failed',
        body: err?.message ?? 'Unknown error during rescan.',
        icon: 'information',
      });
    } finally {
      setIsReindexing(false);
    }
  }, [isReindexing, loadIndex, loadAllStats, toast, dialog]);

  // ── Clear cache ────────────────────────────────────────────────────────────
  const handleClearAllCache = useCallback(async () => {
    const confirmed = await dialog.destructive({
      title: 'Clear All Cache?',
      body: 'This will wipe both disk and memory caches.',
      icon: 'trash',
      deleteText: 'Clear',
    });
    if (!confirmed) return;
    await CacheService.clearAll();
    ImageCache.clearAll();
    setCacheStats(null);
    setMemStats(null);
    toast.success('Cache cleared', 'All caches wiped.');
  }, [dialog, toast]);

  // ── Reset AI tag suggestions ───────────────────────────────────────────────
  const handleResetAiTags = useCallback(async () => {
    const confirmed = await dialog.destructive({
      title: 'Reset Smart Tags?',
      body: 'All AI-generated tag suggestions will be deleted. Manual tags are kept.',
      icon: 'trash',
      deleteText: 'Reset',
    });
    if (!confirmed) return;
    setIsResettingTags(true);
    try {
      const db = DatabaseService.getDb();
      await db.execute('DELETE FROM tag_suggestions');
      setAiTagCount(0);
      toast.success('Smart tags reset', 'AI suggestions cleared.');
    } catch (err) {
      await dialog.alert({
        title: 'Error',
        body: err?.message ?? 'Failed to reset tags.',
        icon: 'information',
      });
    } finally {
      setIsResettingTags(false);
    }
  }, [dialog, toast]);

  // ── Export / Import ────────────────────────────────────────────────────────
  const handleExport = useCallback(async () => {
    if (isExporting) return;
    setIsExporting(true);
    try {
      await BackupService.exportBackup(appState);
    } catch (err) {
      if (err?.message === 'Export cancelled.') return;
      Alert.alert('Export Failed', err?.message ?? 'Unable to export backup.');
    } finally {
      setIsExporting(false);
    }
  }, [isExporting, appState]);

  const handleImport = useCallback(async () => {
    if (isImporting) return;
    setIsImporting(true);
    try {
      const result = await BackupService.importBackup();
      if (!result) return;
      const { appPrefs, favorites, hiddenUris, tagsMap } = result;
      if (appPrefs.theme) dispatch(AppActions.setTheme(appPrefs.theme));
      if (appPrefs.showHidden !== undefined)
        dispatch(AppActions.setShowHidden(appPrefs.showHidden));
      if (appPrefs.sortBy) dispatch(AppActions.setSortBy(appPrefs.sortBy));
      if (appPrefs.accentColor !== undefined)
        dispatch(AppActions.setAccentColor(appPrefs.accentColor));
      if (appPrefs.mediaFilter)
        dispatch(AppActions.setMediaFilter(appPrefs.mediaFilter));
      mediaDispatch(MediaActions.setFavorites(favorites));
      mediaDispatch(MediaActions.setHiddenUris(hiddenUris));
      mediaDispatch(MediaActions.setTags(tagsMap));
      toast.success(
        'Import successful',
        `${favorites.length} favourites · ${hiddenUris.length} hidden files restored.`,
      );
    } catch (err) {
      Alert.alert(
        'Import Failed',
        err?.message ??
          'Unable to read backup. Make sure you selected a valid Pandora backup file.',
      );
    } finally {
      setIsImporting(false);
    }
  }, [isImporting, dispatch, mediaDispatch, toast]);

  // ── Sort ──────────────────────────────────────────────────────────────────
  const handleSortBy = useCallback(
    key => dispatch(AppActions.setSortBy(key)),
    [dispatch],
  );

  // ── NSFW tag handlers ─────────────────────────────────────────────────────
  const handleAddNsfwTag = useCallback(async () => {
    const tag = nsfwInput.trim().toLowerCase();
    if (!tag || nsfwTags.includes(tag)) {
      setNsfwInput('');
      return;
    }
    const next = [...nsfwTags, tag];
    setNsfwTags(next);
    setNsfwInput('');
    try {
      await SearchService.saveNsfwTagNames(next);
    } catch (err) {
      console.warn('[Settings] saveNsfwTagNames error:', err);
    }
  }, [nsfwInput, nsfwTags]);

  const handleRemoveNsfwTag = useCallback(
    async tag => {
      const next = nsfwTags.filter(t => t !== tag);
      setNsfwTags(next);
      try {
        await SearchService.saveNsfwTagNames(next);
      } catch (err) {
        console.warn('[Settings] saveNsfwTagNames error:', err);
      }
    },
    [nsfwTags],
  );

  const handleResetNsfwTags = useCallback(async () => {
    const next = [...SearchService.DEFAULT_NSFW_TAGS];
    setNsfwTags(next);
    try {
      await SearchService.saveNsfwTagNames(next);
      toast.success('Reset', 'NSFW tags restored to defaults.');
    } catch (err) {
      console.warn('[Settings] saveNsfwTagNames error:', err);
    }
  }, [toast]);

  const handleToggleNsfwFilter = useCallback(async () => {
    const next = !appState.nsfwFilterEnabled;
    dispatch(AppActions.setNsfwFilterEnabled(next));
    try {
      await PreferenceService.set('nsfw_filter_enabled', next);
    } catch (err) {
      console.warn('[Settings] saveNsfwFilter error:', err);
    }
  }, [appState.nsfwFilterEnabled, dispatch]);

  // ── Content Shield card press animation ───────────────────────────────────
  const cardScale = useRef(new Animated.Value(1)).current;
  const handleCardPressIn = useCallback(() => {
    Animated.spring(cardScale, {
      toValue: 0.97,
      useNativeDriver: true,
      speed: 50,
      bounciness: 0,
    }).start();
  }, [cardScale]);
  const handleCardPressOut = useCallback(() => {
    Animated.spring(cardScale, {
      toValue: 1,
      useNativeDriver: true,
      speed: 30,
      bounciness: 4,
    }).start();
  }, [cardScale]);

  // ── Version tap (5× to unlock developer screen) ──────────────────────────
  const handleVersionTap = useCallback(() => {
    clearTimeout(devTapTimer.current);
    const next = devTapCount + 1;
    if (next >= 5) {
      setDevTapCount(0);
      navigation.navigate('SmartTagDebug');
      return;
    }
    setDevTapCount(next);
    devTapTimer.current = setTimeout(() => setDevTapCount(0), 2000);
  }, [devTapCount, navigation]);

  // ── Render ─────────────────────────────────────────────────────────────────
  return (
    <ScrollView
      style={[styles.container, { paddingTop: insets.top }]}
      contentContainerStyle={{ paddingBottom: insets.bottom + 80 }}
      showsVerticalScrollIndicator={false}
    >
      {/* ── Page header ─────────────────────────────────────────────────── */}
      <View style={headerStyles.row}>
        <TouchableOpacity
          onPress={() => navigation.getParent()?.goBack()}
          style={headerStyles.back}
          hitSlop={{ top: 10, right: 10, bottom: 10, left: 0 }}
        >
          <Text style={[headerStyles.chevron, { color: colors.text }]}>‹</Text>
        </TouchableOpacity>
        <Text style={[styles.title, { marginTop: 0, marginBottom: 0 }]}>
          settings
        </Text>
      </View>

      {/* ─── Core Settings ──────────────────────────────────────────────── */}
      <SectionLabel label="Core Settings" colors={colors} />

      <View style={styles.row}>
        <View style={styles.rowLeft}>
          <Icon name="setting" size={18} color={iconColor} />
          <Text style={styles.rowLabel}>Dark mode</Text>
        </View>
        <Switch
          value={isDark}
          onValueChange={toggleTheme}
          trackColor={{ false: colors.border, true: accent }}
          thumbColor={colors.white}
        />
      </View>

      <View style={styles.row}>
        <View style={styles.rowLeft}>
          <Icon name="sort" size={18} color={iconColor} />
          <Text style={styles.rowLabel}>Sort by</Text>
        </View>
        <View style={extraStyles.pillGroup}>
          {SORT_OPTIONS.map(opt => {
            const active = sortBy === opt.key;
            return (
              <TouchableOpacity
                key={opt.key}
                onPress={() => handleSortBy(opt.key)}
                activeOpacity={0.7}
                style={[
                  extraStyles.pill,
                  {
                    backgroundColor: active ? accent : colors.surface,
                    borderColor: active ? accent : colors.border,
                  },
                ]}
              >
                <Text
                  style={[
                    extraStyles.pillLabel,
                    { color: active ? colors.white : colors.textSecondary },
                  ]}
                >
                  {opt.label}
                </Text>
              </TouchableOpacity>
            );
          })}
        </View>
      </View>

      {/* ─── Privacy & Security ─────────────────────────────────────────── */}
      <SectionLabel label="Privacy & Security" colors={colors} />

      <View style={styles.row}>
        <Text style={styles.rowLabel}>Hidden files lock</Text>
        <Text style={styles.rowValue}>
          {securityStatus.biometric
            ? 'Biometric'
            : securityStatus.hasPasscode
            ? 'Passcode'
            : 'None'}
        </Text>
      </View>

      <View style={styles.row}>
        <Text style={styles.rowLabel}>App lock</Text>
        <Text style={styles.rowValue}>
          {securityStatus.appLock ? 'Enabled' : 'Off'}
        </Text>
      </View>

      <Text style={[extraStyles.sectionHint, { color: colors.textTertiary }]}>
        Manage authentication from the side panel ›
      </Text>

      {/* ─── Storage & Index ────────────────────────────────────────────── */}
      <SectionLabel label="Storage & Index" colors={colors} />

      {/* Media stats card */}
      {mediaStats ? (
        <MediaStatsCard
          mediaStats={mediaStats}
          dbSizeBytes={dbSizeBytes}
          lastScanTime={lastScanTime}
          cacheStats={cacheStats}
          memStats={memStats}
          colors={colors}
          accent={accent}
          onRefresh={loadAllStats}
        />
      ) : (
        <TouchableOpacity
          style={[styles.row, { justifyContent: 'center' }]}
          onPress={loadAllStats}
          activeOpacity={0.7}
        >
          <Icon name="refresh" size={16} color={iconColor} />
          <Text style={[styles.rowLabel, { marginLeft: 8 }]}>Load stats</Text>
        </TouchableOpacity>
      )}

      {/* Rebuild Media Index */}
      <TouchableOpacity
        style={[styles.row, isReindexing && { opacity: 0.5 }]}
        onPress={handleRebuildIndex}
        disabled={isReindexing}
        activeOpacity={0.7}
      >
        <View style={styles.rowLeft}>
          <Icon name="refresh" size={18} color={iconColor} />
          <Text style={styles.rowLabel}>
            {isReindexing ? 'Scanning…' : 'Rebuild Media Index'}
          </Text>
        </View>
        <Icon name="refresh" size={16} color={iconColor} />
      </TouchableOpacity>

      <Text
        style={[
          extraStyles.sectionHint,
          { color: colors.textTertiary, marginBottom: 8 },
        ]}
      >
        Scans CameraRoll and .nomedia folders, updates the SQLite index.
      </Text>

      {/* Clear cache */}
      <TouchableOpacity
        style={[styles.dangerBtn, { marginTop: 4 }]}
        onPress={handleClearAllCache}
      >
        <Text style={styles.dangerBtnText}>
          {cacheStats
            ? `Clear All Cache  ·  ${cacheStats.totalEntries} entries`
            : 'Clear All Cache'}
        </Text>
      </TouchableOpacity>

      {/* DB size */}
      {dbSizeBytes != null && (
        <View style={[styles.row, { marginTop: 8 }]}>
          <Text style={styles.rowLabel}>Database size</Text>
          <Text style={styles.rowValue}>{formatBytes(dbSizeBytes)}</Text>
        </View>
      )}

      {/* ─── Smart Features ─────────────────────────────────────────────── */}
      <SectionLabel label="Smart Features" colors={colors} />

      {tagCount != null && (
        <View style={styles.row}>
          <View style={styles.rowLeft}>
            <Icon name="tags" size={18} color={iconColor} />
            <Text style={styles.rowLabel}>Manual tags</Text>
          </View>
          <Text style={styles.rowValue}>{tagCount}</Text>
        </View>
      )}

      {aiTagCount != null && (
        <View style={styles.row}>
          <View style={styles.rowLeft}>
            <Icon name="tags" size={18} color={iconColor} />
            <Text style={styles.rowLabel}>AI suggestions</Text>
          </View>
          <Text style={styles.rowValue}>{aiTagCount} assets</Text>
        </View>
      )}

      <TouchableOpacity
        style={styles.row}
        onPress={() => navigation.navigate('SmartTagDebug')}
        activeOpacity={0.7}
      >
        <View style={styles.rowLeft}>
          <Icon name="tags" size={18} color={iconColor} />
          <Text style={styles.rowLabel}>Smart Tag Debug</Text>
        </View>
        <Text style={styles.rowChevron}>›</Text>
      </TouchableOpacity>

      <TouchableOpacity
        style={styles.row}
        onPress={() => navigation.navigate('BatchLearn')}
        activeOpacity={0.7}
      >
        <View style={styles.rowLeft}>
          <Icon name="man" size={18} color={iconColor} />
          <Text style={styles.rowLabel}>Batch Tag Learning</Text>
        </View>
        <Text style={styles.rowChevron}>›</Text>
      </TouchableOpacity>

      <TouchableOpacity
        style={[
          styles.dangerBtn,
          { marginTop: 8 },
          isResettingTags && { opacity: 0.5 },
        ]}
        onPress={handleResetAiTags}
        disabled={isResettingTags}
      >
        <Text style={styles.dangerBtnText}>
          {isResettingTags ? 'Resetting…' : 'Reset AI Tag Suggestions'}
        </Text>
      </TouchableOpacity>

      {/* ─── Smart Tagging Models ────────────────────────────────────────── */}
      <SectionLabel label="Smart Tagging Models" colors={colors} />
      <Text style={[extraStyles.sectionHint, { color: colors.textTertiary }]}>
        Downloaded models are stored on-device and verified via SHA-256.
      </Text>
      {Object.values(MODEL_IDS).map(modelId => {
        const info = modelInfos[modelId];
        const progress = downloading[modelId];
        const err = downloadErr[modelId];
        const isDownloading = progress != null;
        const isDeleting = !!deleting[modelId];
        const isInstalled = !!info; // info is null when not installed, object when installed
        const isEnabled =
          modelId === MODEL_IDS.SCENE
            ? appState.mlSceneEnabled
            : appState.mlFaceEnabled;
        return (
          <View
            key={modelId}
            style={[
              extraStyles.modelCard,
              { backgroundColor: colors.surface, borderColor: colors.border },
              isInstalled && {
                borderColor: isEnabled ? `${accent}55` : colors.border,
              },
            ]}
          >
            {/* ── Header row: name / meta + status chip ── */}
            <View style={extraStyles.modelCardHeader}>
              <View style={extraStyles.modelCardLeft}>
                <Text style={[styles.rowLabel, extraStyles.modelCardName]}>
                  {modelId}
                </Text>
                <Text
                  style={[
                    extraStyles.sectionHint,
                    extraStyles.modelCardMeta,
                    { color: colors.textTertiary },
                  ]}
                >
                  {isInstalled
                    ? `v${info.version}  ·  ${formatBytes(info.sizeBytes ?? 0)}`
                    : info?.sizeBytes
                    ? `~${formatBytes(info.sizeBytes)}`
                    : 'Not installed'}
                </Text>
              </View>
              <View
                style={[
                  extraStyles.modelStatusChip,
                  {
                    backgroundColor: isInstalled
                      ? isEnabled
                        ? '#4CAF5022'
                        : `${colors.textSecondary}22`
                      : isDownloading
                      ? `${accent}22`
                      : '#FF980022',
                  },
                ]}
              >
                <Text
                  style={[
                    extraStyles.modelStatusText,
                    {
                      color: isInstalled
                        ? isEnabled
                          ? '#4CAF50'
                          : colors.textSecondary
                        : isDownloading
                        ? accent
                        : '#FF9800',
                    },
                  ]}
                >
                  {isInstalled
                    ? isEnabled
                      ? 'Active'
                      : 'Disabled'
                    : isDownloading
                    ? 'Downloading'
                    : 'Not installed'}
                </Text>
              </View>
            </View>

            {/* ── Download progress bar ── */}
            {isDownloading && (
              <View style={extraStyles.progressBarTrack}>
                <View
                  style={[
                    extraStyles.progressBarFill,
                    {
                      width: `${Math.round(progress * 100)}%`,
                      backgroundColor: accent,
                    },
                  ]}
                />
              </View>
            )}

            {/* ── Error ── */}
            {!!err && <Text style={extraStyles.modelErr}>{err}</Text>}

            {/* ── INSTALLED state: toggle + Update / Delete actions ── */}
            {isInstalled && !isDownloading && (
              <>
                <View style={extraStyles.modelDivider} />

                {/* Enable / disable toggle */}
                <View style={extraStyles.modelToggleRow}>
                  <Text
                    style={[
                      extraStyles.modelToggleLabel,
                      { color: colors.textSecondary },
                    ]}
                  >
                    {modelId === MODEL_IDS.FACE
                      ? 'Face recognition'
                      : 'Scene detection'}
                  </Text>
                  <Switch
                    value={isEnabled ?? true}
                    onValueChange={val =>
                      handleToggleModelEnabled(modelId, val)
                    }
                    trackColor={{ false: colors.border, true: `${accent}66` }}
                    thumbColor={
                      isEnabled ?? true ? accent : colors.textSecondary
                    }
                  />
                </View>
                {!(isEnabled ?? true) && (
                  <Text
                    style={[
                      extraStyles.modelDisabledHint,
                      { color: colors.textTertiary },
                    ]}
                  >
                    Indexing and suggestions for this signal are paused.
                  </Text>
                )}

                {/* Update + Delete buttons */}
                <View style={extraStyles.modelActionRow}>
                  <TouchableOpacity
                    style={[
                      extraStyles.modelBtn,
                      extraStyles.modelBtnSecondary,
                      { borderColor: colors.border, flex: 1 },
                      isDownloading && extraStyles.modelBtnDisabled,
                    ]}
                    onPress={() => handleModelInstall(modelId)}
                    disabled={isDownloading}
                  >
                    <Text
                      style={[
                        extraStyles.modelBtnText,
                        { color: colors.textSecondary },
                      ]}
                    >
                      Update
                    </Text>
                  </TouchableOpacity>

                  <TouchableOpacity
                    style={[
                      extraStyles.modelBtn,
                      extraStyles.modelBtnDanger,
                      { flex: 1 },
                      isDeleting && extraStyles.modelBtnDisabled,
                    ]}
                    onPress={() => handleModelDelete(modelId)}
                    disabled={isDeleting}
                  >
                    <Text
                      style={[extraStyles.modelBtnText, { color: '#FF5252' }]}
                    >
                      {isDeleting ? 'Deleting…' : 'Delete'}
                    </Text>
                  </TouchableOpacity>
                </View>
              </>
            )}

            {/* ── NOT INSTALLED state: Install button ── */}
            {!isInstalled && (
              <TouchableOpacity
                style={[
                  extraStyles.modelBtn,
                  {
                    backgroundColor: isDownloading ? colors.border : accent,
                    marginTop: 10,
                  },
                  isDownloading && extraStyles.modelBtnDisabled,
                ]}
                onPress={() => handleModelInstall(modelId)}
                disabled={isDownloading}
              >
                <Text
                  style={[extraStyles.modelBtnText, { color: colors.white }]}
                >
                  {isDownloading ? `${Math.round(progress * 100)}%` : 'Install'}
                </Text>
              </TouchableOpacity>
            )}
          </View>
        );
      })}

      {/* ─── Content Filter ─────────────────────────────────────────────── */}
      <SectionLabel label="Content Filter" colors={colors} />

      {/* Global NSFW shield toggle */}
      <Pressable
        onPress={handleToggleNsfwFilter}
        onPressIn={handleCardPressIn}
        onPressOut={handleCardPressOut}
      >
        <Animated.View
          style={[
            styles.row,
            extraStyles.contentShieldRow,
            {
              borderColor: appState.nsfwFilterEnabled
                ? colors.border
                : HOT_PINK,
              transform: [{ scale: cardScale }],
            },
            !appState.nsfwFilterEnabled && extraStyles.contentShieldGlow,
          ]}
        >
          <View style={[styles.rowLeft, { flex: 1 }]}>
            <Image
              source={require('../../assets/icons/nsfw.png')}
              resizeMode="contain"
              fadeDuration={0}
              style={{
                width: 34,
                height: 34,
                marginRight: 10,
                tintColor: appState.nsfwFilterEnabled ? NSFW_RED : HOT_PINK,
              }}
            />
            <View style={{ flex: 1 }}>
              <Text style={styles.rowLabel}>NSFW content visibility</Text>
              <Text
                style={[
                  extraStyles.sectionHint,
                  { color: colors.textTertiary, marginTop: 2 },
                ]}
              >
                {appState.nsfwFilterEnabled
                  ? 'NSFW content hidden — tap to show'
                  : 'NSFW content visible — tap to hide'}
              </Text>
            </View>
          </View>
        </Animated.View>
      </Pressable>

      <Text
        style={[
          extraStyles.sectionHint,
          { color: colors.textTertiary, marginBottom: 10 },
        ]}
      >
        Tag words treated as NSFW:
      </Text>

      {/* Current NSFW tag chips */}
      <View style={extraStyles.tagChipRow}>
        {nsfwTags.map(tag => (
          <View
            key={tag}
            style={[
              extraStyles.tagChip,
              {
                backgroundColor: `${colors.error}18`,
                borderColor: `${colors.error}44`,
              },
            ]}
          >
            <Text style={[extraStyles.tagChipLabel, { color: colors.error }]}>
              {tag}
            </Text>
            <TouchableOpacity
              onPress={() => handleRemoveNsfwTag(tag)}
              hitSlop={{ top: 6, bottom: 6, left: 6, right: 6 }}
            >
              <Text
                style={[extraStyles.tagChipRemove, { color: colors.error }]}
              >
                ×
              </Text>
            </TouchableOpacity>
          </View>
        ))}
      </View>

      {/* Add tag input */}
      <View style={[extraStyles.tagInputRow, { borderColor: colors.border }]}>
        <TextInput
          ref={nsfwInputRef}
          style={[extraStyles.tagInput, { color: colors.text }]}
          value={nsfwInput}
          onChangeText={setNsfwInput}
          placeholder="Add tag…"
          placeholderTextColor={colors.textTertiary}
          autoCapitalize="none"
          autoCorrect={false}
          returnKeyType="done"
          onSubmitEditing={handleAddNsfwTag}
        />
        <TouchableOpacity
          onPress={handleAddNsfwTag}
          activeOpacity={0.7}
          style={[extraStyles.tagAddBtn, { backgroundColor: accent }]}
        >
          <Text style={[extraStyles.tagAddBtnLabel, { color: colors.white }]}>
            Add
          </Text>
        </TouchableOpacity>
      </View>

      {/* Reset to defaults */}
      <TouchableOpacity
        onPress={handleResetNsfwTags}
        activeOpacity={0.7}
        style={[styles.row, { justifyContent: 'center', marginTop: 4 }]}
      >
        <Text
          style={[
            extraStyles.sectionHint,
            { color: colors.textTertiary, marginTop: 0 },
          ]}
        >
          Reset to defaults
        </Text>
      </TouchableOpacity>

      {/* ─── Permissions ────────────────────────────────────────────────── */}
      <SectionLabel label="Permissions" colors={colors} />

      <View style={styles.row}>
        <Text style={styles.rowLabel}>Read Media</Text>
        <Text style={styles.rowValue}>
          {permissions.read ? '✓ Granted' : '✕ Denied'}
        </Text>
      </View>

      <View style={styles.row}>
        <Text style={styles.rowLabel}>Write / Delete</Text>
        <Text style={styles.rowValue}>
          {permissions.write ? '✓ Granted' : '✕ Denied'}
        </Text>
      </View>

      <View style={styles.row}>
        <Text style={styles.rowLabel}>All Files Access</Text>
        <Text style={styles.rowValue}>
          {permissions.manageStorage ? '✓ Granted' : '✕ Denied'}
        </Text>
      </View>

      <Text style={[extraStyles.sectionHint, { color: colors.textTertiary }]}>
        Managed via MediaStore (Android 13+)
      </Text>

      {!permissions.manageStorage && (
        <TouchableOpacity
          style={styles.dangerBtn}
          onPress={requestFullFileAccess}
        >
          <Text style={styles.dangerBtnText}>Grant All Files Access</Text>
        </TouchableOpacity>
      )}

      {!permissions.all && (
        <TouchableOpacity
          style={styles.dangerBtn}
          onPress={requestAllPermissions}
        >
          <Text style={styles.dangerBtnText}>Request All Permissions</Text>
        </TouchableOpacity>
      )}

      {/* ─── Data ───────────────────────────────────────────────────────── */}
      <SectionLabel label="Data" colors={colors} />

      <TouchableOpacity
        style={[styles.row, (isExporting || isImporting) && { opacity: 0.5 }]}
        onPress={handleExport}
        disabled={isExporting || isImporting}
        activeOpacity={0.7}
      >
        <View style={styles.rowLeft}>
          <Icon name="share1" size={18} color={iconColor} />
          <Text style={styles.rowLabel}>
            {isExporting ? 'Exporting…' : 'Export Backup'}
          </Text>
        </View>
        <Text style={styles.rowChevron}>›</Text>
      </TouchableOpacity>

      <TouchableOpacity
        style={[styles.row, (isExporting || isImporting) && { opacity: 0.5 }]}
        onPress={handleImport}
        disabled={isImporting || isExporting}
        activeOpacity={0.7}
      >
        <View style={styles.rowLeft}>
          <Icon name="copy" size={18} color={iconColor} />
          <Text style={styles.rowLabel}>
            {isImporting ? 'Importing…' : 'Import Backup'}
          </Text>
        </View>
        <Text style={styles.rowChevron}>›</Text>
      </TouchableOpacity>

      {/* ─── About ──────────────────────────────────────────────────────── */}
      <SectionLabel label="About" colors={colors} />

      <View style={styles.row}>
        <Text style={styles.rowLabel}>Tag model</Text>
        <Text style={styles.rowValue}>{MODEL_VERSION}</Text>
      </View>

      <View style={styles.row}>
        <Text style={styles.rowLabel}>Database</Text>
        <Text style={styles.rowValue}>v{DB_VERSION}</Text>
      </View>

      {lastScanTime != null && (
        <View style={styles.row}>
          <Text style={styles.rowLabel}>Last full scan</Text>
          <Text style={styles.rowValue}>{formatTimeAgo(lastScanTime)}</Text>
        </View>
      )}

      <TouchableOpacity
        onPress={handleVersionTap}
        activeOpacity={0.75}
        style={styles.row}
      >
        <Text style={[styles.rowLabel, devTapCount > 0 && { color: accent }]}>
          {`Pandora's Box  v${APP_VERSION}  (build ${APP_BUILD})`}
          {devTapCount > 0 ? `   (${5 - devTapCount} more…)` : ''}
        </Text>
      </TouchableOpacity>
    </ScrollView>
  );
}

// ─── Sub-components ───────────────────────────────────────────────────────────

function SectionLabel({ label, colors }) {
  return (
    <Text style={[extraStyles.sectionLabel, { color: colors.textTertiary }]}>
      {label}
    </Text>
  );
}

function MediaStatsCard({
  mediaStats,
  dbSizeBytes,
  lastScanTime,
  cacheStats,
  memStats,
  colors,
  accent,
  onRefresh,
}) {
  return (
    <View
      style={[
        extraStyles.statsCard,
        { backgroundColor: colors.surface, borderColor: colors.border },
      ]}
    >
      {/* Counts row */}
      <View style={extraStyles.statsGrid}>
        <StatItem
          value={mediaStats.images.toLocaleString()}
          label="images"
          colors={colors}
          accent={accent}
        />
        <View
          style={[extraStyles.statDivider, { backgroundColor: colors.border }]}
        />
        <StatItem
          value={mediaStats.videos.toLocaleString()}
          label="videos"
          colors={colors}
          accent={accent}
        />
        <View
          style={[extraStyles.statDivider, { backgroundColor: colors.border }]}
        />
        <StatItem
          value={formatBytes(mediaStats.totalSize)}
          label="stored"
          colors={colors}
          accent={accent}
        />
        {dbSizeBytes != null && (
          <>
            <View
              style={[
                extraStyles.statDivider,
                { backgroundColor: colors.border },
              ]}
            />
            <StatItem
              value={formatBytes(dbSizeBytes)}
              label="database"
              colors={colors}
              accent={accent}
            />
          </>
        )}
      </View>

      {/* Meta rows */}
      {mediaStats.largestFolder && (
        <View
          style={[extraStyles.statsMetaRow, { borderTopColor: colors.border }]}
        >
          <Text
            style={[extraStyles.statsMetaLabel, { color: colors.textTertiary }]}
          >
            Largest folder
          </Text>
          <Text
            style={[
              extraStyles.statsMetaValue,
              { color: colors.textSecondary },
            ]}
            numberOfLines={1}
          >
            {mediaStats.largestFolder} · {mediaStats.largestCount}
          </Text>
        </View>
      )}

      {cacheStats && (
        <View
          style={[extraStyles.statsMetaRow, { borderTopColor: colors.border }]}
        >
          <Text
            style={[extraStyles.statsMetaLabel, { color: colors.textTertiary }]}
          >
            Cache
          </Text>
          <Text
            style={[
              extraStyles.statsMetaValue,
              { color: colors.textSecondary },
            ]}
          >
            {cacheStats.totalEntries} disk entries
            {memStats
              ? `  ·  ${memStats.size}/${memStats.capacity} memory`
              : ''}
          </Text>
        </View>
      )}

      {/* Footer: last scan + refresh */}
      <View
        style={[extraStyles.statsFooter, { borderTopColor: colors.border }]}
      >
        <Text
          style={[extraStyles.statsMetaLabel, { color: colors.textTertiary }]}
        >
          Last scan: {formatTimeAgo(lastScanTime)}
        </Text>
        <TouchableOpacity
          onPress={onRefresh}
          hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
        >
          <Text style={[extraStyles.statsRefreshBtn, { color: accent }]}>
            Refresh ›
          </Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

function StatItem({ value, label, colors, accent }) {
  return (
    <View style={extraStyles.statItem}>
      <Text style={[extraStyles.statValue, { color: accent }]}>{value}</Text>
      <Text style={[extraStyles.statLabel, { color: colors.textTertiary }]}>
        {label}
      </Text>
    </View>
  );
}

// ─── Extra styles (static, no theme dependency) ───────────────────────────────

const headerStyles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 14,
    marginBottom: 24,
  },
  back: { marginRight: 12 },
  chevron: {
    fontSize: 36,
    fontWeight: '200',
    lineHeight: 40,
  },
});

const extraStyles = StyleSheet.create({
  sectionLabel: {
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 1.4,
    textTransform: 'uppercase',
    marginTop: 28,
    marginBottom: 6,
    paddingHorizontal: 4,
  },
  sectionHint: {
    fontSize: 11,
    lineHeight: 16,
    paddingHorizontal: 4,
    marginTop: 6,
    marginBottom: 2,
    opacity: 0.75,
  },
  pillGroup: {
    flexDirection: 'row',
    gap: 6,
  },
  pill: {
    paddingHorizontal: 12,
    paddingVertical: 5,
    borderRadius: 20,
    borderWidth: 1,
  },
  pillLabel: {
    fontSize: 12,
    fontWeight: '500',
  },

  // Stats card
  statsCard: {
    borderRadius: 14,
    borderWidth: StyleSheet.hairlineWidth,
    marginBottom: 12,
    overflow: 'hidden',
  },
  statsGrid: {
    flexDirection: 'row',
    alignItems: 'stretch',
    paddingVertical: 16,
    paddingHorizontal: 4,
  },
  statDivider: {
    width: StyleSheet.hairlineWidth,
    marginVertical: 4,
  },
  statItem: {
    flex: 1,
    alignItems: 'center',
    paddingHorizontal: 4,
  },
  statValue: {
    fontSize: 17,
    fontWeight: '600',
    letterSpacing: -0.3,
  },
  statLabel: {
    fontSize: 10,
    fontWeight: '500',
    letterSpacing: 0.5,
    marginTop: 3,
    textTransform: 'uppercase',
  },
  statsMetaRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  statsMetaLabel: {
    fontSize: 11,
    fontWeight: '500',
  },
  statsMetaValue: {
    fontSize: 11,
    fontWeight: '400',
    flex: 1,
    textAlign: 'right',
    marginLeft: 8,
  },
  statsFooter: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  statsRefreshBtn: {
    fontSize: 12,
    fontWeight: '600',
    letterSpacing: 0.3,
  },

  // Content Shield (NSFW filter) — card border glows when filter is disabled
  contentShieldRow: {
    borderBottomWidth: 0,
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: 14,
    paddingHorizontal: 14,
    marginTop: 6,
    marginBottom: 10,
    // resting elevation
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 3 },
    shadowOpacity: 0.18,
    shadowRadius: 6,
    elevation: 5,
  },
  contentShieldGlow: {
    shadowColor: HOT_PINK,
    shadowOffset: { width: 0, height: 0 },
    shadowOpacity: 0.45,
    shadowRadius: 14,
    elevation: 12,
  },

  // Content Filter — NSFW tag chips
  tagChipRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    paddingHorizontal: 4,
    marginBottom: 12,
  },
  tagChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: 20,
    borderWidth: 1,
  },
  tagChipLabel: {
    fontSize: 12,
    fontWeight: '600',
  },
  tagChipRemove: {
    fontSize: 16,
    fontWeight: '400',
    lineHeight: 18,
  },
  tagInputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: 10,
    paddingLeft: 12,
    paddingRight: 6,
    height: 42,
    gap: 8,
    marginBottom: 4,
  },
  tagInput: {
    flex: 1,
    fontSize: 14,
    paddingVertical: 0,
  },
  tagAddBtn: {
    paddingHorizontal: 14,
    paddingVertical: 7,
    borderRadius: 8,
  },
  tagAddBtnLabel: {
    fontSize: 13,
    fontWeight: '600',
  },

  // Smart Tagging Models
  modelCard: {
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
    marginBottom: 10,
    padding: 14,
    overflow: 'hidden',
  },
  modelCardHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 8,
  },
  modelCardLeft: {
    flex: 1,
  },
  modelCardName: {
    fontWeight: '600',
  },
  modelCardMeta: {
    marginTop: 2,
    opacity: 0.75,
  },
  modelStatusChip: {
    borderRadius: 6,
    paddingHorizontal: 8,
    paddingVertical: 3,
  },
  modelStatusText: {
    fontSize: 11,
    fontWeight: '700',
  },
  progressBarTrack: {
    height: 4,
    borderRadius: 2,
    backgroundColor: 'rgba(128,128,128,0.2)',
    overflow: 'hidden',
    marginBottom: 8,
  },
  progressBarFill: {
    height: 4,
    borderRadius: 2,
  },
  modelErr: {
    fontSize: 11,
    lineHeight: 16,
    marginTop: 4,
    color: '#FF5252',
  },
  modelBtn: {
    borderRadius: 8,
    paddingVertical: 8,
    alignItems: 'center',
    marginTop: 4,
  },
  modelBtnText: {
    fontSize: 13,
    fontWeight: '600',
  },
  modelBtnDisabled: {
    opacity: 0.6,
  },
  modelToggleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingTop: 8,
    paddingBottom: 2,
  },
  modelToggleLabel: {
    fontSize: 13,
    flex: 1,
  },
  modelDisabledHint: {
    fontSize: 11,
    lineHeight: 16,
    marginTop: 2,
    marginBottom: 4,
    fontStyle: 'italic',
  },
  modelDivider: {
    height: StyleSheet.hairlineWidth,
    marginVertical: 10,
  },
  modelActionRow: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 8,
  },
  modelBtnSecondary: {
    backgroundColor: 'transparent',
    borderWidth: 1,
  },
  modelBtnDanger: {
    backgroundColor: 'transparent',
    borderWidth: 1,
    borderColor: '#FF525233',
  },
});
