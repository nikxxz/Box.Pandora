/**
 * Database schema — v11
 *
 * Tables (v1):
 *   albums          — folder/album registry with show/hide & pin flags
 *   media_index     — one row per media file; stores both device & user metadata
 *   tags            — tag definitions
 *   media_tags      — many-to-many junction between media and tags
 *   scan_log        — history of indexing runs for incremental/timestamp-based scans
 *   user_preferences — persisted key/value store (last-used filters, sort, etc.)
 *
 * Tables (v2 — Smart Tagging):
 *   tag_suggestions  — cached ML Kit label scores per asset
 *   tag_prototypes   — learned prototype vectors for custom tags
 *
 * Tables (v7 — Offline Smart Tagging with on-device embeddings):
 *   image_embeddings — TFLite embedding blobs per asset per model version
 *   heuristic_tags   — cached heuristic detector results (screenshot/mono/night/…)
 *   tag_rejections   — user dismissals of suggested tags (for threshold tuning)
 *
 * Column additions (v9 — Tag Categories):
 *   tags.category    — one of: people | animals | places | objects | activity |
 *                      style | content | nsfw | misc
 *
 * Tables (v10 — Face Pipeline + Co-occurrence Learning):
 *   detected_faces   — ML Kit face bounding boxes + quality metadata per asset
 *   face_embeddings  — face identity embeddings per detected face (dim stored per row)
 *   face_clusters    — internal person clusters (hidden; each maps to a people tag)
 *   tag_cooccurrences — counts of how often two tags appear on the same photo
 *
 * Tables (v11 — Tag Intelligence System):
 *   tag_aliases        — alternate search terms that map to a canonical tag
 *   tag_review_queue   — AI-generated suggestions awaiting user review
 *   tag_change_history — log of every accepted/applied tag change
 *
 * Column additions (v11):
 *   tags.last_reviewed_at — Unix ms of last AI analysis
 */

export const DB_VERSION = 11;

// ─── Table definitions ───────────────────────────────────────────────────────

export const TABLE_SQL = [
  // Folders / albums on the device
  `CREATE TABLE IF NOT EXISTS albums (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    name             TEXT    NOT NULL UNIQUE,      -- album name from CameraRoll
    path             TEXT,                         -- absolute filesystem path (if known)
    album_type       TEXT    NOT NULL DEFAULT 'Album', -- CameraRoll type ('Album', 'SmartAlbum', …)
    hidden           INTEGER NOT NULL DEFAULT 0,   -- 0=visible  1=hidden
    pinned           INTEGER NOT NULL DEFAULT 0,   -- pinned to top of grid
    cover_uri        TEXT,                         -- URI of latest item (all types)
    photo_cover_uri  TEXT,                         -- URI of latest photo
    video_cover_uri  TEXT,                         -- URI of latest video
    media_count      INTEGER NOT NULL DEFAULT 0,   -- total count (photos + videos)
    photo_count      INTEGER NOT NULL DEFAULT 0,   -- photos only
    video_count      INTEGER NOT NULL DEFAULT 0,   -- videos only
    last_modified_at INTEGER,                      -- Unix ms of most recent item's timestamp
    last_scanned_at  INTEGER,                      -- Unix ms of last CameraRoll scan
    created_at       INTEGER NOT NULL              -- Unix ms when first indexed
  )`,

  // Core media index — one row per file
  `CREATE TABLE IF NOT EXISTS media_index (
    uri               TEXT    PRIMARY KEY,        -- CameraRoll URI (stable id)
    filename          TEXT    NOT NULL,
    album_id          INTEGER REFERENCES albums(id) ON DELETE SET NULL,

    -- File attributes (populated from CameraRoll / RNFS stat)
    file_size         INTEGER NOT NULL DEFAULT 0, -- bytes
    width             INTEGER NOT NULL DEFAULT 0, -- px
    height            INTEGER NOT NULL DEFAULT 0, -- px
    duration          REAL,                       -- seconds; NULL for images
    extension         TEXT    NOT NULL DEFAULT '',
    media_type        TEXT    NOT NULL DEFAULT 'image', -- 'image' | 'video' | 'audio'

    -- Timestamps (Unix ms)
    device_created_at INTEGER,                    -- timestamp from CameraRoll (converted to ms)
    device_modified_at INTEGER,                   -- file modified time
    indexed_at        INTEGER NOT NULL,           -- when this row was first created
    scanned_at        INTEGER NOT NULL,           -- last time we confirmed the file exists

    -- User metadata
    rating            INTEGER NOT NULL DEFAULT 0, -- 0–5 stars
    favorite          INTEGER NOT NULL DEFAULT 0, -- 0 | 1
    hidden            INTEGER NOT NULL DEFAULT 0, -- 0=visible  1=hidden
    notes             TEXT,                       -- free-form user note

    -- Thumbnail cache (Simple Gallery pattern: persist resolved thumb path)
    -- Stores the file:// path to the pre-generated 320px JPEG in cacheDir/thumbnails/.
    -- Populated by ThumbnailCache after first resolution; survives JS reloads.
    thumb_uri         TEXT                        -- file:// path to cached thumbnail
  )`,

  // Tag catalogue
  `CREATE TABLE IF NOT EXISTS tags (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    name             TEXT    NOT NULL UNIQUE,
    color            TEXT    NOT NULL DEFAULT '#888888', -- hex colour for UI badge
    icon             TEXT,                               -- icon key (matches assets/icons)
    category         TEXT    NOT NULL DEFAULT 'misc',    -- tag category key (see TagCategories)
    usage_count      INTEGER NOT NULL DEFAULT 0,         -- denormalised; updated on tag/untag
    created_at       INTEGER NOT NULL,
    description      TEXT,                               -- optional user-written description
    updated_at       INTEGER,                            -- Unix ms of last metadata edit
    last_reviewed_at INTEGER                             -- Unix ms of last AI analysis
  )`,

  // Many-to-many: media ↔ tags
  `CREATE TABLE IF NOT EXISTS media_tags (
    media_uri TEXT    NOT NULL REFERENCES media_index(uri) ON DELETE CASCADE,
    tag_id    INTEGER NOT NULL REFERENCES tags(id)         ON DELETE CASCADE,
    tagged_at INTEGER NOT NULL,
    PRIMARY KEY (media_uri, tag_id)
  )`,

  // History of indexing / scan runs
  `CREATE TABLE IF NOT EXISTS scan_log (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    scan_type     TEXT    NOT NULL,               -- 'full' | 'incremental'
    album_scope   TEXT,                           -- NULL = all albums
    started_at    INTEGER NOT NULL,
    finished_at   INTEGER,
    files_added   INTEGER NOT NULL DEFAULT 0,
    files_removed INTEGER NOT NULL DEFAULT 0,
    files_updated INTEGER NOT NULL DEFAULT 0,
    status        TEXT    NOT NULL DEFAULT 'running', -- 'running' | 'done' | 'failed'
    error         TEXT
  )`,

  // Persisted key/value store (last-used filters, sort prefs, settings, etc.)
  `CREATE TABLE IF NOT EXISTS user_preferences (
    key        TEXT    PRIMARY KEY,
    value      TEXT    NOT NULL,                  -- JSON-encoded value
    updated_at INTEGER NOT NULL
  )`,

  // ── Smart Tagging tables ──────────────────────────────────────────────────

  // Cached tag suggestion scores (source: ML Kit image labeling or OCR)
  `CREATE TABLE IF NOT EXISTS tag_suggestions (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    asset_id       TEXT    NOT NULL REFERENCES media_index(uri) ON DELETE CASCADE,
    tag_key        TEXT    NOT NULL,               -- tag name or id string
    score          REAL    NOT NULL,               -- similarity score [0, 1]
    source         TEXT    NOT NULL DEFAULT 'generic_vocab', -- metadata | generic_vocab | learned_custom
    model_version  TEXT    NOT NULL DEFAULT '0',
    created_at     INTEGER NOT NULL               -- Unix ms
  )`,

  // Learned prototype embeddings for custom tags (updated on every user tag action)
  `CREATE TABLE IF NOT EXISTS tag_prototypes (
    tag_key        TEXT    PRIMARY KEY,            -- tag name or id string
    prototype_blob BLOB    NOT NULL,               -- Float32 bytes; normalized online mean
    dim            INTEGER NOT NULL DEFAULT 512,
    n              INTEGER NOT NULL DEFAULT 0,     -- number of examples averaged in
    model_version  TEXT    NOT NULL DEFAULT '0',
    updated_at     INTEGER NOT NULL               -- Unix ms
  )`,

  // ── Offline Embedding tables (v7) ────────────────────────────────────────

  // One TFLite embedding blob per asset per model version
  `CREATE TABLE IF NOT EXISTS image_embeddings (
    asset_id      TEXT    NOT NULL REFERENCES media_index(uri) ON DELETE CASCADE,
    model_version TEXT    NOT NULL,
    dim           INTEGER NOT NULL,
    embedding     BLOB    NOT NULL,               -- base64-encoded Float32 array (L2-normalized)
    created_at    INTEGER NOT NULL,
    PRIMARY KEY (asset_id, model_version)
  )`,

  // Cached heuristic detector scores (screenshot / monochrome / night / document / blurry)
  `CREATE TABLE IF NOT EXISTS heuristic_tags (
    asset_id   TEXT    NOT NULL REFERENCES media_index(uri) ON DELETE CASCADE,
    tag_key    TEXT    NOT NULL,                  -- 'screenshot' | 'monochrome' | 'night' | 'document' | 'blurry'
    score      REAL    NOT NULL,                  -- 0..1
    created_at INTEGER NOT NULL,
    PRIMARY KEY (asset_id, tag_key)
  )`,

  // User dismissals of suggested tags — used for per-tag threshold tuning (v2)
  `CREATE TABLE IF NOT EXISTS tag_rejections (
    tag_key    TEXT    NOT NULL,
    asset_id   TEXT    NOT NULL REFERENCES media_index(uri) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (tag_key, asset_id)
  )`,

  // ── Face Pipeline tables (v10) ───────────────────────────────────────────

  // Detected faces per asset (bounding boxes + quality metadata from ML Kit)
  `CREATE TABLE IF NOT EXISTS detected_faces (
    face_id      TEXT    PRIMARY KEY,               -- "{asset_id}_{face_index}"
    asset_id     TEXT    NOT NULL REFERENCES media_index(uri) ON DELETE CASCADE,
    face_index   INTEGER NOT NULL,                  -- 0-based index within the photo
    left_norm    REAL    NOT NULL DEFAULT 0,        -- bounding box, 0.0–1.0 normalised
    top_norm     REAL    NOT NULL DEFAULT 0,
    right_norm   REAL    NOT NULL DEFAULT 0,
    bottom_norm  REAL    NOT NULL DEFAULT 0,
    width_px     INTEGER NOT NULL DEFAULT 0,        -- absolute pixel dimensions
    height_px    INTEGER NOT NULL DEFAULT 0,
    yaw          REAL    NOT NULL DEFAULT 0,        -- head Euler angles (degrees)
    pitch        REAL    NOT NULL DEFAULT 0,
    roll         REAL    NOT NULL DEFAULT 0,
    quality_score REAL   NOT NULL DEFAULT 0,        -- 0–1 composite (size + sharpness)
    cluster_id   TEXT,                              -- FK face_clusters.cluster_id (nullable)
    created_at   INTEGER NOT NULL
  )`,

  // Face identity embeddings (one per detected face).
  // dim is stored per-row: 128 for MobileFaceNet, 512 for ArcFace ResNet100.
  `CREATE TABLE IF NOT EXISTS face_embeddings (
    face_id       TEXT    PRIMARY KEY REFERENCES detected_faces(face_id) ON DELETE CASCADE,
    model_version TEXT    NOT NULL,
    dim           INTEGER NOT NULL DEFAULT 128,
    embedding     BLOB    NOT NULL,                 -- base64-encoded Float32, L2-normalized
    created_at    INTEGER NOT NULL
  )`,

  // Internal person clusters — hidden; each cluster maps to one people-category tag
  `CREATE TABLE IF NOT EXISTS face_clusters (
    cluster_id   TEXT    PRIMARY KEY,               -- UUID
    centroid_blob BLOB,                             -- mean face embedding (L2-normalized, dim stored per row)
    dim          INTEGER NOT NULL DEFAULT 128,
    n            INTEGER NOT NULL DEFAULT 0,        -- number of faces averaged in
    tag_id       INTEGER REFERENCES tags(id) ON DELETE SET NULL, -- bound people tag
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL
  )`,

  // Co-occurrence counts — how often tagA and tagB appear on the same photo
  `CREATE TABLE IF NOT EXISTS tag_cooccurrences (
    tag_id_a  INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    tag_id_b  INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    count     INTEGER NOT NULL DEFAULT 1,
    last_seen INTEGER NOT NULL,                     -- Unix ms of last co-occurrence
    PRIMARY KEY (tag_id_a, tag_id_b)
  )`,

  // ── Tag Intelligence tables (v11) ────────────────────────────────────────

  // Alternate search terms / synonyms for a canonical tag
  `CREATE TABLE IF NOT EXISTS tag_aliases (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    tag_id     INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    alias      TEXT    NOT NULL,                    -- lowercase search term
    source     TEXT    NOT NULL DEFAULT 'ai',       -- 'ai' | 'user'
    created_at INTEGER NOT NULL,
    UNIQUE (tag_id, alias)
  )`,

  // AI-generated suggestions awaiting user review (Accept / Edit / Reject)
  `CREATE TABLE IF NOT EXISTS tag_review_queue (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    tag_id          INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    analysis_type   TEXT    NOT NULL,               -- 'category' | 'description' | 'alias' | 'canonical_name'
    suggested_value TEXT    NOT NULL,               -- the AI's suggestion
    reasoning       TEXT,                           -- AI's reasoning text
    status          TEXT    NOT NULL DEFAULT 'pending', -- 'pending' | 'accepted' | 'edited' | 'rejected'
    edited_value    TEXT,                           -- non-null when user edited before accepting
    created_at      INTEGER NOT NULL,
    reviewed_at     INTEGER                         -- Unix ms when user acted on this item
  )`,

  // Log of every accepted (or edited+accepted) change applied to tags
  `CREATE TABLE IF NOT EXISTS tag_change_history (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    tag_id          INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    tag_name        TEXT    NOT NULL,               -- snapshot of name at time of change
    field_changed   TEXT    NOT NULL,               -- e.g. 'category', 'description', 'alias_added'
    old_value       TEXT,
    new_value       TEXT    NOT NULL,
    change_source   TEXT    NOT NULL DEFAULT 'ai_review', -- 'ai_review' | 'user'
    review_queue_id INTEGER REFERENCES tag_review_queue(id) ON DELETE SET NULL,
    changed_at      INTEGER NOT NULL
  )`,
];

// ─── Indexes ─────────────────────────────────────────────────────────────────

export const INDEX_SQL = [
  // media_index — all columns used in WHERE / ORDER BY clauses get an index
  'CREATE INDEX IF NOT EXISTS idx_media_album      ON media_index(album_id)',
  'CREATE INDEX IF NOT EXISTS idx_media_type       ON media_index(media_type)',
  'CREATE INDEX IF NOT EXISTS idx_media_favorite   ON media_index(favorite)',
  'CREATE INDEX IF NOT EXISTS idx_media_rating     ON media_index(rating)',
  'CREATE INDEX IF NOT EXISTS idx_media_hidden     ON media_index(hidden)',
  'CREATE INDEX IF NOT EXISTS idx_media_created    ON media_index(device_created_at)',
  'CREATE INDEX IF NOT EXISTS idx_media_size       ON media_index(file_size)',
  'CREATE INDEX IF NOT EXISTS idx_media_duration   ON media_index(duration)',
  'CREATE INDEX IF NOT EXISTS idx_media_width      ON media_index(width)',
  'CREATE INDEX IF NOT EXISTS idx_media_height     ON media_index(height)',
  'CREATE INDEX IF NOT EXISTS idx_media_scanned    ON media_index(scanned_at)',

  // media_tags — for reverse lookups (tag → media list)
  'CREATE INDEX IF NOT EXISTS idx_media_tags_tag   ON media_tags(tag_id)',

  // Composite index for the most common query: "all media in an album, newest first"
  // Covers WHERE album_id = ? ORDER BY device_created_at DESC in a single B-tree scan.
  'CREATE INDEX IF NOT EXISTS idx_media_album_date ON media_index(album_id, device_created_at DESC)',

  // albums
  'CREATE INDEX IF NOT EXISTS idx_albums_hidden    ON albums(hidden)',
  'CREATE INDEX IF NOT EXISTS idx_albums_pinned    ON albums(pinned)',

  // tags — sorted by popularity in UI
  'CREATE INDEX IF NOT EXISTS idx_tags_usage           ON tags(usage_count)',

  // tag_suggestions — look up all suggestions for an asset
  'CREATE INDEX IF NOT EXISTS idx_tag_suggestions_asset ON tag_suggestions(asset_id)',

  // image_embeddings — look up by model version for re-indexing queries
  'CREATE INDEX IF NOT EXISTS idx_img_emb_model         ON image_embeddings(model_version)',

  // heuristic_tags — look up all heuristic results for an asset
  'CREATE INDEX IF NOT EXISTS idx_heuristic_asset        ON heuristic_tags(asset_id)',

  // tag_rejections — look up rejection count per tag
  'CREATE INDEX IF NOT EXISTS idx_tag_rejections_key     ON tag_rejections(tag_key)',

  // detected_faces — look up faces by asset, or by cluster
  'CREATE INDEX IF NOT EXISTS idx_detected_faces_asset   ON detected_faces(asset_id)',
  'CREATE INDEX IF NOT EXISTS idx_detected_faces_cluster ON detected_faces(cluster_id)',

  // face_embeddings — look up unembedded faces by model version
  'CREATE INDEX IF NOT EXISTS idx_face_emb_model         ON face_embeddings(model_version)',

  // face_clusters — look up cluster bound to a people tag
  'CREATE INDEX IF NOT EXISTS idx_face_cluster_tag       ON face_clusters(tag_id)',

  // tag_cooccurrences — look up co-occurring tags from either direction
  'CREATE INDEX IF NOT EXISTS idx_cooccur_a              ON tag_cooccurrences(tag_id_a)',
  'CREATE INDEX IF NOT EXISTS idx_cooccur_b              ON tag_cooccurrences(tag_id_b)',

  // tag_aliases — fast lookup by alias text or by tag
  'CREATE INDEX IF NOT EXISTS idx_tag_aliases_tag        ON tag_aliases(tag_id)',
  'CREATE INDEX IF NOT EXISTS idx_tag_aliases_alias      ON tag_aliases(alias)',

  // tag_review_queue — filter by status and by tag
  'CREATE INDEX IF NOT EXISTS idx_review_queue_status    ON tag_review_queue(status)',
  'CREATE INDEX IF NOT EXISTS idx_review_queue_tag       ON tag_review_queue(tag_id)',

  // tag_change_history — timeline queries
  'CREATE INDEX IF NOT EXISTS idx_change_history_tag     ON tag_change_history(tag_id)',
  'CREATE INDEX IF NOT EXISTS idx_change_history_time    ON tag_change_history(changed_at)',
];

// ─── Migrations ──────────────────────────────────────────────────────────────
// When the schema needs to evolve, add a new entry here and bump DB_VERSION.
// DatabaseService runs any un-applied migration steps on startup.
//
// Each key is the TARGET version number. Steps run sequentially when upgrading
// from any earlier version to DB_VERSION.

export const MIGRATIONS = {
  // v3 — Thumbnail URI persistence (Simple Gallery pattern).
  // Persists the resolved file:// thumb path so ThumbnailCache skips the
  // native bridge on subsequent folder opens (zero bridge calls on warm start).
  3: ['ALTER TABLE media_index ADD COLUMN thumb_uri TEXT'],

  // v6 — Remove face recognition tables (People feature removed).
  // DROP TABLE IF EXISTS so this is safe on DBs that never had these tables.
  6: [
    'DROP TABLE IF EXISTS face_anchors',
    'DROP TABLE IF EXISTS face_rejections',
    'DROP TABLE IF EXISTS people_faces',
    'DROP TABLE IF EXISTS people',
    'DROP TABLE IF EXISTS faces',
    'DROP TABLE IF EXISTS image_embeddings',
  ],

  // v7 — Offline smart tagging with on-device TFLite embeddings.
  // Adds image_embeddings, heuristic_tags, and tag_rejections tables.
  // Note: v6 dropped a previous incarnation of image_embeddings (face-specific);
  //       this v7 version has a composite PK (asset_id, model_version) to support
  //       multiple model versions coexisting during incremental re-indexing.
  7: [
    `CREATE TABLE IF NOT EXISTS image_embeddings (
      asset_id      TEXT    NOT NULL REFERENCES media_index(uri) ON DELETE CASCADE,
      model_version TEXT    NOT NULL,
      dim           INTEGER NOT NULL,
      embedding     BLOB    NOT NULL,
      created_at    INTEGER NOT NULL,
      PRIMARY KEY (asset_id, model_version)
    )`,
    `CREATE TABLE IF NOT EXISTS heuristic_tags (
      asset_id   TEXT    NOT NULL REFERENCES media_index(uri) ON DELETE CASCADE,
      tag_key    TEXT    NOT NULL,
      score      REAL    NOT NULL,
      created_at INTEGER NOT NULL,
      PRIMARY KEY (asset_id, tag_key)
    )`,
    `CREATE TABLE IF NOT EXISTS tag_rejections (
      tag_key    TEXT    NOT NULL,
      asset_id   TEXT    NOT NULL REFERENCES media_index(uri) ON DELETE CASCADE,
      created_at INTEGER NOT NULL,
      PRIMARY KEY (tag_key, asset_id)
    )`,
    'CREATE INDEX IF NOT EXISTS idx_img_emb_model     ON image_embeddings(model_version)',
    'CREATE INDEX IF NOT EXISTS idx_heuristic_asset   ON heuristic_tags(asset_id)',
    'CREATE INDEX IF NOT EXISTS idx_tag_rejections_key ON tag_rejections(tag_key)',
  ],

  // v8 — Tag description + edit timestamp.
  // Adds optional free-text description and an updated_at column so the tags
  // screen can display when a tag was last edited.
  8: [
    'ALTER TABLE tags ADD COLUMN description TEXT',
    'ALTER TABLE tags ADD COLUMN updated_at  INTEGER',
  ],

  // v9 — Tag categories.
  // Adds a category column so each tag can be grouped into one of 9 buckets:
  //   people | animals | places | objects | activity | style | content | nsfw | misc
  // All existing rows start as 'misc'; TagService.backfillTagCategories() will
  // re-classify them by name in the background after the migration runs.
  9: ["ALTER TABLE tags ADD COLUMN category TEXT NOT NULL DEFAULT 'misc'"],

  // v10 — Face pipeline + co-occurrence learning.
  10: [
    `CREATE TABLE IF NOT EXISTS detected_faces (
      face_id       TEXT    PRIMARY KEY,
      asset_id      TEXT    NOT NULL REFERENCES media_index(uri) ON DELETE CASCADE,
      face_index    INTEGER NOT NULL,
      left_norm     REAL    NOT NULL DEFAULT 0,
      top_norm      REAL    NOT NULL DEFAULT 0,
      right_norm    REAL    NOT NULL DEFAULT 0,
      bottom_norm   REAL    NOT NULL DEFAULT 0,
      width_px      INTEGER NOT NULL DEFAULT 0,
      height_px     INTEGER NOT NULL DEFAULT 0,
      yaw           REAL    NOT NULL DEFAULT 0,
      pitch         REAL    NOT NULL DEFAULT 0,
      roll          REAL    NOT NULL DEFAULT 0,
      quality_score REAL    NOT NULL DEFAULT 0,
      cluster_id    TEXT,
      created_at    INTEGER NOT NULL
    )`,
    `CREATE TABLE IF NOT EXISTS face_embeddings (
      face_id       TEXT    PRIMARY KEY REFERENCES detected_faces(face_id) ON DELETE CASCADE,
      model_version TEXT    NOT NULL,
      dim           INTEGER NOT NULL DEFAULT 128,
      embedding     BLOB    NOT NULL,
      created_at    INTEGER NOT NULL
    )`,
    `CREATE TABLE IF NOT EXISTS face_clusters (
      cluster_id    TEXT    PRIMARY KEY,
      centroid_blob BLOB,
      dim           INTEGER NOT NULL DEFAULT 128,
      n             INTEGER NOT NULL DEFAULT 0,
      tag_id        INTEGER REFERENCES tags(id) ON DELETE SET NULL,
      created_at    INTEGER NOT NULL,
      updated_at    INTEGER NOT NULL
    )`,
    `CREATE TABLE IF NOT EXISTS tag_cooccurrences (
      tag_id_a  INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
      tag_id_b  INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
      count     INTEGER NOT NULL DEFAULT 1,
      last_seen INTEGER NOT NULL,
      PRIMARY KEY (tag_id_a, tag_id_b)
    )`,
    'CREATE INDEX IF NOT EXISTS idx_detected_faces_asset   ON detected_faces(asset_id)',
    'CREATE INDEX IF NOT EXISTS idx_detected_faces_cluster ON detected_faces(cluster_id)',
    'CREATE INDEX IF NOT EXISTS idx_face_emb_model         ON face_embeddings(model_version)',
    'CREATE INDEX IF NOT EXISTS idx_face_cluster_tag       ON face_clusters(tag_id)',
    'CREATE INDEX IF NOT EXISTS idx_cooccur_a              ON tag_cooccurrences(tag_id_a)',
    'CREATE INDEX IF NOT EXISTS idx_cooccur_b              ON tag_cooccurrences(tag_id_b)',
  ],

  // v11 — Tag Intelligence System.
  // Adds alias, review queue, and change history tables.
  // Also adds last_reviewed_at to tags (ALTER TABLE is idempotent via try/catch in DatabaseService).
  11: [
    'ALTER TABLE tags ADD COLUMN last_reviewed_at INTEGER',
    `CREATE TABLE IF NOT EXISTS tag_aliases (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      tag_id     INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
      alias      TEXT    NOT NULL,
      source     TEXT    NOT NULL DEFAULT 'ai',
      created_at INTEGER NOT NULL,
      UNIQUE (tag_id, alias)
    )`,
    `CREATE TABLE IF NOT EXISTS tag_review_queue (
      id              INTEGER PRIMARY KEY AUTOINCREMENT,
      tag_id          INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
      analysis_type   TEXT    NOT NULL,
      suggested_value TEXT    NOT NULL,
      reasoning       TEXT,
      status          TEXT    NOT NULL DEFAULT 'pending',
      edited_value    TEXT,
      created_at      INTEGER NOT NULL,
      reviewed_at     INTEGER
    )`,
    `CREATE TABLE IF NOT EXISTS tag_change_history (
      id              INTEGER PRIMARY KEY AUTOINCREMENT,
      tag_id          INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
      tag_name        TEXT    NOT NULL,
      field_changed   TEXT    NOT NULL,
      old_value       TEXT,
      new_value       TEXT    NOT NULL,
      change_source   TEXT    NOT NULL DEFAULT 'ai_review',
      review_queue_id INTEGER REFERENCES tag_review_queue(id) ON DELETE SET NULL,
      changed_at      INTEGER NOT NULL
    )`,
    'CREATE INDEX IF NOT EXISTS idx_tag_aliases_tag        ON tag_aliases(tag_id)',
    'CREATE INDEX IF NOT EXISTS idx_tag_aliases_alias      ON tag_aliases(alias)',
    'CREATE INDEX IF NOT EXISTS idx_review_queue_status    ON tag_review_queue(status)',
    'CREATE INDEX IF NOT EXISTS idx_review_queue_tag       ON tag_review_queue(tag_id)',
    'CREATE INDEX IF NOT EXISTS idx_change_history_tag     ON tag_change_history(tag_id)',
    'CREATE INDEX IF NOT EXISTS idx_change_history_time    ON tag_change_history(changed_at)',
  ],
};
