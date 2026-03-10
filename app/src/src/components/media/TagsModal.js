/**
 * TagsModal
 *
 * A full-screen modal for viewing, toggling, searching, and creating tags
 * on a given media item.  Design follows the reference screenshot: dark card
 * with flowing tag pills, accent-highlighted selected tags, and a search bar
 * that doubles as new-tag input.
 */

import React, {
  useState,
  useEffect,
  useCallback,
  useMemo,
  useRef,
} from 'react';
import {
  Alert,
  Keyboard,
  Modal,
  View,
  Text,
  TextInput,
  TouchableOpacity,
  ScrollView,
  StyleSheet,
  Dimensions,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';
import { DotsSpinner } from '../common/LoadingSpinner';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../../providers/ThemeProvider';
import { useAppContext } from '../../store/AppContext';
import { useMediaContext } from '../../store/MediaContext';
import { MediaActions } from '../../store/actions';
import { TagService } from '../../services/database/TagService';
import { TagSuggestionService } from '../../services/database/TagSuggestionService';
import { TagPrototypeService } from '../../services/database/TagPrototypeService';
import { FaceService } from '../../services/database/FaceService';
import { FaceClusterService } from '../../services/database/FaceClusterService';
import { TaggingService } from '../../services/ml/TaggingService';
import { labelImage, recognizeText } from '../../services/ml/MLBridgeModule';
import { classifyLabels } from '../../services/ml/TagTaxonomy';
import { SmartTagSuggestionEngine } from '../../services/ml/SmartTagSuggestionEngine';
import { Icon } from '../ui/Icon';
import { CloseButton } from '../ui/CloseButton';

const { width: SCREEN_W } = Dimensions.get('window');
const MODAL_W = Math.min(SCREEN_W * 0.9, 380);

// ─── Face-similarity batch helper ─────────────────────────────────────────────

/**
 * After applying a people-category tag to `mediaUri`, look for other assets
 * that share the same face cluster (or a similar one) and offer to batch-apply
 * the tag to all of them.
 *
 * Runs fire-and-forget — never throws.
 */
async function _promptSimilarFaces(mediaUri, tagId, tagName) {
  try {
    // Use the stored DB category instead of getCategoryForTag(tagName) so that
    // custom person names like "Alice" correctly identify as 'people'.
    const tagRow = await TagService.getTagById(tagId);
    if (!tagRow || tagRow.category !== 'people') return;

    const faces = await FaceService.getFacesForAsset(mediaUri);
    if (!faces.length) return;

    // Collect all asset URIs from matched clusters (excluding current asset).
    const matchedUris = new Set();
    for (const face of faces) {
      if (face.faceIndex === -1 || !face.clusterId) continue;

      // Direct cluster members.
      const clusterFaces = await FaceService.getFacesForCluster(face.clusterId);
      for (const f of clusterFaces) {
        if (f.assetId && f.assetId !== mediaUri) matchedUris.add(f.assetId);
      }

      // Similar clusters (cosine sim above threshold).
      const cluster = await FaceClusterService.getCluster(face.clusterId);
      if (cluster?.centroid) {
        const similarIds = await FaceClusterService.findSimilarClusters(
          cluster.centroid,
        );
        for (const cId of similarIds) {
          const simFaces = await FaceService.getFacesForCluster(cId);
          for (const f of simFaces) {
            if (f.assetId && f.assetId !== mediaUri) matchedUris.add(f.assetId);
          }
        }
      }
    }

    if (!matchedUris.size) return;
    const matchedArr = Array.from(matchedUris);

    Alert.alert(
      'Similar Faces Found',
      `Found ${matchedArr.length} photo${
        matchedArr.length !== 1 ? 's' : ''
      } with similar faces. Apply "${tagName}" to all of them?`,
      [
        { text: 'Skip', style: 'cancel' },
        {
          text: `Apply to ${matchedArr.length}`,
          onPress: () => {
            TaggingService.batchAddTag(matchedArr, tagId, tagName).catch(err =>
              console.warn('[TagsModal] batch face tag error:', err),
            );
          },
        },
      ],
    );
  } catch (err) {
    console.warn('[TagsModal] _promptSimilarFaces error:', err?.message ?? err);
  }
}

export function TagsModal({
  visible,
  onClose,
  mediaUri,
  mediaRecord = {},
  mediaUris,
}) {
  // Batch mode: when mediaUris (array) is provided, skip pre-selection and
  // suggestions — tags are applied to ALL provided URIs on toggle.
  const isBatch = Array.isArray(mediaUris) && mediaUris.length > 0;
  const batchCount = isBatch ? mediaUris.length : 0;
  const insets = useSafeAreaInsets();
  const { colors, isDark } = useTheme();
  const { state: appState } = useAppContext();
  const { dispatch } = useMediaContext();
  const accent = appState.accentColor ?? colors.accent;

  const [allTags, setAllTags] = useState([]);
  const [selectedIds, setSelectedIds] = useState(new Set());
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(false);

  const [scanning, setScanning] = useState(false);
  const [suggestions, setSuggestions] = useState([]);
  const [appliedSugs, setAppliedSugs] = useState(new Set());

  const [suggestionsHint, setSuggestionsHint] = useState(null);
  const [cacheLoaded, setCacheLoaded] = useState(false);

  // Smart suggestions (heuristic + learned) — applied keys are a subset of selectedIds
  const [smartAppliedKeys, setSmartAppliedKeys] = useState(new Set());

  // Combined smart suggestions loaded from SmartTagSuggestionEngine
  const [smartSuggestions, setSmartSuggestions] = useState([]);
  const [smartSugsLoading, setSmartSugsLoading] = useState(false);
  // Keys dismissed from the combined suggestions bar this session
  const [dismissedSugKeys, setDismissedSugKeys] = useState(new Set());

  // Bumping this forces the smart-suggestions effect to re-run.
  const [smartSugsRevision, setSmartSugsRevision] = useState(0);

  // Suggestions section collapse state — auto-collapses when keyboard opens
  const [sugsCollapsed, setSugsCollapsed] = useState(false);

  // Keyboard height for Android (KAV doesn't work inside Modal on Android)
  const [kbOffset, setKbOffset] = useState(0);

  const searchInputRef = useRef(null);
  // Tracks every tag addition during this modal session.
  // Flushed to TaggingService.runSessionLearning() when the modal closes.
  const pendingLearningRef = useRef([]);

  // Wrap onClose to flush deferred learning before dismissing.
  const handleClose = useCallback(() => {
    const pending = pendingLearningRef.current;
    if (pending.length) {
      TaggingService.runSessionLearning(pending).catch(() => {});
      pendingLearningRef.current = [];
    }
    onClose();
  }, [onClose]);

  useEffect(() => {
    if (Platform.OS !== 'android') return;
    const show = Keyboard.addListener('keyboardDidShow', e => {
      setKbOffset(e.endCoordinates.height);
    });
    const hide = Keyboard.addListener('keyboardDidHide', () => {
      setKbOffset(0);
    });
    return () => {
      show.remove();
      hide.remove();
    };
  }, []);

  // ─── Reset scan state when modal opens ─────────────────────────────────────
  useEffect(() => {
    if (visible) {
      pendingLearningRef.current = [];
      setSuggestions([]);
      setAppliedSugs(new Set());
      setScanning(false);
      setSmartAppliedKeys(new Set());
      setSmartSuggestions([]);
      setSmartSugsLoading(false);
      setDismissedSugKeys(new Set());
      setSmartSugsRevision(0);
      setSuggestionsHint(null);
      setCacheLoaded(false);
      setSugsCollapsed(false);
      setKbOffset(0);
    }
  }, [visible]);

  const isGif = useMemo(() => {
    const name = (mediaRecord?.filename ?? mediaUri ?? '').toLowerCase();
    if (name.endsWith('.gif')) return true;
    const mime = (mediaRecord?.mimeType ?? mediaRecord?.mime_type ?? '')
      .toLowerCase()
      .trim();
    return mime === 'image/gif' || mime.endsWith('/gif');
  }, [
    mediaRecord?.filename,
    mediaRecord?.mimeType,
    mediaRecord?.mime_type,
    mediaUri,
  ]);

  const isPhotoAsset = useMemo(() => {
    const t = (
      mediaRecord?.type ??
      mediaRecord?.media_type ??
      ''
    ).toLowerCase();
    return t === 'image' && !isGif;
  }, [mediaRecord?.type, mediaRecord?.media_type, isGif]);

  // ─── Load tags whenever modal opens ────────────────────────────────────────
  useEffect(() => {
    if (!visible || (!mediaUri && !isBatch)) return;
    let cancelled = false;

    (async () => {
      setLoading(true);
      try {
        if (isBatch) {
          // Batch mode: only load tag catalogue — no per-item selection
          const all = await TagService.getAllTags();
          if (cancelled) return;
          setAllTags(all);
          setSelectedIds(new Set());
        } else {
          const [all, media, cached] = await Promise.all([
            TagService.getAllTags(),
            TagService.getTagsForMedia(mediaUri),
            TagSuggestionService.getMlkitSuggestionsForAsset(mediaUri, {
              maxAgeMs: 1000 * 60 * 60 * 24 * 30,
            }),
          ]);
          if (cancelled) return;
          setAllTags(all);
          setSelectedIds(new Set(media.map(t => t.id)));

          if (cached?.length) {
            setSuggestions(
              cached.map(s => ({ text: s.text, confidence: s.confidence })),
            );
          }
        }
      } catch (err) {
        console.warn('[TagsModal] load error:', err);
      } finally {
        if (!cancelled) {
          setLoading(false);
          setCacheLoaded(true);
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [visible, mediaUri, isBatch]);

  // ─── Filter ────────────────────────────────────────────────────────────────
  const filtered = useMemo(() => {
    if (!search.trim()) return allTags;
    const q = search.trim().toLowerCase();
    return allTags.filter(t => t.name.toLowerCase().includes(q));
  }, [allTags, search]);

  const showCreateButton = useMemo(() => {
    if (!search.trim()) return false;
    return !allTags.some(
      t => t.name.toLowerCase() === search.trim().toLowerCase(),
    );
  }, [allTags, search]);

  // ─── Toggle tag ────────────────────────────────────────────────────────────
  const toggleTag = useCallback(
    async tag => {
      const isSelected = selectedIds.has(tag.id);
      // Optimistic UI
      setSelectedIds(prev => {
        const next = new Set(prev);
        isSelected ? next.delete(tag.id) : next.add(tag.id);
        return next;
      });

      try {
        if (isBatch) {
          // Apply/remove across all selected URIs
          if (isSelected) {
            await TaggingService.batchRemoveTag(mediaUris, tag.id);
            mediaUris.forEach(uri =>
              dispatch(MediaActions.removeTag(uri, tag.name)),
            );
          } else {
            await TaggingService.batchAddTag(mediaUris, tag.id, tag.name);
            mediaUris.forEach(uri =>
              dispatch(MediaActions.addTag(uri, tag.name)),
            );
            mediaUris.forEach(uri =>
              pendingLearningRef.current.push({ mediaUri: uri, tagId: tag.id, tagName: tag.name }),
            );
          }
        } else {
          if (isSelected) {
            await TagService.removeTagFromMedia(mediaUri, tag.id);
            dispatch(MediaActions.removeTag(mediaUri, tag.name));
          } else {
            await TaggingService.addTagToMedia(mediaUri, tag.id, tag.name);
            dispatch(MediaActions.addTag(mediaUri, tag.name));
            pendingLearningRef.current.push({ mediaUri, tagId: tag.id, tagName: tag.name });
            // Fire-and-forget: offer to apply to similar faces if people tag.
            _promptSimilarFaces(mediaUri, tag.id, tag.name);
          }
        }
      } catch (err) {
        console.warn('[TagsModal] toggle error:', err);
        // Rollback
        setSelectedIds(prev => {
          const next = new Set(prev);
          isSelected ? next.add(tag.id) : next.delete(tag.id);
          return next;
        });
      }
    },
    [mediaUri, mediaUris, isBatch, selectedIds, dispatch],
  );

  // ─── Create + apply new tag ────────────────────────────────────────────────
  const createTag = useCallback(async () => {
    const name = search.trim();
    if (!name) return;
    try {
      const tagId = await TagService.createTag(name);
      if (isBatch) {
        await TaggingService.batchAddTag(mediaUris, tagId, name);
        mediaUris.forEach(uri => dispatch(MediaActions.addTag(uri, name)));
      } else {
        await TaggingService.addTagToMedia(mediaUri, tagId, name);
        dispatch(MediaActions.addTag(mediaUri, name));
      }
      setSelectedIds(prev => new Set(prev).add(tagId));
      // Reload tags list
      const all = await TagService.getAllTags();
      setAllTags(all);
      setSearch('');
    } catch (err) {
      console.warn('[TagsModal] create error:', err);
    }
  }, [search, mediaUri, mediaUris, isBatch, dispatch]);

  // ─── Scan image with ML ────────────────────────────────────────────────────
  const handleScan = useCallback(async () => {
    if (scanning || !mediaUri) return;
    if (!isPhotoAsset) {
      setSuggestionsHint(
        'Cannot extract info from non-image file for tag data.',
      );
      return;
    }
    setScanning(true);
    setSuggestions([]);
    setSuggestionsHint(null);

    // Start OCR concurrently but wrap it so it NEVER rejects — any native
    // error becomes an empty array, leaving the main label scan unaffected.
    const ocrPromise = recognizeText(mediaUri).catch(err => {
      console.warn('[TagsModal] OCR non-fatal:', err);
      return [];
    });

    try {
      const raw = await labelImage(mediaUri);

      if (!raw?.length) {
        setScanning(false);
        return;
      }

      // Clean up labels: lowercase, deduplicate, sort by confidence desc
      const seen = new Set();
      const cleaned = raw
        .map(l => ({
          text: l.text.toLowerCase().trim(),
          confidence: l.confidence,
        }))
        .filter(l => {
          if (!l.text || seen.has(l.text)) return false;
          seen.add(l.text);
          return true;
        })
        .sort((a, b) => b.confidence - a.confidence);

      // OCR signal: ocrPromise started concurrently so it is likely already
      // settled by the time we reach here. Never throws (see .catch above).
      const ocrLines = await ocrPromise;
      const joined = (ocrLines ?? []).join(' ').trim();
      if (joined.length >= 12) {
        cleaned.unshift(
          { text: 'printed text', confidence: 0.92 },
          { text: 'document', confidence: 0.85 },
        );
      }

      const curated = classifyLabels(cleaned);
      setSuggestions(curated);
      await TagSuggestionService.saveMlkitSuggestionsForAsset(
        mediaUri,
        curated,
      );
    } catch (err) {
      console.warn('[TagsModal] scan error:', err);
      setSuggestionsHint(
        'Cannot extract info from non-image file for tag data.',
      );
    } finally {
      setScanning(false);
    }
  }, [scanning, mediaUri, isPhotoAsset]);

  // ─── Load smart suggestions (SmartTagSuggestionEngine) ─────────────────────
  useEffect(() => {
    if (!visible || !mediaUri || !isPhotoAsset || isBatch) {
      setSmartSuggestions([]);
      return;
    }
    let cancelled = false;
    setSmartSugsLoading(true);
    SmartTagSuggestionEngine.getSuggestionsForAsset(
      mediaUri,
      mediaRecord,
      Array.from(selectedIds),
    )
      .then(results => {
        if (!cancelled) setSmartSuggestions(results);
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setSmartSugsLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // Reload when the asset changes, or when the user explicitly rescans.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, mediaUri, isPhotoAsset, smartSugsRevision]);

  // Auto-trigger ML suggestions on open (photos only). If cached suggestions
  // exist, we reuse them and skip an immediate re-scan.
  useEffect(() => {
    if (!visible || !mediaUri || isBatch) return;
    if (!cacheLoaded) return;

    if (!isPhotoAsset) {
      setSuggestionsHint(
        'Cannot extract info from non-image file for tag data.',
      );
      return;
    }

    if (suggestions.length === 0 && !scanning) {
      handleScan();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, mediaUri, cacheLoaded, isPhotoAsset]);

  const applySuggestion = useCallback(
    async label => {
      const name = label.text;
      if (appliedSugs.has(name)) return;
      try {
        const existing = allTags.find(
          t => t.name.toLowerCase() === name.toLowerCase(),
        );
        let tagId;
        if (existing) {
          tagId = existing.id;
        } else {
          tagId = await TagService.createTag(name);
          const all = await TagService.getAllTags();
          setAllTags(all);
        }
        if (!selectedIds.has(tagId)) {
          await TaggingService.addTagToMedia(mediaUri, tagId, name);
          dispatch(MediaActions.addTag(mediaUri, name));
          setSelectedIds(prev => new Set(prev).add(tagId));
          pendingLearningRef.current.push({ mediaUri, tagId, tagName: name });
        }
        setAppliedSugs(prev => new Set(prev).add(name));
      } catch (err) {
        console.warn('[TagsModal] applySuggestion error:', err);
      }
    },
    [appliedSugs, allTags, selectedIds, mediaUri, dispatch],
  );

  // ─── Smart suggestion: apply/dismiss ──────────────────────────────────────
  const handleSmartApply = useCallback(
    async tagKey => {
      if (smartAppliedKeys.has(tagKey)) return;
      try {
        const trimmed = tagKey.trim();
        const existing = allTags.find(
          t => t.name.toLowerCase() === trimmed.toLowerCase(),
        );
        let tagId;
        if (existing) {
          tagId = existing.id;
        } else {
          tagId = await TagService.createTag(trimmed);
          const all = await TagService.getAllTags();
          setAllTags(all);
        }
        if (!selectedIds.has(tagId)) {
          await TaggingService.addTagToMedia(mediaUri, tagId, trimmed);
          dispatch(MediaActions.addTag(mediaUri, trimmed));
          setSelectedIds(prev => new Set(prev).add(tagId));
          pendingLearningRef.current.push({ mediaUri, tagId, tagName: trimmed });
          _promptSimilarFaces(mediaUri, tagId, trimmed);
        }
        setSmartAppliedKeys(prev => new Set(prev).add(tagKey));
      } catch (err) {
        console.warn('[TagsModal] handleSmartApply error:', err);
      }
    },
    [smartAppliedKeys, allTags, selectedIds, mediaUri, dispatch],
  );

  // ─── Unified suggestion dismiss / apply ──────────────────────────────────
  const handleSugDismiss = useCallback(
    key => {
      setDismissedSugKeys(prev => new Set(prev).add(key));
      TagPrototypeService.recordRejection(key, mediaUri).catch(() => {});
    },
    [mediaUri],
  );

  const handleSugApply = useCallback(
    async entry => {
      if (entry.isSmart) {
        await handleSmartApply(entry.key);
      } else {
        const label = suggestions.find(l => l.text === entry.key);
        if (label) await applySuggestion(label);
      }
    },
    [handleSmartApply, applySuggestion, suggestions],
  );

  // ─── Rescan suggestions ────────────────────────────────────────────────────
  const handleRescan = useCallback(() => {
    if (scanning || smartSugsLoading) return;
    // Clear stuck dismissals so the freshened list is fully visible.
    setDismissedSugKeys(new Set());
    // Re-run ML Kit label scan.
    handleScan();
    // Re-run smart suggestions engine.
    setSmartSugsRevision(r => r + 1);
  }, [scanning, smartSugsLoading, handleScan]);

  // ─── Styles ────────────────────────────────────────────────────────────────
  const cardBg = colors.card;
  const pillBg = colors.surface;
  const pillBorder = isDark ? 'rgba(255,255,255,0.14)' : colors.border;
  const inputBg = colors.surface;

  // ─── Deduplicated combined suggestion list ─────────────────────────────────
  // Smart-engine results (heuristic / learned / face / cooccur) are shown first.
  // ML Kit entries whose tagKey already exists in the smart list are dropped so
  // the same word never appears twice.
  // Tags already applied to this asset are excluded from suggestions entirely.
  const selectedTagNames = new Set(
    allTags.filter(t => selectedIds.has(t.id)).map(t => t.name.toLowerCase()),
  );
  const smartKeys = new Set(smartSuggestions.map(s => s.tagKey.toLowerCase()));
  const mlkitFiltered = suggestions.filter(
    l => !smartKeys.has(l.text.toLowerCase()),
  );
  const combinedSugs = [
    ...smartSuggestions.map(s => ({
      key: s.tagKey,
      label: s.tagKey,
      pct: Math.round(s.score * 100),
      source: s.source,
      isSmart: true,
    })),
    ...mlkitFiltered.map(l => ({
      key: l.text,
      label: l.text,
      pct: Math.round(l.confidence * 100),
      source: 'mlkit',
      isSmart: false,
    })),
  ].filter(
    e =>
      !dismissedSugKeys.has(e.key) &&
      !selectedTagNames.has(e.key.toLowerCase()),
  );

  if (!visible) return null;

  return (
    <Modal
      visible
      transparent
      animationType="fade"
      statusBarTranslucent
      onRequestClose={handleClose}
    >
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={[
          styles.backdrop,
          { backgroundColor: colors.overlayStrong },
          kbOffset > 0 && { paddingBottom: kbOffset },
        ]}
      >
        {/* Dismiss backdrop */}
        <TouchableOpacity
          style={StyleSheet.absoluteFill}
          activeOpacity={1}
          onPress={handleClose}
        />

        <View
          style={[
            styles.card,
            {
              backgroundColor: cardBg,
              paddingBottom: Math.max(insets.bottom, 16),
            },
          ]}
        >
          {/* Header */}
          <View style={styles.header}>
            <View>
              <Text style={[styles.title, { color: colors.text }]}>Tags</Text>
              {isBatch && (
                <Text style={[styles.batchSubtitle, { color: colors.textSecondary }]}>
                  {`Applying to ${batchCount} item${batchCount !== 1 ? 's' : ''}`}
                </Text>
              )}
            </View>
            <View style={styles.headerActions}>
              <CloseButton onPress={handleClose} />
            </View>
          </View>

          {/* Search / Create bar */}
          <TouchableOpacity
            activeOpacity={1}
            onPress={() => searchInputRef.current?.focus()}
            style={[
              styles.searchRow,
              { backgroundColor: inputBg, borderColor: pillBorder },
            ]}
          >
            <Icon name="search" size={16} color={colors.textTertiary} />
            <TextInput
              ref={searchInputRef}
              style={[styles.searchInput, { color: colors.text }]}
              placeholder="Search or create tag…"
              placeholderTextColor={colors.textTertiary}
              value={search}
              onChangeText={setSearch}
              onFocus={() => setSugsCollapsed(true)}
              onSubmitEditing={showCreateButton ? createTag : undefined}
              returnKeyType={showCreateButton ? 'done' : 'search'}
              autoCapitalize="none"
              autoCorrect={false}
            />
            {search.length > 0 && (
              <TouchableOpacity onPress={() => setSearch('')} hitSlop={HIT}>
                <Icon name="remove" size={14} color={colors.textTertiary} />
              </TouchableOpacity>
            )}
          </TouchableOpacity>

          {/* Suggestions — collapsible, with Rescan button (single-item only) */}
          {!isBatch && (
          <View style={styles.sectionToggleRow}>
            <TouchableOpacity
              style={styles.sectionToggleInner}
              onPress={() => setSugsCollapsed(p => !p)}
              activeOpacity={0.65}
              hitSlop={HIT}
            >
              <Text
                style={[
                  styles.sectionLabel,
                  { color: colors.textSecondary, marginBottom: 0 },
                ]}
              >
                Suggestions
              </Text>
              <Text
                style={[
                  styles.collapseChevron,
                  { color: colors.textSecondary },
                ]}
              >
                {sugsCollapsed ? '▶' : '▾'}
              </Text>
            </TouchableOpacity>
            {isPhotoAsset && (
              <TouchableOpacity
                onPress={handleRescan}
                disabled={scanning || smartSugsLoading}
                hitSlop={HIT}
                style={[
                  styles.rescanBtn,
                  {
                    borderColor: accent,
                    opacity: scanning || smartSugsLoading ? 0.35 : 1,
                  },
                ]}
              >
                <Icon name="refresh" size={11} color={accent} />
                <Text style={[styles.rescanBtnText, { color: accent }]}>
                  Rescan
                </Text>
              </TouchableOpacity>
            )}
          </View>
          )}

          {!isBatch && !sugsCollapsed && (scanning || smartSugsLoading) && (
            <View style={styles.suggestionsLoadingRow}>
              <DotsSpinner color={accent} dotSize={6} gap={5} />
              <Text
                style={[styles.suggestionsHint, { color: colors.textTertiary }]}
              >
                Analysing…
              </Text>
            </View>
          )}

          {!isBatch && !sugsCollapsed && !!suggestionsHint && (
            <Text
              style={[styles.suggestionsHint, { color: colors.textTertiary }]}
            >
              {suggestionsHint}
            </Text>
          )}

          {/* Combined Suggestions (smart + ML Kit, deduplicated, no horizontal scroll) */}
          {!isBatch && !sugsCollapsed && isPhotoAsset && combinedSugs.length > 0 && (
            <View style={styles.suggestionsBox}>
              <View style={styles.suggestionsRow}>
                {combinedSugs.map(entry => {
                  const applied = entry.isSmart
                    ? smartAppliedKeys.has(entry.key)
                    : appliedSugs.has(entry.key);
                  const SRC_ICON = { heuristic: '⚡', learned: '✦' };
                  const srcIcon = SRC_ICON[entry.source];
                  return (
                    <View
                      key={entry.key}
                      style={[
                        styles.pill,
                        styles.sugPillRow,
                        {
                          backgroundColor: applied ? accent + '22' : pillBg,
                          borderColor: applied ? accent : pillBorder,
                        },
                      ]}
                    >
                      <TouchableOpacity
                        onPress={() => handleSugApply(entry)}
                        activeOpacity={0.65}
                        style={styles.sugPillBody}
                      >
                        {!!srcIcon && !applied && (
                          <Text style={[styles.sugPillIcon, { color: accent }]}>
                            {srcIcon}
                          </Text>
                        )}
                        <Text
                          style={[
                            styles.pillText,
                            { color: applied ? accent : colors.text },
                          ]}
                          numberOfLines={1}
                        >
                          {applied ? '✓ ' : ''}
                          {entry.label}
                        </Text>
                        <Text
                          style={[
                            styles.confText,
                            {
                              color: applied
                                ? accent + 'aa'
                                : colors.textTertiary,
                            },
                          ]}
                        >
                          {entry.pct}%
                        </Text>
                      </TouchableOpacity>
                      {!applied && (
                        <TouchableOpacity
                          onPress={() => handleSugDismiss(entry.key)}
                          hitSlop={HIT}
                          style={styles.sugPillDismiss}
                        >
                          <Text
                            style={[
                              styles.sugPillDismissText,
                              { color: colors.textTertiary },
                            ]}
                          >
                            ×
                          </Text>
                        </TouchableOpacity>
                      )}
                    </View>
                  );
                })}
              </View>
            </View>
          )}

          <View
            style={[styles.sectionSeparator, { backgroundColor: pillBorder }]}
          />

          <Text style={[styles.sectionLabel, { color: colors.textSecondary }]}>
            Your tags
          </Text>

          {/* Create button */}
          {showCreateButton && (
            <TouchableOpacity
              style={[styles.createBtn, { backgroundColor: accent }]}
              onPress={createTag}
              activeOpacity={0.7}
            >
              <Text style={styles.createBtnText}>
                + Create "{search.trim()}"
              </Text>
            </TouchableOpacity>
          )}

          {/* Tags grid */}
          {loading ? (
            <View style={styles.loader}>
              <DotsSpinner color={accent} dotSize={8} gap={6} />
            </View>
          ) : (
            <ScrollView
              style={styles.scroll}
              contentContainerStyle={styles.tagsWrap}
              showsVerticalScrollIndicator={false}
              keyboardShouldPersistTaps="handled"
            >
              {filtered.length === 0 && !showCreateButton && (
                <Text
                  style={[styles.emptyText, { color: colors.textTertiary }]}
                >
                  {allTags.length === 0
                    ? 'No tags yet — type above to create one.'
                    : 'No matching tags.'}
                </Text>
              )}
              {filtered.map(tag => {
                const active = selectedIds.has(tag.id);
                return (
                  <TouchableOpacity
                    key={tag.id}
                    onPress={() => toggleTag(tag)}
                    activeOpacity={0.65}
                    style={[
                      styles.pill,
                      {
                        backgroundColor: active ? accent + '22' : pillBg,
                        borderColor: active ? accent : pillBorder,
                      },
                    ]}
                  >
                    <Text
                      style={[
                        styles.pillText,
                        { color: active ? accent : colors.text },
                      ]}
                      numberOfLines={1}
                    >
                      {tag.name}
                    </Text>
                  </TouchableOpacity>
                );
              })}
            </ScrollView>
          )}
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const HIT = { top: 10, right: 10, bottom: 10, left: 10 };

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  card: {
    width: MODAL_W,
    borderRadius: 20,
    paddingTop: 20,
    paddingHorizontal: 20,
    maxHeight: '75%',
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 16,
  },
  headerActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  sectionLabel: {
    fontSize: 11,
    fontWeight: '600',
    letterSpacing: 0.4,
    textTransform: 'uppercase',
    marginBottom: 8,
  },
  sectionToggleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 8,
    gap: 8,
  },
  sectionToggleInner: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  rescanBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: 9,
    paddingVertical: 4,
  },
  rescanBtnText: {
    fontSize: 11,
    fontWeight: '600',
  },
  collapseChevron: {
    fontSize: 12,
    fontWeight: '600',
  },
  sectionSeparator: {
    height: StyleSheet.hairlineWidth,
    width: '100%',
    marginVertical: 12,
    opacity: 0.75,
  },
  suggestionsLoadingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    marginBottom: 10,
  },
  suggestionsHint: {
    fontSize: 12,
    lineHeight: 16,
    marginBottom: 10,
  },
  suggestionsBox: {
    marginBottom: 12,
  },
  suggestionsRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  // Suggestion pill = base pill style + row layout for icon / text / dismiss
  sugPillRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  sugPillBody: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    flexShrink: 1,
  },
  sugPillIcon: {
    fontSize: 11,
    lineHeight: 14,
  },
  sugPillDismiss: {
    paddingLeft: 6,
  },
  sugPillDismissText: {
    fontSize: 16,
    lineHeight: 18,
    fontWeight: '300',
  },
  confText: {
    fontSize: 11,
    fontWeight: '500',
  },
  title: {
    fontSize: 22,
    fontWeight: '700',
    letterSpacing: 0.3,
  },
  batchSubtitle: {
    fontSize: 12,
    fontWeight: '500',
    marginTop: 2,
  },
  searchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: 10,
    borderWidth: 1,
    paddingHorizontal: 12,
    height: 42,
    marginBottom: 12,
    gap: 8,
  },
  searchInput: {
    flex: 1,
    fontSize: 14,
    paddingVertical: 0,
  },
  createBtn: {
    alignSelf: 'flex-start',
    borderRadius: 999,
    paddingHorizontal: 16,
    paddingVertical: 8,
    marginBottom: 12,
  },
  createBtnText: {
    color: '#FFF',
    fontSize: 13,
    fontWeight: '600',
  },
  scroll: {
    flexGrow: 0,
  },
  tagsWrap: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
    paddingBottom: 8,
  },
  pill: {
    borderRadius: 20,
    borderWidth: 1,
    paddingHorizontal: 16,
    paddingVertical: 9,
  },
  pillText: {
    fontSize: 14,
    fontWeight: '500',
  },
  emptyText: {
    fontSize: 13,
    textAlign: 'center',
    marginTop: 16,
  },
  loader: {
    marginTop: 24,
  },
});
