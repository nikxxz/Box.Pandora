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

| Screen                   | File                                | What it exposes                                                                     |
| ------------------------ | ----------------------------------- | ----------------------------------------------------------------------------------- |
| Tagging & AI Settings    | `ui/settings/SettingsSubScreens.kt` | confidence threshold, auto-merge, discovery mode, background tagging, rebuild index |
| Tag Detail / Suggestions | (to be built)                       | accept / reject AI suggestions per asset                                            |
| Face People Gallery      | (to be built)                       | clusters → named people                                                             |
| Visual Similarity Search | (to be built)                       | nearest-neighbour results from scene embeddings                                     |

---

## 3. Three Model Categories

| Category          | Purpose                                                                     | Input                     | Output dim                 |
| ----------------- | --------------------------------------------------------------------------- | ------------------------- | -------------------------- |
| `scene_embedding` | Image-level feature vector for similarity search and tag-prototype matching | 224×224 RGB bitmap        | 1280                       |
| `face_embedding`  | Per-face identity vector for clustering and recognition                     | 112×112 aligned face crop | 512                        |
| `face_detection`  | Bounding box + landmark detection to feed the face embedding pipeline       | Full-resolution bitmap    | bboxes + 5-point landmarks |

---

## 4. Confirmed Model Assignments

| Category          | Model                                          | File                        | Status                                              |
| ----------------- | ---------------------------------------------- | --------------------------- | --------------------------------------------------- |
| `scene_embedding` | MobileNet V3 Large — Feature Vector (ImageNet) | `mobilenet_v3_scene.tflite` | Model identified; not yet in assets                 |
| `face_embedding`  | ArcFace ResNet-100                             | `arcface_resnet100.tflite`  | Model identified; not yet in assets                 |
| `face_detection`  | _(none chosen)_                                | —                           | **No production model yet** — placeholder stub only |

For `face_detection`, candidates to evaluate in Phase 1: MediaPipe Face Detector, YuNet, RetinaFace-MobileNet. Decision deferred.

---

## 5. Existing Placeholder / Dead Code Inventory

All items below are **infrastructure stubs** — schema and DAO are correct, but nothing writes real data to them yet.

| File                                                              | State                                   | Disposition                                                              |
| ----------------------------------------------------------------- | --------------------------------------- | ------------------------------------------------------------------------ |
| `entity/ImageEmbedding.kt`                                        | Stub — never written to                 | **Keep** — schema is correct, will be populated by `SceneIndexWorker`    |
| `entity/DetectedFace.kt`                                          | Stub — never written to                 | **Keep** — schema correct, populate in `FaceIndexWorker`                 |
| `entity/FaceEmbedding.kt`                                         | Stub — never written to                 | **Keep** — populate in `FaceIndexWorker`                                 |
| `entity/FaceCluster.kt`                                           | Stub — never written to                 | **Keep** — populate in `FaceClusterWorker`                               |
| `entity/TagSuggestion.kt`                                         | Stub — never written to                 | **Keep** — populate in `TagSuggestionWorker`                             |
| `entity/TagPrototype.kt`                                          | Stub — never written to                 | **Keep** — will be seeded from bundled prototype file in Phase 2         |
| `entity/HeuristicTag.kt`                                          | Stub — never written to                 | **Keep** — rule-based complement to ML suggestions, implement in Phase 2 |
| `entity/TagAlias.kt`                                              | Stub                                    | **Keep** — needed for taxonomy management                                |
| `entity/TagReviewQueue.kt`                                        | Stub                                    | **Keep** — human-in-the-loop flow                                        |
| `entity/TagChangeHistory.kt`                                      | Stub                                    | **Keep** — audit trail                                                   |
| `entity/TagCooccurrence.kt`                                       | Partially active (counted on tag apply) | **Keep** — already wired, extend in Phase 2                              |
| `dao/ImageEmbeddingDao.kt`                                        | Correct API                             | **Keep**                                                                 |
| `dao/FaceDao.kt`                                                  | Correct API                             | **Keep**                                                                 |
| `dao/TagSuggestionDao.kt`                                         | Correct API                             | **Keep**                                                                 |
| `dao/TagPrototypeDao.kt`                                          | Correct API                             | **Keep**                                                                 |
| `dao/HeuristicTagDao.kt`                                          | Correct API                             | **Keep**                                                                 |
| `dao/TagRejectionDao.kt`                                          | Correct API                             | **Keep**                                                                 |
| `dao/TagCooccurrenceDao.kt`                                       | Correct API                             | **Keep**                                                                 |
| `repository/MediaRepository.kt` — `transferMetadata()`            | Stub shell calls to embedding/face DAOs | **Keep** — wire up properly in Phase 1                                   |
| `repository/TagRepository.kt` — `getSuggestionsForMedia()`        | Returns empty until workers run         | **Keep** — logic correct                                                 |
| `ui/settings/SettingsSubScreens.kt` — `TaggingAISettingsScreen()` | Controls are no-ops                     | **Keep** — wire to DataStore in Phase 1                                  |
| `worker/SyncWorker.kt`                                            | No AI logic                             | **Keep as-is** — not an AI worker                                        |

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

---

## 9. Phase 3 — Testing Checklist

### Functional tests

| #   | Test                                                                           | Pass condition                                                                                                                                                                                                                                                     |
| --- | ------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| F1  | Category-specific thresholds applied to fused scene suggestions                | A suggestion whose fused score falls below its tag-category threshold is absent from `getSuggestionObjectsForMedia()` results; one above threshold is present                                                                                                      |
| F2  | Stricter identity thresholds suppress weak person matches                      | A fused identity suggestion with score below identity threshold is filtered out; manually boosting the score above threshold makes it reappear                                                                                                                     |
| F3  | Category-specific ambiguity margins work                                       | Two scene suggestions within the ambiguity margin of each other for the same category are both suppressed (or both promoted, per policy); only the dominant one survives outside the margin                                                                        |
| F4  | Reliability stats update after accept/reject events                            | Accepting a suggestion increments `acceptedCount` for the contributing model+category row; rejecting increments `rejectedCount`; `derivedWeight` and `lastUpdatedAt` are updated accordingly                                                                       |
| F5  | Fusion uses reliability weights when present                                   | A model with `derivedWeight = 2.0` contributes proportionally more to fused scores than a neutral-weight model given identical raw scores                                                                                                                          |
| F6  | Neutral defaults used when history is sparse                                   | A model with zero feedback records participates in fusion with weight 1.0× rather than being excluded or penalised                                                                                                                                                 |
| F7  | Rebuild maintenance actions recompute fused outputs without deleting user data | After triggering "Rebuild Fused Tag Suggestions", "Rebuild Fused Identity Suggestions", and "Recalculate Model Reliability": fused rows are rewritten; `acceptedCount`, `rejectedCount`, user-assigned tags, person names, and rejection records are all preserved |
| F8  | Support metadata exposed consistently to UI                                    | `RichSuggestion.contributingModelCount` and `agreementLevel` match the count of distinct model IDs in the underlying fused-suggestion evidence                                                                                                                     |

### Data tests

| #   | Test                                                           | Pass condition                                                                                                                                                                                                                                                      |
| --- | -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| D1  | Reliability stats persist correctly per model and per category | Inserting three feedback events for `(modelId="A", pipelineCategory=SCENE, tagCategory="animals")` and two for `(modelId="A", pipelineCategory=SCENE, tagCategory="vehicles")` produces two distinct `ModelReliabilityStats` rows with correct accept/reject counts |
| D2  | Fused suggestion metadata stores support count and agreement   | A `FusedTagSuggestion` row written after a 3-model ensemble run carries `supportingModelCount = 3` and an `agreementLevel` consistent with the score variance across those models                                                                                   |
| D3  | Reliability recalculation is reproducible from stored feedback | Deleting all `ModelReliabilityStats` rows and re-running the recalculation worker produces identical weight values given an unchanged feedback-event history                                                                                                        |

### UI tests

| #   | Test                                                                        | Pass condition                                                                                                                                                                                                                    |
| --- | --------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| U1  | Suggestion chips/cards can show support-count or agreement badge            | When a `RichSuggestion` carries `contributingModelCount ≥ 2`, the badge renders; when `contributingModelCount == 1` the badge is absent with no layout shift                                                                      |
| U2  | Diagnostics show reliability info without layout breakage                   | Expanding the diagnostics section for an installed model with feedback history renders weight, accept/reject counts, and (for scene models) per-category sub-rows within the card bounds; no overflow or truncation of other rows |
| U3  | Maintenance actions appear in the correct settings area and trigger workers | "Rebuild Fused Tag Suggestions", "Rebuild Fused Identity Suggestions", and "Recalculate Model Reliability" are all visible under Tagging & AI → Maintenance; tapping each enqueues the corresponding `WorkManager` job            |

---

## 10. Phase 3 — Non-Negotiable Implementation Rules

1. **Layered threshold behavior** — Category-specific thresholds are additive on top of the existing single-threshold path. The single-threshold code path must remain fully functional and produce identical results for single-active-model configurations.

2. **Logic stays in the domain layer** — UI must not compute category thresholds, reliability weights, or agreement logic. All such computation belongs in `TagRepository`, `FusionEngine`, or equivalent domain/data layer classes.

3. **Cold-start protection** — `derivedWeight` must not change significantly from 1.0× until a minimum feedback count threshold (e.g. ≥ 10 events) is met. Sparse history must not produce extreme weights in either direction.

4. **Weight clamping** — No single model's `derivedWeight` may exceed a defined maximum (e.g. 3.0×) or fall below a defined minimum (e.g. 0.2×), regardless of feedback history.

5. **Immutable user feedback** — Accept/reject records, user-assigned tags, and person names are never modified or deleted by rebuild or recalculation workers.

6. **No raw evidence blobs in main UI** — Internal evidence structures (embedding distances, per-model raw scores, etc.) must not surface in suggestion chips, tag detail screens, or any primary UI path.

7. **Identity / scene separation** — Person-identity fusion logic (identity pipeline, `FaceCluster`, `PersonName`) must not share code paths or threshold tables with generic scene-tag logic.

---

## 11. Phase 3 — Done Criteria

Phase 3 is complete when **all** of the following are true:

- [ ] Fused scene and identity suggestions are filtered using category-aware thresholds and ambiguity rules (F1, F2, F3)
- [ ] Model contributions to fusion are weighted by persisted `ModelReliabilityStats` (F5, F6)
- [ ] Accept/reject feedback durably updates per-model, per-category reliability over time (F4, D1)
- [ ] Rebuild and recalculation workers reprocess fused outputs without touching user data (F7, D3)
- [ ] `RichSuggestion` exposes `contributingModelCount` and `agreementLevel` to the UI (F8, D2)
- [ ] Suggestion chips or cards can optionally render an ensemble-support badge (U1)
- [ ] Model diagnostics display reliability weight and accept/reject summary without layout breakage (U2)
- [ ] All three maintenance actions are present and functional in Settings → Maintenance (U3)
- [ ] The single-active-model code path continues to produce correct results with no regression

---

## 12. Phase 4 — Thermal-Aware Orchestration and Scheduling Refinement

### Scope

Harden the ensemble runtime so that enabling multiple models is powerful without making the device dangerous to hold. Phase 4 adds no new ML model families, no cloud processing, and no large UI redesigns.

| In scope                                            | Out of scope                 |
| --------------------------------------------------- | ---------------------------- |
| Thermal-aware ensemble execution guards             | New ML model families        |
| Dynamic concurrency scaling                         | Heavy debug dashboards       |
| Foreground vs background scheduling refinement      | Large UI redesigns           |
| Resumable worker checkpointing via `Result.retry()` | Cloud / off-device inference |
| Failure isolation and cooldown behavior             | —                            |
| Calibration hooks for thresholds and agreement      | —                            |

---

### New package: `ml/policy/`

All Phase 4 runtime safety logic lives in one place so workers never reimplement it inline.

```
app/src/main/java/com/example/boxpandora/ml/policy/
  ThermalLevel.kt               ← enum: NORMAL, ELEVATED, HOT, CRITICAL
  ThermalMonitor.kt             ← wraps PowerManager thermal API (API 29+); Closeable
  EnsembleRuntimePolicy.kt      ← central Proceed/Throttle/Pause/Stop gate
  DynamicConcurrencyController.kt ← per-batch ConcurrencyLimits from thermal + charge state
```

---

### `ThermalLevel` — thermal severity ladder

```
NORMAL    → full ensemble concurrency
ELEVATED  → reduced batch size; single-model-per-item concurrency
HOT       → background paused; foreground throttled (2 s batch pause)
CRITICAL  → all ensemble execution stopped immediately (hard stop, both modes)
```

Maps onto `PowerManager.THERMAL_STATUS_*` constants (API 29+). Devices running API < 29 always report `NORMAL` — the WorkManager `setRequiresBatteryNotLow` constraint already blocks jobs at the OS level on those devices.

---

### `ThermalMonitor` — live thermal source

- Registers a `PowerManager.OnThermalStatusChangedListener` on API 29+; no-op listener on API 24–28.
- Seeds from `PowerManager.currentThermalStatus` at init so the first call is accurate.
- Stores raw platform integer in an `AtomicInteger` for lock-free reads from any thread.
- `Closeable` — removes the listener via a stored reference in `onTerminate`.
- Owned by `PandoraApp`; workers access it as `(applicationContext as PandoraApp).thermalMonitor`.

---

### `EnsembleRuntimePolicy` — the single gating point

Called at **every batch boundary** inside ensemble workers. Workers must not re-implement any of this logic.

#### Decision table

| Thermal  | Background                   | Foreground (user-triggered) |
| -------- | ---------------------------- | --------------------------- |
| CRITICAL | **Stop** — no retry          | **Stop** — no retry         |
| HOT      | **Pause** → `Result.retry()` | **Throttle** 2 s            |
| ELEVATED | **Throttle** 500 ms          | **Proceed**                 |
| NORMAL   | Check battery guards (below) | **Proceed**                 |

Additional background-only guards (checked only when `!isForeground`):

| Condition                                                 | Decision |
| --------------------------------------------------------- | -------- |
| Battery saver active + `pauseEnsembleOnBatterySaver=true` | Pause    |
| Battery capacity < 15 % and not charging                  | Pause    |
| Not charging + `ensembleOnlyWhileCharging=true`           | Pause    |

`PolicyDecision.Stop` → worker returns `Result.success` (suppresses automatic WorkManager retry).  
`PolicyDecision.Pause` → worker returns `Result.retry` (WorkManager re-enqueues when conditions improve).  
`PolicyDecision.Throttle` → worker sleeps `batchPauseMs` then continues.  
`PolicyDecision.Proceed` → no delay.

---

### `DynamicConcurrencyController` — elastic batch sizing

Replaces the fixed `BATCH_SIZE = 16` constant in ensemble workers with thermal- and charge-aware values.

| Thermal  | Charging | batchSize | scene models/item | face detectors/item | recognizers/face |
| -------- | -------- | --------- | ----------------- | ------------------- | ---------------- |
| NORMAL   | yes      | 16        | 2                 | 2                   | 2                |
| NORMAL   | no       | 12        | 1                 | 1                   | 1                |
| ELEVATED | yes      | 8         | 1                 | 1                   | 1                |
| ELEVATED | no       | 6         | 1                 | 1                   | 1                |
| HOT      | \*       | 4         | 1                 | 1                   | 1                |
| CRITICAL | \*       | 1         | 1                 | 1                   | 1                |

`sceneModelParallelism` of 2 means two scene models may be run concurrently for a single media item when charging and thermal pressure is low. The orchestrators are currently sequential; this field is the hook point for upgrading them to coroutine-parallel execution in a later phase without changing the policy contract.

---

### Wiring

#### `PandoraApp`

- Added `lateinit var thermalMonitor: ThermalMonitor`
- Initialized in `onCreate` after `indexingStatsStore`
- `close()` called in `onTerminate`

#### `TagSuggestionWorker.runEnsemblePath()`

- Fixed `BATCH_SIZE` constant renamed to `SINGLE_ACTIVE_BATCH_SIZE` (single-active path is not thermal-aware yet)
- Ensemble path creates `EnsembleRuntimePolicy(app.thermalMonitor)` once per run
- Calls `policy.evaluate()` before every batch; acts on `Stop` / `Pause` / `Throttle` / `Proceed`
- Calls `DynamicConcurrencyController.limitsFor(thermal, isCharging)` before each batch to get adaptive batch size and future concurrency limits
- On `Stop`: logs reason + asset count, returns `Result.success` with `stoppedByPolicy` key
- On `Pause`: logs reason + asset count, returns `Result.retry()`

#### Face workers (`FaceIndexWorker`, `FaceClusterWorker`)

- Follow the same pattern as `TagSuggestionWorker`; not yet wired in Phase 4 since the face ensemble path uses the same thermal source and scheduling constraints
- Should be wired in a follow-up pass using the identical call pattern

---

## 13. Phase 4 — Testing Checklist

### Thermal / policy tests

| #   | Test                                                                                   | Pass condition                                                                                               |
| --- | -------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| T1  | `ThermalMonitor` maps all platform statuses correctly                                  | `NONE`/`LIGHT` → NORMAL; `MODERATE` → ELEVATED; `SEVERE`/`CRITICAL` → HOT; `EMERGENCY`/`SHUTDOWN` → CRITICAL |
| T2  | `EnsembleRuntimePolicy` returns Stop on CRITICAL regardless of foreground flag         | Both `isForeground=true` and `isForeground=false` receive `PolicyDecision.Stop`                              |
| T3  | Background HOT → Pause; Foreground HOT → Throttle 2000 ms                              | Correct `PolicyDecision` type and `batchPauseMs` value                                                       |
| T4  | Background battery saver active → Pause (when setting enabled)                         | `pauseEnsembleOnBatterySaver=true` triggers Pause; `false` does not                                          |
| T5  | Background not-charging → Pause (when `ensembleOnlyWhileCharging=true`)                | Pause returned; foreground path returns Proceed under same conditions                                        |
| T6  | `DynamicConcurrencyController`: NORMAL+charging → batchSize=16; ELEVATED+no charge → 6 | Exact `ConcurrencyLimits` values match table                                                                 |

### Worker integration tests

| #   | Test                                                    | Pass condition                                                                                                  |
| --- | ------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| W1  | Ensemble scan stops cleanly on CRITICAL thermal mid-run | Worker returns `Result.success` with `stoppedByPolicy=thermal_critical`; already-written suggestions are intact |
| W2  | Ensemble scan retries on HOT background mid-run         | Worker returns `Result.retry()`; no data loss                                                                   |
| W3  | Batch size shrinks as thermal worsens during scan       | Items processed per DB page match controller output for the thermal level observed at each batch boundary       |
| W4  | Single-active path is unaffected by policy changes      | `runSingleActivePath` uses `SINGLE_ACTIVE_BATCH_SIZE=16` regardless of thermal level                            |

---

## 14. Phase 4 — Non-Negotiable Rules

1. **One gating point** — Workers call `EnsembleRuntimePolicy.evaluate()`, nothing else. No inline "if hot then skip" logic in workers or orchestrators.
2. **Re-evaluate per batch** — Policy is checked before every batch of assets, not only at job startup.
3. **Graceful degradation** — The transition from full throughput to stopped is: NORMAL → small throttle → larger throttle → pause-and-retry → hard stop. No sudden silence.
4. **User-triggered runs bypass soft guards only** — Foreground runs (`isForeground=true`) skip battery/charging checks but are still hard-stopped by CRITICAL thermal.
5. **Single-active path unchanged** — `runSingleActivePath` continues to use a fixed batch size and has no thermal policy. It is not ensemble work.
6. **`ThermalMonitor` is a singleton** — Shared from `PandoraApp`. Workers must not instantiate their own `ThermalMonitor`.

---

## 15. Phase 4 — Done Criteria

Phase 4 is complete when **all** of the following are true:

- [ ] `ThermalMonitor` registers a platform listener on API 29+, reports NORMAL on older devices, and is properly closed on app termination (T1)
- [ ] `EnsembleRuntimePolicy` correctly gates all four thermal levels with the foreground/background distinction (T2, T3)
- [ ] Battery saver and charging guards pause background ensemble work as configured (T4, T5)
- [ ] `DynamicConcurrencyController` returns correct `ConcurrencyLimits` for all thermal/charge combinations (T6)
- [ ] `TagSuggestionWorker.runEnsemblePath` calls policy before every batch and acts on all four decision types (W1, W2, W3)
- [ ] Batch size in the ensemble path is driven by `DynamicConcurrencyController`, not a compile-time constant (W3)
- [ ] The single-active path remains unaffected (W4)
- [ ] `ThermalMonitor` is exposed via `PandoraApp` and not instantiated per-worker

---

## 16. Phase 4 — Task 4: Foreground vs Background Scheduling Refinement

### What changed

| Symbol                                      | File                     | Change                                                                              |
| ------------------------------------------- | ------------------------ | ----------------------------------------------------------------------------------- |
| `KEY_IS_FOREGROUND = "is_foreground"`       | `AiIndexScheduler.kt`    | New `const val` — WorkManager input-data key                                        |
| All seven `OneTimeWorkRequestBuilder` calls | `AiIndexScheduler.kt`    | Added `.setInputData(workDataOf(KEY_IS_FOREGROUND to forceRun))`                    |
| `RebuildFusedIdentityWorker` builder        | `AiIndexScheduler.kt`    | `.setInputData(workDataOf(KEY_IS_FOREGROUND to true))` — always foreground          |
| `doWork()`                                  | `TagSuggestionWorker.kt` | Reads `inputData.getBoolean(KEY_IS_FOREGROUND, false)`, passes to `runEnsemblePath` |
| `runEnsemblePath(isForeground: Boolean)`    | `TagSuggestionWorker.kt` | New parameter threaded to `EnsembleRuntimePolicy.evaluate(isForeground = ...)`      |

### Behaviour contract

- `isForeground = true` (user-triggered run): battery-saver and charging guards are bypassed; CRITICAL thermal still triggers hard stop.
- `isForeground = false` (periodic background work): all guards active — battery saver, charging state, and full thermal ladder.

---

## 17. Phase 4 — Task 5: Resumable Checkpointing

### New files

| File                                | Purpose                                                          |
| ----------------------------------- | ---------------------------------------------------------------- |
| `worker/EnsembleCheckpoint.kt`      | `EnsembleRunType` enum + `EnsembleCheckpoint` data class         |
| `worker/EnsembleCheckpointStore.kt` | SharedPreferences-backed store, namespaced per `EnsembleRunType` |

### Checkpoint fields

```
EnsembleCheckpoint(
    runType, lastProcessedId, offset, processedCount, suggestionsWritten,
    totalItems, startedAt, executionMode, participatingModelIds,
    batchNumber, pauseReason
)
```

### Validity rule

A loaded checkpoint is **discarded** (fresh scan from offset 0) when either:

- `checkpoint.executionMode != config.pipelineMode.name` — pipeline mode changed since last run
- `checkpoint.participatingModelIds != config.enabledSceneModelIds.sorted().joinToString(",")` — model set changed

### Save/restore points in `TagSuggestionWorker.runEnsemblePath`

| Event                   | Action                                                                                        |
| ----------------------- | --------------------------------------------------------------------------------------------- |
| Worker starts           | Load and validate checkpoint; restore `offset`/`processedCount`/`suggestionsWritten` if valid |
| End of each batch       | Save checkpoint                                                                               |
| `PolicyDecision.Pause`  | Save checkpoint with `pauseReason`, return `Result.retry()`                                   |
| `PolicyDecision.Stop`   | Save checkpoint with `pauseReason`, return `Result.success(stoppedByPolicy=...)`              |
| Scan completes normally | Clear checkpoint                                                                              |

### `PandoraApp` additions

```kotlin
lateinit var ensembleCheckpointStore: EnsembleCheckpointStore
// onCreate: ensembleCheckpointStore = EnsembleCheckpointStore(this)
```

---

## 18. Phase 4 — Task 6: Model Failure Containment and Cooldown

### Problem

Before this task, a model that crashed repeatedly during an ensemble scan would:

1. Keep failing on every subsequent asset, burning CPU/NPU inside the catch block.
2. Apply a reliability penalty on each failure — potentially crashing its weight to zero — without any backoff.
3. Never self-heal within a single worker invocation.

### Solution: `EnsembleModelCooldownStore`

**File:** `ml/policy/EnsembleModelCooldownStore.kt`

Per-model-ID consecutive failure tracker backed by SharedPreferences (`"pandora_ensemble_cooldowns"`).

| Method                                | Behaviour                                                                                            |
| ------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `recordFailure(modelId): Boolean`     | Increments counter; sets 30-minute cooldown window if `count >= 3`; returns `true` on cooldown entry |
| `isCooledDown(modelId): Boolean`      | Returns `true` while `System.currentTimeMillis() < coolUntil`                                        |
| `getCooldownReason(modelId): String?` | Human-readable reason for UI / diagnostics; `null` if not cooled down                                |
| `clearFailures(modelId)`              | Resets counter + window after a successful runner inference                                          |
| `clearAll()`                          | Clears all model state (full index rebuild, model-set reset)                                         |

### Constants

| Constant               | Value  | Meaning                              |
| ---------------------- | ------ | ------------------------------------ |
| `COOLDOWN_THRESHOLD`   | 3      | Consecutive failures before cooldown |
| `COOLDOWN_DURATION_MS` | 30 min | How long the model is skipped        |

### Distinction from `ModelManager` failure tracking

| Store                                       | Granularity           | Guard type                                               |
| ------------------------------------------- | --------------------- | -------------------------------------------------------- |
| `ModelManager.hasTooManyFailures(category)` | Per `ModelCategory`   | Category-level crash-loop guard for installation/loading |
| `EnsembleModelCooldownStore`                | Per model manifest ID | Runtime skip during ensemble inference                   |

These two systems are independent and serve different failure modes.

### Wiring in `SceneEnsembleOrchestrator`

```kotlin
// Before every runner:
if (cooldownStore.isCooledDown(runner.modelId)) {
    Log.d(TAG, "Skipping cooled-down model ${runner.modelId} for $assetId — $reason")
    continue
}

// On successful evidence:
cooldownStore.clearFailures(runner.modelId)

// On exception — replaces bare log:
val nowCooledDown = cooldownStore.recordFailure(runner.modelId)
if (nowCooledDown) Log.w(TAG, "Model ${runner.modelId} entered cooldown — skipped ~30 min")
```

`cooldownStore` is a new constructor parameter on `SceneEnsembleOrchestrator`, supplied by `PandoraApp.ensembleModelCooldownStore` at instantiation time in `TagSuggestionWorker`.

### Testing checklist (Task 6)

| #   | Test                                                       | Pass condition                                                                                              |
| --- | ---------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| C1  | Three consecutive failures trigger cooldown                | `recordFailure` returns `true` on third call; `isCooledDown` returns `true` immediately after               |
| C2  | Cooled-down model is skipped for remaining assets in batch | Runner loop continues past `isCooledDown=true` without calling `runForAsset`                                |
| C3  | Successful run clears failure counter                      | After `clearFailures`, `isCooledDown` returns `false` and `recordFailure` count resets to 1 on next failure |
| C4  | Cooldown expires after 30 min                              | `isCooledDown` returns `false` once wall clock exceeds `coolUntil`                                          |
| C5  | `getCooldownReason` returns `null` when not cooled down    | No spurious reason strings in diagnostics                                                                   |
| C6  | Single failing model does not stop other runners           | Remaining runners still fire; partial evidence is fused normally                                            |

---

## §19 — Phase 4, Task 7: Policy-Aware Batch Sizing

### Motivation

Batch size was previously fixed (thermal + charging only). Face-detection pipelines are meaningfully heavier than scene-only runs, and background jobs should never compete for main-thread memory with foreground UI. The same batch that is safe on a charging flagship becomes a jank source on a mid-range device in the background.

### Design

`DynamicConcurrencyController.limitsFor()` now accepts three additional Boolean parameters:

| Parameter       | Meaning                                                                                         |
| --------------- | ----------------------------------------------------------------------------------------------- |
| `isForeground`  | Worker is in the foreground service (user-visible)                                              |
| `hasFaceModels` | At least one face-detection or face-recognition model is enabled in the current ensemble config |
| `isLowMemory`   | `ActivityManager.MemoryInfo.lowMemory` is `true`                                                |

The thermal/charging baseline is extracted into a private `baseFor(thermal, isCharging)` helper that returns an unmodified `ConcurrencyLimits`. Modifiers are applied **multiplicatively** to `batchSize` only; `parallelism` is unchanged:

| Condition                                              | Batch modifier |
| ------------------------------------------------------ | -------------- |
| `!isForeground`                                        | × 0.75         |
| `hasFaceModels`                                        | × 0.75         |
| `isForeground && !hasFaceModels && NORMAL && charging` | × 1.25         |
| `isLowMemory`                                          | × 0.5, min 1   |

Modifiers are applied in order; the result is rounded down and clamped to at least 1.

### Worker wiring (`TagSuggestionWorker`)

```kotlin
val memInfo = android.app.ActivityManager.MemoryInfo().also {
    (applicationContext.getSystemService(Context.ACTIVITY_SERVICE)
        as ActivityManager).getMemoryInfo(it)
}
val limits = DynamicConcurrencyController.limitsFor(
    thermal       = app.thermalMonitor.currentLevel,
    isCharging    = isCharging,
    isForeground  = isForeground,
    hasFaceModels = config.isFaceEnsembleReady,
    isLowMemory   = memInfo.lowMemory,
)
```

`config.isFaceEnsembleReady` is an existing field that is `true` when at least one face detector or recognizer in the config is ready for use.

### Testing checklist (Task 7)

| #   | Test                                                | Pass condition                                                                    |
| --- | --------------------------------------------------- | --------------------------------------------------------------------------------- |
| B1  | Background + face models → batch ≤ 56 % of baseline | Two × 0.75 modifiers compound to 0.5625; max batch 4 on NORMAL charging becomes 2 |
| B2  | Low-memory flag halves batch, minimum 1             | `isLowMemory=true` with baseline 1 → still returns 1                              |
| B3  | Ideal foreground scene-only charging → batch × 1.25 | Baseline 4 becomes 5; no other modifiers apply                                    |
| B4  | Face + low-memory → 0.75 × 0.5 = 0.375, min 1       | Baseline 3 → 1                                                                    |
| B5  | `parallelism` unchanged by any modifier             | All modifier paths leave `limits.parallelism` equal to `baseFor()` value          |

---

## §20 — Phase 4, Task 8: Calibration Hooks

### Motivation

Fusion thresholds, agreement bonuses, and reliability-weight strength were hard-coded in `SuggestionFusionEngine`. Operators need a way to tune these values per-deployment without recompiling (e.g., lower category thresholds for a photography use-case, or dampen reliability weights for a freshly installed model set).

### New files

**`ml/config/FusionCalibration.kt`**

```kotlin
data class FusionCalibration(
    val version: Int = 0,
    val categoryThresholdMultipliers: Map<String, Float> = emptyMap(),
    val ambiguityMarginMultiplier: Float = 1.0f,
    val agreementBonusScale: Float = 1.0f,
    val reliabilityWeightStrength: Float = 1.0f,
    val identityThresholdMultiplier: Float = 1.0f,
) {
    companion object {
        val DEFAULT = FusionCalibration()
    }
}
```

- `categoryThresholdMultipliers`: per-category override of the global `minFusedConfidence` floor (e.g., `"people" to 0.85f`)
- `ambiguityMarginMultiplier`: scales the margin band before the "ambiguous" label is emitted
- `agreementBonusScale`: multiplies the agreement bonus that is added when multiple models agree
- `reliabilityWeightStrength`: at 0.0 all model weights become 1.0 (equal); at 1.0 full Bayesian weight; values > 1 amplify weight drift
- `identityThresholdMultiplier`: scales the face-identity acceptance threshold

**`ml/config/FusionCalibrationStore.kt`** — SharedPreferences key `"pandora_fusion_calibration"`, stores JSON of the data class. Provides `load()`, `save(cal)`, `reset()`.

### Modified files

**`SuggestionFusionEngine.fuse()`** gains a `calibration: FusionCalibration = FusionCalibration.DEFAULT` parameter. Applied at:

```
effectiveThreshold = max(globalFloor, minFusedConfidence × catThreshMult)
effectiveWeight    = 1.0f + (rawWeight − 1.0f) × reliabilityWeightStrength
bonus              = rawBonus × calibration.agreementBonusScale
ambiguityMargin    = rawMargin × calibration.ambiguityMarginMultiplier
```

**`SceneEnsembleOrchestrator`** gains a `calibration: FusionCalibration = FusionCalibration.DEFAULT` constructor parameter that is threaded into every `fusionEngine.fuse()` call.

**`TagSuggestionWorker`** loads calibration once at the start of `runEnsemblePath()`:

```kotlin
val calibration = app.fusionCalibrationStore.load()
```

and passes it to the orchestrator constructor.

### Testing checklist (Task 8)

| #   | Test                                                           | Pass condition                                                                    |
| --- | -------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| CA1 | `reliabilityWeightStrength = 0.0` → all model weights become 1 | Engine produces equal-weight average regardless of per-model reliability scores   |
| CA2 | `agreementBonusScale = 0.0` → no bonus applied                 | Score for a tri-model-agree suggestion equals raw mean without bonus              |
| CA3 | Category multiplier 0.85 lowers effective threshold            | A suggestion above 0.85 × floor but below 1.0 × floor passes with override        |
| CA4 | `DEFAULT` calibration is identity (no observable change)       | Engine output identical to pre-calibration baseline with all defaults             |
| CA5 | `FusionCalibrationStore.reset()` restores DEFAULT              | After `save(custom)` then `reset()`, `load()` returns `FusionCalibration.DEFAULT` |

---

## §21 — Phase 4, Task 9: Run Versioning

### Motivation

After a model update or calibration change, cached fused suggestions may be stale. The system needs to record which model versions and calibration version produced each batch of fused results so that repository layers can decide when to invalidate.

### New files

**`ml/ensemble/EnsembleRunManifest.kt`**

```kotlin
data class EnsembleRunManifest(
    val runId: String,
    val pipelineMode: String,
    val participatingModelIds: List<String>,
    val modelVersions: String,          // CSV of "id:versionKey"
    val calibrationVersion: Int,
    val reliabilityStatsTimestamp: Long,
    val runStartedAt: Long,
    val runCompletedAt: Long? = null,
    val assetsProcessed: Int = 0,
    val suggestionsWritten: Int = 0,
)
```

**`ml/ensemble/EnsembleRunManifestStore.kt`** — SharedPreferences key `"pandora_ensemble_run_manifests"`. Stores a rolling window of the last 5 manifests.

- `save(manifest)` — persists a new manifest (replaces oldest if window full)
- `complete(runId, completedAt, assetsProcessed, suggestionsWritten)` — patches the matching manifest in-place
- `getLatest()` — returns the most recently saved manifest, or `null`
- `getAll()` — returns all manifests sorted newest-first
- `isCompatible(manifest, currentPipelineMode, currentModelIds, currentCalibrationVersion)` — returns `true` if pipeline mode, model ID set, and calibration version all match the current config

### Worker wiring

```kotlin
// ── Before the main while loop ──────────────────────────────────────────
val workerRunId = UUID.randomUUID().toString()
app.ensembleRunManifestStore.save(EnsembleRunManifest(
    runId                     = workerRunId,
    pipelineMode              = currentMode,
    participatingModelIds     = currentModelIds,
    modelVersions             = modelVersionCsv,    // "id:versionKey" CSV built from installed models
    calibrationVersion        = calibration.version,
    reliabilityStatsTimestamp = System.currentTimeMillis(),
    runStartedAt              = startedAt,
))

// ── After the main while loop (success path) ────────────────────────────
app.ensembleRunManifestStore.complete(
    runId              = workerRunId,
    completedAt        = System.currentTimeMillis(),
    assetsProcessed    = processed,
    suggestionsWritten = suggestionsWritten,
)
```

`PandoraApp` exposes `ensembleRunManifestStore` as a `lateinit` field initialized in `onCreate`.

### Testing checklist (Task 9)

| #   | Test                                                            | Pass condition                                                                         |
| --- | --------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| RV1 | Manifest is created before the first asset is processed         | `getLatest()` returns a manifest with non-null `runStartedAt` even if worker is killed |
| RV2 | `complete()` patches the right manifest in the rolling window   | Only the matching `runId` manifest gains `runCompletedAt`; others unmodified           |
| RV3 | Rolling window caps at 5                                        | After 6 saves, `getAll()` returns exactly 5 manifests; oldest is evicted               |
| RV4 | `isCompatible` returns `false` after model update               | Manifest saved before update fails check once `currentModelIds` changes                |
| RV5 | `isCompatible` returns `false` after calibration version bumped | Manifest with `calibrationVersion = 0` fails once store saves version 1                |
| RV6 | `modelVersionCsv` is deterministically sorted                   | Same model set produces identical CSV string regardless of insertion order             |

---

## §22 — Phase 4, Task 10: Ensemble Guardrails UI

### Motivation

"Enable all compatible models" is a powerful option that can unknowingly cause repeated policy throttles or pauses on constrained devices. The user needs passive, non-alarming feedback about the cost and status of their ensemble configuration without the system ever auto-disabling it.

### New file: `EnsembleBlockedStore`

**`ml/policy/EnsembleBlockedStore.kt`** — SharedPreferences key `"pandora_ensemble_blocked"`.

- `consecutiveBlockCount: Int` — incremented on each Stop or Pause decision
- `lastBlockReason: String?` — stores the policy reason string for display
- `isSuggestingFallback: Boolean` — `true` when `consecutiveBlockCount >= 3`
- `recordBlock(reason: String)` — increments count, saves reason
- `recordSuccessfulStart()` — resets count to 0

Worker integration: `recordBlock(decision.reason)` is called on every `Stop` and `Pause` decision before returning; `recordSuccessfulStart()` is called the first time a run successfully passes through policy (guarded by `hasReportedSuccessfulStart` flag).

### New types in `ModelManagerViewModel`

```kotlin
enum class EnsembleCostHint { LOW, MEDIUM, HIGH }
// LOW  = 1 participating model
// MEDIUM = 2–3 participating models
// HIGH = 4+ participating models

enum class EnsemblePolicyState { NORMAL, THROTTLED, PAUSED, STOPPED }

data class EnsembleStatusSummary(
    val enabledSceneCount: Int,
    val enabledDetectorCount: Int,
    val enabledRecognizerCount: Int,
    val costHint: EnsembleCostHint,
    val policyState: EnsemblePolicyState,
    val consecutiveBlockCount: Int,
    val isSuggestingFallback: Boolean,
)
```

`computeEnsembleStatus()` reads live thermal level, `PowerManager.isPowerSaveMode`, battery percentage, charger state, `ensembleOnlyWhileCharging` setting, and `EnsembleBlockedStore.consecutiveBlockCount` to determine `EnsemblePolicyState`. It is called inside `refresh()`.

ViewModel constructor gains `thermalMonitor: ThermalMonitor` and `ensembleBlockedStore: EnsembleBlockedStore`, supplied by `PandoraApp` fields in the `ViewModelProvider.Factory`.

### UI: `EnsembleStatusSection` composable

Rendered inside `ExecutionModeSection` when `isEnsemble == true` and `status != null`. Contains:

1. **Participation chips** — one chip per enabled model category, joined by " · " separator (e.g., `Scene · Face · Identity`)
2. **Cost hint chip** — colored badge: LOW = green, MEDIUM = amber, HIGH = red
3. **Policy state row** — icon + label row, red text when `STOPPED`
4. **Fallback hint text** — non-alarmist single-line note shown only when `isSuggestingFallback`, e.g. _"Ensemble has been paused repeatedly — consider enabling fewer models."_

The system never auto-disables ensemble mode; the UI only suggests.

### Testing checklist (Task 10)

| #   | Test                                                                 | Pass condition                                                                          |
| --- | -------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| G1  | Three consecutive blocks → `isSuggestingFallback = true`             | After three `recordBlock()` calls, `isSuggestingFallback` is `true`                     |
| G2  | `recordSuccessfulStart()` resets the block counter                   | After `recordBlock` × 2 then `recordSuccessfulStart()`, count = 0 and flag = false      |
| G3  | `computeEnsembleStatus()` reflects CRITICAL thermal as PAUSED        | `ThermalMonitor.currentLevel == CRITICAL` maps to `EnsemblePolicyState.PAUSED`          |
| G4  | `EnsembleCostHint.HIGH` when 4+ models enabled                       | Setting four models enabled produces `costHint = HIGH` in `EnsembleStatusSummary`       |
| G5  | Fallback hint text is visible in UI only when `isSuggestingFallback` | Composable renders hint row iff `status.isSuggestingFallback == true`; hidden otherwise |
| G6  | System never auto-disables ensemble mode                             | No code path in worker, VM, or screen calls `setPipelineMode(SINGLE)` automatically     |

---

## §23 — Phase 4, Task 11: End-to-End Sanity Checks

These checks validate complete data flows rather than individual units. Each flow must be exercisable in a local test environment without a device.

### A. Scene ensemble path

| #   | Verification                                                       | Expected outcome                                                                                                                                         |
| --- | ------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SE1 | Zero enabled scene models                                          | Orchestrator returns immediately; no fused outputs written; worker result = `SUCCESS` (nothing to do)                                                    |
| SE2 | One enabled model in ensemble mode                                 | Produces valid `FusedSceneSuggestion` records; no crash from missing second opinion                                                                      |
| SE3 | Multiple models produce grouped canonical suggestions              | `SuggestionFusionEngine` groups by category+label; each output has exactly one canonical form                                                            |
| SE4 | Rejected suggestions stay suppressed                               | After a rejection is recorded, the same (target, label) pair does not reappear in subsequent fused output                                                |
| SE5 | Accepted suggestions reinforce correct learning path               | Acceptance increments the correct model's reliability numerator in `ReliabilityStatsStore`                                                               |
| SE6 | Fused outputs rebuild correctly after threshold/reliability change | Triggering a rebuild with altered calibration produces a different (or same, if within margin) output set without corrupting accepted/rejected decisions |

### B. Face ensemble path

| #   | Verification                                                     | Expected outcome                                                                                  |
| --- | ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| FE1 | Zero detectors configured                                        | Face pipeline branch exits cleanly; no `NullPointerException` or empty-list crash                 |
| FE2 | Overlapping detector boxes merge into one fused face             | IoU-based merge produces single `FusedFace`; both raw detections consumed                         |
| FE3 | Duplicate embeddings not generated from duplicate raw detections | De-duplication pass removes embeddings whose source boxes were merged; count matches merged faces |
| FE4 | Identity fusion suppresses ambiguous matches                     | Matches below identity threshold are not emitted as suggestions; `ambiguousCount` incremented     |
| FE5 | Accepted/rejected identity suggestions update expected state     | Acceptance links face cluster to person; rejection marks cluster as `REJECTED_IDENTITY`           |
| FE6 | Existing named-people tags remain the canonical identity source  | A rebuild never overwrites a `confirmedPerson` tag; confirmed identity survives                   |

### C. Runtime policy path

| #   | Verification                                               | Expected outcome                                                                                                                       |
| --- | ---------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| RP1 | Background work pauses in battery saver when configured    | `EnsembleRuntimePolicy` returns `Pause` when `powerSave=true` and `pauseInBatterySaver=true`                                           |
| RP2 | Background ensemble work requires charging when configured | Policy returns `Stop` when `isCharging=false` and `ensembleOnlyWhileCharging=true`                                                     |
| RP3 | Thermal transitions reduce concurrency                     | `DynamicConcurrencyController` returns lower `batchSize` as thermal level escalates                                                    |
| RP4 | HOT/CRITICAL thermal pauses or stops safely                | Worker respects `Pause`/`Stop` decision; no assets processed after decision is applied                                                 |
| RP5 | Resumed runs continue from checkpoint, not from zero       | After pause and resume, `checkpoint.nextAssetOffset` is the value saved before pause; previously processed assets are not re-processed |

### D. Failure path

| #   | Verification                                      | Expected outcome                                                                               |
| --- | ------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| FP1 | One failed model does not fail the whole run      | Exception from one `ModelRunner.runForAsset()` is caught; remaining runners fire normally      |
| FP2 | Repeated failures cause cooldown                  | `ModelCooldownStore.recordFailure()` returns `true` on the third consecutive failure           |
| FP3 | Cooled-down model is skipped until eligible again | `isCooledDown()` returns `true` and runner is bypassed; returns `false` after cooldown expires |
| FP4 | Diagnostics surface failure/cooldown state        | `EnsembleStatusSummary` reflects `policyState = STOPPED` or `PAUSED`; `lastBlockReason` is set |

### E. Maintenance/rebuild path

| #   | Verification                                                         | Expected outcome                                                                                                        |
| --- | -------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| MP1 | Rebuild fused scene suggestions preserves manual tags and rejections | After rebuild, rows with `source = USER` are unchanged; no accepted/rejected decisions are lost                         |
| MP2 | Rebuild fused identity suggestions preserves confirmed people        | `confirmedPerson` associations survive a full identity rebuild                                                          |
| MP3 | Recalculating reliability does not wipe user decisions               | `ReliabilityStatsStore` recalculation reads from event log; does not touch `FusedSceneSuggestion.userDecision`          |
| MP4 | Stale fused runs are replaced or marked correctly                    | `EnsembleRunManifestStore.isCompatible()` returns `false` for stale manifests; corresponding fused rows are invalidated |

---

## §24 — Phase 4, Task 12: Explicit Invariants

These invariants must be enforced in code where feasible and validated by tests. They are the load-bearing constraints of the entire pipeline; a violation here means the app is lying to the user.

### Invariant table

| ID    | Invariant                                                                        | Enforcement point                                                                                                            |
| ----- | -------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| INV-1 | Raw evidence must never be shown directly as final UI suggestions                | `SuggestionRepository` only reads from the fused-suggestion table, never from the raw-evidence table                         |
| INV-2 | Fused suggestions must always be canonicalized before display                    | `SuggestionFusionEngine.fuse()` always applies the canonicalization pass before returning results; no raw label escapes      |
| INV-3 | One fused face must not map to duplicate simultaneous final identity suggestions | De-duplication enforced in `FaceIdentityFusionEngine` before writing; unique constraint on `(assetId, faceClusterId)` in DB  |
| INV-4 | A rejected fused suggestion must not reappear unchanged for the same target      | `SuggestionRepository.getForAsset()` filters out rows with `userDecision = REJECTED`                                         |
| INV-5 | Ensemble mode must never silently fall back to single-active mode                | No code path calls `setPipelineMode(SINGLE)` without explicit user action; `EnsembleBlockedStore` only surfaces a suggestion |
| INV-6 | Stale checkpoints must not resume against incompatible model/config versions     | Worker calls `EnsembleRunManifestStore.isCompatible()` before resuming; mismatch discards checkpoint and starts fresh        |
| INV-7 | User tags, user rejections, and confirmed identities must survive rebuilds       | Rebuild writes only to rows with `source != USER`; user-sourced rows are read-only during rebuild                            |

### Test coverage requirements

Each invariant must have at least one test that demonstrates a violation attempt is blocked:

- **INV-1**: Test that `SuggestionRepository.getForAsset()` never returns a row from the raw-evidence table
- **INV-2**: Inject an un-canonicalized label string into `fuse()`; assert output label matches canonical form
- **INV-3**: Feed two identical raw detections; assert only one `FusedIdentitySuggestion` is produced
- **INV-4**: After `recordRejection(assetId, label)`, assert `getForAsset(assetId)` does not include that label
- **INV-5**: Trigger every policy `Stop` and `Pause` path; assert `getPipelineMode()` still returns `ENSEMBLE` after each
- **INV-6**: Save a manifest with `modelIds=[A,B]`; call resume with `modelIds=[A,C]`; assert checkpoint is discarded
- **INV-7**: Run a full rebuild; assert all rows where `source = USER` have identical values before and after

---

## §25 — Phase 4, Task 13: Metrics and Logging for Runtime Sanity

### Purpose

Logs must explain _why_ a run slowed or stopped. User-facing messaging stays compact; internal diagnostics preserve full detail.

### Required logging events

| Event                        | Log tag                        | Level | Fields                                                                                                                |
| ---------------------------- | ------------------------------ | ----- | --------------------------------------------------------------------------------------------------------------------- |
| Ensemble run start           | `EnsembleWorker`               | INFO  | `runId`, `pipelineMode`, `participatingModelIds`, `calibrationVersion`, `totalAssets`                                 |
| Ensemble run finish          | `EnsembleWorker`               | INFO  | `runId`, `assetsProcessed`, `suggestionsWritten`, `durationMs`                                                        |
| Ensemble run paused          | `EnsembleWorker`               | WARN  | `runId`, `pauseReason`, `pausedAtOffset`, `consecutiveBlockCount`                                                     |
| Ensemble run resumed         | `EnsembleWorker`               | INFO  | `runId`, `resumeFromOffset`                                                                                           |
| Thermal state change         | `ThermalMonitor`               | INFO  | `previousLevel`, `newLevel`, `timestamp`                                                                              |
| Concurrency change           | `DynamicConcurrencyController` | DEBUG | `thermal`, `isCharging`, `isForeground`, `hasFaceModels`, `isLowMemory`, `resultingBatchSize`, `resultingParallelism` |
| Batch size change (same run) | `EnsembleWorker`               | DEBUG | `runId`, `previousBatchSize`, `newBatchSize`, `trigger`                                                               |
| Model cooldown activated     | `SceneEnsembleOrchestrator`    | WARN  | `modelId`, `failureCount`, `coolUntil`                                                                                |
| Per-model failure            | `SceneEnsembleOrchestrator`    | WARN  | `modelId`, `assetId`, `exceptionType`, `consecutiveFailures`                                                          |
| Fused rebuild completed      | `SuggestionRepository`         | INFO  | `rebuiltCount`, `preservedUserDecisions`, `invalidatedStaleCount`, `durationMs`                                       |

### Logging rules

- All log calls route through the existing `PandoraLog` wrapper (or equivalent internal abstraction) — no bare `android.util.Log` in orchestration code.
- `DEBUG` events are stripped in release builds via ProGuard/R8 rules already defined in `proguard-rules.pro`.
- Log messages must not include personally identifiable information (image paths that reveal user names, face-cluster IDs linked to real names, etc.).
- Every `WARN`-level event must include enough context to reproduce or explain the condition without re-running the job.

---

## §26 — Phase 4, Task 14: Maintenance and Recovery Actions

### Scope

These actions target only runtime and orchestration state. They must not touch any row, preference, or file whose ownership belongs to the user (tags, decisions, confirmed people).

### Required actions

#### Clear Paused Ensemble Runs

- **What it does**: Resets `EnsembleBlockedStore` (sets `consecutiveBlockCount = 0`, clears `lastBlockReason`).
- **What it does NOT do**: Does not delete `EnsembleRunManifest` records; does not touch checkpoints.
- **When to surface**: Settings → AI / Maintenance.

#### Reset Model Cooldowns

- **What it does**: Calls `ModelCooldownStore.clearFailures(modelId)` for all cooled-down models.
- **What it does NOT do**: Does not re-enable disabled models; does not clear failure count for models still within their cooldown eligibility window.
- **When to surface**: Settings → AI / Maintenance, or inline in the model management screen alongside a cooled-down model's status row.

#### Resume Eligible Background AI Work

- **What it does**: Enqueues a new `TagSuggestionWorker` run (replacing any existing enqueued-but-not-started run) so eligible work begins on the next scheduler opportunity.
- **What it does NOT do**: Does not bypass policy checks; the rescheduled worker still obeys thermal/charging/battery-saver constraints.
- **When to surface**: Settings → AI / Maintenance.

#### Discard Stale Fused Outputs _(optional)_

- **What it does**: Deletes `FusedSceneSuggestion` rows where `source != USER` and the corresponding `EnsembleRunManifest` is incompatible with the current model set.
- **What it does NOT do**: Does not delete rows with `source = USER`; does not delete confirmed identities.
- **When to surface**: Only surface if stale manifests are detected; otherwise keep hidden.
- **Risk level**: Medium — triggers a full rebuild on next worker run. Requires an explicit confirmation dialog.

### Safety requirements for all actions

- Each action must log what it cleared (IDs, counts) at INFO level before executing.
- Each action must be a single idempotent operation: calling it twice produces the same result as calling it once.
- None of these actions may trigger a `WorkManager` cancel for a currently-running worker; they only affect state that the next worker run reads.

---

## §27 — Phase 4, Task 15: UI Sanity Surfaces

### Design principle

Do not build a cockpit of blinking lights. Make the app legible. One concise status block per surface; no duplicate information between surfaces.

### Model Management screen additions

Extend the existing `EnsembleStatusSection` composable (introduced in §22) with the following information when ensemble mode is active:

| Field                         | Display                                                                                                                                               |
| ----------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| Ensemble active / paused      | Chip: green "Active" or amber "Paused" / red "Stopped"                                                                                                |
| Pause reason (when paused)    | Single-line text below the chip; one of: "Charging required", "Battery saver", "Thermal protection", "No enabled models", "Model cooldown saturation" |
| Last successful run timestamp | Compact relative time (e.g., "Last run 2 h ago") sourced from `EnsembleRunManifestStore.getLatest()?.runCompletedAt`; hidden if never run             |

### Maintenance surface additions

Add a dedicated **"AI Maintenance"** subsection inside the existing Settings screen (or within the existing maintenance section, wherever it is currently located). It contains:

- "Clear Paused Ensemble Runs" action button (only visible when `consecutiveBlockCount > 0`)
- "Reset Model Cooldowns" action button (only visible when at least one model is cooled down)
- "Resume Background AI Work" action button (always visible in this section)
- "Discard Stale Fused Outputs" action button (only visible when `EnsembleRunManifestStore` contains at least one incompatible manifest)

Each button shows a one-line subtitle describing what it will clear. Destructive actions (Discard Stale Fused Outputs) require a confirmation dialog before executing.

### What this surface must NOT contain

- No real-time counters that change while the user is watching (no polling updates in maintenance)
- No raw model scores or embedding distances
- No internal IDs (run UUIDs, cluster IDs)
- No "fix everything" mega-button

---

## §28 — Phase 4, Task 16: Testing Checklist

### Functional tests

| #   | Test                                                           | Pass condition                                                                                   |
| --- | -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| FT1 | Thermal policy reduces concurrency as expected                 | Step through all `ThermalLevel` values; `batchSize` strictly non-increasing as level rises       |
| FT2 | CRITICAL thermal state pauses/stops ensemble jobs              | Policy returns `Stop` or `Pause` at CRITICAL; worker respects decision within the same iteration |
| FT3 | Checkpoint resume picks up from correct location               | Save checkpoint at offset 42; resume; first processed asset index is 42                          |
| FT4 | Checkpoint invalidates on incompatible config/model-set change | Change enabled model IDs; `isCompatible()` returns `false`; worker starts fresh (offset = 0)     |
| FT5 | Repeated model failures trigger cooldown                       | Three `recordFailure()` calls in succession; `isCooledDown()` returns `true`                     |
| FT6 | Cooled-down model is excluded from new runs                    | `isCooledDown() = true` → runner is bypassed; `runForAsset` not called                           |
| FT7 | Clearing cooldown restores eligibility                         | After `clearFailures()`, `isCooledDown()` returns `false`; runner fires on next asset            |
| FT8 | Foreground actions obey hard-stop safety rules                 | `isEnsembleAllowedInForeground` constraint respected; no ensemble work on UI thread              |
| FT9 | Fused run version metadata updates correctly                   | `EnsembleRunManifestStore.complete()` patches the correct manifest; fields match worker output   |

### Data tests

| #   | Test                                                                | Pass condition                                                                                     |
| --- | ------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| DT1 | Fused outputs carry run-version metadata                            | Every `FusedSceneSuggestion` row references a valid `pipelineRunId` matching a manifest            |
| DT2 | Checkpoint state persists and resumes correctly                     | Kill worker mid-run; re-enqueue; resume offset equals last saved checkpoint                        |
| DT3 | Recovery actions clear only orchestration state                     | After "Clear Paused Ensemble Runs": `consecutiveBlockCount = 0`; no fused suggestion rows changed  |
| DT4 | Rebuilds remain reproducible under same calibration/runtime version | Two consecutive rebuilds with identical `FusionCalibration` and model set produce identical output |

### UI tests

| #   | Test                                                     | Pass condition                                                                                          |
| --- | -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| UT1 | Pause reasons render cleanly                             | Each `EnsemblePolicyState` produces the correct localized subtitle string in `EnsembleStatusSection`    |
| UT2 | Ensemble cost hint and participation counts are accurate | Enabling 4 models → `HIGH` chip visible; count chips match `EnsembleStatusSummary.enabled*Count` fields |
| UT3 | Fallback suggestion appears only when appropriate        | `isSuggestingFallback = false` → hint row not rendered; `= true` → exactly one hint row rendered        |
| UT4 | Maintenance recovery actions are visible and safe        | Button visibility matches preconditions; destructive button shows confirmation dialog before acting     |

### Regression tests

| #   | Test                                                          | Pass condition                                                                                              |
| --- | ------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| RT1 | Single-active mode still behaves exactly as before            | All Phase 1–3 single-active-path tests pass without modification                                            |
| RT2 | Phase 1/2/3 fused suggestion and identity flows work normally | Scene and identity suggestion flows produce identical outputs; no regressions in acceptance/rejection logic |
| RT3 | Maintenance actions preserve user semantic data               | Run all maintenance/recovery actions; assert zero rows with `source = USER` were modified                   |

---

## §29 — Phase 4, Task 17: Non-Negotiable Implementation Rules

These rules are architectural constraints. They apply to all future implementation work within Phase 4 and must not be relaxed under any circumstances.

1. **Do not silently switch users from ensemble to single-active mode.** The only code path that changes `pipelineMode` to `SINGLE` is an explicit user action in the UI. Background workers, policy evaluators, and maintenance actions may not call `setPipelineMode()`.

2. **Do not ignore thermal state once a run has started.** `ThermalMonitor.currentLevel` must be sampled at least once per batch iteration inside a running worker. A thermal escalation mid-run must be acted upon in the same iteration.

3. **Do not resume checkpoints across incompatible model/config versions.** Before resuming from a saved checkpoint, the worker must call `EnsembleRunManifestStore.isCompatible()`. A mismatch discards the checkpoint and starts fresh (offset = 0).

4. **Do not let one unstable model repeatedly poison the ensemble.** Any model that fails three consecutive times must be cooled down for the remainder of the run window. `ModelCooldownStore.recordFailure()` is the only acceptable mechanism; inline error suppression is not.

5. **Do not wipe user tags, user rejections, or confirmed people during orchestration recovery.** All recovery and maintenance actions operate exclusively on rows, preferences, and files that are owned by the pipeline (not the user). A row with `source = USER` is permanently read-only from the perspective of orchestration code.

6. **Do not expose raw evidence as final UI state.** `SuggestionRepository` is the only provider of suggestion data to the UI layer. Raw evidence tables (model output buffers, embedding caches) must not be referenced in any `ViewModel` or `@Composable`.

7. **Do not let runtime policy live only in UI code.** `EnsembleRuntimePolicy` is authoritative. The UI may read policy-derived state from `EnsembleStatusSummary` to display information, but may not re-derive or override policy decisions independently.

---

## §30 — Phase 4, Task 18: Done Criteria

Phase 4 is complete when **all** of the following are true:

| #   | Criterion                                                                                                                                                           |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Ensemble mode is thermally aware and dynamically throttled — concurrency changes are applied per-batch within a live run                                            |
| 2   | Background and foreground scheduling differ appropriately — batch modifiers for `isForeground`, `hasFaceModels`, and `isLowMemory` are wired through the full stack |
| 3   | Long runs are resumable with safe checkpoints — a killed worker resumes from the correct offset; incompatible configs start fresh                                   |
| 4   | Flaky models are cooled down and isolated — three consecutive failures trigger cooldown; cooled-down models are skipped; cooldown expires automatically             |
| 5   | Fused outputs are versioned and traceable — every fused row traces to an `EnsembleRunManifest`; stale manifests are detectable                                      |
| 6   | Maintenance includes orchestration recovery tools — at least "Clear Paused Runs", "Reset Cooldowns", and "Resume Background Work" are implemented and surfaced      |
| 7   | End-to-end sanity checks cover scene, face, runtime policy, failure, and rebuild paths — all items in §23 have at least one passing test                            |
| 8   | All seven invariants in §24 have passing enforcement tests                                                                                                          |
| 9   | The app remains stable and predictable on the single-active path — all Phase 1–3 regression tests pass                                                              |
| 10  | The seven non-negotiable rules in §29 are verifiably respected — no code path exists that violates them                                                             |
