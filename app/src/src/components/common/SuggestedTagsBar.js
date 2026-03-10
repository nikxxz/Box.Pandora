/**
 * SuggestedTagsBar
 *
 * A horizontal chip row displaying smart tag suggestions (heuristic + learned).
 * Tapping a chip adds the tag to the photo; tapping the × dismisses it.
 *
 * Props:
 *   assetId        {string}   — content:// or ph:// URI of the photo
 *   mediaRecord    {object}   — row from media_index { filename, album_name, uri }
 *   onApply        {function} — (tagName: string) => void — called when user taps a chip
 *   onDismiss      {function} — (tagKey: string) => void — called when user taps ×
 *   appliedKeys    {Set}      — Set of tagKey strings already applied (shown as ✓)
 *   style          {object}   — optional outer style override
 *
 * Data loading:
 *   The bar auto-loads suggestions when assetId changes. Loading state shows a
 *   compact shimmer row. If no suggestions come back the bar renders nothing.
 */

import React, { useEffect, useState, useCallback } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  ScrollView,
  StyleSheet,
  ActivityIndicator,
} from 'react-native';
import { useTheme } from '../../providers/ThemeProvider';
import { useAppContext } from '../../store/AppContext';
import { SmartTagSuggestionEngine } from '../../services/ml/SmartTagSuggestionEngine';
import { TagPrototypeService } from '../../services/database/TagPrototypeService';

// ─── Source label map ─────────────────────────────────────────────────────────

const SOURCE_ICON = {
  heuristic: '⚡',
  learned: '✦',
};

// ─── Component ────────────────────────────────────────────────────────────────

export function SuggestedTagsBar({
  assetId,
  mediaRecord = {},
  existingTagIds = [],
  onApply,
  onDismiss,
  appliedKeys,
  style,
}) {
  const { colors } = useTheme();
  const { state: appState } = useAppContext();
  const accent = appState.accentColor ?? colors.accent;

  const [suggestions, setSuggestions] = useState([]);
  const [loading, setLoading] = useState(false);
  const [dismissed, setDismissed] = useState(new Set());

  // Reset and reload when the asset changes.
  useEffect(() => {
    if (!assetId) {
      setSuggestions([]);
      return;
    }
    setDismissed(new Set());
    setSuggestions([]);
    let cancelled = false;
    setLoading(true);

    SmartTagSuggestionEngine.getSuggestionsForAsset(
      assetId,
      mediaRecord,
      existingTagIds,
    )
      .then(results => {
        if (!cancelled) setSuggestions(results);
      })
      .catch(err => {
        console.warn('[SuggestedTagsBar]', err?.message ?? err);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
    // mediaRecord is usually a stable ref passed from parent — include assetId only
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [assetId]);

  const handleApply = useCallback(
    tagKey => {
      onApply?.(tagKey);
    },
    [onApply],
  );

  const handleDismiss = useCallback(
    tagKey => {
      setDismissed(prev => new Set(prev).add(tagKey));
      // Record rejection directly so learning is guaranteed regardless of
      // whether the parent wires an onDismiss callback.
      TagPrototypeService.recordRejection(tagKey, assetId).catch(() => {});
      onDismiss?.(tagKey);
    },
    [assetId, onDismiss],
  );

  // Filter out dismissed chips.
  const visible = suggestions.filter(s => !dismissed.has(s.tagKey));

  if (!loading && visible.length === 0) return null;

  const pillBg = colors.surface;

  return (
    <View style={[styles.container, style]}>
      {/* <Text style={[styles.label, { color: colors.textSecondary }]}>
        Smart Suggestions
      </Text> */}

      {loading ? (
        <View style={styles.loadingRow}>
          <ActivityIndicator size="small" color={accent} />
          <Text style={[styles.loadingText, { color: colors.textTertiary }]}>
            analysing…
          </Text>
        </View>
      ) : (
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.row}
        >
          {visible.map(sug => {
            const applied = appliedKeys?.has(sug.tagKey);
            const icon = SOURCE_ICON[sug.source] ?? '·';
            const pct = Math.round(sug.score * 100);

            return (
              <View
                key={sug.tagKey + sug.source}
                style={[
                  styles.chip,
                  {
                    backgroundColor: applied ? accent + '22' : pillBg,
                    borderColor: applied ? accent : accent + '44',
                  },
                ]}
              >
                <TouchableOpacity
                  onPress={() => handleApply(sug.tagKey)}
                  activeOpacity={0.65}
                  style={styles.chipBody}
                >
                  <Text style={[styles.chipIcon, { color: accent }]}>
                    {applied ? '✓' : icon}
                  </Text>
                  <Text
                    style={[
                      styles.chipText,
                      { color: applied ? accent : colors.text },
                    ]}
                    numberOfLines={1}
                  >
                    {sug.tagKey}
                  </Text>
                  {!applied && (
                    <Text
                      style={[styles.chipPct, { color: colors.textTertiary }]}
                    >
                      {pct}%
                    </Text>
                  )}
                </TouchableOpacity>

                {!applied && (
                  <TouchableOpacity
                    onPress={() => handleDismiss(sug.tagKey)}
                    hitSlop={HIT}
                    style={styles.dismissBtn}
                  >
                    <Text
                      style={[
                        styles.dismissText,
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
        </ScrollView>
      )}
    </View>
  );
}

// ─── Constants ────────────────────────────────────────────────────────────────

const HIT = { top: 8, left: 8, right: 8, bottom: 8 };

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  container: {
    marginBottom: 8,
  },
  label: {
    fontSize: 11,
    fontWeight: '600',
    letterSpacing: 0.5,
    textTransform: 'uppercase',
    marginBottom: 6,
    marginHorizontal: 4,
  },
  loadingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 4,
    gap: 8,
    marginTop: 4,
    marginBottom: 8,
  },
  loadingText: {
    fontSize: 12,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingRight: 8,
    gap: 6,
  },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
    borderRadius: 20,
    paddingVertical: 5,
    paddingLeft: 10,
    paddingRight: 6,
    maxWidth: 180,
  },
  chipBody: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    flexShrink: 1,
  },
  chipIcon: {
    fontSize: 11,
    lineHeight: 14,
  },
  chipText: {
    fontSize: 12,
    fontWeight: '500',
    flexShrink: 1,
  },
  chipPct: {
    fontSize: 10,
    marginLeft: 2,
  },
  dismissBtn: {
    marginLeft: 4,
    paddingHorizontal: 3,
  },
  dismissText: {
    fontSize: 16,
    lineHeight: 18,
    fontWeight: '300',
  },
});
