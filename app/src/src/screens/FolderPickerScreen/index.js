/**
 * FolderPickerScreen
 *
 * A folder-selection overlay used for "Copy To" and "Move To" operations.
 *
 * Route params:
 *   mode         {string}   — 'copy' | 'move'
 *   items        {object[]} — normalised media items to copy/move
 *   sourceFolder {object}   — the folder the operation was triggered from
 *                             (used to navigate back and to dim in the list)
 *
 * On completion:
 *   Navigates to 'FolderDetail' with `completedOperation` param so FolderScreen
 *   can remove moved items from its local list and clear the selection.
 */

import React, {
  useCallback,
  useEffect,
  useMemo,
  useState,
  useRef,
} from 'react';
import {
  BackHandler,
  Modal,
  Text,
  TouchableOpacity,
  View,
  StyleSheet,
} from 'react-native';
import { DotsSpinner } from '../../components/common/LoadingSpinner';
import { CommonActions } from '@react-navigation/native';
import { AppHeader } from '../../components/common/AppHeader';
import { EmptyState } from '../../components/common/EmptyState';
import { LoadingSpinner } from '../../components/common/LoadingSpinner';
import { FolderCard } from '../../components/grid/FolderCard';
import { MediaGrid } from '../../components/grid/MediaGrid';
import { Icon } from '../../components/ui/Icon';
import { useMediaLibrary } from '../../hooks/useMediaLibrary';
import { useTheme } from '../../providers/ThemeProvider';
import { useToast } from '../../providers/ToastProvider';
import { createStyles } from './styles';

// ─── Static styles (not theme-dependent) ─────────────────────────────────────

const pickerStyles = StyleSheet.create({
  dimmedCard: {
    opacity: 0.38,
  },
  sourceLabel: {
    position: 'absolute',
    bottom: 12,
    left: 0,
    right: 0,
    alignItems: 'center',
  },
  sourceLabelText: {
    fontSize: 10,
    fontWeight: '600',
    color: '#fff',
    backgroundColor: 'rgba(0,0,0,0.55)',
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 6,
    overflow: 'hidden',
    textTransform: 'uppercase',
    letterSpacing: 0.6,
  },
});

// ─── Component ────────────────────────────────────────────────────────────────

export function FolderPickerScreen({ route, navigation }) {
  const {
    mode = 'copy',
    items = [],
    sourceFolder = null,
    returnRoute,
  } = route.params ?? {};
  const isCopy = mode === 'copy';

  const { colors } = useTheme();
  const styles = useMemo(() => createStyles(colors), [colors]);
  const toast = useToast();

  // Guard: params must be present (mode, items are required for operation)
  useEffect(() => {
    if (!route.params || !route.params.items) {
      toast.error('Navigation Error', 'Invalid picker parameters.');
      navigation.goBack();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const {
    folders,
    isLoading,
    loadIndex,
    copyMediaToFolder,
    moveMediaToFolder,
    checkDestConflicts,
  } = useMediaLibrary();

  // Operating flag — prevents double-taps and shows the loading overlay
  const [isOperating, setIsOperating] = useState(false);
  const operatingRef = useRef(false);

  // Conflict resolution state — a pending Promise is stored in conflictResolveRef;
  // setting conflictInfo shows the ConflictModal.
  const [conflictInfo, setConflictInfo] = useState(null);
  const conflictResolveRef = useRef(null);

  /** Show the conflict modal and return a Promise that resolves with the user's choice. */
  const askConflictResolution = useCallback(
    (conflicts, existingNames, destFolder) =>
      new Promise(resolve => {
        conflictResolveRef.current = resolve;
        setConflictInfo({ conflicts, existingNames, destFolder });
      }),
    [],
  );

  /** Called by each conflict-modal button. */
  const resolveConflict = useCallback(choice => {
    setConflictInfo(null);
    conflictResolveRef.current?.(choice);
  }, []);

  // ─── Block ALL back navigation while an operation is in progress ──────────────────
  // Uses operatingRef (synchronous) instead of isOperating state so that
  // setting operatingRef.current = false immediately before navigate() allows
  // the navigation pop to proceed without waiting for a React re-render cycle.
  useEffect(() => {
    // Block Android hardware back button
    const hardwareBackSub = BackHandler.addEventListener(
      'hardwareBackPress',
      () => !!operatingRef.current, // true = consume event (blocks back)
    );

    // Block swipe-back gesture and header back button in React Navigation
    const beforeRemoveSub = navigation.addListener('beforeRemove', e => {
      if (operatingRef.current) {
        e.preventDefault();
      }
    });

    return () => {
      hardwareBackSub.remove();
      beforeRemoveSub();
    };
  }, [navigation]);

  // Load the folder grid when the screen mounts (if not yet loaded)
  useEffect(() => {
    if (!folders.length) {
      loadIndex(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ─── Handle destination folder pick ────────────────────────────────────────

  const handlePickFolder = useCallback(
    async destFolder => {
      if (operatingRef.current) return; // guard double-taps
      operatingRef.current = true;
      setIsOperating(true);

      const count = items.length;
      const destName = destFolder.name;
      const destPath = destFolder.path ?? null;

      try {
        // ─── 1. Conflict detection ──────────────────────────────────────────
        const { conflicts, existingNames } = await checkDestConflicts(
          items,
          destName,
          destPath,
        );

        // ─── 2. Ask user how to resolve (if any conflicts) ────────────────
        let copyOptions = {};

        if (conflicts.length > 0) {
          // Hide loading briefly while the user interacts with the modal
          setIsOperating(false);
          const resolution = await askConflictResolution(
            conflicts,
            existingNames,
            destFolder,
          );
          setIsOperating(true);

          if (resolution === 'cancel') {
            operatingRef.current = false;
            setIsOperating(false);
            return;
          }

          if (resolution === 'replace') {
            copyOptions.replaceExistingUris = Object.fromEntries(
              conflicts
                .filter(c => c.existingUri)
                .map(c => [c.item.uri, c.existingUri]),
            );
          } else if (resolution === 'keep-both') {
            // Auto-generate a unique name for each conflicting file
            const usedNames = new Set(existingNames);
            const rm = {};
            for (const c of conflicts) {
              const newName = generateUniqueName(c.filename, usedNames);
              rm[c.item.uri] = newName;
              usedNames.add(newName.toLowerCase()); // avoid duplicate suffixes
            }
            copyOptions.renameMap = rm;
          } else if (resolution === 'skip') {
            copyOptions.skipUris = new Set(conflicts.map(c => c.item.uri));
          }
        }
        // ─── 3. Run copy or move ──────────────────────────────────────────
        if (isCopy) {
          // ── Copy ───────────────────────────────────────────────────────────
          const { results } = await copyMediaToFolder(items, destName, {
            ...copyOptions,
            destFolderPath: destPath,
          });
          const successCount = results.filter(r => r.success).length;
          const failCount =
            count - successCount - (copyOptions.skipUris?.size ?? 0);

          // Clear the operating flag BEFORE navigating so the beforeRemove
          // listener no longer blocks the screen pop.
          operatingRef.current = false;
          setIsOperating(false);

          // Reset the stack to [Main, FolderDetail(source)] so pressing back
          // from the destination folder always returns to the Home screen
          // regardless of how deep the navigation was before the picker opened.
          if (returnRoute === 'Favorites' || returnRoute === 'Main') {
            // Return to the Main tab stack
            navigation.dispatch(
              CommonActions.reset({ index: 0, routes: [{ name: 'Main' }] }),
            );
          } else {
            navigation.dispatch(
              CommonActions.reset({
                index: 1,
                routes: [
                  { name: 'Main' },
                  {
                    name: 'FolderDetail',
                    params: {
                      folder: sourceFolder,
                      completedOperation: {
                        mode: 'copy',
                        movedUris: [],
                        copiedCount: successCount,
                      },
                    },
                  },
                ],
              }),
            );
          }

          if (failCount > 0) {
            toast.error(
              `${failCount} file${failCount > 1 ? 's' : ''} failed`,
              'Some files could not be copied.',
            );
          } else {
            toast.success(
              `${successCount} file${successCount > 1 ? 's' : ''} copied`,
              `To "${destName}"`,
            );
          }
        } else {
          // ── Move ─────────────────────────────────────────────────────────
          const { results, movedUris } = await moveMediaToFolder(
            items,
            destName,
            { ...copyOptions, destFolderPath: destPath },
          );

          const successCopied = results.filter(r => r.success).length;
          const fullyMoved = movedUris.length;
          const failCount =
            count - successCopied - (copyOptions.skipUris?.size ?? 0);

          // Clear the operating flag BEFORE navigating so the beforeRemove
          // listener no longer blocks the screen pop.
          operatingRef.current = false;
          setIsOperating(false);

          // Reset the stack to [Main, FolderDetail(source)] so pressing back
          // from the destination folder always returns to the Home screen.
          if (returnRoute === 'Favorites' || returnRoute === 'Main') {
            // Return to the Main tab stack
            navigation.dispatch(
              CommonActions.reset({ index: 0, routes: [{ name: 'Main' }] }),
            );
          } else {
            navigation.dispatch(
              CommonActions.reset({
                index: 1,
                routes: [
                  { name: 'Main' },
                  {
                    name: 'FolderDetail',
                    params: {
                      folder: sourceFolder,
                      completedOperation: {
                        mode: 'move',
                        movedUris,
                        movedCount: fullyMoved,
                      },
                    },
                  },
                ],
              }),
            );
          }

          if (failCount > 0) {
            toast.error(
              `${failCount} file${failCount > 1 ? 's' : ''} failed`,
              'Some files could not be moved.',
            );
          } else if (successCopied > fullyMoved) {
            // Copies succeeded but some originals couldn't be deleted
            toast.info(
              `${successCopied} file${successCopied > 1 ? 's' : ''} copied`,
              'Originals could not be removed — check storage permissions.',
            );
          } else {
            toast.success(
              `${fullyMoved} file${fullyMoved > 1 ? 's' : ''} moved`,
              `To "${destName}"`,
            );
          }
        }
      } catch (err) {
        setIsOperating(false);
        operatingRef.current = false;
        toast.error(
          isCopy ? 'Copy failed' : 'Move failed',
          err?.message ?? 'An unexpected error occurred.',
        );
      }
    },
    [
      isCopy,
      items,
      sourceFolder,
      returnRoute,
      copyMediaToFolder,
      moveMediaToFolder,
      checkDestConflicts,
      askConflictResolution,
      navigation,
      toast,
    ],
  );

  // ─── Render folder item ─────────────────────────────────────────────────────

  const renderFolder = useCallback(
    ({ item }) => {
      const isSource = item.name === sourceFolder?.name;

      return (
        <View style={isSource ? pickerStyles.dimmedCard : undefined}>
          <FolderCard
            folder={item}
            isHidden={item.hidden}
            onPress={isSource ? undefined : handlePickFolder}
          />
          {isSource && (
            <View style={pickerStyles.sourceLabel} pointerEvents="none">
              <Text style={pickerStyles.sourceLabelText}>Current folder</Text>
            </View>
          )}
        </View>
      );
    },
    [sourceFolder, handlePickFolder],
  );

  // ─── Banner text ────────────────────────────────────────────────────────────

  const bannerText = isCopy
    ? `Select a folder to copy `
    : `Select a folder to move `;

  const bannerCountText =
    items.length === 1 ? '1 file' : `${items.length} files`;

  // ─── Render ─────────────────────────────────────────────────────────────────

  return (
    <View style={styles.container}>
      <AppHeader
        title={isCopy ? 'Copy To' : 'Move To'}
        showBack
        onBackPress={() => navigation.goBack()}
      />

      {/* ─── Mode banner ─────────────────────────────────────────────────── */}
      <View style={styles.banner}>
        <Icon
          name={isCopy ? 'copy' : 'moveRight'}
          size={18}
          color={colors.textSecondary}
        />
        <Text style={styles.bannerText}>
          {bannerText}
          <Text style={styles.bannerCount}>{bannerCountText}</Text>
          {' into…'}
        </Text>
      </View>

      {/* ─── Folder grid ─────────────────────────────────────────────────── */}
      {isLoading && !folders.length ? (
        <LoadingSpinner message="Loading folders…" />
      ) : (
        <MediaGrid
          data={folders}
          renderItem={renderFolder}
          numColumns={2}
          ListEmptyComponent={
            <EmptyState
              icon="☐"
              message="No folders found"
              subMessage="Pull down to refresh."
            />
          }
        />
      )}

      {/* ─── Operating overlay ───────────────────────────────────────────── */}
      {isOperating && (
        <View style={styles.loadingOverlay} pointerEvents="box-only">
          <View style={styles.loadingCard}>
            <DotsSpinner dotSize={13} gap={9} />
            <Text style={styles.loadingText}>
              {isCopy ? 'Copying files…' : 'Moving files…'}
            </Text>
          </View>
        </View>
      )}

      {/* ─── Conflict resolution modal ──────────────────────────────── */}
      {conflictInfo && (
        <ConflictModal
          conflictInfo={conflictInfo}
          itemCount={items.length}
          colors={colors}
          onResolve={resolveConflict}
        />
      )}
    </View>
  );
}

// ─── ConflictModal sub-component ─────────────────────────────────────────

function ConflictModal({ conflictInfo, itemCount, colors, onResolve }) {
  const { conflicts, destFolder } = conflictInfo;
  const conflictCount = conflicts.length;
  const nonConflictCount = itemCount - conflictCount;
  const hasNonConflicted = nonConflictCount > 0;

  const bodyText =
    conflictCount === 1
      ? `"${conflicts[0].filename}" already exists in "${destFolder.name}".`
      : `${conflictCount} files already exist in "${destFolder.name}".`;

  return (
    <Modal
      visible
      transparent
      animationType="fade"
      statusBarTranslucent
      onRequestClose={() => onResolve('cancel')}
    >
      <View style={conflictStyles.backdrop}>
        <View
          style={[
            conflictStyles.card,
            {
              backgroundColor: colors.card,
              shadowColor: colors.shadow ?? '#000',
            },
          ]}
        >
          {/* Icon */}
          <View
            style={[
              conflictStyles.iconWrap,
              { backgroundColor: colors.surface },
            ]}
          >
            <Text style={conflictStyles.iconEmoji}>⚠️</Text>
          </View>

          {/* Title */}
          <Text style={[conflictStyles.title, { color: colors.text }]}>
            File{conflictCount > 1 ? 's' : ''} Already Exist
          </Text>

          {/* Body */}
          <Text style={[conflictStyles.body, { color: colors.textSecondary }]}>
            {bodyText}
          </Text>

          {/* Divider */}
          <View
            style={[conflictStyles.divider, { backgroundColor: colors.border }]}
          />

          {/* Replace */}
          <TouchableOpacity
            style={conflictStyles.btnReplace}
            activeOpacity={0.78}
            onPress={() => onResolve('replace')}
          >
            <Text style={conflictStyles.btnTextWhite}>
              {conflictCount > 1 ? 'Replace All' : 'Replace'}
            </Text>
          </TouchableOpacity>

          {/* Keep Both */}
          <TouchableOpacity
            style={[
              conflictStyles.btn,
              { backgroundColor: colors.accent, borderColor: colors.accent },
            ]}
            activeOpacity={0.78}
            onPress={() => onResolve('keep-both')}
          >
            <Text style={conflictStyles.btnTextWhite}>Keep Both</Text>
          </TouchableOpacity>

          {/* Skip conflicted (only if there are non-conflicted items to proceed with) */}
          {hasNonConflicted && (
            <TouchableOpacity
              style={[
                conflictStyles.btn,
                { backgroundColor: colors.surface, borderColor: colors.border },
              ]}
              activeOpacity={0.78}
              onPress={() => onResolve('skip')}
            >
              <Text style={[conflictStyles.btnText, { color: colors.text }]}>
                Skip
                {conflictCount > 1
                  ? ` ${conflictCount} conflicted`
                  : ' This File'}
                {' — continue with '}
                {nonConflictCount}
              </Text>
            </TouchableOpacity>
          )}

          {/* Cancel */}
          <TouchableOpacity
            style={conflictStyles.cancelBtn}
            activeOpacity={0.7}
            onPress={() => onResolve('cancel')}
          >
            <Text
              style={[
                conflictStyles.cancelText,
                { color: colors.textSecondary },
              ]}
            >
              Cancel
            </Text>
          </TouchableOpacity>
        </View>
      </View>
    </Modal>
  );
}

/** Module-level — needs no hook context. */
function generateUniqueName(filename, existingNames) {
  const lastDot = filename.lastIndexOf('.');
  const base = lastDot > 0 ? filename.slice(0, lastDot) : filename;
  const ext = lastDot > 0 ? filename.slice(lastDot) : '';

  let candidate = `${base}_(copy)${ext}`;
  let counter = 2;
  while (existingNames.has(candidate.toLowerCase())) {
    candidate = `${base}_(copy ${counter})${ext}`;
    counter++;
  }
  return candidate;
}

const conflictStyles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.55)',
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 28,
  },
  card: {
    width: '100%',
    maxWidth: 360,
    borderRadius: 22,
    paddingTop: 28,
    paddingBottom: 18,
    paddingHorizontal: 22,
    alignItems: 'stretch',
    shadowOpacity: 0.18,
    shadowRadius: 24,
    shadowOffset: { width: 0, height: 8 },
    elevation: 12,
  },
  iconWrap: {
    alignSelf: 'center',
    width: 52,
    height: 52,
    borderRadius: 26,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 14,
  },
  iconEmoji: {
    fontSize: 26,
  },
  title: {
    fontSize: 20,
    fontWeight: '700',
    textAlign: 'center',
    letterSpacing: -0.3,
    marginBottom: 8,
  },
  body: {
    fontSize: 13,
    lineHeight: 19,
    textAlign: 'center',
    marginBottom: 4,
  },
  divider: {
    height: StyleSheet.hairlineWidth,
    marginVertical: 16,
  },
  btn: {
    paddingVertical: 13,
    borderRadius: 999,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 9,
    borderWidth: 1.5,
  },
  btnReplace: {
    paddingVertical: 13,
    borderRadius: 999,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 9,
    borderWidth: 1.5,
    backgroundColor: '#EF4444',
    borderColor: '#EF4444',
  },
  btnText: {
    fontSize: 14,
    fontWeight: '600',
    letterSpacing: 0.2,
  },
  btnTextWhite: {
    fontSize: 14,
    fontWeight: '600',
    letterSpacing: 0.2,
    color: '#fff',
  },
  cancelBtn: {
    paddingVertical: 10,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 2,
  },
  cancelText: {
    fontSize: 14,
    fontWeight: '500',
  },
});
