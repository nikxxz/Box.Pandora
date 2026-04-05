# Box.Pandora

**Version 1.5** · An AI-powered media gallery for Android. Box.Pandora organises your photos and videos on-device, using local machine learning models to understand what's in each image, detect and group people by face, and surface intelligent tag suggestions — all without any data leaving your device.

---

## Table of Contents

1. [What's New in 1.5](#whats-new-in-15)
2. [App Overview](#1-app-overview)
3. [Using AI, ML and Tagging Features](#2-using-ai-ml-and-tagging-features)
4. [Technical Reference](#3-technical-reference)

---

## What's New in 1.5

### Tag Integrity Improvements
- **Tag associations are now preserved when hiding or unhiding a folder.** Previously, toggling a folder's hidden state caused a URI scheme change (`content://` → `file://`) that silently wiped all tag associations for every item in that folder. Three layers of protection were added:
  - `setAlbumsHidden` now calls `scanFileWait` per-file and explicitly transfers all metadata before the sync runs
  - `syncMediaStore` detects URI-scheme transitions and calls `transferMetadata` instead of deleting
  - A file-exists guard skips deletion of DB entries whose physical file still exists during MediaStore re-indexing
- **Empty-scan safety guard.** If `syncMediaStore` returns zero items against a non-empty database (transient MediaStore failure), the entire deletion pass is skipped to prevent a cascade-wipe of tag associations.
- **SQLite variable-limit fix.** Batch deletions are now chunked in groups of 500, preventing `SQLiteException: too many SQL variables` when deleting large sets of URIs.

### Library Health Scanner
A new **Settings → Library → Library Health** screen scans and fixes common database inconsistencies:
- **Stale tag counts** — tag `usage_count` values that no longer match the actual `media_tags` row count
- **Orphaned tag associations** — `media_tags` rows that reference deleted media files
- **Unused tags** — tags with no associated images (optionally delete them)
- **Duplicate tag names** — tags sharing the same normalised name (merged on fix, preserving all associations)

All issues show an individual **Fix** button. A **Fix All** button applies every available repair in one pass. The screen auto-scans on open and shows a result summary after each fix.

### Show Filenames in Folder View
A subtitle icon (caption lines) in the folder-view header toggles filename labels on every thumbnail. The state is local to the folder screen session — no persistence between navigations. The icon is hidden during multi-select to keep the toolbar uncluttered.

### Move/Copy Dialog Fix
Selecting a destination folder in the copy or move dialog no longer crashes the app. The dialog now dismisses immediately on selection before the async file operation begins.

### New App Icon
The launcher icon has been updated to the crystal-prism artwork across all density buckets (mdpi → xxxhdpi) and the adaptive icon layers (foreground, background, monochrome for Android 13 themed icons).

---

# 1. App Overview

## What It Does

Box.Pandora is a privacy-first, fully offline media manager. It reads from your device's MediaStore, organises your library into albums and tags, and optionally runs local AI models to analyse your photos, detect faces, and recommend tags — all computed on-device.

There is no cloud sync, no account required, and no images are ever transmitted off your device.

## Core Features

### Media Browsing
- Browse your entire photo and video library grouped by album/folder
- Grid view with configurable thumbnail density
- Full-screen media viewer with pinch-to-zoom and video playback (ExoPlayer)
- Favourites collection (star any photo or video)
- Search by filename, tag, or media type
- **Show Filenames toggle** in folder view — tap the subtitle icon to overlay each thumbnail with its filename

### Tagging System
- Apply unlimited tags to any photo or video
- Tags are organised by category: `people`, `place`, `style`, `clothing`, `pose`, `animal`, `object`, `mood`, `misc`
- Each tag has a customisable colour and icon
- Multi-select batch tagging across any number of files
- Tag aliases — map alternative names to a canonical tag
- Tag co-occurrence tracking — related tags are suggested when applying a tag
- Tag history and audit log of all changes

### Folder Management
- **Hide / Unhide folders** — adding a `.nomedia` file hides a folder from the system gallery; Box.Pandora preserves all tags and metadata through this transition
- **Show hidden folders** toggle in Library settings reveals hidden folders within the app
- Copy, move, rename, and delete folders with conflict resolution

### AI Tag Suggestions (Scene Tagging)
- A lightweight on-device model (MobileNet V3, ~8 MB) analyses each image and understands its visual content
- The app learns from the tags you already apply and builds a personal vocabulary of what each tag looks like
- New photos are scored against this vocabulary and suggested tags are shown for review
- You accept or reject each suggestion; rejections are remembered and never re-suggested
- Works fully offline once the model is installed

### People & Face Recognition
- A face detector (YuNet ~345 KB, or SCRFD ~17 MB) scans your photos for faces
- A face recognition model (ArcFace ResNet-100 FP16 ~120 MB) creates a unique signature for each face
- Similar faces are automatically grouped into clusters
- You name a cluster to create a person tag (e.g., "Alice")
- The app then learns Alice's appearance and suggests unidentified faces that likely belong to her
- All face recognition runs entirely on-device

### Library Health
- **Settings → Library → Library Health** scans your tag database for inconsistencies and lets you fix them individually or all at once
- Covers stale counts, orphaned associations, unused tags, and duplicate names

### Visual Similarity Search *(Phase 3 — planned)*
- Find photos visually similar to any image in your library
- Uses the same scene embeddings as tag suggestions

### Settings and Controls
- Toggle each AI feature independently
- Set a confidence threshold (Low / Medium / High) to control how aggressively tags are suggested
- Restrict model downloads to Wi-Fi only
- Enable or disable background indexing (charging-only by default)
- Maintenance tools: repair stale data, rebuild individual pipeline stages, or wipe and rescan everything

---

# 2. Using AI, ML and Tagging Features

## Getting Started

AI features are **disabled by default**. To enable them:

1. Open **Settings → Tagging & AI**
2. Enable **Scene Suggestions** to activate tag suggestions
3. Enable **People Suggestions** to activate face recognition
4. Tap **Scan New Media** to start indexing your library in the background

The app will only run background indexing when the device is not in battery-saver mode. User-triggered actions (Scan New Media, Repair, Rebuild) run immediately regardless of battery state.

---

## Scene Tagging (Tag Suggestions)

### How It Works

Scene tagging runs in three steps, each handled by a background worker:

1. **Scene Indexing** — The app embeds every unindexed photo into a 512-dimensional vector using the scene model. This describes "what the image looks like" as a point in high-dimensional space.

2. **Prototype Building** — For every tag you have applied to at least one photo, the app computes a mean embedding ("prototype") representing the average appearance of images with that tag.

3. **Suggestion Scoring** — Each embedded photo is compared to all prototypes using cosine similarity. If a photo is close enough to a prototype (above your confidence threshold), that tag is suggested.

### Building Your Vocabulary

The suggestion system is **learning-based, not rule-based**. It does not come with a pre-defined list of categories. Instead:

- The more tags you apply manually, the more the app learns
- Each accepted suggestion further reinforces the prototype
- Rejected suggestions are permanently suppressed for that photo

To get useful suggestions quickly, start by tagging 5–10 representative photos for each tag you care about, then run **Scan New Media**. After the pipeline completes, go to **Settings → Tagging & AI → Rebuild Tag Suggestions** to generate a fresh batch.

### Confidence Threshold

Found under **Settings → Tagging & AI → Suggestion Confidence** (cycles between Low / Medium / High).

| Level | Threshold | Behaviour |
|---|---|---|
| **Low** | 0.20 | Many suggestions, lower precision — good for discovery |
| **Medium** | 0.50 | Balanced — recommended starting point |
| **High** | 0.75 | Fewer suggestions, higher precision — best after tagging many examples |

A second-best margin rule is also applied internally: if two tags score similarly close, neither is suggested (to avoid ambiguous classifications).

### Model Management

Go to **Settings → Model Management** to see the scene model:

- **MobileNet V3 Scene** (~8 MB) — the default. Lightweight and fast.
- If the model is not installed, tap **Download**
- Once downloaded, tap **Set Active** if it is not already

The model is stored locally on your device. Tap **Diagnostics** on any model card to see install state, runtime support, checksum status, and crash-loop failure count.

---

## Face Recognition (People Detection)

Face recognition runs a three-stage pipeline: **detect → embed → cluster → suggest**.

### Step 1 — Enable the Feature

In **Settings → Tagging & AI**, enable:
- **People Suggestions** (master toggle)
- Optionally: **People Detection in Videos (Experimental)** if you want video frames scanned

### Step 2 — Install the Models

Go to **Settings → Model Management → Face Detection** and **Face Recognition**:

**Face Detectors** (install one):
| Model | Size | Best For |
|---|---|---|
| YuNet | ~345 KB | Fast, good on clear front-facing photos |
| SCRFD 10G | ~17 MB | Better on group shots, small or angled faces |

**Face Recognition Models** (install one):
| Model | Size | Notes |
|---|---|---|
| ArcFace ResNet-100 FP16 | ~120 MB | Recommended — half size, minimal accuracy loss |
| ArcFace ResNet-100 FP32 | ~240 MB | Marginal improvement in difficult conditions |
| ArcFace ResNet-100 ONNX | ~240 MB | Fallback if TFLite causes inference errors |

Download over Wi-Fi is strongly recommended. Downloads can be resumed if interrupted.

### Step 3 — Run Face Indexing

Tap **Scan New Media** in **Settings → Tagging & AI**. The workers will run in order:

1. **Face Index** — detects faces in every unscanned photo, computes a 128D embedding for each
2. **Face Cluster** — groups similar embeddings into clusters
3. **Person Profile** — rebuilds cluster prototypes from your confirmed people tags
4. **Person Suggestion** — matches unassigned faces against confirmed clusters and queues suggestions for review

### Step 4 — Name People

After clustering completes, go to the **People** section (under Tags → category: people). You will see face clusters represented by a thumbnail. Tap a cluster to:

- View all faces grouped into it
- Assign a name (this creates a `people` tag and links it to the cluster)
- Hide the cluster if it contains noise/objects detected as faces
- Move individual faces to a different cluster if misassigned

### Step 5 — Review Suggestions

Once you have named at least one person, **PersonSuggestionWorker** will propose unidentified faces that look like that person. These appear as pending suggestions. You can:

- **Accept** — the face is confirmed as belonging to that person
- **Reject** — the face is excluded from future suggestions

Accepted suggestions feed back into the person's profile, improving future matches.

### Face Pipeline Coverage

Go to **Settings → Model Management → Face Pipeline Coverage** to see which media types are being scanned:

| Media Type | Status | Notes |
|---|---|---|
| Images (JPEG, PNG, HEIC, WebP) | Enabled | Always scanned |
| Animated GIFs | Disabled | Frame sampling not yet implemented |
| Videos | Disabled / Experimental | Enable via "People Detection in Videos" toggle |

---

## Library Health

Found at **Settings → Library → Library Health**.

The scanner checks four categories of issues:

| Issue | What It Means | Fix Action |
|---|---|---|
| **Stale tag counts** | A tag's stored `usage_count` doesn't match the actual number of tagged images | Recalculates all counts from `media_tags` |
| **Orphaned associations** | A `media_tags` row references a media file that no longer exists | Removes the dangling rows |
| **Unused tags** | A tag exists but has no images attached | Permanently deletes the tag (requires confirmation) |
| **Duplicate names** | Two or more tags share the same normalised name | Merges all into the one with the highest usage count; all associations are preserved |

> **Tip:** Run a health scan after any large batch operation (bulk tagging, importing, or restoring a backup) to catch any inconsistencies early.

---

## Maintenance Actions

All found under **Settings → Tagging & AI → AI Maintenance**:

| Action | What It Does | When To Use |
|---|---|---|
| **Scan New Media** | Indexes media added since the last run (non-destructive) | Routine — after adding new photos |
| **Repair Stale AI Data** | Retries previously-failed scan attempts without discarding successful results | After a crash or corrupted run |
| **Rebuild Scene Embeddings** | Clears all scene embeddings and re-runs from scratch | After switching to a different scene model |
| **Rebuild Tag Prototypes** | Recomputes mean centroids for all tags | After bulk-tagging a large batch of photos |
| **Rebuild Tag Suggestions** | Clears and rescores all suggestions against current prototypes | After rebuilding prototypes or changing confidence level |
| **Re-scan Faces** | Clears all detected faces and re-scans everything | After switching to a different face detector |
| **Rebuild People Matching** | Regenerates person suggestions from current confirmed clusters | After naming several new people |
| **Full AI Rescan** | Clears all AI data and restarts the entire pipeline | After a major model upgrade or database inconsistency |
| **Clear All AI Data** | Wipes all AI-generated data without scheduling workers | To start fresh manually |
| **Scan by Folder** | Runs the AI pipeline for one specific folder only | Targeted indexing without processing the whole library |

> **Note:** User-applied tags, tag rejections, and confirmed person names are never deleted by any maintenance action.

---

## Model Health Diagnostics

Each model card in **Settings → Model Management** has a **Diagnostics** toggle that shows:

- Installed / Active state
- Whether the runtime format (TFLite / ONNX) is supported on this device
- Whether a SHA-256 checksum is present in the manifest (for integrity verification)
- Whether expected file size is declared in the manifest
- Whether the on-disk file size matches the manifest (quick corruption check)
- Inference failure count (models are suspended after 3 consecutive failures)

If a model shows **ONNX runtime not available**, the ONNX Runtime library is not present in this build. Use a TFLite model instead.

---

# 3. Technical Reference

## Architecture Overview

Box.Pandora follows a layered architecture:

```
UI (Jetpack Compose)
    ↓ collect StateFlow
ViewModels
    ↓ call
Repositories  (MediaRepository, TagRepository)
    ↓ query / write
Room Database (AppDatabase v12)

Background Workers (WorkManager)
    ↓ read/write database
    ↓ run inference via
ML Inference Services
    ↓ execute model via
Model Runtime (TFLite / ONNX)
    ↓ load file from
ModelManager → ModelStorage (filesDir)
```

All singletons (database, repositories, managers) are initialised in `PandoraApp` and retrieved via `(context.applicationContext as PandoraApp).{field}`.

---

## Technology Stack

| Layer | Library | Version |
|---|---|---|
| UI | Jetpack Compose + Material3 | Compose BOM 2024.x |
| Navigation | Compose Navigation | 2.7.7 |
| Database | Room | 2.6.1 |
| Async | Coroutines + Flow | 1.8.0 |
| Background Work | WorkManager | 2.9.0 |
| Image Loading | Coil | 2.6.0 |
| Video Playback | Media3 / ExoPlayer | 1.3.0 |
| TFLite Inference | TensorFlow Lite | 2.14.0 |
| ONNX Inference | ONNX Runtime | 1.17.0 |
| Settings | DataStore | 1.1.1 |
| Paging | Paging 3 | 3.2.1 |
| Min SDK | Android 7.0 (API 24) | |
| Target SDK | Android 15 (API 35) | |

---

## Project Structure

```
app/src/main/java/com/example/boxpandora/
├── PandoraApp.kt                   App singleton — initialises all dependencies
├── MainActivity.kt                 Single activity; hosts the Compose NavHost
│
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt          Room database definition + all migrations (v1→v12)
│   │   ├── entity/                 Room entity classes (one per DB table)
│   │   └── dao/                    Data access interfaces
│   ├── repository/
│   │   ├── MediaRepository.kt      Media search, sync, CRUD, hide/unhide with tag preservation
│   │   └── TagRepository.kt        Tag lifecycle, bulk tagging, co-occurrence, health scanning
│   └── manager/
│       ├── ThumbnailManager.kt     Coil thumbnail caching + extraction
│       ├── FileSystemManager.kt    File I/O, rename, delete, scan file wait
│       ├── MediaContentObserver.kt MediaStore change watcher
│       └── MediaStoreRepository.kt MediaStore API abstraction
│
├── ml/
│   ├── config/
│   │   ├── AiSettings.kt           Persisted AI preferences (DataStore, immutable data class)
│   │   ├── AiSettingsRepository.kt DataStore read/write wrapper
│   │   └── AiFeatureFlags.kt       Compile-time feature gates (GIF/video face detection)
│   ├── model/
│   │   ├── ModelMetadata.kt        Static model declaration (id, version, format, dims, sha256)
│   │   ├── ModelManifest.kt        Full list of available models (bundled asset)
│   │   ├── ModelCategory.kt        Enum: SCENE_EMBEDDING, FACE_DETECTION, FACE_EMBEDDING
│   │   ├── ModelSource.kt          Sealed: BundledAsset | RemoteDownload
│   │   └── InstalledModel.kt       Runtime wrapper (metadata + file + isActive)
│   ├── manager/
│   │   ├── ModelManager.kt         Model lifecycle — install, activate, delete, crash-loop guard
│   │   ├── ModelStorage.kt         Filesystem layout under filesDir/ml_models/
│   │   ├── ModelDownloadRepository.kt HTTP download + SHA-256 checksum verification
│   │   └── ModelInstallResult.kt   Sealed result type for install operations
│   ├── runtime/
│   │   ├── ModelRuntime.kt         Inference interface
│   │   ├── ModelRuntimeFactory.kt  Dispatches TfliteRuntime or OnnxRuntime
│   │   ├── TfliteRuntime.kt        TensorFlow Lite executor
│   │   └── OnnxRuntime.kt          ONNX Runtime executor
│   ├── inference/
│   │   ├── SceneEmbeddingService.kt  Loads model, preprocesses image, returns 512D embedding
│   │   ├── FaceDetectionService.kt   Loads detector, returns DetectionResult list per image
│   │   └── FaceEmbeddingService.kt   Loads ArcFace, aligns face crop, returns 128D embedding
│   ├── detection/
│   │   ├── FaceDetectionEngine.kt    Abstract detector interface
│   │   ├── ScrfdDetectionEngine.kt   SCRFD implementation
│   │   ├── YunetDetectionEngine.kt   YuNet implementation
│   │   ├── FaceAlignmentHelper.kt    Landmark → affine crop for embedding input
│   │   └── DetectionResult.kt        Bbox, landmarks, pose, quality score
│   ├── engine/
│   │   ├── TagPrototypeEngine.kt     Computes mean centroid per tag from confirmed embeddings
│   │   ├── TagSuggestionEngine.kt    Cosine scoring + second-best margin + rejection filter
│   │   ├── FaceClusterEngine.kt      Conservative centroid clustering + user correction handling
│   │   ├── PersonProfileEngine.kt    Per-person cluster centroid from confirmed training data
│   │   └── EmbeddingUtils.kt         ByteArray↔FloatArray, L2-norm, cosine similarity
│   └── sampling/
│       └── FrameSampler.kt           Uniform frame extraction for GIF/video embedding
│
├── worker/
│   ├── AiIndexScheduler.kt           Schedules all workers; KEEP vs REPLACE policy logic
│   ├── IndexingStatsStore.kt         Persists per-worker run history (SharedPreferences)
│   ├── SceneIndexWorker.kt           Embeds unindexed media → image_embeddings
│   ├── PrototypeBuildWorker.kt       Builds tag centroids → tag_prototypes
│   ├── TagSuggestionWorker.kt        Scores assets → tag_suggestions
│   ├── FaceIndexWorker.kt            Detects + embeds faces → detected_faces, face_embeddings
│   ├── FaceClusterWorker.kt          Clusters embeddings → face_clusters, cluster_id assignments
│   ├── PersonProfileWorker.kt        Person-tag → confirmed cluster centroid
│   └── PersonSuggestionWorker.kt     Matches faces → person_suggestions
│
└── ui/
    ├── main/
    │   ├── MainScreen.kt             NavHost + bottom navigation
    │   ├── Screen.kt                 Screen enum (routes, icons, labels)
    │   ├── FoldersScreen.kt          Album grid with hide/show support and multi-select
    │   ├── FolderDetailScreen.kt     Media list for a single album with filename toggle
    │   ├── TagsScreen.kt             Tag browser (category tabs, tag cards)
    │   ├── TagGalleryScreen.kt       Media filtered by a single tag
    │   ├── FavoritesScreen.kt        Starred media grid
    │   ├── SuggestionsScreen.kt      Review pending tag suggestions
    │   ├── SimilarImagesScreen.kt    Similarity search results (Phase 3)
    │   └── AiDebugScreen.kt          Developer diagnostics for AI pipeline
    ├── settings/
    │   ├── SettingsScreen.kt          Main settings navigation hub
    │   ├── SettingsSubScreens.kt      All settings sub-screens (Library, AI, Display, etc.)
    │   ├── SettingsComponents.kt      Reusable settings row/toggle/slider composables
    │   ├── ModelManagementScreen.kt   Install/activate/delete AI models, diagnostics
    │   └── MaintenanceProgressDialog.kt  Worker progress monitor during indexing
    └── components/
        ├── media/
        │   ├── MediaViewer.kt          Full-screen media viewer + video player
        │   └── MediaThumbnail.kt       Coil-backed thumbnail with filename overlay support
        └── grid/
            └── DynamicMediaGrid.kt     Adaptive grid layout with filename toggle pass-through
```

---

## Database Schema (Room v12)

### Tables

| Table | Primary Key | Purpose |
|---|---|---|
| `media_index` | `uri` | All discovered media files |
| `albums` | `id` | Folder groupings |
| `scan_log` | `id` | MediaStore sync history |
| `tags` | `id` | Canonical tag definitions |
| `media_tags` | `(media_uri, tag_id)` | Many-to-many media↔tag joins |
| `tag_aliases` | `id` | Alternative names for tags |
| `tag_review_queue` | `id` | Tags pending user review |
| `tag_change_history` | `id` | Audit log of all tag changes |
| `tag_cooccurrences` | `(tag_id_a, tag_id_b)` | How often two tags appear together |
| `tag_rejections` | `(asset_id, tag_key)` | User-rejected suggestions (never resurface) |
| `tag_suggestions` | `id` | AI-proposed tag assignments (pending review) |
| `tag_prototypes` | `tag_key` | Mean embedding vector per tag |
| `image_embeddings` | `(asset_id, model_version)` | Scene embedding vectors |
| `detected_faces` | `face_id` | Face bbox + pose + quality per asset |
| `face_embeddings` | `face_id` | ArcFace 128D vectors per face |
| `face_clusters` | `cluster_id` | Grouped face identities (centroid + metadata) |
| `face_cluster_corrections` | `id` | User edits (move face, exclude face) |
| `face_scan_log` | `(asset_id, detector_version)` | Which assets have been face-scanned |
| `person_suggestions` | `id` | Unconfirmed face→cluster match candidates |
| `user_preferences` | `key` | Simple key-value settings store |

### Key Provenance Columns

Every AI-generated row carries model version information so workers can detect stale data after a model upgrade:

| Table | Provenance Column | Values |
|---|---|---|
| `image_embeddings` | `model_version` | `"scene_embedding:mobilenet_v3-1.0.0"` |
| `detected_faces` | `detector_model_version` | Model id + version string |
| `face_embeddings` | `model_id`, `model_version` | Model id + version string |
| `face_clusters` | `embedder_version` | Active embedder version at clustering time |
| `face_scan_log` | `detector_version` | Detector version at scan time |
| `tag_suggestions` | `model_version` | Scene model version |
| `tag_prototypes` | `model_version` | Scene model version |

The `face_scan_log.result_status` column controls retry behaviour:

| Value | Meaning | Re-processed by… |
|---|---|---|
| `faces_found` | Scanned, faces detected | Never (unless full rebuild) |
| `no_faces_found` | Scanned, no faces | Never (unless full rebuild) |
| `failed` | Processing error | Repair action only |
| `skipped` | Intentionally skipped | N/A |

---

## Worker Pipeline Details

```
SyncWorker ──────────────────────────────────────────────────────────────────┐
                                                                              │
                                                                              ▼
SceneIndexWorker ──► PrototypeBuildWorker ──► TagSuggestionWorker
(embed media)         (build tag centroids)    (score & suggest tags)

FaceIndexWorker ──► FaceClusterWorker ──► PersonProfileWorker ──► PersonSuggestionWorker
(detect & embed)     (group by similarity)   (person-tag centroids)   (match & suggest)
```

All workers are:
- **Unique by name** — only one instance runs at a time per named slot
- **KEEP policy** (default) — passive scheduling is a no-op if already queued
- **REPLACE policy** — used by all user-triggered maintenance actions to guarantee restart
- **Idempotent** — safe to re-run; rows already processed are skipped via scan logs
- **Constraint-aware** — battery not low + storage not low; charging required unless `forceRun=true`

---

## Tag Metadata Preservation

`MediaRepository.transferMetadata(oldItem, newItem, deleteOld)` is the central function for preserving user data across URI changes. It atomically:

1. Copies `rating`, `isFavorite`, `notes`, `isHidden` from the old item to the new
2. Calls `tagRepository.transferTagMetadata(oldUri, newUri)` to re-point all `media_tags` rows
3. Copies `image_embeddings` rows to the new URI
4. Copies `detected_faces` and `face_embeddings` rows to the new URI
5. Optionally deletes the old item (when `deleteOld = true`)

All steps run inside a single `database.withTransaction` block so no partial state is visible.

`syncMediaStore` uses this when it detects that a URI in `urisToDelete` matches the `filePath` of a freshly scanned item under a different URI scheme (indicating a hide/unhide transition rather than a true deletion).

---

## ML Model Lifecycle

```
ModelManifest (bundled asset JSON)
    └── ModelMetadata[]
            ├── id, version, format (tflite / onnx)
            ├── source (BundledAsset | RemoteDownload + url)
            ├── sha256, sizeBytes (integrity)
            └── inputWidth, inputHeight, outputDim

ModelManager
    ├── getInstalledModels() → reads filesDir/ml_models/
    ├── getActiveModel(category) → SharedPreferences pointer
    ├── activate(meta) → updates SharedPreferences
    ├── installFromUrl(meta, progressCallback) → download + verify → ModelStorage
    ├── delete(meta) → removes files
    ├── getModelFailureCount(category) → crash-loop counter
    └── clearModelFailures(category) → resets counter

ModelStorage layout:
    filesDir/ml_models/{category}/{model_id}/{version}/model.{tflite|onnx}
                                                        manifest_sidecar.json

ModelRuntimeFactory.create(InstalledModel)
    ├── format == "onnx"  → OnnxRuntime  (requires ORT AAR on classpath)
    └── format == "tflite" → TfliteRuntime

Crash-loop guard:
    - Each inference failure increments a per-category counter
    - Counter >= 3 → ModelManager.hasTooManyFailures(category) returns true
    - Workers check this flag and return early (model suspended)
    - User clears via Settings → Model Management → Clear Suspension
```

---

## Settings Architecture

### AI Settings (DataStore)

`ml/config/AiSettings.kt` — immutable data class, persisted via DataStore:

| Field | Type | Default | Effect |
|---|---|---|---|
| `sceneTaggingEnabled` | Boolean | true | Gates SceneIndex/Prototype/Suggestion workers |
| `faceProcessingEnabled` | Boolean | false | Gates all face workers |
| `backgroundIndexingEnabled` | Boolean | true | Allows passive scheduling |
| `confidenceThreshold` | Float | 0.5 | Minimum score for a tag suggestion to appear (Low=0.20, Medium=0.50, High=0.75) |
| `autoIndexOnSync` | Boolean | true | Triggers indexing after MediaStore sync |
| `wifiOnlyDownloads` | Boolean | true | Requires UNMETERED network for model downloads |
| `faceDetectionInVideos` | Boolean | false | Enables video frame sampling in FaceIndexWorker |

### Compile-Time Feature Flags

`ml/config/AiFeatureFlags.kt` — object with `const val` fields:

| Flag | Default | Controls |
|---|---|---|
| `FACE_DETECTION_ON_GIF` | false | Whether GIFs are sampled for face detection |
| `FACE_DETECTION_ON_VIDEO` | true | Whether the video toggle is exposed in UI |

---

## Navigation Routes

Defined in `ui/main/Screen.kt`:

| Route | Screen | Notes |
|---|---|---|
| `folders` | FoldersScreen | Default start destination |
| `favorites` | FavoritesScreen | Bottom nav tab |
| `tags` | TagsScreen | Bottom nav tab |
| `tag_gallery/{tagId}` | TagGalleryScreen | Parameterised by tag ID |
| `settings` | SettingsScreen | Accessible from app bar |
| `settings/model_management` | ModelManagementScreen | Sub-screen |
| `settings/tagging_ai` | Tagging & AI sub-screen | Sub-screen |
| `settings/library/health` | LibraryHealthScreen | Sub-screen |
| `ai_debug` | AiDebugScreen | Dev/debug only |
| `suggestions` | SuggestionsScreen | Review tag suggestions |
| `similar_images/{assetId}` | SimilarImagesScreen | Phase 3 |

The `MediaViewer` is not a navigation route — it is a modal overlay managed by state in `MainScreen`.

---

## ViewModel → Repository → DAO Map

| ViewModel | Repository / Manager | Key DAOs |
|---|---|---|
| FoldersViewModel | MediaRepository | MediaItemDao, AlbumDao |
| FolderDetailViewModel | MediaRepository | MediaItemDao |
| FavoritesViewModel | MediaRepository | MediaItemDao |
| TagsViewModel | TagRepository | TagDao, MediaTagDao |
| TagGalleryViewModel | TagRepository, MediaRepository | TagDao, MediaTagDao, MediaItemDao |
| LibraryHealthViewModel | TagRepository | TagDao, MediaTagDao |
| AiSettingsViewModel | AiSettingsRepository, AppDatabase, ModelManager | All face/embedding DAOs |
| ModelManagerViewModel | ModelManager, AiSettingsRepository | (no direct DAO access) |
| SuggestionsViewModel | TagRepository | TagSuggestionDao, TagDao |
| SimilarImagesViewModel | MediaRepository | ImageEmbeddingDao |
| AiDebugViewModel | MediaRepository, TagRepository | Multiple |

---

## Adding a New Model

1. Add a `ModelMetadata` entry to the manifest JSON in `app/src/main/assets/`
2. Assign it a `ModelCategory` (or add a new category enum value)
3. If a new category: add a new `InstalledModel` slot in `ModelManager` and a new `ModelUiState` group in `ModelManagementScreen`
4. Create a new `InferenceService` that calls `ModelRuntimeFactory.create(model)` and implement preprocessing/postprocessing
5. Consume the service from the appropriate worker
6. If a new DB table is needed: add an entity + DAO, bump `AppDatabase.VERSION`, and add a migration

---

## Database Migrations Summary

| Version | Change |
|---|---|
| 1 → 2 | Initial schema |
| 2 → 3 | Added `scan_log` table |
| 3 → 4 | Added `tag_change_history` |
| 4 → 5 | Added `image_embeddings`, `tag_prototypes`, `tag_suggestions` |
| 5 → 6 | Added `detected_faces`, `face_embeddings` |
| 6 → 7 | Added `face_clusters`, `face_cluster_corrections` |
| 7 → 8 | Added `face_scan_log` |
| 8 → 9 | Added `person_suggestions` |
| 9 → 10 | Added `media_type`, `scan_method` to `image_embeddings` |
| 10 → 11 | Added `face_detection_in_videos` to AiSettings, updated `face_scan_log` result statuses |
| 11 → 12 | Added `embedder_version TEXT NOT NULL DEFAULT ''` to `face_clusters` |

---

## Permissions

Declared in `AndroidManifest.xml`:

| Permission | Required For |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | Full media library access (Android 11+) |
| `READ_EXTERNAL_STORAGE` | Media access fallback (Android < 11) |
| `INTERNET` | Model downloads |

The app uses `enableOnBackInvokedCallback = true` for predictive back gesture support on Android 13+.

---

*Box.Pandora v1.5 — private AI media intelligence, entirely on-device.*
