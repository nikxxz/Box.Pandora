# AI Architecture Plan — BoxPandora
> Phase 0 Baseline · 2026-03-14

---

## 1. Current Status

**This codebase is a well-structured placeholder.**
Complete database schema and DAOs exist for every AI feature. Zero ML inference code or model files exist anywhere. No ML dependencies are declared in `libs.versions.toml`.

`android:largeHeap="true"` is already set in the manifest — the only runtime concession to future inference work.

---

## 2. Canonical File Layout

### Model Files (binary assets)
```
app/src/main/assets/models/
  scene/
    mobilenet_v3_scene.tflite        ← MobileNet V3 feature vector (scene_embedding)
  face/
    arcface_resnet100.tflite         ← ArcFace ResNet-100 (face_embedding)
    [face_detector.tflite]           ← no production model yet (face_detection)
```
Model files are **never committed to git**. They ship via a download-on-demand mechanism or are sideloaded during dev. A `models/.gitignore` and a download helper will be added in Phase 1.

### Model Metadata
```
app/src/main/java/com/example/boxpandora/ml/model/
  ModelRegistry.kt       ← enum / sealed class mapping category → asset path + version string
  ModelMetadata.kt       ← data class: name, category, inputShape, normMean, normStd, outputDim
```
The `modelVersion` column already present in `ImageEmbedding`, `FaceEmbedding`, and `TagPrototype` entities maps directly to `ModelMetadata.version`.

### Inference Services
```
app/src/main/java/com/example/boxpandora/ml/inference/
  SceneEmbeddingService.kt     ← loads scene model, runs bitmap → FloatArray
  FaceEmbeddingService.kt      ← loads face model, runs face crop → FloatArray
  FaceDetectionService.kt      ← placeholder stub until a detector model is chosen
```
Each service is a plain Kotlin class (not a ViewModel). It holds a single TFLite `Interpreter` instance, is created once per worker invocation, and is closed in a `finally` block. No singleton patterns.

### Indexing Workers
```
app/src/main/java/com/example/boxpandora/worker/
  SceneIndexWorker.kt          ← iterates unindexed assets, calls SceneEmbeddingService, writes ImageEmbedding rows
  FaceIndexWorker.kt           ← detects faces, crops, calls FaceEmbeddingService, writes DetectedFace + FaceEmbedding rows
  FaceClusterWorker.kt         ← reads all face embeddings, runs clustering, writes FaceCluster rows
  TagSuggestionWorker.kt       ← nearest-neighbour lookup against TagPrototype table, writes TagSuggestion rows
```
All workers use `CoroutineWorker` + `WorkManager`. Chaining order: `SceneIndexWorker` → `TagSuggestionWorker`; `FaceIndexWorker` → `FaceClusterWorker`. Workers are idempotent — they skip assets that already have a current-model-version embedding.

### UI Screens exposing AI features
| Screen | File | What it exposes |
|---|---|---|
| Tagging & AI Settings | `ui/settings/SettingsSubScreens.kt` | confidence threshold, auto-merge, discovery mode, background tagging, rebuild index |
| Tag Detail / Suggestions | (to be built) | accept / reject AI suggestions per asset |
| Face People Gallery | (to be built) | clusters → named people |
| Visual Similarity Search | (to be built) | nearest-neighbour results from scene embeddings |

---

## 3. Three Model Categories

| Category | Purpose | Input | Output dim |
|---|---|---|---|
| `scene_embedding` | Image-level feature vector for similarity search and tag-prototype matching | 224×224 RGB bitmap | 1280 |
| `face_embedding` | Per-face identity vector for clustering and recognition | 112×112 aligned face crop | 512 |
| `face_detection` | Bounding box + landmark detection to feed the face embedding pipeline | Full-resolution bitmap | bboxes + 5-point landmarks |

---

## 4. Confirmed Model Assignments

| Category | Model | File | Status |
|---|---|---|---|
| `scene_embedding` | MobileNet V3 Large — Feature Vector (ImageNet) | `mobilenet_v3_scene.tflite` | Model identified; not yet in assets |
| `face_embedding` | ArcFace ResNet-100 | `arcface_resnet100.tflite` | Model identified; not yet in assets |
| `face_detection` | *(none chosen)* | — | **No production model yet** — placeholder stub only |

For `face_detection`, candidates to evaluate in Phase 1: MediaPipe Face Detector, YuNet, RetinaFace-MobileNet. Decision deferred.

---

## 5. Existing Placeholder / Dead Code Inventory

All items below are **infrastructure stubs** — schema and DAO are correct, but nothing writes real data to them yet.

| File | State | Disposition |
|---|---|---|
| `entity/ImageEmbedding.kt` | Stub — never written to | **Keep** — schema is correct, will be populated by `SceneIndexWorker` |
| `entity/DetectedFace.kt` | Stub — never written to | **Keep** — schema correct, populate in `FaceIndexWorker` |
| `entity/FaceEmbedding.kt` | Stub — never written to | **Keep** — populate in `FaceIndexWorker` |
| `entity/FaceCluster.kt` | Stub — never written to | **Keep** — populate in `FaceClusterWorker` |
| `entity/TagSuggestion.kt` | Stub — never written to | **Keep** — populate in `TagSuggestionWorker` |
| `entity/TagPrototype.kt` | Stub — never written to | **Keep** — will be seeded from bundled prototype file in Phase 2 |
| `entity/HeuristicTag.kt` | Stub — never written to | **Keep** — rule-based complement to ML suggestions, implement in Phase 2 |
| `entity/TagAlias.kt` | Stub | **Keep** — needed for taxonomy management |
| `entity/TagReviewQueue.kt` | Stub | **Keep** — human-in-the-loop flow |
| `entity/TagChangeHistory.kt` | Stub | **Keep** — audit trail |
| `entity/TagCooccurrence.kt` | Partially active (counted on tag apply) | **Keep** — already wired, extend in Phase 2 |
| `dao/ImageEmbeddingDao.kt` | Correct API | **Keep** |
| `dao/FaceDao.kt` | Correct API | **Keep** |
| `dao/TagSuggestionDao.kt` | Correct API | **Keep** |
| `dao/TagPrototypeDao.kt` | Correct API | **Keep** |
| `dao/HeuristicTagDao.kt` | Correct API | **Keep** |
| `dao/TagRejectionDao.kt` | Correct API | **Keep** |
| `dao/TagCooccurrenceDao.kt` | Correct API | **Keep** |
| `repository/MediaRepository.kt` — `transferMetadata()` | Stub shell calls to embedding/face DAOs | **Keep** — wire up properly in Phase 1 |
| `repository/TagRepository.kt` — `getSuggestionsForMedia()` | Returns empty until workers run | **Keep** — logic correct |
| `ui/settings/SettingsSubScreens.kt` — `TaggingAISettingsScreen()` | Controls are no-ops | **Keep** — wire to DataStore in Phase 1 |
| `worker/SyncWorker.kt` | No AI logic | **Keep as-is** — not an AI worker |

**Nothing should be deleted in Phase 0.** All stubs are architecturally correct placeholders.

---

## 6. Dependencies to Add (Phase 1)

```toml
# gradle/libs.versions.toml
tflite = "2.14.0"

[libraries]
tflite-task-vision = { module = "org.tensorflow:tensorflow-lite-task-vision", version.ref = "tflite" }
tflite-gpu-delegate = { module = "org.tensorflow:tensorflow-lite-gpu-delegate-plugin", version.ref = "tflite" }
```

Only `tflite-task-vision` is needed initially. GPU delegate is optional and added only after CPU inference is validated.

---

## 7. What Phase 1 Must Not Change

- Database schema (no new migrations until inference is proven)
- Existing DAO signatures
- `TagRepository` suggestion/rejection public API
- `MediaRepository.transferMetadata()` signature
- `SyncWorker` — it is media-sync only and must stay that way

---

## 8. Open Questions (resolved before Phase 1 starts)

1. **Face detection model** — which model, which input resolution?
2. **Prototype seed file format** — pre-computed `.bin` bundled in assets, or generated at first launch from a vocab list?
3. **Index trigger** — manual ("Rebuild Index" button) only, or automatic on new media sync?
4. **Similarity search UI** — standalone screen or inline in `FolderDetailScreen`?
