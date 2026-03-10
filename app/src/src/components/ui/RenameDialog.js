import React, { useEffect, useRef, useState } from 'react';
import {
  Animated,
  Easing,
  Keyboard,
  KeyboardAvoidingView,
  Modal,
  Platform,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  TouchableWithoutFeedback,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../../providers/ThemeProvider';
import { useAppContext } from '../../store/AppContext';

/**
 * RenameDialog
 *
 * A themed animated dialog with an autofocused TextInput for renaming a file
 * or folder.  Accent color is applied to the action button and text input
 * focus ring — accent overrides day/night theming on those elements.
 *
 * Props:
 *   visible      — boolean
 *   onClose      — () => void          — cancel
 *   onConfirm    — (name: string) => void  — user confirmed with a valid name
 *   initialName  — string              — pre-filled value
 *   title        — string              — dialog heading  (default: 'Rename')
 *   placeholder  — string              — input placeholder
 */
export function RenameDialog({
  visible,
  onClose,
  onConfirm,
  initialName = '',
  title = 'Rename',
  placeholder = 'Enter new name',
}) {
  const { colors, isDark } = useTheme();
  const { state: appState } = useAppContext();
  const insets = useSafeAreaInsets();
  const accent = appState.accentColor ?? colors.accent;

  const [value, setValue] = useState(initialName);

  // Reset input whenever the dialog opens with a new initialName
  useEffect(() => {
    if (visible) setValue(initialName);
  }, [visible, initialName]);

  // ── Enter / exit animation ────────────────────────────────────────────────
  const opacity = useRef(new Animated.Value(0)).current;
  const scale = useRef(new Animated.Value(0.9)).current;

  // `mountedVisible` keeps the Modal mounted during the close animation so the
  // scale+opacity exit plays fully before the Modal is torn down.
  const [mountedVisible, setMountedVisible] = useState(false);

  useEffect(() => {
    if (visible) {
      opacity.setValue(0);
      scale.setValue(0.9);
      setMountedVisible(true);
      Animated.parallel([
        Animated.timing(opacity, {
          toValue: 1,
          duration: 160,
          easing: Easing.out(Easing.cubic),
          useNativeDriver: true,
        }),
        Animated.spring(scale, {
          toValue: 1,
          tension: 200,
          friction: 24,
          useNativeDriver: true,
        }),
      ]).start();
    } else {
      Animated.parallel([
        Animated.timing(opacity, {
          toValue: 0,
          duration: 110,
          easing: Easing.in(Easing.quad),
          useNativeDriver: true,
        }),
        Animated.timing(scale, {
          toValue: 0.9,
          duration: 110,
          easing: Easing.in(Easing.quad),
          useNativeDriver: true,
        }),
      ]).start(({ finished }) => { if (finished) setMountedVisible(false); });
    }
  }, [visible, opacity, scale]);

  const handleConfirm = () => {
    const trimmed = value.trim();
    if (!trimmed) return;
    Keyboard.dismiss();
    onConfirm(trimmed);
  };

  const handleCancel = () => {
    Keyboard.dismiss();
    onClose();
  };

  const cardBg = colors.card;
  const cardBorder = colors.border;
  const inputBg = isDark ? '#0D0D0D' : colors.background;
  const isConfirmDisabled =
    value.trim().length === 0 || value.trim() === initialName;

  return (
    <Modal
      visible={mountedVisible}
      transparent
      animationType="none"
      statusBarTranslucent
      onRequestClose={handleCancel}
    >
      {/* ── Backdrop ──────────────────────────────────────────────────── */}
      <TouchableWithoutFeedback onPress={handleCancel}>
        <Animated.View
          style={[
            styles.backdrop,
            { backgroundColor: colors.overlayStrong, opacity },
          ]}
        />
      </TouchableWithoutFeedback>

      {/* ── Card (keyboard-aware) ──────────────────────────────────────── */}
      <KeyboardAvoidingView
        style={[
          styles.centeredWrapper,
          { paddingTop: insets.top, paddingBottom: insets.bottom },
        ]}
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
        pointerEvents="box-none"
      >
        <Animated.View
          style={[
            styles.card,
            {
              backgroundColor: cardBg,
              borderColor: cardBorder,
              opacity,
              transform: [{ scale }],
              shadowColor: colors.black,
              shadowOpacity: isDark ? 0.7 : 0.22,
              shadowRadius: 24,
              shadowOffset: { width: 0, height: 8 },
              elevation: 20,
            },
          ]}
        >
          {/* Title */}
          <Text style={[styles.title, { color: colors.text }]}>{title}</Text>

          {/* Input */}
          <TextInput
            style={[
              styles.input,
              {
                backgroundColor: inputBg,
                borderColor: accent,
                color: colors.text,
              },
            ]}
            value={value}
            onChangeText={setValue}
            placeholder={placeholder}
            placeholderTextColor={colors.textTertiary}
            autoFocus
            selectTextOnFocus
            returnKeyType="done"
            onSubmitEditing={handleConfirm}
            selectionColor={accent}
          />

          {/* Buttons */}
          <View style={styles.btnRow}>
            <TouchableOpacity
              onPress={handleCancel}
              style={[
                styles.btn,
                styles.btnCancel,
                { borderColor: cardBorder },
              ]}
              activeOpacity={0.7}
            >
              <Text style={[styles.btnText, { color: colors.textSecondary }]}>
                Cancel
              </Text>
            </TouchableOpacity>

            <TouchableOpacity
              onPress={handleConfirm}
              disabled={isConfirmDisabled}
              style={[
                styles.btn,
                styles.btnConfirm,
                {
                  backgroundColor: isConfirmDisabled ? accent + '55' : accent,
                },
              ]}
              activeOpacity={0.78}
            >
              <Text style={[styles.btnText, { color: '#FFFFFF' }]}>Rename</Text>
            </TouchableOpacity>
          </View>
        </Animated.View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    ...StyleSheet.absoluteFillObject,
  },
  centeredWrapper: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 28,
  },
  card: {
    width: '100%',
    borderRadius: 20,
    borderWidth: 1,
    paddingHorizontal: 22,
    paddingVertical: 24,
    gap: 16,
  },
  title: {
    fontSize: 17,
    fontWeight: '600',
    letterSpacing: 0.2,
  },
  input: {
    height: 48,
    borderRadius: 12,
    borderWidth: 1.5,
    paddingHorizontal: 14,
    fontSize: 15,
    fontWeight: '400',
  },
  btnRow: {
    flexDirection: 'row',
    gap: 10,
    marginTop: 4,
  },
  btn: {
    flex: 1,
    height: 44,
    borderRadius: 999,
    alignItems: 'center',
    justifyContent: 'center',
  },
  btnCancel: {
    borderWidth: 1.5,
  },
  btnConfirm: {},
  btnText: {
    fontSize: 15,
    fontWeight: '600',
  },
});
