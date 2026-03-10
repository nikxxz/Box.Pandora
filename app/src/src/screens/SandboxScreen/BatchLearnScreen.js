/**
 * BatchLearnScreen
 *
 * Batch-train person tags from a labelled folder tree:
 *   <root>/
 *     Alice/   <- subfolder name becomes the "People" tag
 *       img1.jpg
 *       img2.jpg
 *     Bob/
 *       img1.png
 *       …
 *
 * For every image in each person subfolder the screen:
 *   1. Runs scene embedding → updates tag prototype (online mean)
 *   2. Runs face detection → for each quality face:
 *        – embeds with MobileFaceNet
 *        – finds / creates a face cluster
 *        – binds that cluster to the person tag
 */

import React, {
  useState,
  useCallback,
  useEffect,
  useRef,
  useMemo,
} from 'react';
import {
  View,
  Text,
  ScrollView,
  FlatList,
  TouchableOpacity,
  ActivityIndicator,
  StyleSheet,
  Alert,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import RNFS from 'react-native-fs';

import { useTheme } from '../../providers/ThemeProvider';
import { Icon } from '../../components/ui/Icon';
import { createStyles } from './styles';
import { TagService } from '../../services/database/TagService';
import { TagPrototypeService } from '../../services/database/TagPrototypeService';
import { FaceClusterBackfillService } from '../../services/database/FaceClusterBackfillService';
import {
  embedImage,
  MODEL_VERSION,
} from '../../services/ml/EmbeddingBridgeModule';
import { FaceBridgeModule } from '../../services/ml/FaceBridgeModule';
import { DatabaseService } from '../../services/database/DatabaseService';
import { TaggingService } from '../../services/ml/TaggingService';
import { EmbeddingIndexer } from '../../services/ml/EmbeddingIndexer';
import { FaceIndexer } from '../../services/ml/FaceIndexer';

// ─── helpers ─────────────────────────────────────────────────────────────────

const IMAGE_EXTS = ['.jpg', '.jpeg', '.png', '.heic', '.webp', '.bmp', '.gif'];
const isImageFile = name =>
  IMAGE_EXTS.some(ext => name.toLowerCase().endsWith(ext));

/** Split a path into breadcrumb segments. */
function pathBreadcrumbs(dirPath) {
  const parts = dirPath.replace(/\/$/, '').split('/').filter(Boolean);
  const crumbs = [];
  let built = '';
  for (const p of parts) {
    built = `${built}/${p}`;
    crumbs.push({ label: p, path: built });
  }
  return crumbs;
}

// ─── STATUS constants ─────────────────────────────────────────────────────────
const STATUS = {
  PENDING: 'pending',
  RUNNING: 'running',
  DONE: 'done',
  ERROR: 'error',
  SKIPPED: 'skipped',
};

// ─── Post-learning phase keys & labels ───────────────────────────────────────
const PHASE_KEYS = ['merge', 'rebind', 'autotag', 'embed', 'faceindex'];
const PHASE_LABEL = {
  merge: 'Merge anonymous clusters',
  rebind: 'Re-bind gallery faces',
  autotag: 'Auto-tag gallery photos',
  embed: 'Scene embedding index',
  faceindex: 'Face detection index',
};

// ─── BatchLearnScreen ─────────────────────────────────────────────────────────

export function BatchLearnScreen({ navigation }) {
  const insets = useSafeAreaInsets();
  const { colors, isDark } = useTheme();
  const styles = useMemo(
    () => createStyles({ ...colors, isDark }),
    [colors, isDark],
  );

  // ── Browse state ───────────────────────────────────────────────────────────
  const [isBrowsing, setIsBrowsing] = useState(false);
  const [browsePath, setBrowsePath] = useState(
    RNFS.ExternalStorageDirectoryPath,
  );
  const [dirEntries, setDirEntries] = useState([]); // sub-dirs in browsePath
  const [browseError, setBrowseError] = useState(null);
  const [loadingDir, setLoadingDir] = useState(false);

  // ── Selection state ────────────────────────────────────────────────────────
  const [rootPath, setRootPath] = useState(null); // chosen training folder
  const [persons, setPersons] = useState([]); // [{ name, path, fileCount }]

  // ── Learning state ─────────────────────────────────────────────────────────
  const [isRunning, setIsRunning] = useState(false);
  const [progress, setProgress] = useState([]); // [{ name, current, total, status, facesFound }]
  const [summary, setSummary] = useState(null); // { personsOk, imagesOk, facesFound }
  const [phases, setPhases] = useState({}); // phaseKey → { status, detail }
  const cancelRef = useRef(false);

  const phasesRunning = useMemo(
    () => Object.values(phases).some(p => p.status === STATUS.RUNNING),
    [phases],
  );

  const setPhase = useCallback((key, status, detail) => {
    setPhases(prev => ({ ...prev, [key]: { status, detail } }));
  }, []);

  // ── Check face model availability on mount ─────────────────────────────────
  const [faceAvailable, setFaceAvailable] = useState(false);
  useEffect(() => {
    (async () => {
      try {
        const ok = await FaceBridgeModule.isNativeAvailable();
        setFaceAvailable(!!ok);
      } catch {
        setFaceAvailable(false);
      }
    })();
  }, []);

  // ─── Directory browser ────────────────────────────────────────────────────

  const loadDir = useCallback(async dirPath => {
    setLoadingDir(true);
    setBrowseError(null);
    try {
      const entries = await RNFS.readDir(dirPath);
      const dirs = entries
        .filter(e => e.isDirectory() && !e.name.startsWith('.'))
        .sort((a, b) => a.name.localeCompare(b.name));
      setDirEntries(dirs);
      setBrowsePath(dirPath);
    } catch (err) {
      setBrowseError(err?.message ?? 'Cannot read directory.');
      setDirEntries([]);
    } finally {
      setLoadingDir(false);
    }
  }, []);

  useEffect(() => {
    if (isBrowsing) {
      loadDir(browsePath);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isBrowsing]);

  const handleOpenBrowser = useCallback(() => {
    setBrowsePath(RNFS.ExternalStorageDirectoryPath);
    setDirEntries([]);
    setBrowseError(null);
    setIsBrowsing(true);
  }, []);

  const handleBrowseEnter = useCallback(
    dirPath => {
      loadDir(dirPath);
    },
    [loadDir],
  );

  const handleBrowseUp = useCallback(() => {
    const parent = browsePath.substring(0, browsePath.lastIndexOf('/'));
    if (parent.length < 2) return; // don't go above "/"
    loadDir(parent);
  }, [browsePath, loadDir]);

  const handleSelectFolder = useCallback(async () => {
    // Scan selected folder for sub-dirs (persons)
    try {
      const entries = await RNFS.readDir(browsePath);
      const subDirs = entries.filter(
        e => e.isDirectory() && !e.name.startsWith('.'),
      );
      if (subDirs.length === 0) {
        Alert.alert(
          'No sub-folders found',
          'The selected folder has no sub-folders. Each sub-folder should be named after a person and contain their photos.',
          [{ text: 'OK' }],
        );
        return;
      }
      // Count media files per sub-dir
      const personList = await Promise.all(
        subDirs.map(async sub => {
          try {
            const files = await RNFS.readDir(sub.path);
            const count = files.filter(
              f => f.isFile() && isImageFile(f.name),
            ).length;
            return { name: sub.name, path: sub.path, fileCount: count };
          } catch {
            return { name: sub.name, path: sub.path, fileCount: 0 };
          }
        }),
      );

      // Check which persons already have trained data in the DB
      //   cluster_count — face_clusters rows bound to this person tag
      //   sample_count  — running-mean sample count from tag_prototypes
      let enrichedList = personList;
      try {
        const names = personList.map(p => p.name);
        const placeholders = names.map(() => '?').join(', ');
        const db = DatabaseService.getDb();
        const { rows: trainedRows } = await db.execute(
          `SELECT t.name,
                  COUNT(DISTINCT fc.cluster_id) AS cluster_count,
                  COALESCE(MAX(tp.n), 0) AS sample_count
           FROM tags t
           LEFT JOIN face_clusters fc ON fc.tag_id = t.id
           LEFT JOIN tag_prototypes tp ON tp.tag_key = t.name
           WHERE t.category = 'people'
             AND t.name IN (${placeholders})
           GROUP BY t.id, t.name`,
          names,
        );
        const trainedMap = {};
        for (const row of trainedRows) {
          trainedMap[row.name] = {
            clusterCount: Number(row.cluster_count ?? 0),
            sampleCount: Number(row.sample_count ?? 0),
          };
        }
        enrichedList = personList.map(p => ({
          ...p,
          ...(trainedMap[p.name] ?? { clusterCount: 0, sampleCount: 0 }),
        }));
      } catch {
        // Non-fatal — training state badges will just not appear
      }

      setRootPath(browsePath);
      setPersons(enrichedList);
      setProgress([]);
      setSummary(null);
      setIsBrowsing(false);
    } catch (err) {
      Alert.alert('Error', err?.message ?? 'Could not scan folder.');
    }
  }, [browsePath]);

  const handleCancelBrowse = useCallback(() => {
    setIsBrowsing(false);
  }, []);

  // ─── Post-learning pipeline ───────────────────────────────────────────────
  /**
   * Sequential 5-phase post-processing pipeline. Called after every batch
   * learning run (unless cancelled). Provides live status for each stage.
   *
   *   merge     — fold anonymous clusters → nearest tag-bound cluster
   *   rebind    — re-assign residual gallery faces to bound clusters
   *   autotag   — write media_tags rows for all gallery photos whose faces
   *               are now bound to a people tag we just trained
   *   embed     — run scene EmbeddingIndexer on any un-indexed gallery images
   *   faceindex — run FaceIndexer on any un-indexed gallery images
   */
  const _runPostLearning = useCallback(
    async (personsProcessed, totalFacesFound) => {
      if (cancelRef.current) return;

      if (totalFacesFound > 0) {
        // ── Phase 1: merge anonymous clusters ────────────────────────────────
        setPhase('merge', STATUS.RUNNING, 'Merging anonymous clusters…');
        try {
          const { merged } =
            await FaceClusterBackfillService.mergeAnonymousClusters();
          setPhase(
            'merge',
            STATUS.DONE,
            `${merged} cluster${merged !== 1 ? 's' : ''} merged`,
          );
          setSummary(prev =>
            prev ? { ...prev, mergedClusters: merged } : prev,
          );
        } catch (err) {
          setPhase('merge', STATUS.ERROR, err?.message ?? 'Merge failed');
        }

        if (cancelRef.current) return;

        // ── Phase 2: re-bind unassigned faces ────────────────────────────────
        setPhase('rebind', STATUS.RUNNING, 'Re-binding gallery faces…');
        try {
          const { reassigned } =
            await FaceClusterBackfillService.rebindUnassignedFaces();
          setPhase(
            'rebind',
            STATUS.DONE,
            `${reassigned} face${reassigned !== 1 ? 's' : ''} re-assigned`,
          );
        } catch (err) {
          setPhase('rebind', STATUS.ERROR, err?.message ?? 'Rebind failed');
        }

        if (cancelRef.current) return;

        // ── Phase 3: auto-tag gallery photos ─────────────────────────────────
        setPhase('autotag', STATUS.RUNNING, 'Querying matched gallery photos…');
        try {
          const db = DatabaseService.getDb();
          let totalTagged = 0;

          for (const { tagId, tagName } of personsProcessed) {
            if (cancelRef.current) break;

            // Gallery assets whose face clusters are bound to this tag
            const { rows: faceRows } = await db.execute(
              `SELECT DISTINCT df.asset_id
               FROM detected_faces df
               JOIN face_clusters fc ON fc.cluster_id = df.cluster_id
               WHERE fc.tag_id = ? AND df.face_index != -1`,
              [tagId],
            );
            if (!faceRows.length) continue;

            // Exclude assets already carrying this tag
            const { rows: alreadyRows } = await db.execute(
              'SELECT media_uri FROM media_tags WHERE tag_id = ?',
              [tagId],
            );
            const alreadySet = new Set(alreadyRows.map(r => r.media_uri));
            const toTag = faceRows
              .map(r => r.asset_id)
              .filter(uri => !alreadySet.has(uri));

            if (toTag.length > 0) {
              await TaggingService.batchAddTag(toTag, tagId, tagName);
              totalTagged += toTag.length;
              setPhase(
                'autotag',
                STATUS.RUNNING,
                `Tagging… ${totalTagged} photo${totalTagged !== 1 ? 's' : ''}`,
              );
            }
          }

          setPhase(
            'autotag',
            STATUS.DONE,
            `${totalTagged} photo${totalTagged !== 1 ? 's' : ''} tagged`,
          );
        } catch (err) {
          setPhase('autotag', STATUS.ERROR, err?.message ?? 'Auto-tag failed');
        }

        if (cancelRef.current) return;
      }

      // ── Phase 4: scene embedding index ──────────────────────────────────────
      setPhase('embed', STATUS.RUNNING, 'Running scene embedding index…');
      try {
        let embedCount = 0;
        await EmbeddingIndexer.runToCompletion({
          onBatchComplete: ({ indexed }) => {
            embedCount += indexed;
            setPhase(
              'embed',
              STATUS.RUNNING,
              `Scene indexing… ${embedCount} indexed`,
            );
          },
        });
        setPhase(
          'embed',
          STATUS.DONE,
          `${embedCount} image${embedCount !== 1 ? 's' : ''} embedded`,
        );
      } catch (err) {
        setPhase('embed', STATUS.ERROR, err?.message ?? 'Embed index failed');
      }

      if (cancelRef.current) return;

      // ── Phase 5: face detection / embedding index ────────────────────────────
      setPhase('faceindex', STATUS.RUNNING, 'Running face detection index…');
      try {
        let faceDetected = 0;
        await FaceIndexer.runToCompletion({
          onBatchComplete: ({ detected }) => {
            faceDetected += detected;
            setPhase(
              'faceindex',
              STATUS.RUNNING,
              `Face indexing… ${faceDetected} detected`,
            );
          },
        });
        setPhase(
          'faceindex',
          STATUS.DONE,
          `${faceDetected} face${faceDetected !== 1 ? 's' : ''} detected`,
        );
      } catch (err) {
        setPhase(
          'faceindex',
          STATUS.ERROR,
          err?.message ?? 'Face index failed',
        );
      }
    },
    [setPhase],
  );

  // ─── Batch learning ───────────────────────────────────────────────────────

  const handleStart = useCallback(async () => {
    if (isRunning || persons.length === 0) return;
    setIsRunning(true);
    setSummary(null);
    setPhases({});
    cancelRef.current = false;

    const initialProgress = persons.map(p => ({
      name: p.name,
      current: 0,
      total: p.fileCount,
      status: STATUS.PENDING,
      facesFound: 0,
    }));
    setProgress(initialProgress);

    let totalImagesOk = 0;
    let totalFacesFound = 0;
    let personsOk = 0;
    const personsProcessed = []; // collected for auto-tagging phase

    for (let i = 0; i < persons.length; i++) {
      if (cancelRef.current) break;

      const person = persons[i];

      // Mark as running
      setProgress(prev => {
        const next = [...prev];
        next[i] = { ...next[i], status: STATUS.RUNNING, current: 0 };
        return next;
      });

      try {
        // 1. Find or create a "people" tag
        let tag = await TagService.getTagByName(person.name);
        let tagId;
        if (tag) {
          tagId = tag.id;
        } else {
          tagId = await TagService.createTag(
            person.name,
            '#4A90E2',
            null,
            null,
            'people',
          );
        }

        // 2. Scan media files in the person folder
        const entries = await RNFS.readDir(person.path);
        const mediaFiles = entries.filter(
          e => e.isFile() && isImageFile(e.name),
        );
        let imagesProcessed = 0;
        let facesFound = 0;

        for (const fileEntry of mediaFiles) {
          if (cancelRef.current) break;
          const assetId = `file://${fileEntry.path}`;

          // 2a. Scene embedding → tag prototype
          try {
            const result = await embedImage(assetId);
            if (result?.embedding?.length > 0) {
              await TagPrototypeService.updatePrototype(
                person.name,
                result.embedding,
                result.modelVersion ?? MODEL_VERSION,
              );
            }
          } catch {
            // non-fatal: continue
          }

          // 2b. Face detection + embedding → cluster centroid → tag binding
          //
          // NOTE: We intentionally do NOT write to detected_faces or face_embeddings.
          // Those tables reference media_index(uri) ON DELETE CASCADE, so any records
          // saved for training images (which are never in media_index) would either
          // fail immediately with an FK violation, or be wiped when the image is later
          // deleted from disk.  The durable learning lives in face_clusters (centroid
          // mean) and face_clusters.tag_id — neither has a media_index dependency.
          //
          // Cluster strategy (batch images carry ground-truth labels):
          //   1. Reuse a cluster already bound to THIS tag if centroid is close.
          //   2. Absorb a nearby unbound anonymous cluster (claim it for this tag).
          //   3. Create a brand-new cluster — never touch another tag's cluster.
          if (faceAvailable) {
            try {
              const faces = await FaceBridgeModule.detectFaces(assetId);
              if (faces && faces.length > 0) {
                for (const face of faces) {
                  if (face.faceIndex === -1 || (face.qualityScore ?? 1) < 0.3) {
                    continue;
                  }
                  const { embedding: faceEmb, modelAvailable } =
                    await FaceBridgeModule.embedFace(assetId, face);
                  if (!modelAvailable || !faceEmb?.length) continue;

                  facesFound++;
                  // Ground-truth aware cluster placement: prefer this tag's own
                  // clusters, then absorb unbound ones, then create.
                  await FaceClusterBackfillService.findOrClaimClusterForTag(
                    faceEmb,
                    tagId,
                  );
                }
              }
            } catch {
              // non-fatal
            }
          }

          imagesProcessed++;
          const localImagesProcessed = imagesProcessed;
          const localFacesFound = facesFound;
          setProgress(prev => {
            const next = [...prev];
            next[i] = {
              ...next[i],
              current: localImagesProcessed,
              total: mediaFiles.length,
              facesFound: localFacesFound,
            };
            return next;
          });
        }

        totalImagesOk += imagesProcessed;
        totalFacesFound += facesFound;
        personsOk++;
        personsProcessed.push({ tagId, tagName: person.name });

        setProgress(prev => {
          const next = [...prev];
          next[i] = {
            ...next[i],
            status: cancelRef.current ? STATUS.SKIPPED : STATUS.DONE,
            current: imagesProcessed,
            facesFound,
          };
          return next;
        });
      } catch (err) {
        setProgress(prev => {
          const next = [...prev];
          next[i] = {
            ...next[i],
            status: STATUS.ERROR,
            errorMsg: err?.message ?? 'Unknown error',
          };
          return next;
        });
      }
    }

    setSummary({
      personsOk,
      imagesOk: totalImagesOk,
      facesFound: totalFacesFound,
      cancelled: cancelRef.current,
    });
    setIsRunning(false);

    // ── Post-learning pipeline (awaited, with per-phase live status) ──────────
    if (!cancelRef.current) {
      await _runPostLearning(personsProcessed, totalFacesFound);
    }
  }, [isRunning, persons, faceAvailable, _runPostLearning]);

  const handleCancel = useCallback(() => {
    cancelRef.current = true;
  }, []);

  const handleReset = useCallback(() => {
    setRootPath(null);
    setPersons([]);
    setProgress([]);
    setSummary(null);
    setPhases({});
    setIsRunning(false);
    cancelRef.current = false;
  }, []);

  // ─── Derived ──────────────────────────────────────────────────────────────
  const totalImages = useMemo(
    () => persons.reduce((s, p) => s + p.fileCount, 0),
    [persons],
  );

  const crumbs = useMemo(() => pathBreadcrumbs(browsePath), [browsePath]);

  // ─── Render: folder browser ───────────────────────────────────────────────
  if (isBrowsing) {
    return (
      <View
        style={[
          batchStyles.flex,
          { backgroundColor: colors.background, paddingTop: insets.top },
        ]}
      >
        {/* Header */}
        <View style={batchStyles.header}>
          <TouchableOpacity
            onPress={handleCancelBrowse}
            style={batchStyles.backBtn}
            hitSlop={{ top: 12, right: 12, bottom: 12, left: 0 }}
          >
            <Text style={[batchStyles.backChevron, { color: colors.text }]}>
              ‹
            </Text>
          </TouchableOpacity>
          <Text style={[batchStyles.headerTitle, { color: colors.text }]}>
            Pick Training Folder
          </Text>
        </View>

        {/* Breadcrumb */}
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          style={[
            batchStyles.breadcrumbScroll,
            { borderBottomColor: colors.divider },
          ]}
          contentContainerStyle={batchStyles.breadcrumbContent}
        >
          <TouchableOpacity
            onPress={() => loadDir(RNFS.ExternalStorageDirectoryPath)}
          >
            <Text style={[batchStyles.crumbLink, { color: colors.accent }]}>
              storage
            </Text>
          </TouchableOpacity>
          {crumbs
            .slice(
              crumbs.findIndex(
                c => c.path === RNFS.ExternalStorageDirectoryPath,
              ) + 1,
            )
            .map((c, idx, arr) => (
              <React.Fragment key={c.path}>
                <Text
                  style={[batchStyles.crumbSep, { color: colors.textTertiary }]}
                >
                  {' / '}
                </Text>
                {idx < arr.length - 1 ? (
                  <TouchableOpacity onPress={() => loadDir(c.path)}>
                    <Text
                      style={[batchStyles.crumbLink, { color: colors.accent }]}
                    >
                      {c.label}
                    </Text>
                  </TouchableOpacity>
                ) : (
                  <Text
                    style={[batchStyles.crumbCurrent, { color: colors.text }]}
                  >
                    {c.label}
                  </Text>
                )}
              </React.Fragment>
            ))}
        </ScrollView>

        {/* Select this folder */}
        <TouchableOpacity
          style={[
            batchStyles.selectFolderBtn,
            { backgroundColor: colors.accent },
          ]}
          onPress={handleSelectFolder}
          activeOpacity={0.82}
        >
          <Icon name="picture" size={16} color="#fff" />
          <Text style={batchStyles.selectFolderText}>Select this folder</Text>
        </TouchableOpacity>

        {/* Up button */}
        {browsePath !== RNFS.ExternalStorageDirectoryPath && (
          <TouchableOpacity
            style={[
              batchStyles.upBtn,
              { borderColor: colors.border, backgroundColor: colors.surface },
            ]}
            onPress={handleBrowseUp}
            activeOpacity={0.7}
          >
            <Icon name="back" size={15} color={colors.textSecondary} />
            <Text
              style={[batchStyles.upBtnText, { color: colors.textSecondary }]}
            >
              .. (go up)
            </Text>
          </TouchableOpacity>
        )}

        {/* Directory listing */}
        {loadingDir ? (
          <ActivityIndicator
            style={batchStyles.activityCenter}
            color={colors.accent}
          />
        ) : browseError ? (
          <Text
            style={[
              batchStyles.errorText,
              batchStyles.browseMessage,
              { color: colors.error },
            ]}
          >
            {browseError}
          </Text>
        ) : dirEntries.length === 0 ? (
          <Text
            style={[
              batchStyles.emptyText,
              batchStyles.browseMessage,
              { color: colors.textTertiary },
            ]}
          >
            No sub-folders here.
          </Text>
        ) : (
          <FlatList
            data={dirEntries}
            keyExtractor={item => item.path}
            renderItem={({ item }) => (
              <TouchableOpacity
                style={[
                  batchStyles.dirRow,
                  { borderBottomColor: colors.divider },
                ]}
                onPress={() => handleBrowseEnter(item.path)}
                activeOpacity={0.7}
              >
                <Icon name="picture" size={18} color={colors.accent} />
                <Text
                  style={[batchStyles.dirName, { color: colors.text }]}
                  numberOfLines={1}
                >
                  {item.name}
                </Text>
                <Text
                  style={[batchStyles.dirChev, { color: colors.textTertiary }]}
                >
                  ›
                </Text>
              </TouchableOpacity>
            )}
            contentContainerStyle={{ paddingBottom: insets.bottom + 40 }}
          />
        )}
      </View>
    );
  }

  // ─── Render: main screen ──────────────────────────────────────────────────
  return (
    <ScrollView
      style={[styles.container, { paddingTop: insets.top }]}
      contentContainerStyle={{ paddingBottom: insets.bottom + 80 }}
      showsVerticalScrollIndicator={false}
    >
      {/* Header */}
      <View style={batchStyles.header}>
        <TouchableOpacity
          onPress={() => navigation.goBack()}
          style={batchStyles.backBtn}
          hitSlop={{ top: 12, right: 12, bottom: 12, left: 0 }}
        >
          <Text style={[batchStyles.backChevron, { color: colors.text }]}>
            ‹
          </Text>
        </TouchableOpacity>
        <Text style={[batchStyles.headerTitle, { color: colors.text }]}>
          Batch Tag Learning
        </Text>
      </View>

      <Text style={[batchStyles.subtitle, { color: colors.textSecondary }]}>
        Pick a folder whose sub-folders are named after people. Each image
        inside will train that person's tag prototype and face cluster.
      </Text>

      {/* ── Face model notice ───────────────────────────────────────── */}
      {!faceAvailable && (
        <View
          style={[
            batchStyles.notice,
            isDark ? batchStyles.noticeDark : batchStyles.noticeLight,
          ]}
        >
          <Icon name="information" size={15} color="#F59E0B" />
          <Text
            style={[batchStyles.noticeText, { color: colors.textSecondary }]}
          >
            Face model not installed — face embedding will be skipped. Scene
            embeddings will still train the prototype.
          </Text>
        </View>
      )}

      {/* ── Folder picker ───────────────────────────────────────────── */}
      <SectionLabel label="Training Folder" colors={colors} />

      <TouchableOpacity
        style={[
          batchStyles.folderRow,
          {
            backgroundColor: colors.surface,
            borderColor: colors.border,
          },
        ]}
        onPress={handleOpenBrowser}
        activeOpacity={0.75}
        disabled={isRunning}
      >
        <Icon
          name="picture"
          size={20}
          color={rootPath ? colors.accent : colors.textTertiary}
        />
        <Text
          style={[
            batchStyles.folderRowText,
            { color: rootPath ? colors.text : colors.textTertiary },
          ]}
          numberOfLines={2}
        >
          {rootPath
            ? rootPath.replace(
                RNFS.ExternalStorageDirectoryPath,
                'Internal Storage',
              )
            : 'Tap to pick training folder…'}
        </Text>
        <Text style={[batchStyles.dirChev, { color: colors.textTertiary }]}>
          ›
        </Text>
      </TouchableOpacity>

      {/* ── Persons preview ──────────────────────────────────────────── */}
      {persons.length > 0 && (
        <>
          <SectionLabel
            label={`People Found  ·  ${persons.length} folder${
              persons.length !== 1 ? 's' : ''
            }`}
            colors={colors}
          />
          <View
            style={[
              batchStyles.personListCard,
              { backgroundColor: colors.surface, borderColor: colors.border },
            ]}
          >
            {persons.map((p, idx) => (
              <View
                key={p.path}
                style={[
                  batchStyles.personPreviewRow,
                  idx < persons.length - 1 && {
                    borderBottomWidth: StyleSheet.hairlineWidth,
                    borderBottomColor: colors.divider,
                  },
                ]}
              >
                <Icon
                  name="man"
                  size={14}
                  color={
                    p.clusterCount > 0 ? colors.accent : colors.textTertiary
                  }
                />
                <View style={batchStyles.personNameWrap}>
                  <Text
                    style={[batchStyles.personName, { color: colors.text }]}
                    numberOfLines={1}
                  >
                    {p.name}
                  </Text>
                  {(p.clusterCount > 0 || p.sampleCount > 0) && (
                    <Text
                      style={[
                        batchStyles.trainedHint,
                        { color: colors.accent },
                      ]}
                      numberOfLines={1}
                    >
                      {'↻ '}
                      {[
                        p.clusterCount > 0 &&
                          `${p.clusterCount} cluster${
                            p.clusterCount !== 1 ? 's' : ''
                          }`,
                        p.sampleCount > 0 &&
                          `${p.sampleCount} sample${
                            p.sampleCount !== 1 ? 's' : ''
                          }`,
                      ]
                        .filter(Boolean)
                        .join(' · ')}
                    </Text>
                  )}
                </View>
                <Text
                  style={[
                    batchStyles.personCount,
                    { color: colors.textTertiary },
                  ]}
                >
                  {p.fileCount} image{p.fileCount !== 1 ? 's' : ''}
                </Text>
              </View>
            ))}
          </View>
          <Text style={[batchStyles.statsHint, { color: colors.textTertiary }]}>
            {totalImages} image{totalImages !== 1 ? 's' : ''} total across{' '}
            {persons.length} person{persons.length !== 1 ? 's' : ''}
          </Text>
        </>
      )}

      {/* ── Controls ─────────────────────────────────────────────────── */}
      {persons.length > 0 && !summary && (
        <View style={batchStyles.controlRow}>
          {!isRunning ? (
            <TouchableOpacity
              style={[
                batchStyles.primaryBtn,
                { backgroundColor: colors.accent },
              ]}
              onPress={handleStart}
              activeOpacity={0.82}
            >
              <Icon name="tags" size={16} color="#fff" />
              <Text style={batchStyles.primaryBtnText}>
                Start Batch Learning
              </Text>
            </TouchableOpacity>
          ) : (
            <TouchableOpacity
              style={[batchStyles.cancelBtn, { borderColor: colors.error }]}
              onPress={handleCancel}
              activeOpacity={0.82}
            >
              <Text
                style={[batchStyles.cancelBtnText, { color: colors.error }]}
              >
                Cancel
              </Text>
            </TouchableOpacity>
          )}
        </View>
      )}

      {/* ── Progress list ─────────────────────────────────────────────── */}
      {progress.length > 0 && (
        <>
          <SectionLabel label="Progress" colors={colors} />
          <View
            style={[
              batchStyles.progressCard,
              { backgroundColor: colors.surface, borderColor: colors.border },
            ]}
          >
            {progress.map((item, idx) => (
              <ProgressRow
                key={item.name + idx}
                item={item}
                colors={colors}
                isLast={idx === progress.length - 1}
              />
            ))}
          </View>
        </>
      )}

      {/* ── Summary ───────────────────────────────────────────────────── */}
      {summary && (
        <>
          <SectionLabel
            label={summary.cancelled ? 'Cancelled' : 'Complete'}
            colors={colors}
          />
          <View
            style={[
              batchStyles.summaryCard,
              {
                backgroundColor: colors.surface,
                borderColor: summary.cancelled
                  ? colors.border
                  : `${colors.accent}55`,
              },
            ]}
          >
            <SummaryRow
              label="People trained"
              value={`${summary.personsOk} / ${persons.length}`}
              colors={colors}
            />
            <SummaryRow
              label="Images processed"
              value={summary.imagesOk.toLocaleString()}
              colors={colors}
            />
            <SummaryRow
              label="Faces embedded"
              value={
                faceAvailable
                  ? summary.facesFound.toLocaleString()
                  : 'skipped (model missing)'
              }
              colors={colors}
            />
            {summary.mergedClusters != null && summary.mergedClusters > 0 && (
              <SummaryRow
                label="Anonymous clusters merged"
                value={summary.mergedClusters.toLocaleString()}
                colors={colors}
              />
            )}
            {summary.cancelled && (
              <Text
                style={[
                  batchStyles.cancelledNote,
                  { color: colors.textTertiary },
                ]}
              >
                Run was cancelled. Partial results were saved.
              </Text>
            )}
          </View>

          {/* ── Post-learning phases ─────────────────────────────────── */}
          {Object.keys(phases).length > 0 && (
            <PhaseList phases={phases} colors={colors} />
          )}

          <TouchableOpacity
            style={[
              batchStyles.resetBtn,
              { borderColor: colors.border },
              phasesRunning && batchStyles.disabledOpacity,
            ]}
            onPress={handleReset}
            disabled={phasesRunning}
            activeOpacity={0.75}
          >
            <Text
              style={[
                batchStyles.resetBtnText,
                { color: colors.textSecondary },
              ]}
            >
              {phasesRunning ? 'Processing…' : 'Start over'}
            </Text>
          </TouchableOpacity>
        </>
      )}
    </ScrollView>
  );
}

// ─── Sub-components ───────────────────────────────────────────────────────────

function SectionLabel({ label, colors }) {
  return (
    <Text style={[batchStyles.sectionLabel, { color: colors.textTertiary }]}>
      {label}
    </Text>
  );
}

function ProgressRow({ item, colors, isLast }) {
  const isRunning = item.status === STATUS.RUNNING;
  const isDone = item.status === STATUS.DONE;
  const isError = item.status === STATUS.ERROR;
  const isSkipped = item.status === STATUS.SKIPPED;

  const statusColor = isDone
    ? '#4CAF50'
    : isError
    ? colors.error
    : isRunning
    ? colors.accent
    : colors.textTertiary;

  const statusText = isDone
    ? '✓ done'
    : isError
    ? '✕ error'
    : isRunning
    ? `${item.current} / ${item.total}`
    : isSkipped
    ? '– skipped'
    : '…';

  const pct = item.total > 0 ? (item.current / item.total) * 100 : 0;

  return (
    <View
      style={[
        batchStyles.progressRow,
        !isLast && {
          borderBottomWidth: StyleSheet.hairlineWidth,
          borderBottomColor: colors.divider,
        },
      ]}
    >
      <View style={batchStyles.progressRowTop}>
        <Text
          style={[
            batchStyles.personName,
            batchStyles.personNameFlex,
            { color: colors.text },
          ]}
          numberOfLines={1}
        >
          {item.name}
        </Text>
        {isRunning && (
          <ActivityIndicator
            size="small"
            color={colors.accent}
            style={batchStyles.activityInline}
          />
        )}
        <Text style={[batchStyles.progressStatus, { color: statusColor }]}>
          {statusText}
        </Text>
      </View>

      {/* Progress bar */}
      {(isRunning || isDone) && item.total > 0 && (
        <View
          style={[
            batchStyles.progressTrack,
            { backgroundColor: colors.border },
          ]}
        >
          <View
            style={[
              batchStyles.progressFill,
              // eslint-disable-next-line react-native/no-inline-styles
              {
                width: `${pct}%`,
                backgroundColor: isDone ? '#4CAF50' : colors.accent,
              },
            ]}
          />
        </View>
      )}

      {isError && item.errorMsg && (
        <Text
          style={[batchStyles.progressError, { color: colors.error }]}
          numberOfLines={2}
        >
          {item.errorMsg}
        </Text>
      )}

      {isDone && item.facesFound > 0 && (
        <Text
          style={[batchStyles.progressHint, { color: colors.textTertiary }]}
        >
          {item.facesFound} face{item.facesFound !== 1 ? 's' : ''} embedded
        </Text>
      )}
    </View>
  );
}

function SummaryRow({ label, value, colors }) {
  return (
    <View style={batchStyles.summaryRow}>
      <Text style={[batchStyles.summaryLabel, { color: colors.textSecondary }]}>
        {label}
      </Text>
      <Text style={[batchStyles.summaryValue, { color: colors.text }]}>
        {value}
      </Text>
    </View>
  );
}

/**
 * Shows all post-learning phases that have been started.
 * Each phase key maps to PHASE_LABEL for a human-readable title.
 */
function PhaseList({ phases, colors }) {
  const keys = PHASE_KEYS.filter(k => phases[k] != null);
  if (!keys.length) return null;
  return (
    <>
      <Text style={[batchStyles.sectionLabel, { color: colors.textTertiary }]}>
        Post-processing
      </Text>
      <View
        style={[
          batchStyles.phaseCard,
          { backgroundColor: colors.surface, borderColor: colors.border },
        ]}
      >
        {keys.map((k, idx) => (
          <PhaseRow
            key={k}
            phaseKey={k}
            phase={phases[k]}
            colors={colors}
            isLast={idx === keys.length - 1}
          />
        ))}
      </View>
    </>
  );
}

function PhaseRow({ phaseKey, phase, colors, isLast }) {
  const isRunning = phase.status === STATUS.RUNNING;
  const isDone = phase.status === STATUS.DONE;
  const isError = phase.status === STATUS.ERROR;

  const accentColor = isDone
    ? '#4CAF50'
    : isError
    ? colors.error
    : colors.accent;

  return (
    <View
      style={[
        batchStyles.phaseRow,
        !isLast && {
          borderBottomWidth: StyleSheet.hairlineWidth,
          borderBottomColor: colors.divider,
        },
      ]}
    >
      {/* Left: spinner / icon */}
      <View style={batchStyles.phaseIconWrap}>
        {isRunning ? (
          <ActivityIndicator size="small" color={colors.accent} />
        ) : (
          <Text style={[batchStyles.phaseIcon, { color: accentColor }]}>
            {isDone ? '✓' : isError ? '✕' : '·'}
          </Text>
        )}
      </View>

      {/* Centre: label + detail */}
      <View style={batchStyles.phaseLabelWrap}>
        <Text
          style={[batchStyles.phaseLabel, { color: colors.text }]}
          numberOfLines={1}
        >
          {PHASE_LABEL[phaseKey] ?? phaseKey}
        </Text>
        {phase.detail ? (
          <Text
            style={[batchStyles.phaseDetail, { color: colors.textTertiary }]}
            numberOfLines={2}
          >
            {phase.detail}
          </Text>
        ) : null}
      </View>

      {/* Right: status badge */}
      <Text style={[batchStyles.phaseStatus, { color: accentColor }]}>
        {isDone ? 'done' : isError ? 'error' : isRunning ? '…' : ''}
      </Text>
    </View>
  );
}

// ─── Local styles ─────────────────────────────────────────────────────────────

const batchStyles = StyleSheet.create({
  flex: { flex: 1 },

  header: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 14,
    marginBottom: 20,
  },
  backBtn: { marginRight: 12 },
  backChevron: {
    fontSize: 36,
    fontWeight: '200',
    lineHeight: 40,
  },
  headerTitle: {
    fontSize: 22,
    fontWeight: '300',
    letterSpacing: 0.5,
  },

  subtitle: {
    fontSize: 13,
    lineHeight: 20,
    marginBottom: 4,
    opacity: 0.85,
  },

  sectionLabel: {
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 1.4,
    textTransform: 'uppercase',
    marginTop: 24,
    marginBottom: 8,
    paddingHorizontal: 4,
  },

  notice: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 8,
    borderRadius: 10,
    borderWidth: StyleSheet.hairlineWidth,
    padding: 12,
    marginTop: 12,
  },
  noticeText: {
    flex: 1,
    fontSize: 12,
    lineHeight: 18,
  },

  // Folder picker row
  folderRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
    paddingVertical: 14,
    paddingHorizontal: 14,
    minHeight: 52,
  },
  folderRowText: {
    flex: 1,
    fontSize: 14,
    lineHeight: 20,
  },

  // Person preview card
  personListCard: {
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
    overflow: 'hidden',
  },
  personPreviewRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 14,
    paddingVertical: 10,
  },
  personNameWrap: {
    flex: 1,
  },
  personName: {
    fontSize: 14,
    fontWeight: '500',
  },
  trainedHint: {
    fontSize: 11,
    lineHeight: 16,
    marginTop: 1,
    opacity: 0.9,
  },
  personCount: {
    fontSize: 12,
    fontWeight: '400',
  },
  statsHint: {
    fontSize: 11,
    marginTop: 6,
    paddingHorizontal: 4,
    opacity: 0.75,
  },

  // Control buttons
  controlRow: {
    marginTop: 20,
    marginBottom: 8,
  },
  primaryBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    borderRadius: 12,
    paddingVertical: 15,
  },
  primaryBtnText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
    letterSpacing: 0.3,
  },
  cancelBtn: {
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 12,
    paddingVertical: 14,
    borderWidth: 1,
  },
  cancelBtnText: {
    fontSize: 15,
    fontWeight: '600',
  },

  // Progress card
  progressCard: {
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
    overflow: 'hidden',
  },
  progressRow: {
    paddingHorizontal: 14,
    paddingVertical: 12,
  },
  progressRowTop: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 4,
  },
  progressStatus: {
    fontSize: 12,
    fontWeight: '600',
  },
  progressTrack: {
    height: 3,
    borderRadius: 2,
    overflow: 'hidden',
    marginTop: 4,
  },
  progressFill: {
    height: '100%',
    borderRadius: 2,
  },
  progressError: {
    fontSize: 11,
    marginTop: 4,
  },
  progressHint: {
    fontSize: 11,
    marginTop: 4,
    opacity: 0.75,
  },

  // Summary card
  summaryCard: {
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
    overflow: 'hidden',
  },
  summaryRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: 'rgba(128,128,128,0.15)',
  },
  summaryLabel: {
    fontSize: 14,
  },
  summaryValue: {
    fontSize: 14,
    fontWeight: '600',
  },
  cancelledNote: {
    fontSize: 12,
    paddingHorizontal: 16,
    paddingBottom: 12,
    opacity: 0.75,
  },

  resetBtn: {
    alignItems: 'center',
    borderRadius: 10,
    paddingVertical: 12,
    marginTop: 14,
    borderWidth: StyleSheet.hairlineWidth,
  },
  resetBtnText: {
    fontSize: 14,
    fontWeight: '500',
  },

  // Browser styles
  breadcrumbScroll: {
    borderBottomWidth: StyleSheet.hairlineWidth,
    maxHeight: 40,
  },
  breadcrumbContent: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  crumbLink: {
    fontSize: 13,
    fontWeight: '500',
  },
  crumbSep: {
    fontSize: 13,
  },
  crumbCurrent: {
    fontSize: 13,
    fontWeight: '600',
  },

  selectFolderBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    marginHorizontal: 16,
    marginTop: 12,
    marginBottom: 6,
    borderRadius: 10,
    paddingVertical: 13,
  },
  selectFolderText: {
    color: '#fff',
    fontSize: 15,
    fontWeight: '600',
  },

  upBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginHorizontal: 16,
    marginVertical: 4,
    borderRadius: 8,
    borderWidth: StyleSheet.hairlineWidth,
    paddingVertical: 10,
    paddingHorizontal: 12,
  },
  upBtnText: {
    fontSize: 13,
    fontStyle: 'italic',
  },

  dirRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 20,
    paddingVertical: 14,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  dirName: {
    flex: 1,
    fontSize: 15,
  },
  dirChev: {
    fontSize: 22,
    fontWeight: '300',
  },

  errorText: {
    fontSize: 13,
    lineHeight: 20,
  },
  emptyText: {
    fontSize: 13,
    fontStyle: 'italic',
  },

  // Computed (static) variants
  activityCenter: { marginTop: 40 },
  activityInline: { marginRight: 6 },
  browseMessage: { margin: 20 },
  personNameFlex: { flex: 1 },
  noticeDark: { backgroundColor: '#2A2200', borderColor: '#4A3800' },
  noticeLight: { backgroundColor: '#FFFBEB', borderColor: '#FCD34D' },
  disabledOpacity: { opacity: 0.4 },

  // Phase list (post-learning)
  phaseCard: {
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
    overflow: 'hidden',
    marginBottom: 4,
  },
  phaseRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 14,
    paddingVertical: 11,
    gap: 10,
  },
  phaseIconWrap: {
    width: 24,
    alignItems: 'center',
  },
  phaseIcon: {
    fontSize: 16,
    fontWeight: '600',
    lineHeight: 20,
  },
  phaseLabelWrap: {
    flex: 1,
  },
  phaseLabel: {
    fontSize: 13,
    fontWeight: '500',
    lineHeight: 18,
  },
  phaseDetail: {
    fontSize: 11,
    lineHeight: 16,
    marginTop: 1,
  },
  phaseStatus: {
    fontSize: 11,
    fontWeight: '600',
    letterSpacing: 0.4,
    minWidth: 32,
    textAlign: 'right',
  },
});
