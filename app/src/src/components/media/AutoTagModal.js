/**
 * AutoTagModal
 *
 * Full-screen (centered dialog) modal that drives the Auto Tag workflow:
 *
 *   Scanning phase:
 *     • Shows a progress bar + current filename being processed.
 *     • Displays running counts of tags applied.
 *
 *   Face-confirm phase (fires when a face with a known name is found):
 *     • Shows the photo that contains the face.
 *     • Shows a highlight rect over the detected face.
 *     • Shows the suggested person name.
 *     • User can confirm, correct the name, or skip.
 *
 *   Done phase:
 *     • Summary stats — tagged / labels applied / faces named.
 *     • Close button.
 *
 * Props:
 *   visible   — boolean
 *   folder    — folder object (has .name and .id / .albumId)
 *   items     — MediaItem[]  (already loaded list for this folder)
 *   onClose   — () => void
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Animated,
  Easing,
  Keyboard,
  KeyboardAvoidingView,
  Modal as RNModal,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  TouchableWithoutFeedback,
  View,
} from 'react-native';
import { Image } from 'expo-image';
import { useTheme } from '../../providers/ThemeProvider';
import { useAppContext } from '../../store/AppContext';
import { AutoTagService } from '../../services/ml/AutoTagService';
import { TagService } from '../../services/database/TagService';

// ─── Phases ───────────────────────────────────────────────────────────────────
const PHASE_IDLE = 'idle';
const PHASE_SCANNING = 'scanning';
const PHASE_FACE_CONFIRM = 'face_confirm';
const PHASE_DONE = 'done';

// ─── Component ────────────────────────────────────────────────────────────────

export function AutoTagModal({ visible, folder, items = [], onClose }) {
  const { colors } = useTheme();
  const { state: appState } = useAppContext();
  const accent = appState.accentColor ?? colors.accent;

  // ── State ─────────────────────────────────────────────────────────────────
  const [phase, setPhase] = useState(PHASE_IDLE);
  const [progress, setProgress] = useState({
    current: 0,
    total: 0,
    filename: '',
  });
  const [stats, setStats] = useState({
    tagged: 0,
    labelsApplied: 0,
    facesNamed: 0,
    skipped: 0,
  });
  const [faceInfo, setFaceInfo] = useState(null); // { uri, suggestedName, faceBounds, ... }
  const [nameInput, setNameInput] = useState('');
  const [peopleOptions, setPeopleOptions] = useState([]);

  // Session ref so cancel() survives re-renders
  const sessionRef = useRef(null);

  // ── Animate in/out ────────────────────────────────────────────────────────
  const opacity = useRef(new Animated.Value(0)).current;
  const scale = useRef(new Animated.Value(0.93)).current;
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    if (visible) {
      opacity.setValue(0);
      scale.setValue(0.93);
      setMounted(true);
      Animated.parallel([
        Animated.timing(opacity, {
          toValue: 1,
          duration: 180,
          easing: Easing.out(Easing.cubic),
          useNativeDriver: true,
        }),
        Animated.spring(scale, {
          toValue: 1,
          tension: 200,
          friction: 22,
          useNativeDriver: true,
        }),
      ]).start();
    } else {
      Animated.parallel([
        Animated.timing(opacity, {
          toValue: 0,
          duration: 140,
          useNativeDriver: true,
        }),
        Animated.spring(scale, {
          toValue: 0.93,
          tension: 200,
          friction: 22,
          useNativeDriver: true,
        }),
      ]).start(() => {
        setMounted(false);
        setPhase(PHASE_IDLE);
        setProgress({ current: 0, total: 0, filename: '' });
        setFaceInfo(null);
        setNameInput('');
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible]);

  // ── Start scanning when modal opens ───────────────────────────────────────
  useEffect(() => {
    if (!visible || !items?.length) return;

    const session = AutoTagService.createSession({
      items,
      onProgress: (current, total, filename) => {
        setPhase(PHASE_SCANNING);
        setProgress({ current, total, filename });
      },
      onFaceConfirm: info => {
        setFaceInfo(info);
        setNameInput(info.suggestedName ?? '');
        setPhase(PHASE_FACE_CONFIRM);
        // Cluster candidates (from embedding similarity) go first in the chip
        // list so the best matches are immediately visible; then all other
        // known people tags follow as fallback options.
        const candidates = info.clusterCandidates ?? [];
        TagService.getTagsByCategory('people')
          .then(tags => {
            const candidateSet = new Set(candidates.map(n => n.toLowerCase()));
            const rest = tags.map(t => t.name).filter(n => !candidateSet.has(n.toLowerCase()));
            setPeopleOptions([...candidates, ...rest]);
          })
          .catch(() => setPeopleOptions(candidates));
      },
      onDone: finalStats => {
        setStats(finalStats);
        setPhase(PHASE_DONE);
        sessionRef.current = null;
      },
    });

    sessionRef.current = session;
    session.start();

    return () => {
      session.cancel();
      sessionRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible]);

  // ── Handlers ──────────────────────────────────────────────────────────────

  const handleConfirmFace = useCallback(() => {
    const name = nameInput.trim();
    if (!name) return;
    Keyboard.dismiss();
    sessionRef.current?.confirmFace(name);
    setFaceInfo(null);
    setPeopleOptions([]);
    setPhase(PHASE_SCANNING);
  }, [nameInput]);

  const handleSkipFace = useCallback(() => {
    Keyboard.dismiss();
    sessionRef.current?.skipFace();
    setFaceInfo(null);
    setPeopleOptions([]);
    setPhase(PHASE_SCANNING);
  }, []);

  const handleClose = useCallback(() => {
    sessionRef.current?.cancel();
    sessionRef.current = null;
    onClose();
  }, [onClose]);

  // ── Filtered people chips (live filter as user types) ────────────────────
  const filteredOptions =
    nameInput.trim().length === 0
      ? peopleOptions
      : peopleOptions.filter(n =>
          n.toLowerCase().includes(nameInput.trim().toLowerCase()),
        );

  // ── Guard ─────────────────────────────────────────────────────────────────
  if (!mounted) return null;

  // ── Derived for progress bar ───────────────────────────────────────────────
  const pct = progress.total > 0 ? progress.current / progress.total : 0;

  // ── Face bounding box (normalised → relative %) ───────────────────────────
  const faceStyle = faceInfo
    ? {
        left: `${faceInfo.faceBounds.leftNorm * 100}%`,
        top: `${faceInfo.faceBounds.topNorm * 100}%`,
        width: `${
          (faceInfo.faceBounds.rightNorm - faceInfo.faceBounds.leftNorm) * 100
        }%`,
        height: `${
          (faceInfo.faceBounds.bottomNorm - faceInfo.faceBounds.topNorm) * 100
        }%`,
      }
    : null;

  const confirmBtnOpacity = nameInput.trim() ? 1 : 0.4;

  return (
    <RNModal
      visible
      transparent
      animationType="none"
      statusBarTranslucent
      onRequestClose={handleClose}
    >
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
        style={s.flex}
      >
        <TouchableWithoutFeedback
          onPress={phase === PHASE_DONE ? handleClose : undefined}
        >
          <View
            style={[s.backdrop, { backgroundColor: colors.overlayStrong }]}
          />
        </TouchableWithoutFeedback>

        <Animated.View
          pointerEvents="box-none"
          style={[s.centeredWrap, { opacity, transform: [{ scale }] }]}
        >
          <View style={[s.card, { backgroundColor: colors.card }]}>
            {/* ── Header ───────────────────────────────────────── */}
            <View style={s.header}>
              <Text style={[s.title, { color: colors.text }]} numberOfLines={1}>
                Auto Tag{folder?.name ? ` — ${folder.name}` : ''}
              </Text>
              {(phase === PHASE_DONE || phase === PHASE_IDLE) && (
                <TouchableOpacity
                  onPress={handleClose}
                  hitSlop={HIT}
                  style={s.closeBtn}
                >
                  <Text
                    style={[s.closeBtnText, { color: colors.textSecondary }]}
                  >
                    ✕
                  </Text>
                </TouchableOpacity>
              )}
            </View>

            {/* ── Scanning phase ────────────────────────────────── */}
            {(phase === PHASE_SCANNING || phase === PHASE_IDLE) && (
              <View style={s.body}>
                <ActivityIndicator
                  color={accent}
                  size="small"
                  style={s.spinner}
                />
                <Text style={[s.label, { color: colors.textSecondary }]}>
                  {progress.total > 0
                    ? `${progress.current} / ${progress.total}`
                    : 'Starting…'}
                </Text>
                {/* Progress bar */}
                <View style={[s.track, { backgroundColor: colors.border }]}>
                  <View
                    style={[
                      s.fill,
                      { width: `${pct * 100}%`, backgroundColor: accent },
                    ]}
                  />
                </View>
                {progress.filename ? (
                  <Text
                    style={[s.filename, { color: colors.textTertiary }]}
                    numberOfLines={1}
                  >
                    {progress.filename}
                  </Text>
                ) : null}
              </View>
            )}

            {/* ── Face confirmation phase ───────────────────────── */}
            {phase === PHASE_FACE_CONFIRM && faceInfo && (
              <View style={s.body}>
                <Text style={[s.facePrompt, { color: colors.text }]}>
                  We found a familiar face. Is this…
                </Text>

                {/* Photo with face highlight */}
                <View style={s.imageWrap}>
                  <Image
                    source={{ uri: faceInfo.uri }}
                    style={s.faceImage}
                    contentFit="contain"
                    cachePolicy="memory-disk"
                  />
                  {faceStyle && (
                    <View
                      style={[s.faceRect, faceStyle, { borderColor: accent }]}
                    />
                  )}
                </View>

                {/* Name input */}
                <Text style={[s.inputLabel, { color: colors.textSecondary }]}>
                  Suggested name
                </Text>
                <TextInput
                  style={[
                    s.input,
                    {
                      backgroundColor: colors.background,
                      color: colors.text,
                      borderColor: accent,
                    },
                  ]}
                  value={nameInput}
                  onChangeText={setNameInput}
                  placeholder="Enter person's name"
                  placeholderTextColor={colors.textTertiary}
                  autoCapitalize="words"
                  autoCorrect={false}
                  returnKeyType="done"
                  onSubmitEditing={handleConfirmFace}
                />

                {/* Existing people chips */}
                {filteredOptions.length > 0 && (
                  <>
                    <Text
                      style={[s.inputLabel, { color: colors.textSecondary }]}
                    >
                      Or select existing person
                    </Text>
                    <ScrollView
                      horizontal
                      showsHorizontalScrollIndicator={false}
                      style={s.chipsScroll}
                      contentContainerStyle={s.chipsContent}
                      keyboardShouldPersistTaps="handled"
                    >
                      {filteredOptions.map(pName => {
                        const selected = nameInput.trim() === pName;
                        const chipBg = selected ? accent : colors.background;
                        const chipBorder = selected ? accent : colors.border;
                        const chipTextColor = selected ? '#fff' : colors.text;
                        return (
                          <TouchableOpacity
                            key={pName}
                            style={[
                              s.chip,
                              {
                                backgroundColor: chipBg,
                                borderColor: chipBorder,
                              },
                            ]}
                            onPress={() => setNameInput(pName)}
                            activeOpacity={0.7}
                          >
                            <Text
                              style={[s.chipText, { color: chipTextColor }]}
                            >
                              {pName}
                            </Text>
                          </TouchableOpacity>
                        );
                      })}
                    </ScrollView>
                  </>
                )}

                {/* Action row */}
                <View style={s.actionRow}>
                  <TouchableOpacity
                    style={[
                      s.btn,
                      s.btnOutline,
                      { borderColor: colors.border },
                    ]}
                    onPress={handleSkipFace}
                    activeOpacity={0.7}
                  >
                    <Text style={[s.btnText, { color: colors.textSecondary }]}>
                      Skip
                    </Text>
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={[
                      s.btn,
                      s.btnFill,
                      {
                        backgroundColor: accent,
                        opacity: confirmBtnOpacity,
                      },
                    ]}
                    onPress={handleConfirmFace}
                    disabled={!nameInput.trim()}
                    activeOpacity={0.8}
                  >
                    <Text style={[s.btnText, s.btnTextFill]}>Continue</Text>
                  </TouchableOpacity>
                </View>
              </View>
            )}

            {/* ── Done phase ────────────────────────────────────── */}
            {phase === PHASE_DONE && (
              <View style={s.body}>
                <Text style={[s.doneIcon]}>✓</Text>
                <Text style={[s.doneTitle, { color: colors.text }]}>
                  Auto Tag Complete
                </Text>
                <View style={[s.statGrid, { borderColor: colors.border }]}>
                  <StatRow
                    label="Images processed"
                    value={stats.tagged + stats.skipped}
                    colors={colors}
                  />
                  <StatRow
                    label="Labels applied"
                    value={stats.labelsApplied}
                    colors={colors}
                    accent={accent}
                  />
                  <StatRow
                    label="Faces named"
                    value={stats.facesNamed}
                    colors={colors}
                    accent={accent}
                  />
                </View>
                <TouchableOpacity
                  style={[
                    s.btn,
                    s.btnFill,
                    s.btnWide,
                    { backgroundColor: accent },
                  ]}
                  onPress={handleClose}
                  activeOpacity={0.8}
                >
                  <Text style={[s.btnText, s.btnTextFill]}>Done</Text>
                </TouchableOpacity>
              </View>
            )}
          </View>
        </Animated.View>
      </KeyboardAvoidingView>
    </RNModal>
  );
}

// ─── Stat row ─────────────────────────────────────────────────────────────────
function StatRow({ label, value, colors, accent }) {
  return (
    <View style={s.statRow}>
      <Text style={[s.statLabel, { color: colors.textSecondary }]}>
        {label}
      </Text>
      <Text style={[s.statValue, { color: accent ?? colors.text }]}>
        {value}
      </Text>
    </View>
  );
}

// ─── Constants ────────────────────────────────────────────────────────────────
const HIT = { top: 8, right: 8, bottom: 8, left: 8 };

// ─── Styles ───────────────────────────────────────────────────────────────────
const s = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: {
    ...StyleSheet.absoluteFillObject,
  },
  centeredWrap: {
    ...StyleSheet.absoluteFillObject,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 24,
  },
  card: {
    width: '100%',
    maxWidth: 420,
    borderRadius: 20,
    overflow: 'hidden',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 8 },
    shadowOpacity: 0.36,
    shadowRadius: 24,
    elevation: 20,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingTop: 18,
    paddingBottom: 10,
    gap: 8,
  },
  title: {
    flex: 1,
    fontSize: 16,
    fontWeight: '700',
    letterSpacing: -0.2,
  },
  closeBtn: { padding: 4 },
  closeBtnText: { fontSize: 16, fontWeight: '600' },

  body: {
    paddingHorizontal: 20,
    paddingBottom: 20,
    alignItems: 'center',
  },
  spinner: { marginBottom: 10 },
  label: { fontSize: 13, fontWeight: '500', marginBottom: 8 },
  track: {
    width: '100%',
    height: 4,
    borderRadius: 2,
    overflow: 'hidden',
    marginBottom: 8,
  },
  fill: { height: '100%', borderRadius: 2 },
  filename: {
    fontSize: 12,
    width: '100%',
    textAlign: 'center',
  },

  // Face confirm
  facePrompt: {
    fontSize: 14,
    fontWeight: '600',
    marginBottom: 12,
    textAlign: 'center',
  },
  imageWrap: {
    width: '100%',
    aspectRatio: 1,
    marginBottom: 14,
    borderRadius: 10,
    overflow: 'hidden',
    position: 'relative',
  },
  faceImage: {
    ...StyleSheet.absoluteFillObject,
  },
  faceRect: {
    position: 'absolute',
    borderWidth: 2,
    borderRadius: 4,
  },
  inputLabel: {
    alignSelf: 'flex-start',
    fontSize: 12,
    fontWeight: '500',
    marginBottom: 6,
    marginTop: 2,
  },
  input: {
    width: '100%',
    height: 44,
    borderRadius: 10,
    borderWidth: 1.5,
    paddingHorizontal: 14,
    fontSize: 15,
    marginBottom: 10,
  },
  chipsScroll: {
    width: '100%',
    marginBottom: 14,
  },
  chipsContent: {
    flexDirection: 'row',
    gap: 8,
    paddingVertical: 2,
  },
  chip: {
    flexShrink: 0,
    height: 32,
    paddingHorizontal: 14,
    borderRadius: 16,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  chipText: {
    fontSize: 13,
    fontWeight: '500',
  },
  actionRow: {
    flexDirection: 'row',
    width: '100%',
    gap: 10,
  },
  btn: {
    flex: 1,
    height: 44,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  btnOutline: { borderWidth: 1 },
  btnFill: {},
  btnWide: { flex: 0, width: '100%', marginTop: 4 },
  btnText: { fontSize: 14, fontWeight: '600' },
  btnTextFill: { color: '#fff' },

  // Done
  doneIcon: { fontSize: 40, marginBottom: 8, marginTop: 4 },
  doneTitle: { fontSize: 17, fontWeight: '700', marginBottom: 16 },
  statGrid: {
    width: '100%',
    borderWidth: 1,
    borderRadius: 12,
    overflow: 'hidden',
    marginBottom: 18,
  },
  statRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingHorizontal: 14,
    paddingVertical: 10,
  },
  statLabel: { fontSize: 13 },
  statValue: { fontSize: 13, fontWeight: '700' },
});
