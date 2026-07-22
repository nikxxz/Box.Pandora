# Box.Pandora

Box.Pandora is a privacy-focused Android media gallery for browsing, organising, tagging, and analysing photos and videos on the device. It is a single-module Jetpack Compose application backed by MediaStore and a Room index. Optional TensorFlow Lite and ONNX models provide scene embeddings, tag suggestions, face detection, face recognition, and experimental ensemble inference.

Current app version: **1.5** (`versionCode 6`). Package/application ID: `com.example.boxpandora`.

> Development status: the gallery, tagging, file operations, settings, maintenance, and single-model AI pipelines are implemented. AI models must be downloaded before inference. Face processing is disabled by default. Video/GIF face extraction is unfinished, and ensemble AI and visual-similarity work should be treated as experimental.

## Features

### Gallery and file management

- Browse all media or albums/folders, with paging, search, sorting, and image/video/GIF filters.
- View images and videos full-screen; images support zoom and videos use Media3/ExoPlayer.
- Favourite, rename, copy, move, hide/unhide, share, and delete media; perform batch operations from multi-select.
- Pin, rename, copy, move, hide/unhide, and delete folders.
- Toggle filenames in folder grids and show hidden folders from settings.
- A MediaStore observer and `SyncWorker` keep the Room index synchronized.
- Hide/unhide operations preserve tags and AI metadata when a media URI changes between `content://` and `file://`.

The app uses broad filesystem access (`MANAGE_EXTERNAL_STORAGE`) on Android 11+ and legacy read access through Android 10. This is appropriate for a full file-manager-style gallery but affects Play Store distribution and should be reviewed before release.

### Tags and library health

- Create, rename, merge, delete, colour, categorise, and describe tags.
- Attach or remove tags individually or in batches.
- Resolve aliases and track tag co-occurrence, change history, review items, and rejected AI suggestions.
- Browse media by tag and review pending AI tag suggestions.
- Library Health detects and repairs stale usage counts, orphaned associations, unused tags, and duplicate normalized names.

Tag categories currently used by the UI/data model include `people`, `place`, `style`, `clothing`, `pose`, `animal`, `object`, `mood`, and `misc`.

### Privacy, backup, and preferences

- Optional app lock using device credentials or a four-digit PIN.
- Theme mode, accent colour, sort order, gallery visibility, and library filters are persisted in the `user_preferences` Room table.
- Tag metadata can be imported/exported as JSON; the Room database can be backed up and restored from the Backup & Data settings screen.
- Android Auto Backup is enabled in the manifest. Review the backup rules and threat model before describing all persisted metadata as strictly device-only.

### On-device AI

The default pipeline is:

```text
MediaStore sync
    ├─ SceneIndexWorker → PrototypeBuildWorker → TagSuggestionWorker
    └─ FaceIndexWorker → FaceClusterWorker → PersonProfileWorker
                                              → PersonSuggestionWorker
```

- **Scene tagging:** a MobileNet V3 feature-vector model produces 1280-dimensional image embeddings. Tag prototypes are built from already-tagged media and scored with cosine similarity. Accepted/rejected review decisions feed back into the tagging workflow.
- **People:** YuNet or SCRFD detects faces; ArcFace creates 512-dimensional embeddings; workers cluster faces and build person profiles/suggestions.
- **Model management:** models are downloaded from the external Pandora assets repository, checksum-verified, stored below `filesDir/ml_models/`, and activated by category. TFLite and ONNX runtimes are both included.
- **Execution modes:** `SINGLE_ACTIVE` is the default. `ENSEMBLE_ALL_ENABLED` adds per-model evidence, fusion, reliability statistics, checkpoints, cooldowns, and thermal/concurrency policy. This path is advanced/experimental and has considerably more state than the single-model flow.
- **Scheduling:** WorkManager unique work and chains apply battery/storage/charging constraints. User-triggered maintenance can rebuild individual stages or clear/recreate AI data.

AI preferences live in DataStore through `AiSettingsRepository`. Scene tagging and background indexing default to enabled; face processing defaults to disabled. No model binary is bundled in the APK, so enabled does not mean operational until the corresponding model is installed.

#### Known AI limitations

- GIF face sampling is not implemented.
- The video-face setting is exposed as experimental, but `FaceIndexWorker` currently skips videos because frame extraction is not implemented.
- Pose values on detected faces are placeholders (`0.0`); landmark-derived pose estimation remains future work.
- Fusion calibration staleness/version handling is only partially wired.
- Visual similarity has a route, screen, service, and local work/tests, but should remain marked experimental until the current working-tree implementation is completed, reviewed, and committed.

## Architecture

Box.Pandora uses a pragmatic layered MVVM-style architecture without a dependency-injection framework:

```text
Jetpack Compose screens
        ↓ StateFlow / PagingData
ViewModels and ViewModel factories
        ↓
MediaRepository / TagRepository / managers
        ↓
Room DAOs + MediaStore + filesystem

WorkManager workers
        ↓
AI services, engines, orchestrators, and runtimes
        ↓
Room AI tables + model files in app storage
```

`PandoraApp` is the composition root. It manually constructs the Room database, repositories, model/settings managers, caches, observers, and AI policy stores. Screens retrieve these singletons through the application instance and use explicit ViewModel factories.

### Main app flow

`MainActivity` hosts `MainScreen`, which owns the Compose `NavHost`, application lock gate, bottom navigation, drawer, maintenance state, and modal media viewer. The start destination is Folders. Bottom navigation contains Folders, Favorites, and Tags; detail routes cover folders, tag galleries, settings, AI suggestions/debugging, and similar images.

The main data flow is:

1. `MediaStoreRepository` queries device images and videos.
2. `MediaRepository.syncMediaStore()` reconciles results with Room, albums, URI transitions, and deletions.
3. DAOs expose Flow or Paging sources to repositories and ViewModels.
4. Compose screens collect the resulting state.
5. WorkManager reads indexed media, runs optional inference, and stores embeddings, evidence, clusters, prototypes, and suggestions back in Room.

## Important files

```text
app/src/main/java/com/example/boxpandora/
├── PandoraApp.kt                         application composition root
├── MainActivity.kt                       Android/Compose entry point
├── data/
│   ├── local/AppDatabase.kt              Room schema v16 and migrations
│   ├── local/entity/                     gallery, tag, face, and ensemble entities
│   ├── local/dao/                        Flow, paging, and maintenance queries
│   ├── repository/MediaRepository.kt     sync, gallery, file/folder operations
│   ├── repository/TagRepository.kt       tag lifecycle, suggestions, health repair
│   └── manager/                          filesystem, thumbnails, MediaStore observer
├── ml/
│   ├── config/                           DataStore settings, modes, flags, calibration
│   ├── manager/ and storage/             model download/install/activation/integrity
│   ├── runtime/ and inference/           TFLite/ONNX execution and preprocessing
│   ├── detection/ and clustering/        face detection, alignment, clustering
│   ├── engine/ and ensemble/             prototypes, scoring, fusion, reliability
│   └── search/SimilaritySearchService.kt visual similarity (experimental/local WIP)
├── worker/                               sync and AI WorkManager pipelines
└── ui/
    ├── main/MainScreen.kt                navigation and top-level UI state
    ├── main/*Screen.kt                   gallery, tags, suggestions, similarity/debug
    ├── main/viewmodel/                   screen state and operations
    ├── settings/                         settings, model management, maintenance
    └── components/                       grids, thumbnails, viewer, shared UI

app/src/main/assets/models/model_manifest.json  downloadable model catalogue
app/src/main/AndroidManifest.xml                permissions/application configuration
gradle/libs.versions.toml                       dependency version catalogue
AI_ARCHITECTURE_PLAN.md                         design history; not current user docs
```

## Storage and schema

Room database `pandora_db` is currently schema version **16**. It contains four broad groups:

- **Library:** `media_index`, `albums`, `scan_log`, `user_preferences`.
- **Tags:** `tags`, `media_tags`, `tag_aliases`, `tag_review_queue`, `tag_change_history`, `tag_cooccurrences`, `tag_rejections`, `heuristic_tags`.
- **Single-model AI:** `image_embeddings`, `tag_prototypes`, `tag_suggestions`, `detected_faces`, `face_embeddings`, `face_clusters`, `face_cluster_corrections`, `face_scan_log`, `person_suggestions`.
- **Ensemble AI:** `model_inference_evidence`, `fused_scene_suggestions`, `fused_faces`, `identity_inference_evidence`, `fused_identity_suggestions`, `model_reliability_stats`.

Foreign keys generally cascade user/AI metadata when the owning media or tag is deleted. `MediaRepository.transferMetadata()` is the important abstraction for a physical file whose URI changes: it transfers library fields, tags, embeddings, and face records transactionally before the old index row is removed.

## Build and setup

### Requirements

- Android Studio with Android SDK 35 installed.
- JDK 11 (the Gradle build requests a Java 11 toolchain).
- An Android device or emulator running API 24 or newer.
- Network access if downloading AI models.

### Build

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

Install the debug APK with Android Studio or `adb`. On first launch, grant the requested all-files access so the library can be indexed. AI models are optional and are installed from Settings → Model Management.

Release builds currently use the debug signing configuration. Create a proper private release signing setup before distribution; do not commit signing credentials.

### Build configuration

| Setting | Value |
|---|---:|
| compile/target SDK | 35 |
| minimum SDK | 24 |
| Java/Kotlin JVM target | 11 |
| Room schema | 16 |
| build system | Gradle Kotlin DSL + version catalogue + KSP |
| release shrinking | R8 minification and resource shrinking enabled |

Principal pinned dependencies are Compose BOM `2024.02.00`, Kotlin `2.0.0`, AGP `8.3.2`, Room `2.6.1`, Navigation `2.7.7`, WorkManager `2.9.0`, Paging `3.2.1`, Coil `2.6.0`, Media3 `1.3.0`, TensorFlow Lite `2.14.0`, ONNX Runtime `1.17.0`, Coroutines `1.8.0`, and DataStore `1.1.1`.

These versions are internally coherent with the current project but are old relative to current Android releases. Upgrade them as a coordinated, tested change rather than piecemeal, especially Kotlin/KSP/Compose/AGP and Room migrations.

## Permissions and platform notes

Declared permissions:

- `INTERNET` for remote model downloads.
- `MANAGE_EXTERNAL_STORAGE` for full-library and filesystem operations on Android 11+.
- `READ_EXTERNAL_STORAGE` through API 29.

The app does not declare Android 13 `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`; it relies on all-files access. The manifest also enables predictive back callbacks, a `FileProvider` for sharing, `largeHeap`, and Android backup. Test permission-denied, partially accessible storage, OEM MediaStore behavior, and process death before shipping.

## Current risks and technical debt

- **Migration safety:** only migrations from schema 5 onward are registered, and `fallbackToDestructiveMigration()` is enabled. Opening an older or otherwise unsupported database can erase local indexed/tag/AI data. Export/backup coverage and non-destructive migrations should be addressed before schema changes.
- **Distribution/security configuration:** broad storage permission, Android backup, `largeHeap`, and debug signing in `release` all need an explicit production decision.
- **Large central classes:** `MainScreen`, `SettingsSubScreens`, `MediaRepository`, AI settings/model ViewModels, and several workers carry many responsibilities. This raises regression risk and makes focused tests harder.
- **Manual dependency wiring:** direct application-singleton access and repeated custom ViewModel factories couple UI code to concrete storage/manager implementations.
- **AI complexity:** single-model and ensemble tables/workflows coexist, with checkpoints, reliability, thermal rules, and multiple suggestion sources. Future work must define which source is canonical and preserve model-version provenance.
- **Media identity fragility:** the database primary key is a URI while filesystem operations can change URI schemes or paths. Metadata-transfer protections exist, but moves, hide/unhide, MediaStore delays, and empty scans remain high-risk paths.
- **Privacy:** the PIN is a fast SHA-256 hash without a per-user salt or slow password KDF. It is a convenience lock, not strong protection against offline database access.
- **Testing:** the repository has only template tests plus local similarity-search unit tests. Core sync, migration, metadata-transfer, tagging, backup/restore, workers, and permission flows lack meaningful automated coverage.
- **Encoding:** several source comments contain mojibake from an earlier text-encoding conversion. It does not normally affect runtime behavior but reduces maintainability.

## Before adding features

1. Preserve or commit the current local similarity/search work on a dedicated branch and establish a clean baseline.
2. Add migration tests for every supported Room version and remove destructive fallback once coverage is complete.
3. Add focused tests around MediaStore reconciliation, URI/path identity transfer, tag count invariants, and file conflict handling.
4. Decide the supported permission/distribution model and production backup/signing policy.
5. Clarify single-model versus ensemble product behavior and mark the experimental UI accordingly.
6. Split the largest screen/repository/worker files along existing feature boundaries before making them substantially larger.
7. Upgrade the Android toolchain and libraries in a separate, verified change.

## Repository state at the documentation assessment

The remote was fetched before this README was updated. Local `main` and `origin/main` both pointed to commit `9064e1f` (`--v1.5`) and were **0 ahead / 0 behind**, so no sync operation was required. The working tree already contained modified application files and untracked similarity-search tests. Those files were preserved; this assessment changes only `README.md`.
