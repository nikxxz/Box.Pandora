/* eslint-disable react-hooks/exhaustive-deps */
import React, {
  useCallback,
  useEffect,
  useMemo,
  useState,
} from 'react';
import {
  ActivityIndicator,
  Alert,
  FlatList,
  Text,
  TextInput,
  TouchableOpacity,
  View,
  ScrollView,
} from 'react-native';
import { AppHeader } from '../../components/common/AppHeader';
import { EmptyState } from '../../components/common/EmptyState';
import { SettingsSidebar } from '../../components/common/SettingsSidebar';
import { useTheme } from '../../providers/ThemeProvider';
import { useAppContext } from '../../store/AppContext';
import { DatabaseService } from '../../services/database/DatabaseService';
import { TagAliasService } from '../../services/database/TagAliasService';
import { TagReviewQueueService } from '../../services/database/TagReviewQueueService';
import { TagIntelligenceService } from '../../services/ai/TagIntelligenceService';
import { createStyles } from './styles';

// ─── Type labels for display ───────────────────────────────────────────────────
const TYPE_LABELS = {
  category: 'Category',
  description: 'Description',
  alias: 'Search Alias',
  canonical_name: 'Canonical Name',
};

// ─── Component ────────────────────────────────────────────────────────────────

export function TagIntelligenceScreen({ navigation }) {
  const { colors } = useTheme();
  const styles = useMemo(() => createStyles(colors), [colors]);
  const { state: appState } = useAppContext();
  const accentColor = appState.accentColor ?? colors.accent;

  // ── Tag list (overview) ────────────────────────────────────────────────────
  const [tags, setTags] = useState([]);
  const [tagsLoading, setTagsLoading] = useState(true);
  const [tagSearchQuery, setTagSearchQuery] = useState('');
  const [sidebarVisible, setSidebarVisible] = useState(false);

  // ── Detail view ────────────────────────────────────────────────────────────
  const [selectedTag, setSelectedTag] = useState(null);
  const [aliases, setAliases] = useState([]);
  const [suggestions, setSuggestions] = useState([]); // pending queue rows
  const [analyzing, setAnalyzing] = useState(false);

  // ── Per-suggestion edit state: Map<id, string|null> ───────────────────────
  const [editValues, setEditValues] = useState({});
  const [editingId, setEditingId] = useState(null);

  // ─── Load tag list ─────────────────────────────────────────────────────────

  const loadTags = useCallback(async () => {
    const db = DatabaseService.getDb();
    if (!db) return;
    setTagsLoading(true);
    try {
      // Try the full v11 query first; fall back if migration hasn't run yet.
      let rows;
      try {
        ({ rows } = await db.execute(
          `SELECT t.id, t.name, t.color, t.category, t.description,
                  t.usage_count, t.last_reviewed_at,
                  (SELECT COUNT(*) FROM tag_review_queue WHERE tag_id = t.id AND status = 'pending') AS pending_count
           FROM tags t
           ORDER BY t.usage_count DESC, t.name ASC`,
        ));
      } catch {
        ({ rows } = await db.execute(
          `SELECT id, name, color, category, description, usage_count,
                  NULL AS last_reviewed_at, 0 AS pending_count
           FROM tags
           ORDER BY usage_count DESC, name ASC`,
        ));
      }
      setTags(rows);
    } finally {
      setTagsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadTags();
  }, []);

  const filteredTags = useMemo(() => {
    const q = tagSearchQuery.trim().toLowerCase();
    if (!q) return tags;
    return tags.filter(tag => {
      const name = String(tag.name ?? '').toLowerCase();
      const category = String(tag.category ?? '').toLowerCase();
      const desc = String(tag.description ?? '').toLowerCase();
      return name.includes(q) || category.includes(q) || desc.includes(q);
    });
  }, [tags, tagSearchQuery]);

  // ─── Open a tag for detail ─────────────────────────────────────────────────

  const openTag = useCallback(async tag => {
    setSelectedTag(tag);
    setEditValues({});
    setEditingId(null);
    // Load aliases and pending suggestions in parallel (tables may not exist yet)
    const [aliasRows, queueRows] = await Promise.all([
      TagAliasService.getAliasesForTag(tag.id).catch(() => []),
      TagReviewQueueService.getPendingForTag(tag.id).catch(() => []),
    ]);
    setAliases(aliasRows);
    setSuggestions(queueRows);
  }, []);

  const closeDetail = useCallback(() => {
    setSelectedTag(null);
    setSuggestions([]);
    setAliases([]);
    setEditValues({});
    setEditingId(null);
    loadTags(); // refresh list (pending counts, last_reviewed_at)
  }, [loadTags]);

  // ─── Run AI analysis ───────────────────────────────────────────────────────

  const handleAnalyze = useCallback(async () => {
    if (!selectedTag || analyzing) return;
    setAnalyzing(true);
    try {
      const rows = await TagIntelligenceService.analyzeTag(selectedTag);
      setSuggestions(rows);
      // Refresh tag's last_reviewed_at
      const db = DatabaseService.getDb();
      if (db) {
        const { rows: tagRows } = await db.execute(
          'SELECT * FROM tags WHERE id = ?',
          [selectedTag.id],
        );
        if (tagRows[0]) setSelectedTag(tagRows[0]);
      }
    } catch (err) {
      Alert.alert('Analysis Failed', err?.message ?? 'Something went wrong.');
    } finally {
      setAnalyzing(false);
    }
  }, [selectedTag, analyzing]);

  // ─── Accept / Edit / Reject ────────────────────────────────────────────────

  const handleAccept = useCallback(async (row) => {
    const edited = editValues[row.id] ?? null;
    // Validate edited value if editing
    if (editingId === row.id && (!edited || !edited.trim())) {
      Alert.alert('Empty value', 'Please enter a value or cancel editing.');
      return;
    }
    try {
      await TagIntelligenceService.acceptSuggestion(
        { ...row, tag_id: row.tag_id ?? selectedTag.id },
        selectedTag.name,
        editingId === row.id && edited?.trim() ? edited.trim() : null,
      );
      setSuggestions(prev => prev.filter(s => s.id !== row.id));
      setEditingId(null);
      // Refresh aliases if it was an alias suggestion
      if (row.analysis_type === 'alias') {
        const aliasRows = await TagAliasService.getAliasesForTag(selectedTag.id);
        setAliases(aliasRows);
      }
      // Refresh tag name/category/description in header
      const db = DatabaseService.getDb();
      if (db) {
        const { rows: tagRows } = await db.execute(
          'SELECT * FROM tags WHERE id = ?',
          [selectedTag.id],
        );
        if (tagRows[0]) setSelectedTag(tagRows[0]);
      }
    } catch (err) {
      Alert.alert('Error', err?.message ?? 'Could not apply change.');
    }
  }, [editValues, editingId, selectedTag]);

  const handleReject = useCallback(async (row) => {
    try {
      await TagIntelligenceService.rejectSuggestion(row);
      setSuggestions(prev => prev.filter(s => s.id !== row.id));
      if (editingId === row.id) setEditingId(null);
    } catch (err) {
      Alert.alert('Error', err?.message ?? 'Could not reject suggestion.');
    }
  }, [editingId]);

  const handleRemoveAlias = useCallback(async (alias) => {
    try {
      await TagAliasService.removeAlias(alias.id);
      setAliases(prev => prev.filter(a => a.id !== alias.id));
    } catch {}
  }, []);

  // ─── Tag list row ──────────────────────────────────────────────────────────

  const renderTagRow = useCallback(({ item }) => {
    const hasPending = item.pending_count > 0;
    const wasReviewed = item.last_reviewed_at != null;
    return (
      <TouchableOpacity
        style={styles.tagRow}
        onPress={() => openTag(item)}
        activeOpacity={0.7}
      >
        <View style={[styles.tagColorDot, { backgroundColor: item.color ?? '#888' }]} />
        <View style={styles.tagRowContent}>
          <Text style={styles.tagRowName}>{item.name}</Text>
          <Text style={styles.tagRowMeta}>
            {item.category} · {item.usage_count} photo{item.usage_count !== 1 ? 's' : ''}
          </Text>
        </View>
        <View style={styles.tagRowRight}>
          {hasPending && (
            <Text style={[styles.tagPendingBadge, { color: accentColor }]}>
              {item.pending_count} pending
            </Text>
          )}
          {!hasPending && wasReviewed && (
            <Text style={styles.tagReviewedBadge}>reviewed</Text>
          )}
          <Text style={styles.chevron}>›</Text>
        </View>
      </TouchableOpacity>
    );
  }, [styles, accentColor, openTag]);

  // ─── Suggestion card ───────────────────────────────────────────────────────

  const renderSuggestion = useCallback((row) => {
    const typeColor = accentColor;
    const isEditing = editingId === row.id;
    const editVal = editValues[row.id] ?? row.suggested_value;

    return (
      <View
        key={row.id}
        style={[styles.suggestionCard, { borderColor: colors.border, backgroundColor: colors.surface }]}
      >
        <Text style={[styles.suggestionType, { color: typeColor }]}>
          {TYPE_LABELS[row.analysis_type] ?? row.analysis_type}
        </Text>

        {isEditing ? (
          <TextInput
            style={[
              styles.editInput,
              {
                borderColor: accentColor,
                color: colors.text,
                backgroundColor: colors.background,
              },
            ]}
            value={editVal}
            onChangeText={v => setEditValues(prev => ({ ...prev, [row.id]: v }))}
            autoFocus
            multiline={row.analysis_type === 'description'}
          />
        ) : (
          <Text style={styles.suggestionValue}>{row.suggested_value}</Text>
        )}

        {!!row.reasoning && (
          <Text style={styles.suggestionReasoning}>{row.reasoning}</Text>
        )}

        <View style={styles.suggestionActions}>
          {/* Accept */}
          <TouchableOpacity
            style={[styles.actionBtn, { backgroundColor: `${accentColor}22`, borderColor: accentColor }]}
            onPress={() => handleAccept(row)}
            activeOpacity={0.8}
          >
            <Text style={[styles.actionBtnLabel, { color: accentColor }]}>
              {isEditing ? 'Apply Edit' : 'Accept'}
            </Text>
          </TouchableOpacity>

          {/* Edit / Cancel Edit */}
          <TouchableOpacity
            style={[styles.actionBtn, { borderColor: colors.border }]}
            onPress={() => {
              if (isEditing) {
                setEditingId(null);
                setEditValues(prev => {
                  const next = { ...prev };
                  delete next[row.id];
                  return next;
                });
              } else {
                setEditingId(row.id);
                setEditValues(prev => ({ ...prev, [row.id]: row.suggested_value }));
              }
            }}
            activeOpacity={0.8}
          >
            <Text style={[styles.actionBtnLabel, { color: colors.textSecondary }]}>
              {isEditing ? 'Cancel' : 'Edit'}
            </Text>
          </TouchableOpacity>

          {/* Reject */}
          <TouchableOpacity
            style={[styles.actionBtn, { borderColor: colors.border }]}
            onPress={() => handleReject(row)}
            activeOpacity={0.8}
          >
            <Text style={[styles.actionBtnLabel, { color: colors.textTertiary }]}>
              Reject
            </Text>
          </TouchableOpacity>
        </View>
      </View>
    );
  }, [styles, colors, accentColor, editingId, editValues, handleAccept, handleReject]);

  // ─── Detail view ───────────────────────────────────────────────────────────

  if (selectedTag) {
    const lastReviewed = selectedTag.last_reviewed_at
      ? new Date(selectedTag.last_reviewed_at).toLocaleDateString()
      : null;

    return (
      <View style={styles.container}>
        <AppHeader
          title={selectedTag.name}
          showBack
          onBackPress={closeDetail}
          onSettingsPress={() => setSidebarVisible(true)}
        />

        <ScrollView
          style={styles.detailScroll}
          contentContainerStyle={styles.detailScrollContent}
          keyboardShouldPersistTaps="handled"
        >
          {/* ─ Tag info card ─────────────────────────────────────────────── */}
          <View style={[styles.tagCard, { borderColor: colors.border, backgroundColor: colors.surface }]}>
            <Text style={styles.tagCardName}>{selectedTag.name}</Text>
            <Text style={styles.tagCardMeta}>
              {selectedTag.category} · {selectedTag.usage_count ?? 0} photo{selectedTag.usage_count !== 1 ? 's' : ''}
              {lastReviewed ? ` · reviewed ${lastReviewed}` : ''}
            </Text>
            {!!selectedTag.description && (
              <Text style={styles.tagCardDesc}>{selectedTag.description}</Text>
            )}
          </View>

          {/* ─ Analyze button ─────────────────────────────────────────────── */}
          <TouchableOpacity
            style={[styles.analyzeBtn, { backgroundColor: accentColor }]}
            onPress={handleAnalyze}
            disabled={analyzing}
            activeOpacity={0.8}
          >
            {analyzing ? (
              <ActivityIndicator color="#fff" size="small" />
            ) : (
              <Text style={styles.analyzeBtnLabel}>
                {suggestions.length > 0 ? '↻ Re-Analyze with AI' : '✦ Analyze with AI'}
              </Text>
            )}
          </TouchableOpacity>

          {/* ─ Pending suggestions ────────────────────────────────────────── */}
          {suggestions.length > 0 && (
            <>
              <Text style={styles.sectionLabel}>Pending Suggestions</Text>
              {suggestions.map(renderSuggestion)}
            </>
          )}

          {/* ─ No suggestions ─────────────────────────────────────────────── */}
          {!analyzing && suggestions.length === 0 && selectedTag.last_reviewed_at && (
            <View style={styles.emptyBox}>
              <Text style={styles.emptyIcon}>✓</Text>
              <Text style={styles.emptyLabel}>All suggestions reviewed.{'\n'}Tap Re-Analyze to run AI again.</Text>
            </View>
          )}

          {/* ─ Aliases ────────────────────────────────────────────────────── */}
          {aliases.length > 0 && (
            <>
              <Text style={[styles.sectionLabel, { marginTop: 20 }]}>Search Aliases</Text>
              <View style={styles.aliasesWrap}>
                {aliases.map(a => (
                  <TouchableOpacity
                    key={a.id}
                    style={[styles.aliasPill, { borderColor: colors.border }]}
                    onPress={() =>
                      Alert.alert(
                        'Remove Alias',
                        `Remove "${a.alias}" from search aliases?`,
                        [
                          { text: 'Cancel', style: 'cancel' },
                          { text: 'Remove', style: 'destructive', onPress: () => handleRemoveAlias(a) },
                        ],
                      )
                    }
                    activeOpacity={0.7}
                  >
                    <Text style={styles.aliasPillText}>{a.alias}</Text>
                    <Text style={styles.aliasPillRemove}>✕</Text>
                  </TouchableOpacity>
                ))}
              </View>
            </>
          )}
        </ScrollView>

        <SettingsSidebar
          visible={sidebarVisible}
          onClose={() => setSidebarVisible(false)}
          onNavigate={routeName => navigation.navigate(routeName)}
          onCreateFolder={() => {}}
        />
      </View>
    );
  }

  // ─── Tag list (overview) ───────────────────────────────────────────────────

  return (
    <View style={styles.container}>
      <AppHeader
        title="tag intelligence"
        onSettingsPress={() => setSidebarVisible(true)}
      />

      {tagsLoading ? (
        <View style={styles.loadingWrap}>
          <ActivityIndicator color={accentColor} size="large" />
          <Text style={styles.loadingLabel}>Loading tags…</Text>
        </View>
      ) : tags.length === 0 ? (
        <EmptyState
          icon="◎"
          message="No tags yet"
          subMessage="Start tagging photos to use Tag Intelligence."
        />
      ) : (
        <FlatList
          data={filteredTags}
          keyExtractor={item => String(item.id)}
          renderItem={renderTagRow}
          contentContainerStyle={styles.listContent}
          ListHeaderComponent={
            <View style={styles.listHeader}>
              <Text style={styles.listHeaderTitle}>{filteredTags.length} Tags</Text>
              <TextInput
                style={[
                  styles.searchInput,
                  {
                    borderColor: colors.border,
                    color: colors.text,
                    backgroundColor: colors.surface,
                  },
                ]}
                placeholder="Search tags..."
                placeholderTextColor={colors.textTertiary}
                value={tagSearchQuery}
                onChangeText={setTagSearchQuery}
                autoCapitalize="none"
                autoCorrect={false}
                clearButtonMode="while-editing"
              />
              <Text style={styles.listHeaderSub}>
                Select a tag to analyze it with AI and review suggestions.
              </Text>
            </View>
          }
          ListEmptyComponent={
            <EmptyState
              icon="*"
              message="No matching tags"
              subMessage="Try a different search term."
            />
          }
        />
      )}

      <SettingsSidebar
        visible={sidebarVisible}
        onClose={() => setSidebarVisible(false)}
        onNavigate={routeName => navigation.navigate(routeName)}
        onCreateFolder={() => {}}
      />
    </View>
  );
}
