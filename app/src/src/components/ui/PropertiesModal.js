import React, { useEffect, useRef, useState } from 'react';
import {
  Animated,
  Easing,
  Modal,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  TouchableWithoutFeedback,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../../providers/ThemeProvider';
import { useAppContext } from '../../store/AppContext';
import { MediaIndexService } from '../../services/database/MediaIndexService';
import { AlbumIndexService } from '../../services/database/AlbumIndexService';
import { Icon } from './Icon';
import { CloseButton } from './CloseButton';

// ─── Helpers ─────────────────────────────────────────────────────────────────

function formatBytes(bytes) {
  if (!bytes || bytes === 0) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024)
    return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

function formatDuration(seconds) {
  if (!seconds) return '—';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0)
    return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  return `${m}:${String(s).padStart(2, '0')}`;
}

function formatDate(tsMs) {
  if (!tsMs) return '—';
  const d = new Date(tsMs);
  return d.toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function formatRating(r) {
  if (!r || r === 0) return 'Not rated';
  return '★'.repeat(r) + '☆'.repeat(5 - r);
}

// ─── Row ─────────────────────────────────────────────────────────────────────

function PropRow({ label, value, colors, accent, mono = false }) {
  if (value === null || value === undefined || value === '') return null;
  return (
    <View style={styles.propRow}>
      <Text style={[styles.propLabel, { color: colors.textSecondary }]}>
        {label}
      </Text>
      <Text
        style={[
          styles.propValue,
          { color: colors.text },
          mono && styles.propMono,
        ]}
        selectable
      >
        {value}
      </Text>
    </View>
  );
}

function SectionHeader({ title, colors }) {
  return (
    <Text style={[styles.sectionHeader, { color: colors.textTertiary }]}>
      {title}
    </Text>
  );
}

// ─── PropertiesModal ─────────────────────────────────────────────────────────

/**
 * PropertiesModal
 *
 * Animated bottom-sheet showing detailed properties for a media file or album.
 *
 * Props:
 *   visible  — boolean
 *   onClose  — () => void
 *   item     — media item from CameraRoll / SQLite  (file properties mode)
 *   folder   — album/folder object                  (folder properties mode)
 *
 * Pass exactly one of `item` or `folder`.
 */
export function PropertiesModal({ visible, onClose, item, folder }) {
  const insets = useSafeAreaInsets();
  const { colors, isDark } = useTheme();
  const { state: appState } = useAppContext();
  const accent = appState.accentColor ?? colors.accent;

  const [dbRow, setDbRow] = useState(null);
  const [dbAlbum, setDbAlbum] = useState(null);
  const [loading, setLoading] = useState(false);

  // mountedVisible keeps the Modal in the tree while the exit animation plays.
  // When visible→false we animate out first, then set mountedVisible=false.
  const [mountedVisible, setMountedVisible] = useState(false);

  // ── Slide-in animation ────────────────────────────────────────────────────
  const translateY = useRef(new Animated.Value(600)).current;
  const backdropOpacity = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    if (visible) {
      // Reset to off-screen before mounting so no stale position is shown.
      translateY.setValue(600);
      backdropOpacity.setValue(0);
      setMountedVisible(true);
      Animated.parallel([
        Animated.timing(backdropOpacity, {
          toValue: 1,
          duration: 240,
          easing: Easing.out(Easing.quad),
          useNativeDriver: true,
        }),
        Animated.spring(translateY, {
          toValue: 0,
          tension: 160,
          friction: 22,
          useNativeDriver: true,
        }),
      ]).start();
    } else {
      Animated.parallel([
        Animated.timing(backdropOpacity, {
          toValue: 0,
          duration: 180,
          easing: Easing.in(Easing.quad),
          useNativeDriver: true,
        }),
        Animated.timing(translateY, {
          toValue: 600,
          duration: 200,
          easing: Easing.in(Easing.cubic),
          useNativeDriver: true,
        }),
      ]).start(({ finished }) => {
        if (finished) setMountedVisible(false);
      });
    }
  }, [visible, translateY, backdropOpacity]);

  // ── Load SQLite data when item changes ────────────────────────────────────
  useEffect(() => {
    // Use mountedVisible (not visible) so data stays populated during the
    // closing animation and is only cleared after the sheet is fully hidden.
    if (!mountedVisible) {
      setDbRow(null);
      setDbAlbum(null);
      return;
    }

    let cancelled = false;
    async function load() {
      setLoading(true);
      try {
        if (item?.uri) {
          const row = await MediaIndexService.getByUri(item.uri);
          if (!cancelled) setDbRow(row);
        } else if (folder?.name) {
          const albumRow = await AlbumIndexService.getByName(folder.name);
          if (!cancelled) setDbAlbum(albumRow);
        }
      } catch (err) {
        console.warn('[PropertiesModal] load error:', err);
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    load();
    return () => {
      cancelled = true;
    };
  }, [mountedVisible, visible, item?.uri, folder?.name]);

  // ── Derived display values ────────────────────────────────────────────────

  // Merge live item data + SQLite row for best accuracy
  const merged = dbRow
    ? {
        filename: dbRow.filename || item?.filename || '—',
        mediaType: dbRow.media_type || item?.type || 'image',
        extension: dbRow.extension || '',
        fileSize: dbRow.file_size || item?.fileSize || 0,
        width: dbRow.width || item?.width || 0,
        height: dbRow.height || item?.height || 0,
        duration: dbRow.duration ?? item?.duration ?? null,
        createdAt: dbRow.device_created_at,
        modifiedAt: dbRow.device_modified_at,
        indexedAt: dbRow.indexed_at,
        scannedAt: dbRow.scanned_at,
        rating: dbRow.rating ?? 0,
        favorite: dbRow.favorite === 1,
        hidden: dbRow.hidden === 1,
        notes: dbRow.notes ?? '',
        albumName: dbRow.album_name ?? item?.albumName ?? '',
        uri: dbRow.uri || item?.uri || '',
      }
    : item
    ? {
        filename: item.filename || '—',
        mediaType: item.type || 'image',
        extension: item.filename?.split('.').pop() || '',
        fileSize: item.fileSize || 0,
        width: item.width || 0,
        height: item.height || 0,
        duration: item.duration ?? null,
        createdAt: item.timestamp ? item.timestamp * 1000 : null,
        modifiedAt: null,
        indexedAt: null,
        scannedAt: null,
        rating: 0,
        favorite: false,
        hidden: false,
        notes: '',
        albumName: item.albumName || '',
        uri: item.uri || '',
      }
    : null;

  const isVideo = merged?.mediaType?.includes('video');

  const sheetBg = colors.card;
  const dividerColor = isDark ? 'rgba(255,255,255,0.07)' : colors.divider;
  const handleColor = isDark ? 'rgba(255,255,255,0.18)' : colors.border;
  const sectionBg = colors.surface;

  return (
    <Modal
      visible={mountedVisible}
      transparent
      animationType="none"
      statusBarTranslucent
      onRequestClose={onClose}
    >
      {/* Backdrop */}
      <TouchableWithoutFeedback onPress={onClose}>
        <Animated.View
          style={[
            styles.backdrop,
            { backgroundColor: colors.overlayStrong, opacity: backdropOpacity },
          ]}
        />
      </TouchableWithoutFeedback>

      {/* Sheet */}
      <Animated.View
        style={[
          styles.sheet,
          {
            backgroundColor: sheetBg,
            paddingBottom: insets.bottom + 20,
            transform: [{ translateY }],
          },
        ]}
      >
        {/* Drag handle */}
        <View style={[styles.handle, { backgroundColor: handleColor }]} />

        {/* Header row */}
        <View style={styles.headerRow}>
          <Icon
            name={folder ? 'information' : isVideo ? 'video' : 'picture'}
            size={22}
            color={colors.textSecondary}
          />
          <Text
            style={[styles.headerTitle, { color: colors.text }]}
            numberOfLines={1}
          >
            {folder
              ? folder.name ?? 'Folder'
              : merged?.filename ?? 'Properties'}
          </Text>
          <CloseButton onPress={onClose} style={styles.closeBtn} />
        </View>

        <View style={[styles.divider, { backgroundColor: dividerColor }]} />

        <ScrollView
          style={styles.scroll}
          contentContainerStyle={styles.scrollContent}
          showsVerticalScrollIndicator={false}
        >
          {loading && (
            <Text style={[styles.loadingText, { color: colors.textTertiary }]}>
              Loading…
            </Text>
          )}

          {/* ── FILE PROPERTIES ─────────────────────────────────────── */}
          {merged && !folder && (
            <>
              <SectionHeader title="GENERAL" colors={colors} />
              <View
                style={[
                  styles.section,
                  {
                    backgroundColor: sectionBg,
                    borderColor: dividerColor,
                  },
                ]}
              >
                <PropRow
                  label="Name"
                  value={merged.filename}
                  colors={colors}
                  accent={accent}
                />
                <PropRow
                  label="Type"
                  value={merged.mediaType === 'video' ? 'Video' : 'Image'}
                  colors={colors}
                  accent={accent}
                />
                <PropRow
                  label="Extension"
                  value={merged.extension ? `.${merged.extension}` : '—'}
                  colors={colors}
                  accent={accent}
                />
                <PropRow
                  label="Size"
                  value={formatBytes(merged.fileSize)}
                  colors={colors}
                  accent={accent}
                />
                <PropRow
                  label="Album"
                  value={merged.albumName || '—'}
                  colors={colors}
                  accent={accent}
                />
              </View>

              {(merged.width > 0 || merged.duration) && (
                <>
                  <SectionHeader
                    title={isVideo ? 'VIDEO' : 'IMAGE'}
                    colors={colors}
                  />
                  <View
                    style={[
                      styles.section,
                      {
                        backgroundColor: sectionBg,
                        borderColor: dividerColor,
                      },
                    ]}
                  >
                    {merged.width > 0 && merged.height > 0 && (
                      <PropRow
                        label="Dimensions"
                        value={`${merged.width} × ${merged.height} px`}
                        colors={colors}
                        accent={accent}
                      />
                    )}
                    {merged.width > 0 && merged.height > 0 && (
                      <PropRow
                        label="Megapixels"
                        value={`${(
                          (merged.width * merged.height) /
                          1_000_000
                        ).toFixed(1)} MP`}
                        colors={colors}
                        accent={accent}
                      />
                    )}
                    {isVideo && (
                      <PropRow
                        label="Duration"
                        value={formatDuration(merged.duration)}
                        colors={colors}
                        accent={accent}
                      />
                    )}
                    {isVideo && merged.fileSize && merged.duration ? (
                      <PropRow
                        label="Bitrate"
                        value={`${(
                          (merged.fileSize * 8) /
                          merged.duration /
                          1000
                        ).toFixed(0)} kbps`}
                        colors={colors}
                        accent={accent}
                      />
                    ) : null}
                  </View>
                </>
              )}

              <SectionHeader title="DATES" colors={colors} />
              <View
                style={[
                  styles.section,
                  {
                    backgroundColor: sectionBg,
                    borderColor: dividerColor,
                  },
                ]}
              >
                <PropRow
                  label="Created"
                  value={formatDate(merged.createdAt)}
                  colors={colors}
                  accent={accent}
                />
                <PropRow
                  label="Modified"
                  value={formatDate(merged.modifiedAt)}
                  colors={colors}
                  accent={accent}
                />
                <PropRow
                  label="Indexed"
                  value={formatDate(merged.indexedAt)}
                  colors={colors}
                  accent={accent}
                />
                <PropRow
                  label="Last Synced"
                  value={formatDate(merged.scannedAt)}
                  colors={colors}
                  accent={accent}
                />
              </View>

              <SectionHeader title="USER DATA" colors={colors} />
              <View
                style={[
                  styles.section,
                  {
                    backgroundColor: sectionBg,
                    borderColor: dividerColor,
                  },
                ]}
              >
                <PropRow
                  label="Favorite"
                  value={merged.favorite ? '★  Yes' : '☆  No'}
                  colors={colors}
                  accent={accent}
                />
                <PropRow
                  label="Hidden"
                  value={merged.hidden ? '👁  Hidden' : '✓  Visible'}
                  colors={colors}
                  accent={accent}
                />
                <PropRow
                  label="Rating"
                  value={formatRating(merged.rating)}
                  colors={colors}
                  accent={accent}
                />
                {merged.notes ? (
                  <PropRow
                    label="Notes"
                    value={merged.notes}
                    colors={colors}
                    accent={accent}
                  />
                ) : null}
              </View>

              <SectionHeader title="IDENTIFIER" colors={colors} />
              <View
                style={[
                  styles.section,
                  {
                    backgroundColor: sectionBg,
                    borderColor: dividerColor,
                  },
                ]}
              >
                <PropRow
                  label="URI"
                  value={merged.uri}
                  colors={colors}
                  accent={accent}
                  mono
                />
              </View>
            </>
          )}

          {/* ── FOLDER PROPERTIES ───────────────────────────────────── */}
          {folder && (
            <>
              <SectionHeader title="GENERAL" colors={colors} />
              <View
                style={[
                  styles.section,
                  {
                    backgroundColor: sectionBg,
                    borderColor: dividerColor,
                  },
                ]}
              >
                <PropRow
                  label="Name"
                  value={folder.name ?? '—'}
                  colors={colors}
                  accent={accent}
                />
                <PropRow
                  label="Type"
                  value={dbAlbum?.album_type ?? 'Album'}
                  colors={colors}
                  accent={accent}
                />
                {dbAlbum?.path ? (
                  <PropRow
                    label="Path"
                    value={dbAlbum.path}
                    colors={colors}
                    accent={accent}
                    mono
                  />
                ) : null}
              </View>

              <SectionHeader title="CONTENTS" colors={colors} />
              <View
                style={[
                  styles.section,
                  {
                    backgroundColor: sectionBg,
                    borderColor: dividerColor,
                  },
                ]}
              >
                <PropRow
                  label="Total Items"
                  value={String(folder.count ?? dbAlbum?.media_count ?? 0)}
                  colors={colors}
                  accent={accent}
                />
                <PropRow
                  label="Photos"
                  value={String(folder.photoCount ?? dbAlbum?.photo_count ?? 0)}
                  colors={colors}
                  accent={accent}
                />
                <PropRow
                  label="Videos"
                  value={String(folder.videoCount ?? dbAlbum?.video_count ?? 0)}
                  colors={colors}
                  accent={accent}
                />
              </View>

              <SectionHeader title="DATES" colors={colors} />
              <View
                style={[
                  styles.section,
                  {
                    backgroundColor: sectionBg,
                    borderColor: dividerColor,
                  },
                ]}
              >
                <PropRow
                  label="Last Modified"
                  value={formatDate(
                    folder.lastModified ?? dbAlbum?.last_modified_at,
                  )}
                  colors={colors}
                  accent={accent}
                />
                <PropRow
                  label="Last Scanned"
                  value={formatDate(dbAlbum?.last_scanned_at)}
                  colors={colors}
                  accent={accent}
                />
                <PropRow
                  label="First Indexed"
                  value={formatDate(dbAlbum?.created_at)}
                  colors={colors}
                  accent={accent}
                />
              </View>

              <SectionHeader title="VISIBILITY" colors={colors} />
              <View
                style={[
                  styles.section,
                  {
                    backgroundColor: sectionBg,
                    borderColor: dividerColor,
                  },
                ]}
              >
                <PropRow
                  label="Hidden"
                  value={
                    folder.hidden || dbAlbum?.hidden === 1
                      ? '👁  Hidden'
                      : '✓  Visible'
                  }
                  colors={colors}
                  accent={accent}
                />
                <PropRow
                  label="Pinned"
                  value={dbAlbum?.pinned === 1 ? '📌  Pinned to top' : 'No'}
                  colors={colors}
                  accent={accent}
                />
              </View>
            </>
          )}

          {!merged && !folder && !loading && (
            <Text style={[styles.emptyText, { color: colors.textTertiary }]}>
              No properties available.
            </Text>
          )}
        </ScrollView>
      </Animated.View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    ...StyleSheet.absoluteFillObject,
  },
  sheet: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    borderTopLeftRadius: 24,
    borderTopRightRadius: 24,
    maxHeight: '85%',
    overflow: 'hidden',
  },
  handle: {
    alignSelf: 'center',
    width: 36,
    height: 4,
    borderRadius: 2,
    marginTop: 10,
    marginBottom: 6,
  },
  headerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 10,
    gap: 10,
  },
  headerTitle: {
    flex: 1,
    fontSize: 16,
    fontWeight: '600',
    letterSpacing: 0.2,
  },
  closeBtn: {
    width: 28,
    height: 28,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  divider: {
    height: StyleSheet.hairlineWidth,
    marginHorizontal: 0,
  },
  scroll: {
    flex: 1,
  },
  scrollContent: {
    paddingHorizontal: 16,
    paddingTop: 12,
    paddingBottom: 8,
  },
  sectionHeader: {
    fontSize: 11,
    fontWeight: '600',
    letterSpacing: 0.8,
    marginTop: 16,
    marginBottom: 6,
    marginLeft: 4,
  },
  section: {
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
    overflow: 'hidden',
  },
  propRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    paddingHorizontal: 14,
    paddingVertical: 9,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: 'rgba(128,128,128,0.12)',
    gap: 12,
  },
  propLabel: {
    width: 100,
    fontSize: 13,
    fontWeight: '500',
    flexShrink: 0,
    paddingTop: 1,
  },
  propValue: {
    flex: 1,
    fontSize: 13,
    fontWeight: '400',
  },
  propMono: {
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    fontSize: 11,
  },
  loadingText: {
    textAlign: 'center',
    marginTop: 32,
    fontSize: 14,
  },
  emptyText: {
    textAlign: 'center',
    marginTop: 48,
    fontSize: 14,
  },
});
