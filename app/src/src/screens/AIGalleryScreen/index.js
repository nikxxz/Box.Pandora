/* eslint-disable react-hooks/exhaustive-deps */
import React, { useCallback, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Keyboard,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { AppHeader } from '../../components/common/AppHeader';
import { EmptyState } from '../../components/common/EmptyState';
import { SettingsSidebar } from '../../components/common/SettingsSidebar';
import { MediaGrid } from '../../components/grid/MediaGrid';
import { MediaThumbnail } from '../../components/grid/MediaThumbnail';
import { MediaViewer } from '../../components/media/MediaViewer';
import { TagResolverService } from '../../services/ai/TagResolverService';
import { AISearchService } from '../../services/ai/AISearchService';
import { useTheme } from '../../providers/ThemeProvider';
import { useAppContext } from '../../store/AppContext';
import { createStyles } from './styles';

// ─── Pipeline phases ──────────────────────────────────────────────────────────
//   idle → resolving → searching → done
//                               ↘ error
const PHASE_IDLE = 'idle';
const PHASE_RESOLVING = 'resolving';  // loading tags + asking AI
const PHASE_SEARCHING = 'searching'; // SQLite query
const PHASE_DONE = 'done';
const PHASE_ERROR = 'error';

const PHASE_LABELS = {
  [PHASE_RESOLVING]: 'Resolving tags…',
  [PHASE_SEARCHING]: 'Searching library…',
};

// ─── Component ────────────────────────────────────────────────────────────────

export function AIGalleryScreen({ navigation }) {
  const { colors } = useTheme();
  const styles = useMemo(() => createStyles(colors), [colors]);
  const { state: appState } = useAppContext();
  const accentColor = appState.accentColor ?? colors.accent;

  // ─── UI ────────────────────────────────────────────────────────────────────
  const [sidebarVisible, setSidebarVisible] = useState(false);
  const [viewerIndex, setViewerIndex] = useState(-1);
  const [showDebug, setShowDebug] = useState(false);

  // ─── Search pipeline state ────────────────────────────────────────────────
  const [query, setQuery] = useState('');
  const [phase, setPhase] = useState(PHASE_IDLE);
  const [resolveResult, setResolveResult] = useState(null); // TagResolverService output
  const [results, setResults] = useState([]);
  const [errorMessage, setErrorMessage] = useState('');

  const inputRef = useRef(null);
  const resultsRef = useRef(results);
  resultsRef.current = results;

  const isLoading = phase === PHASE_RESOLVING || phase === PHASE_SEARCHING;

  // ─── Search handler ───────────────────────────────────────────────────────

  const handleSearch = useCallback(async () => {
    const q = query.trim();
    if (!q || isLoading) return;
    Keyboard.dismiss();
    setResolveResult(null);
    setResults([]);
    setErrorMessage('');
    setShowDebug(false);

    try {
      // Stage 1+2: load tags, pre-filter candidates, ask AI to resolve
      setPhase(PHASE_RESOLVING);
      const resolved = await TagResolverService.resolve(q);
      setResolveResult(resolved);

      // Stage 3: execute search with only resolved tag IDs
      setPhase(PHASE_SEARCHING);
      const tagIds = resolved.matched.map(m => m.tagId);
      const items = await AISearchService.search({
        tagIds,
        metadata: resolved.metadata,
        matchMode: 'weighted',
      });
      setResults(items);
      setPhase(PHASE_DONE);
    } catch (err) {
      setErrorMessage(err?.message ?? 'Something went wrong.');
      setPhase(PHASE_ERROR);
    }
  }, [query, isLoading]);

  // ─── Viewer ───────────────────────────────────────────────────────────────

  const handleThumbnailPress = useCallback(item => {
    const idx = resultsRef.current.findIndex(r => r.uri === item.uri);
    setViewerIndex(idx >= 0 ? idx : 0);
  }, []);

  const renderItem = useCallback(
    ({ item }) => (
      <MediaThumbnail item={item} onPress={handleThumbnailPress} />
    ),
    [handleThumbnailPress],
  );

  // ─── List header: resolution summary + debug panel ────────────────────────

  const ListHeader = useMemo(() => {
    if (!resolveResult) return null;
    const { matched, unmatched, candidates, metadata, aiRaw } = resolveResult;
    const tagIds = matched.map(m => m.tagId);

    return (
      <View style={styles.listHeader}>
        {/* ── Resolved tag chips ─────────────────────────────────────── */}
        {matched.length > 0 && (
          <View style={styles.resolveSection}>
            <Text style={styles.resolveLabel}>RESOLVED TAGS</Text>
            <View style={styles.chipsRow}>
              {matched.map(m => (
                <View
                  key={m.tagId}
                  style={[styles.chip, styles.chipResolved, { borderColor: accentColor }]}
                >
                  <Text style={[styles.chipText, { color: accentColor }]}>
                    {m.tagName}
                  </Text>
                </View>
              ))}
            </View>
          </View>
        )}

        {/* ── Unmatched concept chips ────────────────────────────────── */}
        {unmatched.length > 0 && (
          <View style={styles.resolveSection}>
            <Text style={styles.resolveLabel}>NOT FOUND IN LIBRARY</Text>
            <View style={styles.chipsRow}>
              {unmatched.map((c, i) => (
                <View key={i} style={[styles.chip, styles.chipUnmatched]}>
                  <Text style={[styles.chipText, { color: colors.textTertiary }]}>
                    {c}
                  </Text>
                </View>
              ))}
            </View>
          </View>
        )}

        {/* ── Metadata badges ────────────────────────────────────────── */}
        {metadata && (
          <View style={styles.chipsRow}>
            {metadata.media_type && metadata.media_type !== 'all' && (
              <View style={styles.chip}>
                <Text style={styles.chipText}>{metadata.media_type}</Text>
              </View>
            )}
            {(metadata.date_from || metadata.date_to) && (
              <View style={styles.chip}>
                <Text style={styles.chipText}>
                  {[metadata.date_from, metadata.date_to].filter(Boolean).join(' → ')}
                </Text>
              </View>
            )}
            {metadata.folder && (
              <View style={styles.chip}>
                <Text style={styles.chipText}>folder: {metadata.folder}</Text>
              </View>
            )}
            {metadata.sort && metadata.sort !== 'relevance' && (
              <View style={styles.chip}>
                <Text style={styles.chipText}>{metadata.sort.replace('_', ' ')}</Text>
              </View>
            )}
          </View>
        )}

        {/* ── Result count + match info ──────────────────────────────── */}
        {phase === PHASE_DONE && (
          <View style={styles.resultMeta}>
            <Text style={styles.resultCount}>
              {results.length > 0
                ? `${results.length} result${results.length !== 1 ? 's' : ''}`
                : 'No results found'}
            </Text>
            {tagIds.length > 0 && (
              <Text style={styles.matchNote}>
                {tagIds.length} tag{tagIds.length !== 1 ? 's' : ''} · weighted relevance
              </Text>
            )}
          </View>
        )}

        {/* ── Debug panel toggle ─────────────────────────────────────── */}
        <TouchableOpacity
          style={styles.debugToggle}
          onPress={() => setShowDebug(v => !v)}
          activeOpacity={0.7}
        >
          <Text style={[styles.debugToggleLabel, { color: accentColor }]}>
            {showDebug ? '▲ hide debug' : '▼ show debug'}
          </Text>
        </TouchableOpacity>

        {/* ── Debug panel ───────────────────────────────────────────── */}
        {showDebug && (
          <View style={[styles.debugPanel, { borderColor: colors.border }]}>
            <DebugRow label="QUERY" value={query} colors={colors} />
            <DebugRow
              label="CANDIDATES SENT TO AI"
              value={`${candidates.length} tags`}
              colors={colors}
            />
            <DebugRow
              label="RESOLVED TAG IDs"
              value={
                tagIds.length > 0 ? tagIds.join(', ') : 'none'
              }
              colors={colors}
            />
            <DebugRow
              label="UNMATCHED CONCEPTS"
              value={
                unmatched.length > 0 ? unmatched.join(', ') : 'none'
              }
              colors={colors}
            />
            <DebugRow label="MATCH MODE" value="any · weighted" colors={colors} />
            {aiRaw && (
              <View style={styles.debugJsonWrap}>
                <Text style={styles.debugKey}>AI RAW RESPONSE</Text>
                <Text style={styles.debugJson} selectable>
                  {JSON.stringify(aiRaw, null, 2)}
                </Text>
              </View>
            )}
          </View>
        )}
      </View>
    );
  }, [
    resolveResult,
    phase,
    results.length,
    showDebug,
    query,
    accentColor,
    colors,
    styles,
  ]);

  // ─── Empty / error ────────────────────────────────────────────────────────

  const EmptyView = useMemo(() => {
    if (phase === PHASE_ERROR) {
      return (
        <EmptyState icon="✕" message="Search failed" subMessage={errorMessage} />
      );
    }
    if (phase === PHASE_DONE && results.length === 0) {
      return (
        <EmptyState
          icon="◎"
          message="No matches found"
          subMessage={
            resolveResult?.matched.length === 0
              ? 'None of the query concepts matched existing tags in your library.'
              : 'Tags were resolved but no media has those tags yet.'
          }
        />
      );
    }
    if (phase === PHASE_IDLE) {
      return (
        <EmptyState
          icon="✦"
          message="Ask your gallery"
          subMessage={'"show me dog photos" · "monochrome portraits from last year"'}
        />
      );
    }
    return null;
  }, [phase, results.length, errorMessage, resolveResult]);

  // ─── Render ───────────────────────────────────────────────────────────────

  return (
    <View style={styles.container}>
      <AppHeader
        title="ai gallery"
        onSettingsPress={() => setSidebarVisible(true)}
      />

      {/* ─── Natural language input ───────────────────────────────────── */}
      <View style={[styles.searchBar, { borderBottomColor: colors.border }]}>
        <TextInput
          ref={inputRef}
          style={[
            styles.searchInput,
            {
              backgroundColor: colors.surface,
              color: colors.text,
              borderColor: colors.border,
            },
          ]}
          value={query}
          onChangeText={setQuery}
          placeholder="Describe what you're looking for…"
          placeholderTextColor={colors.textTertiary}
          returnKeyType="search"
          onSubmitEditing={handleSearch}
          editable={!isLoading}
          autoCorrect={false}
          autoCapitalize="none"
          multiline={false}
        />
        <TouchableOpacity
          style={[
            styles.sendBtn,
            { backgroundColor: accentColor },
            (!query.trim() || isLoading) && styles.sendBtnDisabled,
          ]}
          onPress={handleSearch}
          disabled={!query.trim() || isLoading}
          activeOpacity={0.8}
        >
          {isLoading ? (
            <ActivityIndicator color="#FFF" size="small" />
          ) : (
            <Text style={styles.sendBtnLabel}>↑</Text>
          )}
        </TouchableOpacity>
      </View>

      {/* ─── Loading ─────────────────────────────────────────────────── */}
      {isLoading && (
        <View style={styles.loadingWrap}>
          <ActivityIndicator color={accentColor} size="large" />
          <Text style={[styles.loadingLabel, { color: colors.textSecondary }]}>
            {PHASE_LABELS[phase] ?? '…'}
          </Text>
        </View>
      )}

      {/* ─── Results ─────────────────────────────────────────────────── */}
      {!isLoading && (
        <MediaGrid
          data={results}
          renderItem={renderItem}
          numColumns={3}
          ListHeaderComponent={ListHeader}
          ListEmptyComponent={EmptyView}
        />
      )}

      {/* ─── Viewer ──────────────────────────────────────────────────── */}
      <MediaViewer
        visible={viewerIndex >= 0}
        items={results}
        initialIndex={viewerIndex}
        initialMaximized
        onClose={() => setViewerIndex(-1)}
      />

      {/* ─── Sidebar ─────────────────────────────────────────────────── */}
      <SettingsSidebar
        visible={sidebarVisible}
        onClose={() => setSidebarVisible(false)}
        onNavigate={routeName => navigation.navigate(routeName)}
      />
    </View>
  );
}

// ─── Debug row ────────────────────────────────────────────────────────────────
function DebugRow({ label, value, colors }) {
  return (
    <View style={{ marginBottom: 8 }}>
      <Text
        style={{
          fontSize: 9,
          fontWeight: '700',
          letterSpacing: 1.5,
          color: colors.textTertiary,
          marginBottom: 2,
        }}
      >
        {label}
      </Text>
      <Text
        style={{
          fontSize: 12,
          color: colors.textSecondary,
          fontFamily: 'monospace',
        }}
        selectable
      >
        {value}
      </Text>
    </View>
  );
}
