/**
 * SmartTagDebugScreen
 *
 * Developer / QA screen for the smart tagging pipeline:
 *   • Scene embedding model status + Start/Stop scan toggle
 *   • Face detection model status + Start face batch button
 *   • Co-occurrence top pairs
 *   • Prototype backfill
 *   • Learned tag prototypes with rejection counts
 *   • Per-photo suggestion preview (5-signal)
 *   • Tuning constants reference
 */

import React, { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
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
import { useToast } from '../../providers/ToastProvider';
import { useDialog } from '../../providers/DialogProvider';
import { getEmbeddingModelInfo } from '../../services/ml/EmbeddingBridgeModule';
import {
  EmbeddingIndexer,
  setSceneIndexingEnabled,
} from '../../services/ml/EmbeddingIndexer';
import {
  FaceIndexer,
  setFaceIndexingEnabled,
} from '../../services/ml/FaceIndexer';
import { SmartTagSuggestionEngine } from '../../services/ml/SmartTagSuggestionEngine';
import { ModelManager, MODEL_IDS } from '../../services/ml/ModelManager';
import { TagPrototypeService } from '../../services/database/TagPrototypeService';
import { PrototypeBackfillService } from '../../services/database/PrototypeBackfillService';
import { FaceClusterBackfillService } from '../../services/database/FaceClusterBackfillService';
import { CooccurrenceService } from '../../services/database/CooccurrenceService';
import { FaceClusterService } from '../../services/database/FaceClusterService';
import { PreferenceService } from '../../services/database/PreferenceService';
import { useAppContext } from '../../store/AppContext';
import { AppActions } from '../../store/actions';
import { formatShortDate } from '../../utils/formatters';

// ─── Helpers ──────────────────────────────────────────────────────────────────
function formatBytes(bytes) {
  if (bytes == null) return '—';
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
function Row({ label, value, valueColor }) {
  const { colors } = useTheme();
  return (
    <View style={styles.row}>
      <Text style={[styles.rowLabel, { color: colors.textSecondary }]}>
        {label}
      </Text>
      <Text
        style={[styles.rowValue, { color: valueColor ?? colors.text }]}
        numberOfLines={2}
      >
        {String(value ?? '—')}
      </Text>
    </View>
  );
}

function SectionHeader({ title }) {
  const { colors } = useTheme();
  return (
    <Text style={[styles.sectionHeader, { color: colors.textSecondary }]}>
      {title}
    </Text>
  );
}

const SOURCE_COLORS = {
  heuristic: { bg: '#FF980022', text: '#FF9800' },
  learned: { bg: '#6C63FF22', text: '#6C63FF' },
  mlkit: { bg: '#00BCD422', text: '#00BCD4' },
  face: { bg: '#E91E6322', text: '#E91E63' },
  group: { bg: '#43A04722', text: '#43A047' },
};

function SourceChip({ source }) {
  const c = SOURCE_COLORS[source] ?? { bg: '#88888822', text: '#888888' };
  return (
    <View style={[styles.sourceChip, { backgroundColor: c.bg }]}>
      <Text style={[styles.sourceText, { color: c.text }]}>{source}</Text>
    </View>
  );
}

function ProgressBar({ progress, color }) {
  return (
    <View style={styles.progressBg}>
      <View
        style={[
          styles.progressFill,
          {
            width: `${Math.round((progress ?? 0) * 100)}%`,
            backgroundColor: color,
          },
        ]}
      />
    </View>
  );
}

// ─── Scan log colours / icons ─────────────────────────────────────────────────

const LOG_META = {
  batch: { icon: '●', color: '#FF9800' },
  progress: { icon: '›', color: '#888888' },
  done: { icon: '✓', color: '#4CAF50' },
  error: { icon: '✕', color: '#FF5252' },
  info: { icon: 'ℹ', color: '#00BCD4' },
};

/**
 * Renders an array of `{type, text}` scan log entries in a themed card.
 * Pass an empty/null array to render nothing.
 */
function ScanLogBox({ log }) {
  const { colors, isDark } = useTheme();
  if (!log || log.length === 0) return null;
  const bg = isDark ? '#1C1C1E' : '#F2F2F7';
  return (
    <View style={[styles.logBox, { backgroundColor: bg }]}>
      {log.map((entry, i) => {
        const meta = LOG_META[entry.type] ?? LOG_META.info;
        const textColor =
          entry.type === 'progress'
            ? colors.textTertiary
            : colors.textSecondary;
        return (
          <View key={i} style={styles.logEntry}>
            <Text style={[styles.logIcon, { color: meta.color }]}>
              {meta.icon}
            </Text>
            <Text style={[styles.logText, { color: textColor }]}>
              {entry.text}
            </Text>
          </View>
        );
      })}
    </View>
  );
}

/**
 * Single row in the KNOWN PEOPLE list.
 */
function PersonRow({ name, n }) {
  const { colors } = useTheme();
  return (
    <View style={styles.protoRow}>
      <Text style={[styles.personIcon]}>👤</Text>
      <View style={{ flex: 1, marginLeft: 8 }}>
        <Text style={[styles.protoKey, { color: colors.text }]}>{name}</Text>
        <Text style={[styles.protoMeta, { color: colors.textTertiary }]}>
          {n} face{n !== 1 ? 's' : ''} learned
        </Text>
      </View>
      <Text style={[styles.protoN, { color: '#E91E63' }]}>✓</Text>
    </View>
  );
}

// ─── Model card ───────────────────────────────────────────────────────────────

function ModelCard({
  modelId,
  label,
  accent,
  installedInfo,
  manifestEntry,
  enabled,
  downloading,
  downloadErr,
  deleting,
  onInstall,
  onDelete,
  onToggle,
}) {
  const { colors, isDark } = useTheme();
  const rowBg = isDark ? '#2C2C2E' : '#FFFFFF';
  const isInstalled = !!installedInfo;
  const isDownloading = downloading != null;
  const isDeleting = !!deleting;
  const updateAvailable =
    isInstalled &&
    manifestEntry?.version &&
    manifestEntry.version !== installedInfo?.version;

  const statusLabel = isDownloading
    ? `${Math.round((downloading ?? 0) * 100)}%`
    : isInstalled
    ? updateAvailable
      ? 'Update available'
      : 'Installed'
    : 'Not installed';

  return (
    <View
      style={[
        styles.modelCard,
        {
          backgroundColor: rowBg,
          borderColor: isInstalled ? `${accent}55` : colors.border,
        },
      ]}
    >
      {/* Header */}
      <View style={styles.modelCardHeader}>
        <Text style={[styles.modelCardTitle, { color: colors.text }]}>
          {label}
        </Text>
        <View
          style={[
            styles.statusChip,
            { backgroundColor: isInstalled ? '#4CAF5022' : '#FF980022' },
          ]}
        >
          {isDownloading && (
            <ActivityIndicator
              size="small"
              color={accent}
              style={{ marginRight: 4 }}
            />
          )}
          <Text
            style={[
              styles.statusChipText,
              { color: isInstalled ? '#4CAF50' : '#FF9800' },
            ]}
          >
            {statusLabel}
          </Text>
        </View>
      </View>

      {/* Manifest info */}
      {manifestEntry && (
        <>
          <Row label="Available ver." value={manifestEntry.version} />
          <Row label="Size" value={formatBytes(manifestEntry.sizeBytes)} />
          {manifestEntry.outputDim != null && (
            <Row label="Output dim" value={`${manifestEntry.outputDim}-d`} />
          )}
          <Row
            label="SHA-256"
            value={
              manifestEntry.sha256
                ? `${manifestEntry.sha256.slice(0, 16)}…`
                : '—'
            }
          />
        </>
      )}

      {/* Installed info */}
      {isInstalled && (
        <>
          <View
            style={[styles.modelDivider, { backgroundColor: colors.border }]}
          />
          <Row label="Installed ver." value={installedInfo.version} />
          <Row
            label="Installed on"
            value={
              installedInfo.installedAt
                ? formatShortDate(Math.floor(installedInfo.installedAt / 1000))
                : '—'
            }
          />
          <Row
            label="File path"
            value={installedInfo.path?.split('/').slice(-3).join('/')}
          />
        </>
      )}

      {/* Download error */}
      {!!downloadErr && <Text style={styles.errorText}>{downloadErr}</Text>}

      {/* Progress bar */}
      {isDownloading && <ProgressBar progress={downloading} color={accent} />}

      {/* Enable/disable toggle */}
      {isInstalled && (
        <>
          <View
            style={[styles.modelDivider, { backgroundColor: colors.border }]}
          />
          <View style={styles.toggleRow}>
            <Text style={[styles.toggleLabel, { color: colors.text }]}>
              {enabled ? 'Enabled' : 'Disabled'}
            </Text>
            <Switch
              value={!!enabled}
              onValueChange={onToggle}
              trackColor={{ false: colors.border, true: `${accent}88` }}
              thumbColor={enabled ? accent : colors.textTertiary}
            />
          </View>
        </>
      )}

      {/* Action buttons */}
      <View style={styles.modelBtnRow}>
        {!isInstalled ? (
          <TouchableOpacity
            style={[
              styles.modelBtn,
              {
                backgroundColor: accent,
                flex: 1,
                opacity: isDownloading ? 0.6 : 1,
              },
            ]}
            onPress={() => onInstall(modelId)}
            disabled={isDownloading}
          >
            {isDownloading ? (
              <View style={styles.btnRow}>
                <ActivityIndicator
                  color="#FFF"
                  size="small"
                  style={{ marginRight: 6 }}
                />
                <Text style={styles.modelBtnText}>Downloading…</Text>
              </View>
            ) : (
              <Text style={styles.modelBtnText}>⬇ Install</Text>
            )}
          </TouchableOpacity>
        ) : (
          <>
            <TouchableOpacity
              style={[
                styles.modelBtn,
                styles.modelBtnOutline,
                {
                  borderColor: accent,
                  flex: 1,
                  opacity: isDownloading || isDeleting ? 0.5 : 1,
                },
              ]}
              onPress={() => onInstall(modelId)}
              disabled={isDownloading || isDeleting}
            >
              {isDownloading ? (
                <View style={styles.btnRow}>
                  <ActivityIndicator
                    color={accent}
                    size="small"
                    style={{ marginRight: 6 }}
                  />
                  <Text style={[styles.modelBtnText, { color: accent }]}>
                    {Math.round((downloading ?? 0) * 100)}%
                  </Text>
                </View>
              ) : (
                <Text style={[styles.modelBtnText, { color: accent }]}>
                  {updateAvailable ? '⬆ Update' : '⟳ Reinstall'}
                </Text>
              )}
            </TouchableOpacity>
            <TouchableOpacity
              style={[
                styles.modelBtn,
                styles.modelBtnDanger,
                { opacity: isDeleting || isDownloading ? 0.5 : 1 },
              ]}
              onPress={() => onDelete(modelId)}
              disabled={isDeleting || isDownloading}
            >
              {isDeleting ? (
                <ActivityIndicator color="#FF5252" size="small" />
              ) : (
                <Text style={[styles.modelBtnText, { color: '#FF5252' }]}>
                  Delete
                </Text>
              )}
            </TouchableOpacity>
          </>
        )}
      </View>
    </View>
  );
}

// ─── Screen ───────────────────────────────────────────────────────────────────

export function SmartTagDebugScreen({ navigation }) {
  const insets = useSafeAreaInsets();
  const { colors, isDark } = useTheme();
  const toast = useToast();
  const dialog = useDialog();
  const { state: appState, dispatch } = useAppContext();
  const rowBg = isDark ? '#2C2C2E' : '#FFFFFF';
  const accent = colors.accent ?? '#6C63FF';

  // Scene model + indexing
  const [modelInfo, setModelInfo] = useState(null);
  const [indexStats, setIndexStats] = useState(null);
  const [scanning, setScanning] = useState(false);
  const [indexLog, setIndexLog] = useState([]);

  // Face indexing
  const [faceStats, setFaceStats] = useState(null);
  const [clusterStats, setClusterStats] = useState(null);
  const [faceScanning, setFaceScanning] = useState(false);
  const [faceLog, setFaceLog] = useState([]);
  const [knownPeople, setKnownPeople] = useState([]);

  // Co-occurrence
  const [cooccurPairs, setCooccurPairs] = useState([]);
  const [tagCliques, setTagCliques] = useState([]);

  // Model manager + manifest
  const [sceneModelInfo, setSceneModelInfo] = useState(null);
  const [faceModelInfo, setFaceModelInfo] = useState(null);
  const [sceneManifest, setSceneManifest] = useState(null);
  const [faceManifest, setFaceManifest] = useState(null);

  // Download / delete
  const [downloading, setDownloading] = useState({}); // modelId → 0-1
  const [downloadErr, setDownloadErr] = useState({}); // modelId → string|null
  const [deleting, setDeleting] = useState({}); // modelId → bool

  // Backfill (scene prototypes)
  const [backfillStats, setBackfillStats] = useState(null);
  const [backfilling, setBackfilling] = useState(false);
  const [backfillMsg, setBackfillMsg] = useState('');

  // Backfill (face cluster → people-tag links)
  const [faceBackfillStats, setFaceBackfillStats] = useState(null);
  const [faceBackfilling, setFaceBackfilling] = useState(false);
  const [faceBackfillMsg, setFaceBackfillMsg] = useState('');

  // Prototypes + rejections
  const [prototypes, setPrototypes] = useState([]);
  const [rejections, setRejections] = useState({});

  // Preview
  const [previewUri, setPreviewUri] = useState('');
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewSugs, setPreviewSugs] = useState(null);

  // ─── Load on mount ──────────────────────────────────────────────────────────
  const loadAll = useCallback(async () => {
    try {
      await ModelManager.init();
    } catch {}

    const modelInfo_ = await getEmbeddingModelInfo().catch(() => ({
      modelVersion: '',
    }));

    // Fetch manifest entries
    const manifestModels = await ModelManager.checkForUpdates().catch(() => []);
    setSceneManifest(
      manifestModels.find(m => m.id === MODEL_IDS.SCENE) ?? null,
    );
    setFaceManifest(manifestModels.find(m => m.id === MODEL_IDS.FACE) ?? null);

    const [
      info,
      stats,
      fStats,
      cStats,
      bfStats,
      faceBfStats,
      protos,
      rejects,
      pairs,
      cliques,
      named,
    ] = await Promise.all([
      Promise.resolve(modelInfo_),
      EmbeddingIndexer.getIndexingStats().catch(() => null),
      FaceIndexer.getStats().catch(() => null),
      FaceClusterService.getClusterStats().catch(() => null),
      PrototypeBackfillService.getBackfillStats(modelInfo_.modelVersion).catch(
        () => null,
      ),
      FaceClusterBackfillService.getBackfillStats().catch(() => null),
      TagPrototypeService.getAllPrototypes(modelInfo_.modelVersion, 0).catch(
        () => [],
      ),
      TagPrototypeService.getAllRejectionCounts().catch(() => ({})),
      CooccurrenceService.getTopPairs(10).catch(() => []),
      CooccurrenceService.getTagCliques().catch(() => []),
      FaceClusterService.getNamedClusters().catch(() => []),
    ]);

    setModelInfo(info);
    setIndexStats(stats);
    setFaceStats(fStats);
    setClusterStats(cStats);
    setBackfillStats(bfStats);
    setFaceBackfillStats(faceBfStats);
    setPrototypes(protos);
    setRejections(rejects);
    setCooccurPairs(pairs);
    setTagCliques(cliques);
    setKnownPeople(named);
    setSceneModelInfo(ModelManager.getModelInfo(MODEL_IDS.SCENE));
    setFaceModelInfo(ModelManager.getModelInfo(MODEL_IDS.FACE));
  }, []);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  // ─── Install a model ───────────────────────────────────────────────────────
  const handleModelInstall = useCallback(
    async modelId => {
      if (downloading[modelId] != null) return;
      setDownloadErr(prev => ({ ...prev, [modelId]: null }));
      setDownloading(prev => ({ ...prev, [modelId]: 0 }));
      try {
        await ModelManager.downloadAndInstall(modelId, progress => {
          setDownloading(prev => ({ ...prev, [modelId]: progress }));
        });
        toast.success('Installed', `${modelId} is ready.`);
      } catch (err) {
        setDownloadErr(prev => ({
          ...prev,
          [modelId]: err?.message ?? 'Download failed',
        }));
      } finally {
        setDownloading(prev => {
          const n = { ...prev };
          delete n[modelId];
          return n;
        });
        setSceneModelInfo(ModelManager.getModelInfo(MODEL_IDS.SCENE));
        setFaceModelInfo(ModelManager.getModelInfo(MODEL_IDS.FACE));
      }
    },
    [downloading, toast],
  );

  // ─── Delete a model ────────────────────────────────────────────────────────
  const handleModelDelete = useCallback(
    async modelId => {
      const confirmed = await dialog.destructive({
        title: 'Delete model?',
        body: `Removes "${modelId}" from device storage. You can re-download at any time.`,
        confirmLabel: 'Delete',
      });
      if (!confirmed) return;
      setDeleting(prev => ({ ...prev, [modelId]: true }));
      try {
        await ModelManager.uninstall(modelId);
        setSceneModelInfo(ModelManager.getModelInfo(MODEL_IDS.SCENE));
        setFaceModelInfo(ModelManager.getModelInfo(MODEL_IDS.FACE));
        toast.success('Deleted', `${modelId} removed from device.`);
      } catch (err) {
        toast.error('Delete failed', err?.message ?? 'Could not remove model.');
      } finally {
        setDeleting(prev => ({ ...prev, [modelId]: false }));
      }
    },
    [dialog, toast],
  );

  // ─── Enable / disable toggles ──────────────────────────────────────────────
  const handleToggleScene = useCallback(
    async value => {
      setSceneIndexingEnabled(value);
      await PreferenceService.saveMlSceneEnabled(value).catch(() => {});
      dispatch(AppActions.setMlSceneEnabled(value));
    },
    [dispatch],
  );

  const handleToggleFace = useCallback(
    async value => {
      setFaceIndexingEnabled(value);
      await PreferenceService.saveMlFaceEnabled(value).catch(() => {});
      dispatch(AppActions.setMlFaceEnabled(value));
    },
    [dispatch],
  );

  // ─── Scene Start/Stop scan ─────────────────────────────────────────────────
  const handleScanToggle = useCallback(async () => {
    if (scanning) {
      EmbeddingIndexer.cancel();
      setScanning(false);
      return;
    }
    setScanning(true);
    setIndexLog([]);
    try {
      const result = await EmbeddingIndexer.runToCompletion({
        onBatchComplete: stats =>
          setIndexLog(prev => [
            ...prev.filter(e => e.type !== 'progress'),
            {
              type: 'batch',
              text: `Batch ${stats.batchNum}: ${stats.indexed} indexed (${stats.errors} err)`,
            },
          ]),
        onProgress: (cur, total) =>
          setIndexLog(prev => [
            ...prev.filter(e => e.type !== 'progress'),
            { type: 'progress', text: `item ${cur} / ${total}` },
          ]),
        onError: (id, err) =>
          console.warn('[Debug] embed error:', id, err?.message),
      });
      setIndexLog(prev => [
        ...prev.filter(e => e.type !== 'progress'),
        {
          type: 'done',
          text: `Scan complete — ${result.totalIndexed} indexed, ${result.totalErrors} errors.`,
        },
      ]);
      await loadAll();
      toast.show('Scan complete');
    } catch (err) {
      setIndexLog(prev => [
        ...prev,
        { type: 'error', text: err?.message ?? String(err) },
      ]);
    } finally {
      setScanning(false);
    }
  }, [scanning, loadAll, toast]);

  // ─── Face scan (run to completion with cancel) ────────────────────────────
  const handleFaceScan = useCallback(async () => {
    if (faceScanning) {
      FaceIndexer.cancel();
      setFaceScanning(false);
      return;
    }
    setFaceScanning(true);
    setFaceLog([]);
    try {
      const result = await FaceIndexer.runToCompletion({
        onBatchComplete: batchResult =>
          setFaceLog(prev => [
            ...prev.filter(e => e.type !== 'progress'),
            {
              type: 'batch',
              text: `Batch ${batchResult.batchNum}: ${batchResult.detected} detected, ${batchResult.embedded} embedded (${batchResult.errors} err)`,
            },
          ]),
        onProgress: (cur, total) =>
          setFaceLog(prev => [
            ...prev.filter(e => e.type !== 'progress'),
            { type: 'progress', text: `item ${cur} / ${total}` },
          ]),
        onError: (id, err) =>
          console.warn('[Debug] face error:', id, err?.message),
      });
      setFaceLog(prev => [
        ...prev.filter(e => e.type !== 'progress'),
        {
          type: 'done',
          text: `Done: ${result.totalDetected} detected, ${result.totalEmbedded} embedded, ${result.totalErrors} errors.`,
        },
      ]);
      await loadAll();
      toast.show('Face scan complete');
    } catch (err) {
      setFaceLog(prev => [
        ...prev,
        { type: 'error', text: err?.message ?? String(err) },
      ]);
    } finally {
      setFaceScanning(false);
    }
  }, [faceScanning, loadAll, toast]);

  // ─── Prototype backfill ────────────────────────────────────────────────────
  const handleRunBackfill = useCallback(async () => {
    if (backfilling) return;
    setBackfilling(true);
    setBackfillMsg('');
    try {
      const result = await PrototypeBackfillService.backfillAll(
        modelInfo?.modelVersion ?? EmbeddingIndexer.MODEL_VERSION,
        {
          onProgress: (done, total, tagName) =>
            setBackfillMsg(`${done}/${total} — ${tagName}`),
          onError: (tagName, err) =>
            console.warn('[Debug] backfill error:', tagName, err?.message),
        },
      );
      setBackfillMsg(
        `Done: ${result.seeded} seeded, ${result.skipped} skipped, ${result.errors} errors.`,
      );
      await loadAll();
      toast.show('Backfill complete');
    } catch (err) {
      setBackfillMsg(`Error: ${err?.message ?? err}`);
    } finally {
      setBackfilling(false);
    }
  }, [backfilling, modelInfo, loadAll, toast]);

  // ─── Face cluster backfill ─────────────────────────────────────────────────
  const handleFaceClusterBackfill = useCallback(async () => {
    if (faceBackfilling) return;
    setFaceBackfilling(true);
    setFaceBackfillMsg('');
    try {
      const result = await FaceClusterBackfillService.backfillAll({
        onProgress: (done, total, tagName) =>
          setFaceBackfillMsg(`${done}/${total} — ${tagName}`),
        onError: (tagName, err) =>
          console.warn('[Debug] face backfill error:', tagName, err?.message),
      });
      setFaceBackfillMsg(
        `Done: ${result.bound} bound, ${result.created} new clusters, ${result.skipped} skipped, ${result.errors} errors.`,
      );
      await loadAll();
      toast.show('Face-tag links rebuilt');
    } catch (err) {
      setFaceBackfillMsg(`Error: ${err?.message ?? err}`);
    } finally {
      setFaceBackfilling(false);
    }
  }, [faceBackfilling, loadAll, toast]);

  // ─── Suggestion preview ────────────────────────────────────────────────────
  const handlePreview = useCallback(async () => {
    if (!previewUri.trim()) return;
    setPreviewLoading(true);
    setPreviewSugs(null);
    try {
      const sugs = await SmartTagSuggestionEngine.getSuggestionsForAsset(
        previewUri.trim(),
        { uri: previewUri.trim() },
        [], // no existing tags in debug preview
        { forceRefreshHeuristics: true },
      );
      setPreviewSugs(sugs);
    } catch (err) {
      setPreviewSugs([]);
      toast.show('Preview error: ' + (err?.message ?? err));
    } finally {
      setPreviewLoading(false);
    }
  }, [previewUri, toast]);

  return (
    <View style={[styles.container, { backgroundColor: colors.background }]}>
      {/* Header */}
      <View style={[styles.header, { paddingTop: insets.top + 8 }]}>
        <TouchableOpacity
          onPress={() => navigation.goBack()}
          style={styles.backBtn}
        >
          <Text style={[styles.backText, { color: accent }]}>← Back</Text>
        </TouchableOpacity>
        <Text style={[styles.title, { color: colors.text }]}>
          Smart Tag Debug
        </Text>
        <TouchableOpacity onPress={loadAll} style={styles.refreshBtn}>
          <Text style={[styles.backText, { color: accent }]}>Refresh</Text>
        </TouchableOpacity>
      </View>

      <ScrollView
        contentContainerStyle={[
          styles.content,
          { paddingBottom: insets.bottom + 24 },
        ]}
      >
        {/* ── SCENE EMBEDDING MODEL ─────────────────────────────────────── */}
        <SectionHeader title="SCENE EMBEDDING MODEL" />
        <ModelCard
          modelId={MODEL_IDS.SCENE}
          label={sceneManifest?.displayName ?? 'Scene Embeddings'}
          accent={accent}
          installedInfo={sceneModelInfo}
          manifestEntry={sceneManifest}
          enabled={appState.mlSceneEnabled}
          downloading={downloading[MODEL_IDS.SCENE] ?? null}
          downloadErr={downloadErr[MODEL_IDS.SCENE]}
          deleting={deleting[MODEL_IDS.SCENE]}
          onInstall={handleModelInstall}
          onDelete={handleModelDelete}
          onToggle={handleToggleScene}
        />

        {/* Scene indexing stats */}
        <View style={[styles.card, { backgroundColor: rowBg, marginTop: 8 }]}>
          <Row label="Active model ver." value={modelInfo?.modelVersion} />
          <Row label="Embedding dim" value={modelInfo?.dim} />
          <Row
            label="Input size"
            value={
              modelInfo?.inputSize
                ? `${modelInfo.inputSize}×${modelInfo.inputSize}`
                : undefined
            }
          />
          <Row
            label="Model file"
            value={modelInfo?.modelAvailable ? '✓ Available' : '✗ Missing'}
            valueColor={modelInfo?.modelAvailable ? '#4CAF50' : '#FF5252'}
          />
          <Row label="Total images" value={indexStats?.total} />
          <Row label="Indexed" value={indexStats?.indexed} />
          <Row
            label="Pending"
            value={indexStats?.pending}
            valueColor={indexStats?.pending > 0 ? '#FF9800' : undefined}
          />
        </View>
        <TouchableOpacity
          style={[
            styles.btn,
            { backgroundColor: scanning ? '#F44336' : accent },
          ]}
          onPress={handleScanToggle}
        >
          {scanning ? (
            <View style={styles.btnRow}>
              <ActivityIndicator
                color="#FFF"
                size="small"
                style={{ marginRight: 8 }}
              />
              <Text style={styles.btnText}>Stop Scan</Text>
            </View>
          ) : (
            <Text style={styles.btnText}>
              ▶ Start Scene Scan ({EmbeddingIndexer.MODEL_VERSION})
            </Text>
          )}
        </TouchableOpacity>
        <ScanLogBox log={indexLog} />

        {/* ── FACE DETECTION MODEL ────────────────────────────────────────── */}
        <SectionHeader title="FACE DETECTION MODEL" />
        <ModelCard
          modelId={MODEL_IDS.FACE}
          label={faceManifest?.displayName ?? 'Face Identity'}
          accent="#E91E63"
          installedInfo={faceModelInfo}
          manifestEntry={faceManifest}
          enabled={appState.mlFaceEnabled}
          downloading={downloading[MODEL_IDS.FACE] ?? null}
          downloadErr={downloadErr[MODEL_IDS.FACE]}
          deleting={deleting[MODEL_IDS.FACE]}
          onInstall={handleModelInstall}
          onDelete={handleModelDelete}
          onToggle={handleToggleFace}
        />

        {/* Face indexing stats */}
        <View style={[styles.card, { backgroundColor: rowBg, marginTop: 8 }]}>
          <Row
            label="Native available"
            value={FaceIndexer.isFaceDetectionAvailable() ? '✓ Yes' : '✗ No'}
            valueColor={
              FaceIndexer.isFaceDetectionAvailable() ? '#4CAF50' : '#FF5252'
            }
          />
          <Row
            label="Undetected images"
            value={faceStats?.undetected}
            valueColor={faceStats?.undetected > 0 ? '#FF9800' : undefined}
          />
          <Row label="Total faces" value={faceStats?.totalFaces} />
          <Row label="Clustered faces" value={faceStats?.clusteredFaces} />
          <Row
            label="Unclustered faces"
            value={faceStats?.unclustered}
            valueColor={faceStats?.unclustered > 0 ? '#FF9800' : undefined}
          />
          <Row label="Total clusters" value={clusterStats?.totalClusters} />
          <Row
            label="Bound to a tag"
            value={clusterStats?.boundClusters}
            valueColor={clusterStats?.boundClusters > 0 ? '#4CAF50' : undefined}
          />
          <Row
            label="Unbound clusters"
            value={clusterStats?.unboundClusters}
            valueColor={
              clusterStats?.unboundClusters > 0 ? '#FF9800' : undefined
            }
          />
        </View>
        <TouchableOpacity
          style={[
            styles.btn,
            {
              backgroundColor: faceScanning ? '#F44336' : '#E91E63',
              opacity: 1,
            },
          ]}
          onPress={handleFaceScan}
        >
          {faceScanning ? (
            <View style={styles.btnRow}>
              <ActivityIndicator
                color="#FFF"
                size="small"
                style={{ marginRight: 8 }}
              />
              <Text style={styles.btnText}>Stop Face Scan</Text>
            </View>
          ) : (
            <Text style={styles.btnText}>▶ Start Face Scan</Text>
          )}
        </TouchableOpacity>
        <ScanLogBox log={faceLog} />

        {/* ── KNOWN PEOPLE ───────────────────────────────────────────────────── */}
        <SectionHeader title={`KNOWN PEOPLE (${knownPeople.length})`} />
        {knownPeople.length === 0 ? (
          <Text style={[styles.emptyText, { color: colors.textTertiary }]}>
            No named people yet. Apply a ‘people’ category tag to a face photo
            to teach the model.
          </Text>
        ) : (
          <View style={[styles.card, { backgroundColor: rowBg }]}>
            {knownPeople.map(p => (
              <PersonRow key={p.clusterId} name={p.name} n={p.n} />
            ))}
          </View>
        )}

        {/* ── Face-tag links backfill ─────────────────────────────────────── */}
        <SectionHeader title="FACE-TAG LINKS BACKFILL" />
        <Text
          style={[
            styles.emptyText,
            { color: colors.textTertiary, marginBottom: 8 },
          ]}
        >
          Links existing face clusters to people-category tags already in your
          DB. Run after adding person tags to historic photos or after a new
          face scan.
        </Text>
        <View style={[styles.card, { backgroundColor: rowBg }]}>
          <Row label="People tags" value={faceBackfillStats?.peopleTags} />
          <Row
            label="With cluster bound"
            value={faceBackfillStats?.tagsBound}
            valueColor={
              faceBackfillStats?.tagsBound > 0 ? '#4CAF50' : undefined
            }
          />
          <Row
            label="Not yet bound"
            value={faceBackfillStats?.tagsUnbound}
            valueColor={
              faceBackfillStats?.tagsUnbound > 0 ? '#FF9800' : undefined
            }
          />
          <Row
            label="Unbound clusters"
            value={faceBackfillStats?.unboundClusters}
            valueColor={
              faceBackfillStats?.unboundClusters > 0 ? '#FF9800' : undefined
            }
          />
          <Row
            label="Faces with embeddings"
            value={faceBackfillStats?.facesWithEmbeddings}
          />
        </View>
        <TouchableOpacity
          style={[
            styles.btn,
            { backgroundColor: '#E91E63', opacity: faceBackfilling ? 0.6 : 1 },
          ]}
          onPress={handleFaceClusterBackfill}
          disabled={faceBackfilling}
        >
          {faceBackfilling ? (
            <View style={styles.btnRow}>
              <ActivityIndicator
                color="#FFF"
                size="small"
                style={{ marginRight: 8 }}
              />
              <Text style={styles.btnText}>Rebuilding…</Text>
            </View>
          ) : (
            <Text style={styles.btnText}>↺ Rebuild Face-Tag Links</Text>
          )}
        </TouchableOpacity>
        {!!faceBackfillMsg && (
          <Text
            style={[
              styles.emptyText,
              { color: colors.textSecondary, marginTop: 6 },
            ]}
          >
            {faceBackfillMsg}
          </Text>
        )}

        {/* ── Co-occurrence top pairs ───────────────────────────────────────── */}
        <SectionHeader
          title={`CO-OCCURRENCE TOP PAIRS (${cooccurPairs.length})`}
        />
        {cooccurPairs.length === 0 ? (
          <Text style={[styles.emptyText, { color: colors.textTertiary }]}>
            No co-occurrences recorded yet. Apply tags to photos to populate.
          </Text>
        ) : (
          <View style={[styles.card, { backgroundColor: rowBg }]}>
            {cooccurPairs.map((p, i) => (
              <Row
                key={i}
                label={`${p.tagA} + ${p.tagB}`}
                value={`${p.count}×`}
              />
            ))}
          </View>
        )}

        {/* ── Tag cliques (mutual groups) ───────────────────────────────────── */}
        <SectionHeader title={`TAG CLIQUES / GROUPS (${tagCliques.length})`} />
        <Text
          style={[
            styles.emptyText,
            { color: colors.textTertiary, marginBottom: 8 },
          ]}
        >
          Groups where EVERY pair co-occurs ≥ {5}×. When some members are on a
          photo the rest are suggested as "group" candidates.
        </Text>
        {tagCliques.length === 0 ? (
          <Text style={[styles.emptyText, { color: colors.textTertiary }]}>
            No cliques detected yet — need at least 3 tags all used together ≥ 5
            times.
          </Text>
        ) : (
          <View style={[styles.card, { backgroundColor: rowBg }]}>
            {tagCliques.map((group, i) => (
              <Row
                key={i}
                label={`Group ${i + 1} (${group.length} tags)`}
                value={group.map(m => m.tagName).join(' · ')}
              />
            ))}
          </View>
        )}

        {/* ── Prototype backfill ────────────────────────────────────────────── */}
        <SectionHeader title="PROTOTYPE BACKFILL" />
        <View style={[styles.card, { backgroundColor: rowBg }]}>
          <Row label="Total tags" value={backfillStats?.totalTags} />
          <Row
            label="With prototype"
            value={backfillStats?.tagsWithPrototype}
            valueColor={
              backfillStats?.tagsWithPrototype > 0 ? '#4CAF50' : undefined
            }
          />
          <Row
            label="Without prototype"
            value={backfillStats?.tagsWithoutPrototype}
            valueColor={
              backfillStats?.tagsWithoutPrototype > 0 ? '#FF9800' : undefined
            }
          />
          <Row
            label="Ready to backfill"
            value={
              backfillStats != null
                ? backfillStats.tagsWithEmbeddableImages > 0
                  ? `${backfillStats.tagsWithEmbeddableImages} tags`
                  : 'All up to date'
                : undefined
            }
            valueColor={
              backfillStats?.tagsWithEmbeddableImages > 0
                ? '#FF9800'
                : '#4CAF50'
            }
          />
        </View>
        <TouchableOpacity
          style={[
            styles.btn,
            { backgroundColor: '#4CAF50', opacity: backfilling ? 0.6 : 1 },
          ]}
          onPress={handleRunBackfill}
          disabled={backfilling}
        >
          {backfilling ? (
            <View style={styles.btnRow}>
              <ActivityIndicator
                color="#FFF"
                size="small"
                style={{ marginRight: 8 }}
              />
              <Text style={styles.btnText}>
                {backfillMsg || 'Backfilling…'}
              </Text>
            </View>
          ) : (
            <Text style={styles.btnText}>⬆ Backfill Existing Tags</Text>
          )}
        </TouchableOpacity>
        {!backfilling && !!backfillMsg && (
          <Text style={[styles.statusMsg, { color: colors.textSecondary }]}>
            {backfillMsg}
          </Text>
        )}

        {/* ── Learned prototypes ────────────────────────────────────────────── */}
        <SectionHeader title={`LEARNED PROTOTYPES (${prototypes.length})`} />
        {prototypes.length === 0 ? (
          <Text style={[styles.emptyText, { color: colors.textTertiary }]}>
            No prototypes yet. Add tags to photos to start learning.
          </Text>
        ) : (
          <View style={[styles.card, { backgroundColor: rowBg }]}>
            {[...prototypes]
              .sort((a, b) => b.n - a.n)
              .map(p => (
                <View key={p.tagKey} style={styles.protoRow}>
                  <View style={{ flex: 1 }}>
                    <Text style={[styles.protoKey, { color: colors.text }]}>
                      {p.tagKey}
                    </Text>
                    <Text
                      style={[styles.protoMeta, { color: colors.textTertiary }]}
                    >
                      n={p.n} dim={p.dim}
                    </Text>
                  </View>
                  <View style={{ alignItems: 'flex-end' }}>
                    <Text
                      style={[
                        styles.protoN,
                        { color: p.n >= 3 ? '#4CAF50' : '#FF9800' },
                      ]}
                    >
                      {p.n >= 3 ? '✓' : `${p.n}/3`}
                    </Text>
                    {rejections[p.tagKey] > 0 && (
                      <Text style={[styles.protoMeta, { color: '#FF5252' }]}>
                        {rejections[p.tagKey]}× rejected
                      </Text>
                    )}
                  </View>
                </View>
              ))}
          </View>
        )}

        {/* ── Per-photo suggestion preview ──────────────────────────────────── */}
        <SectionHeader title="SUGGESTION PREVIEW" />
        <View style={[styles.card, { backgroundColor: rowBg }]}>
          <Text style={[styles.previewHint, { color: colors.textSecondary }]}>
            Paste a content:// URI to inspect all 5 signals for that photo:
          </Text>
          <TextInput
            value={previewUri}
            onChangeText={setPreviewUri}
            placeholder="content://media/external/images/…"
            placeholderTextColor={colors.textTertiary}
            style={[
              styles.uriInput,
              { color: colors.text, borderColor: colors.border },
            ]}
            autoCapitalize="none"
            autoCorrect={false}
            multiline
          />
          <TouchableOpacity
            style={[styles.btn, { backgroundColor: accent, marginTop: 8 }]}
            onPress={handlePreview}
            disabled={previewLoading}
          >
            {previewLoading ? (
              <ActivityIndicator color="#FFF" size="small" />
            ) : (
              <Text style={styles.btnText}>▶ Show Suggestions</Text>
            )}
          </TouchableOpacity>

          {previewSugs !== null && (
            <View style={styles.sugPreview}>
              {previewSugs.length === 0 ? (
                <Text
                  style={[styles.emptyText, { color: colors.textTertiary }]}
                >
                  No suggestions (no embedding / no prototypes with n≥3).
                </Text>
              ) : (
                previewSugs.map(s => (
                  <View key={s.tagKey + s.source} style={styles.sugRow}>
                    <SourceChip source={s.source} />
                    <Text style={[styles.sugKey, { color: colors.text }]}>
                      {s.tagKey}
                    </Text>
                    <Text
                      style={[styles.sugScore, { color: colors.textSecondary }]}
                    >
                      {(s.score * 100).toFixed(1)}%
                    </Text>
                  </View>
                ))
              )}
            </View>
          )}
        </View>

        {/* ── Tuning constants ──────────────────────────────────────────────── */}
        <SectionHeader title="TUNING CONSTANTS" />
        <View style={[styles.card, { backgroundColor: rowBg }]}>
          <Row label="MIN_EXAMPLES" value={3} />
          <Row
            label="CUSTOM_THRESHOLD"
            value={SmartTagSuggestionEngine.CUSTOM_THRESHOLD}
          />
          <Row label="TOP_K" value={SmartTagSuggestionEngine.TOP_K} />
          <Row label="MARGIN_MIN" value={SmartTagSuggestionEngine.MARGIN_MIN} />
        </View>
      </ScrollView>
    </View>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  container: { flex: 1 },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingBottom: 12,
    justifyContent: 'space-between',
  },
  backBtn: { minWidth: 60 },
  refreshBtn: { minWidth: 60, alignItems: 'flex-end' },
  backText: { fontSize: 15, fontWeight: '500' },
  title: { fontSize: 17, fontWeight: '700' },
  content: { padding: 16 },
  sectionHeader: {
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 0.8,
    textTransform: 'uppercase',
    marginTop: 20,
    marginBottom: 8,
    marginLeft: 4,
  },
  card: { borderRadius: 12, overflow: 'hidden', marginBottom: 4 },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: 'rgba(128,128,128,0.15)',
  },
  rowLabel: { fontSize: 13, flex: 1, marginRight: 8 },
  rowValue: {
    fontSize: 13,
    fontWeight: '500',
    maxWidth: '55%',
    textAlign: 'right',
  },
  btn: {
    marginTop: 10,
    borderRadius: 10,
    paddingVertical: 12,
    alignItems: 'center',
  },
  btnRow: { flexDirection: 'row', alignItems: 'center' },
  btnText: { color: '#FFF', fontSize: 14, fontWeight: '600' },
  statusMsg: { fontSize: 12, marginTop: 6, marginLeft: 4 },
  protoRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: 'rgba(128,128,128,0.15)',
  },
  protoKey: { fontSize: 13, fontWeight: '500' },
  protoMeta: { fontSize: 11, marginTop: 1 },
  protoN: { fontSize: 13, fontWeight: '700' },
  emptyText: { fontSize: 13, marginLeft: 4, marginTop: 4 },
  previewHint: {
    fontSize: 12,
    marginHorizontal: 14,
    marginTop: 10,
    marginBottom: 6,
  },
  uriInput: {
    fontSize: 12,
    borderWidth: 1,
    borderRadius: 8,
    padding: 8,
    marginHorizontal: 14,
    minHeight: 50,
  },
  sugPreview: { marginTop: 12, paddingHorizontal: 14, paddingBottom: 8 },
  sugRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginBottom: 6,
  },
  sourceChip: { borderRadius: 4, paddingHorizontal: 6, paddingVertical: 2 },
  sourceText: { fontSize: 10, fontWeight: '700' },
  sugKey: { flex: 1, fontSize: 13 },
  sugScore: { fontSize: 12, fontWeight: '500' },

  // ── ModelCard ──────────────────────────────────────────────────────────────
  modelCard: {
    borderRadius: 12,
    borderWidth: 1,
    overflow: 'hidden',
    marginBottom: 4,
  },
  modelCardHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 14,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: 'rgba(128,128,128,0.15)',
  },
  modelCardTitle: { fontSize: 14, fontWeight: '600', flex: 1 },
  statusChip: {
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: 6,
    paddingHorizontal: 8,
    paddingVertical: 3,
  },
  statusChipText: { fontSize: 11, fontWeight: '700' },
  modelDivider: { height: StyleSheet.hairlineWidth, marginVertical: 2 },
  toggleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: 'rgba(128,128,128,0.15)',
  },
  toggleLabel: { fontSize: 13 },
  modelBtnRow: { flexDirection: 'row', gap: 8, padding: 12 },
  modelBtn: {
    borderRadius: 8,
    paddingVertical: 10,
    paddingHorizontal: 16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  modelBtnOutline: { backgroundColor: 'transparent', borderWidth: 1 },
  modelBtnDanger: {
    backgroundColor: 'transparent',
    borderWidth: 1,
    borderColor: '#FF525233',
  },
  modelBtnText: { fontSize: 13, fontWeight: '600', color: '#FFF' },
  errorText: {
    fontSize: 11,
    color: '#FF5252',
    marginHorizontal: 14,
    marginTop: 4,
  },
  progressBg: {
    height: 3,
    backgroundColor: 'rgba(128,128,128,0.2)',
    marginHorizontal: 14,
    marginVertical: 6,
    borderRadius: 2,
    overflow: 'hidden',
  },
  progressFill: { height: 3, borderRadius: 2 },

  // ── Scan log ────────────────────────────────────────────────────────────────
  logBox: {
    borderRadius: 10,
    overflow: 'hidden',
    marginTop: 8,
    paddingVertical: 4,
  },
  logEntry: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    paddingHorizontal: 12,
    paddingVertical: 4,
  },
  logIcon: {
    fontSize: 12,
    width: 18,
    marginRight: 6,
    marginTop: 1,
    textAlign: 'center',
  },
  logText: {
    fontSize: 12,
    flex: 1,
    lineHeight: 18,
  },

  // ── Known people ─────────────────────────────────────────────────────────────
  personIcon: {
    fontSize: 18,
    lineHeight: 22,
    marginTop: 2,
  },
});
