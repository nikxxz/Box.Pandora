package com.example.boxpandora.data.repository

import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.example.boxpandora.data.local.AppDatabase
import com.example.boxpandora.data.local.dao.AlbumDao
import com.example.boxpandora.data.local.dao.FaceDao
import com.example.boxpandora.data.local.dao.ImageEmbeddingDao
import com.example.boxpandora.data.local.dao.MediaItemDao
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.manager.FileConflictResolution
import com.example.boxpandora.data.manager.FileSystemManager
import com.example.boxpandora.data.manager.ThumbnailManager
import com.example.boxpandora.data.util.NomediaScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "MediaRepository"

class MediaRepository(
    private val database: AppDatabase,
    private val mediaItemDao: MediaItemDao,
    private val albumDao: AlbumDao,
    private val imageEmbeddingDao: ImageEmbeddingDao,
    private val faceDao: FaceDao,
    private val mediaStoreRepository: MediaStoreRepository,
    private val thumbnailManager: ThumbnailManager,
    private val fileSystemManager: FileSystemManager,
    val tagRepository: TagRepository
) {
    private val nomediaScanner = NomediaScanner()
    private val librarySyncMutex = Mutex()

    data class MediaSearchParams(
        val query: String = "",
        val type: String = "all",
        val format: String = "all",
        val tagCategory: String = "All"
    ) {
        val terms: List<String> get() =
            query.trim().split(Regex("[,\\s]+")).filter { it.isNotBlank() }

        val isActive: Boolean get() =
            query.isNotBlank() || type != "all" || format != "all" || tagCategory != "All"
    }

    suspend fun searchMedia(
        params: MediaSearchParams,
        albumId: Long? = null,
        showHidden: Boolean = false,
        untaggedOnly: Boolean = false
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val candidates = mediaItemDao.searchFiltered(
            showHidden  = showHidden,
            albumId     = albumId ?: -1L,
            type        = params.type,
            format      = params.format.lowercase(),
            tagCategory = params.tagCategory,
            query       = params.query.trim(),
            untaggedOnly = untaggedOnly
        )

        if (candidates.isEmpty()) return@withContext emptyList()

        val terms = params.terms
        if (terms.isEmpty()) return@withContext candidates

        val uriTagMap = tagRepository.getTagNamesForUris(candidates.map { it.uri })
            .groupBy { it.mediaUri }
            .mapValues { (_, rows) ->
                rows.flatMap { listOf(it.tagName.lowercase(), it.normalizedName.lowercase()) }.toSet()
            }

        candidates.filter { item ->
            val fn   = item.filename.lowercase()
            val tags = uriTagMap[item.uri] ?: emptySet()
            terms.all { term ->
                val t = term.lowercase()
                fn.contains(t) || tags.any { it.contains(t) }
            }
        }
    }

    fun getAllMediaPaged(showHidden: Boolean): Flow<PagingData<MediaItem>> {
        return Pager(
            config = PagingConfig(pageSize = 60, enablePlaceholders = true),
            pagingSourceFactory = { mediaItemDao.getAllMediaPaged(showHidden) }
        ).flow
    }

    fun getMediaByAlbumPaged(albumId: Long, showHidden: Boolean, untaggedOnly: Boolean = false): Flow<PagingData<MediaItem>> {
        return Pager(
            config = PagingConfig(pageSize = 60, enablePlaceholders = true),
            pagingSourceFactory = {
                if (untaggedOnly) mediaItemDao.getUntaggedMediaByAlbumPaged(albumId, showHidden)
                else mediaItemDao.getMediaByAlbumPaged(albumId, showHidden)
            }
        ).flow
    }

    fun getAlbumsFlow(showHidden: Boolean): Flow<List<Album>> {
        return albumDao.getAlbumsFlow(showHidden)
    }

    fun getMediaByAlbumFlow(albumName: String, showHidden: Boolean = false): Flow<List<MediaItem>> {
        return mediaItemDao.getMediaByAlbumFlow(albumName, showHidden)
    }

    suspend fun getAlbumIdByName(albumName: String): Long? {
        return albumDao.getByName(albumName)?.id
    }

    fun getMediaByAlbumIdFlow(albumId: Long, showHidden: Boolean = false, untaggedOnly: Boolean = false): Flow<List<MediaItem>> {
        return if (untaggedOnly) mediaItemDao.getUntaggedMediaByAlbumIdFlow(albumId, showHidden)
        else mediaItemDao.getMediaByAlbumIdFlow(albumId, showHidden)
    }

    fun getMediaByTagFlow(tagId: Long, showHidden: Boolean = false): Flow<List<MediaItem>> {
        return mediaItemDao.getMediaByTagFlow(tagId, showHidden)
    }

    suspend fun deleteMediaItems(uris: List<String>): Boolean = withContext(Dispatchers.IO) {
        val items = uris.mapNotNull { mediaItemDao.getByUri(it) }
        if (fileSystemManager.deleteMediaItems(items)) {
            mediaItemDao.deleteByUris(uris)
            uris.forEach { thumbnailManager.deleteThumbnail(it) }
            syncMediaStore()
            true
        } else {
            false
        }
    }

    suspend fun renameMediaItem(uri: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val item = mediaItemDao.getByUri(uri) ?: return@withContext false
        val newFile = fileSystemManager.renameMediaItem(item, newName)
        if (newFile != null) {
            val newUri = fileSystemManager.scanFileWait(newFile)
            if (newUri != null) {
                val newItem = mediaStoreRepository.fetchMediaByPath(newFile.absolutePath)
                if (newItem != null) {
                    transferMetadata(item, newItem, deleteOld = true)
                    thumbnailManager.deleteThumbnail(uri)
                }
            }
            syncMediaStore()
            true
        } else {
            false
        }
    }

    suspend fun copyMediaItems(
        uris: List<String>,
        destinationPath: String,
        onConflict: suspend (fileName: String, destPath: String, itemIndex: Int, totalCount: Int) -> FileConflictResolution =
            { _, _, _, _ -> FileConflictResolution.AUTO_RENAME }
    ): Boolean = withContext(Dispatchers.IO) {
        val items = uris.mapNotNull { mediaItemDao.getByUri(it) }
        val destFolder = File(destinationPath)
        val results = fileSystemManager.copyMediaItems(items, destFolder, onConflict)
        results.forEach { (oldItem, newFile) ->
            val newUri = fileSystemManager.scanFileWait(newFile)
            if (newUri != null) {
                val newItemFromStore = mediaStoreRepository.fetchMediaByPath(newFile.absolutePath)
                if (newItemFromStore != null) {
                    transferMetadata(oldItem, newItemFromStore, deleteOld = false)
                }
            }
        }
        syncMediaStore()
        results.isNotEmpty()
    }

    suspend fun moveMediaItems(
        uris: List<String>,
        destinationPath: String,
        onConflict: suspend (fileName: String, destPath: String, itemIndex: Int, totalCount: Int) -> FileConflictResolution =
            { _, _, _, _ -> FileConflictResolution.AUTO_RENAME }
    ): Boolean = withContext(Dispatchers.IO) {
        val items = uris.mapNotNull { mediaItemDao.getByUri(it) }
        val destFolder = File(destinationPath)
        val results = fileSystemManager.moveMediaItems(items, destFolder, onConflict)
        results.forEach { (oldItem, newFile) ->
            val newUri = fileSystemManager.scanFileWait(newFile)
            if (newUri != null) {
                val newItemFromStore = mediaStoreRepository.fetchMediaByPath(newFile.absolutePath)
                if (newItemFromStore != null) {
                    transferMetadata(oldItem, newItemFromStore, deleteOld = true)
                    thumbnailManager.deleteThumbnail(oldItem.uri)
                }
            }
        }
        syncMediaStore()
        results.isNotEmpty()
    }

    suspend fun setMediaItemFavorite(uri: String, favorite: Int) = withContext(Dispatchers.IO) {
        mediaItemDao.setFavorite(uri, favorite)
    }

    /**
     * Toggle the favourite flag on a single item.
     * Returns the new favorite value (0 or 1).
     */
    suspend fun toggleFavorite(item: MediaItem): Int = withContext(Dispatchers.IO) {
        val newFav = if (item.isFavorite == 1) 0 else 1
        mediaItemDao.setFavorite(item.uri, newFav)
        newFav
    }

    /**
     * Set favourite flag for a batch of items.
     * @param items list of items to update
     * @param toFavorite true = mark as favourite, false = remove favourite
     */
    suspend fun batchToggleFavorite(items: List<MediaItem>, toFavorite: Boolean) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        mediaItemDao.batchSetFavorite(items.map { it.uri }, if (toFavorite) 1 else 0)
    }

    /**
     * Reactive stream of all favourite media items.
     */
    fun getFavoritesFlow(showHidden: Boolean = false): Flow<List<MediaItem>> =
        mediaItemDao.getFavoritesFlow(showHidden)

    /**
     * Fetch a list of MediaItems by their URIs in one query.
     */
    suspend fun getMediaByUris(uris: List<String>): List<MediaItem> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) emptyList() else mediaItemDao.getByUris(uris)
    }

    private suspend fun transferMetadata(
        oldItem: MediaItem,
        newItemFromStore: MediaItem,
        deleteOld: Boolean
    ) = librarySyncMutex.withLock {
        transferMetadataLocked(oldItem, newItemFromStore, deleteOld)
    }

    private suspend fun transferMetadataLocked(
        oldItem: MediaItem,
        newItemFromStore: MediaItem,
        deleteOld: Boolean
    ) {
        val oldUri = oldItem.uri
        val newUri = newItemFromStore.uri

        database.withTransaction {
            val safeNewItem = ensureAlbumExists(newItemFromStore)
            val updatedNewItem = safeNewItem.copy(
                rating     = oldItem.rating,
                isFavorite = oldItem.isFavorite,
                notes      = oldItem.notes,
                isHidden   = oldItem.isHidden
            )
            mediaItemDao.insertAll(listOf(updatedNewItem))
            mediaItemDao.updateAll(listOf(updatedNewItem))

            if (oldUri == newUri) return@withTransaction

            tagRepository.transferTagMetadata(oldUri, newUri)
            val embeddings = imageEmbeddingDao.getForAsset(oldUri)
            if (embeddings.isNotEmpty()) {
                imageEmbeddingDao.insertAll(embeddings.map { it.copy(assetId = newUri) })
            }
            val faces = faceDao.getFacesForAsset(oldUri)
            faces.forEach { face ->
                val newFaceId = face.faceId.replace(oldUri, newUri)
                val embedding = faceDao.getEmbeddingForFace(face.faceId)
                faceDao.insertFace(face.copy(faceId = newFaceId, assetId = newUri))
                embedding?.let { faceDao.insertEmbedding(it.copy(faceId = newFaceId)) }
            }
            if (deleteOld) {
                mediaItemDao.deleteByUris(listOf(oldUri))
            }
        }
    }

    private suspend fun ensureAlbumExists(item: MediaItem): MediaItem {
        val albumId = item.albumId ?: return item
        val albumName = item.albumName
        if (albumName == null) {
            Log.w(TAG, "Dropping unresolved album reference for uri=${item.uri} albumId=$albumId")
            return item.copy(albumId = null)
        }
        if (albumDao.getById(albumId) == null) {
            albumDao.upsertAll(listOf(buildAlbum(albumId, albumName, listOf(item))))
        }
        return item
    }

    private fun buildAlbum(albumId: Long, albumName: String, items: List<MediaItem>): Album {
        val coverItem = items.maxByOrNull { it.deviceCreatedAt ?: 0L } ?: items.first()
        val photoItems = items.filter { it.mediaType == "image" }
        val videoItems = items.filter { it.mediaType == "video" }

        return Album(
            id = albumId,
            name = albumName,
            path = items.firstOrNull { it.filePath != null }?.filePath?.let { File(it).parent },
            mediaCount = items.size,
            photoCount = photoItems.size,
            videoCount = videoItems.size,
            coverUri = coverItem.uri,
            coverFilePath = coverItem.filePath,
            photoCoverUri = photoItems.maxByOrNull { it.deviceCreatedAt ?: 0L }?.uri,
            videoCoverUri = videoItems.maxByOrNull { it.deviceCreatedAt ?: 0L }?.uri,
            lastModifiedAt = items.maxOfOrNull { it.deviceModifiedAt ?: it.deviceCreatedAt ?: 0L },
            isHidden = items.any { it.isHidden == 1 }
        )
    }

    suspend fun deleteAlbums(albumIds: List<Long>): Boolean = withContext(Dispatchers.IO) {
        val albums = albumIds.mapNotNull { albumDao.getById(it) }
        if (fileSystemManager.deleteAlbums(albums)) { syncMediaStore(); true } else false
    }

    suspend fun renameAlbum(albumId: Long, newName: String): Boolean = withContext(Dispatchers.IO) {
        val album = albumDao.getById(albumId) ?: return@withContext false
        val newFolder = fileSystemManager.renameAlbum(album, newName)
        if (newFolder != null) { syncMediaStore(); true } else false
    }

    suspend fun setAlbumsPinned(albumIds: List<Long>, pinned: Boolean) = withContext(Dispatchers.IO) {
        albumIds.forEach { id ->
            albumDao.setPinned(id, pinned)
        }
    }

    suspend fun copyAlbums(albumIds: List<Long>, destinationPath: String): Boolean = withContext(Dispatchers.IO) {
        var anySuccess = false
        for (id in albumIds) {
            val album = albumDao.getById(id) ?: continue
            val items = mediaItemDao.getMediaByAlbum(id)
            val destFolder = File(destinationPath, album.name)
            val results = fileSystemManager.copyMediaItems(items, destFolder)
            results.forEach { (oldItem, newFile) ->
                val newUri = fileSystemManager.scanFileWait(newFile)
                if (newUri != null) {
                    val newItemFromStore = mediaStoreRepository.fetchMediaByPath(newFile.absolutePath)
                    if (newItemFromStore != null) transferMetadata(oldItem, newItemFromStore, deleteOld = false)
                }
            }
            if (results.isNotEmpty()) anySuccess = true
        }
        syncMediaStore()
        anySuccess
    }

    suspend fun moveAlbums(albumIds: List<Long>, destinationPath: String): Boolean = withContext(Dispatchers.IO) {
        var anySuccess = false
        for (id in albumIds) {
            val album = albumDao.getById(id) ?: continue
            val items = mediaItemDao.getMediaByAlbum(id)
            val destFolder = File(destinationPath, album.name)
            val results = fileSystemManager.moveMediaItems(items, destFolder)
            results.forEach { (oldItem, newFile) ->
                val newUri = fileSystemManager.scanFileWait(newFile)
                if (newUri != null) {
                    val newItemFromStore = mediaStoreRepository.fetchMediaByPath(newFile.absolutePath)
                    if (newItemFromStore != null) {
                        transferMetadata(oldItem, newItemFromStore, deleteOld = true)
                        thumbnailManager.deleteThumbnail(oldItem.uri)
                    }
                }
            }
            if (results.isNotEmpty()) anySuccess = true
            if (results.size == items.size) {
                album.path?.let {
                    val oldFolder = File(it)
                    if (oldFolder.exists() && oldFolder.isDirectory && oldFolder.list()?.isEmpty() == true) {
                        oldFolder.delete()
                    }
                }
            }
        }
        syncMediaStore()
        anySuccess
    }

    suspend fun setAlbumsHidden(albumIds: List<Long>, hidden: Boolean) = withContext(Dispatchers.IO) {
        albumIds.forEach { id ->
            val album = albumDao.getById(id) ?: return@forEach
            val folderPath = album.path ?: return@forEach

            // Collect all items whose file_path sits inside this folder BEFORE changing the
            // hidden state.  We query by path prefix rather than albumId because hidden items
            // are indexed by NomediaScanner with a hash-based albumId that differs from the
            // MediaStore bucket ID, so a simple albumId lookup would miss them.
            val existingItems = mediaItemDao.getMediaByFolderPath("$folderPath/")

            if (!fileSystemManager.setAlbumHidden(album, hidden)) return@forEach
            albumDao.setHidden(album.name, hidden)

            if (!hidden) {
                // UNHIDING: existing items have file:// URIs (inserted by NomediaScanner while
                // the folder was hidden).  MediaStore re-indexing is asynchronous — if we let
                // syncMediaStore() run immediately, MediaStore may not have finished and neither
                // scanner sees the files, making them look like genuine deletes and CASCADE-wiping
                // all tag associations.
                //
                // Fix: scanFileWait each file so MediaStore assigns a content:// URI synchronously,
                // then call transferMetadata to move tags/embeddings/faces to the new URI before
                // any deletion can happen.
                for (item in existingItems) {
                    val filePath = item.filePath ?: continue
                    val file = File(filePath)
                    if (!file.exists()) continue

                    // Block until MediaStore has assigned a content:// URI to this file.
                    fileSystemManager.scanFileWait(file)
                    val newItem = mediaStoreRepository.fetchMediaByPath(filePath)
                    if (newItem != null && newItem.uri != item.uri) {
                        Log.d(TAG, "setAlbumsHidden(unhide): transferring tags " +
                            "${item.uri} → ${newItem.uri}  path=$filePath")
                        transferMetadata(item, newItem, deleteOld = true)
                    }
                    // If fetchMediaByPath returned null (MediaStore still not ready), the
                    // file-exists guard in syncMediaStore() prevents deletion of the file://
                    // entry — the URI-transition detection will complete the transfer on the
                    // next sync once MediaStore finishes re-indexing.
                }
            }
            // HIDING case: syncMediaStore() below detects the content:// → file:// transition
            // via the synchronous NomediaScanner walk and calls transferMetadata automatically.
        }
        syncMediaStore()
    }

    suspend fun setMediaItemsHidden(uris: List<String>, hidden: Boolean) = withContext(Dispatchers.IO) {
        uris.forEach { uri ->
            val item = mediaItemDao.getByUri(uri) ?: return@forEach
            val newFile = fileSystemManager.setMediaItemHidden(item, hidden)
            if (newFile != null) {
                val newUri = fileSystemManager.scanFileWait(newFile)
                if (newUri != null) {
                    val newItemFromStore = mediaStoreRepository.fetchMediaByPath(newFile.absolutePath)
                    if (newItemFromStore != null) {
                        transferMetadata(item, newItemFromStore, deleteOld = true)
                    }
                }
            }
        }
        syncMediaStore()
    }

    suspend fun syncMediaStore(
        isFullScan: Boolean = false,
        showImages: Boolean = true,
        showVideos: Boolean = true,
        showGifs: Boolean = true,
        excludedPaths: Set<String> = emptySet(),
        onProgress: ((String, Float) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        librarySyncMutex.withLock {
            try {
            onProgress?.invoke("Fetching MediaStore content...", 0.1f)
            val mediaStoreItems = mediaStoreRepository.fetchAllMedia(
                showImages = showImages,
                showVideos = showVideos,
                showGifs = showGifs,
                excludedPaths = excludedPaths
            )

            onProgress?.invoke("Scanning for hidden folders...", 0.3f)
            val nomediaResults = nomediaScanner.scanForNomediaFolders()
            
            val allScannedItems = mediaStoreItems + nomediaResults.mediaItems
            val allScannedUris = allScannedItems.map { it.uri }.toSet()

            onProgress?.invoke("Comparing with database...", 0.5f)
            val existingUris = mediaItemDao.getAllUris().toSet()

            // Safety guard: if MediaStore returned nothing but we already have items indexed,
            // the scan almost certainly failed (transient permission loss, MediaStore not ready,
            // query returned null, etc.).  Deleting everything here would CASCADE-wipe all
            // media_tags associations while leaving the tags table intact — exactly the
            // "tags show correct counts but no images appear" symptom.  Bail out and let the
            // next sync retry when MediaStore is healthy.
            if (allScannedItems.isEmpty() && existingUris.isNotEmpty()) {
                Log.w(TAG, "syncMediaStore: MediaStore scan returned 0 items but DB has " +
                    "${existingUris.size} existing entries — aborting deletion to prevent data loss")
                onProgress?.invoke("Sync skipped (empty scan)", -1f)
                return@withContext
            }

            val urisToDelete = existingUris.filter { !allScannedUris.contains(it) }
            if (urisToDelete.isNotEmpty()) {
                // Detect URI-scheme transitions caused by folder hide/unhide events.
                //
                // When a folder is hidden (.nomedia added), MediaStore stops returning its items
                // (dropping the content:// URIs) and NomediaScanner picks them up as file:// URIs.
                // The reverse happens on unhide. If we naïvely delete the old content:// URIs,
                // the CASCADE on media_tags wipes every tag association for those items while
                // inserting the file:// entries with no tags — identical to the disappearing-tags
                // bug already seen with empty-scan events.
                //
                // Fix: build a filePath→scannedItem map, then for each URI to be deleted that
                // has the same filePath as a scanned item under a *different* URI, call
                // transferMetadata instead of deleting, which moves tags/embeddings/faces to
                // the new URI.
                val scannedByFilePath = allScannedItems
                    .filter { it.filePath != null }
                    .associateBy { it.filePath!! }

                // Fetch DB rows for the candidates (chunked to stay under SQLite variable limit).
                val dbItemsToDelete = urisToDelete.chunked(500)
                    .flatMap { batch -> mediaItemDao.getByUris(batch) }

                val transitionOldUris = mutableSetOf<String>()

                for (oldItem in dbItemsToDelete) {
                    val filePath = oldItem.filePath ?: continue
                    val scannedCounterpart = scannedByFilePath[filePath] ?: continue
                    if (scannedCounterpart.uri == oldItem.uri) continue // already in scannedUris, shouldn't happen

                    // Same physical file, different URI scheme — this is a hide/unhide transition.
                    Log.d(TAG, "syncMediaStore: URI transition detected — " +
                        "oldUri=${oldItem.uri} → newUri=${scannedCounterpart.uri} path=$filePath")
                    transferMetadataLocked(oldItem, scannedCounterpart, deleteOld = true)
                    transitionOldUris += oldItem.uri
                }

                // Genuine deletes: URIs not explained by a URI-scheme transition AND whose
                // physical file no longer exists on disk.
                //
                // The file-exists guard protects against the hide/unhide timing gap:
                // setAlbumsHidden creates/deletes the .nomedia file and then immediately calls
                // syncMediaStore(), but MediaStore re-indexing is asynchronous.  In that window:
                //   - Hide: MediaStore has dropped the content:// entries; NomediaScanner hasn't
                //     picked up the file:// entries yet → both gone from scan → looks like a delete
                //   - Unhide: NomediaScanner no longer finds file:// entries; MediaStore hasn't
                //     re-added content:// entries yet → same window, same false-delete
                // If the physical file still exists, the item is just temporarily invisible to
                // both scanners.  Skip it — the next sync (triggered once MediaStore finishes
                // re-indexing) will either detect the URI transition and call transferMetadata,
                // or find the item directly.
                val genuineDeletes = urisToDelete.filter { uri ->
                    if (uri in transitionOldUris) return@filter false
                    val dbItem = dbItemsToDelete.find { it.uri == uri }
                    val filePath = dbItem?.filePath
                    if (filePath != null && File(filePath).exists()) {
                        Log.d(TAG, "syncMediaStore: skipping deletion of $uri — " +
                            "file still exists at $filePath (re-index pending)")
                        return@filter false
                    }
                    true
                }
                if (genuineDeletes.isNotEmpty()) {
                    // Batch in chunks of 500 to stay under SQLite's 999-variable limit.
                    genuineDeletes.chunked(500).forEach { batch ->
                        mediaItemDao.deleteByUris(batch)
                    }
                    genuineDeletes.forEach { thumbnailManager.deleteThumbnail(it) }
                }
            }

            // FIX 1: Build and upsert albums BEFORE writing media items.
            // MediaItem.album_id has a FK → Album.id. Inserting/updating media items before
            // the referenced Album row exists causes a foreign key constraint violation.
            onProgress?.invoke("Updating albums...", 0.6f)
            val albums = allScannedItems.groupBy { it.albumId to it.albumName }
                .mapNotNull { (key, items) ->
                    val (albumId, albumName) = key
                    if (albumId == null || albumName == null) return@mapNotNull null
                    buildAlbum(albumId, albumName, items)
                }
            // All FK parent rows now exist in the albums table before any child media rows
            // reference them.
            albumDao.upsertAll(albums)

            onProgress?.invoke("Updating media index...", 0.8f)

            // Diagnostic: warn on any scanned item whose albumId is not in the current scan
            // set (edge case: e.g. a nomedia item whose hashCode ID was not collected above).
            val scannedAlbumIds = albums.map { it.id }.toSet()
            allScannedItems.forEach { item ->
                if (item.albumId != null && item.albumId !in scannedAlbumIds) {
                    Log.w(TAG, "Scanned item references albumId absent from current scan — " +
                        "uri=${item.uri} albumId=${item.albumId} albumName=${item.albumName} " +
                        "bucket=${item.filePath?.let { File(it).parent }} filename=${item.filename}")
                }
            }

            // Log newly discovered items so the bucket/folder context is always visible.
            val newUris = allScannedUris - existingUris
            allScannedItems.filter { it.uri in newUris }.forEach { item ->
                Log.d(TAG, "New media item discovered: uri=${item.uri} albumId=${item.albumId} " +
                    "albumName=${item.albumName} " +
                    "bucket=${item.filePath?.let { File(it).parent }} " +
                    "filename=${item.filename} ext=${item.extension}")
            }

            // FIX 2: Preserve user-mutable fields (rating, favorites, hidden, notes) so that
            // updateAll does not clobber values the user set between scans. Fresh items from
            // MediaStore always carry default zeros/nulls.
            val userMetaMap = mediaItemDao.getAllUserMetadata().associateBy { it.uri }
            val itemsForUpdate = allScannedItems.map { item ->
                val meta = userMetaMap[item.uri]
                if (meta != null) item.copy(
                    rating     = meta.rating,
                    isFavorite = meta.favorite,
                    isHidden   = meta.hidden,
                    notes      = meta.notes
                ) else item
            }

            mediaItemDao.insertAll(allScannedItems)       // IGNORE: new rows only
            mediaItemDao.updateAll(itemsForUpdate)         // @Update: existing rows, user metadata preserved

            // Delete stale albums last. The SET_NULL cascade on media_index.album_id fires
            // automatically for any remaining media rows still pointing to removed albums.
            if (albums.isNotEmpty()) {
                albumDao.deleteStaleAlbums(albums.map { it.id })
            }

            onProgress?.invoke("Sync complete", 1.0f)
            } catch (e: Exception) {
                e.printStackTrace()
                onProgress?.invoke("Error: ${e.message}", -1f)
            }
        }
    }

    suspend fun forceRecheck(
        showImages: Boolean = true,
        showVideos: Boolean = true,
        showGifs: Boolean = true,
        excludedPaths: Set<String> = emptySet(),
        onProgress: (String, Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        onProgress("Clearing cache and database...", 0.05f)
        thumbnailManager.clearAll()
        syncMediaStore(
            isFullScan = true,
            showImages = showImages,
            showVideos = showVideos,
            showGifs = showGifs,
            excludedPaths = excludedPaths,
            onProgress = onProgress
        )
    }

    // ─── Performance maintenance ──────────────────────────────────────────────

    /** Delete all cached thumbnail files on disk and clear persisted thumb URIs in the DB. */
    suspend fun clearThumbnailCache() = withContext(Dispatchers.IO) {
        thumbnailManager.clearAll()
        database.openHelper.writableDatabase
            .execSQL("UPDATE media_index SET thumb_uri = NULL")
    }

    /** Run VACUUM + ANALYZE to compact and re-optimise the SQLite database. */
    suspend fun optimizeDatabase() = withContext(Dispatchers.IO) {
        val db = database.openHelper.writableDatabase
        db.execSQL("VACUUM")
        db.execSQL("ANALYZE")
    }

    /**
     * Remove orphaned rows from auxiliary tables whose media_uri no longer
     * exists in media_index.  Returns the total number of rows deleted.
     */
    suspend fun runSmartCleanup(): Int = withContext(Dispatchers.IO) {
        val db = database.openHelper.writableDatabase
        var removed = 0

        // Orphaned tag assignments (media_tags.media_uri → media_index.uri)
        db.execSQL(
            "DELETE FROM media_tags WHERE media_uri NOT IN (SELECT uri FROM media_index)"
        )
        removed += db.compileStatement("SELECT changes()").simpleQueryForLong().toInt()

        // Orphaned AI tag suggestions (tag_suggestions.asset_id → media_index.uri)
        db.execSQL(
            "DELETE FROM tag_suggestions WHERE asset_id NOT IN (SELECT uri FROM media_index)"
        )
        removed += db.compileStatement("SELECT changes()").simpleQueryForLong().toInt()

        // Orphaned embedding rows (image_embeddings.asset_id → media_index.uri)
        db.execSQL(
            "DELETE FROM image_embeddings WHERE asset_id NOT IN (SELECT uri FROM media_index)"
        )
        removed += db.compileStatement("SELECT changes()").simpleQueryForLong().toInt()

        // Orphaned heuristic tag rows (heuristic_tags.asset_id → media_index.uri)
        db.execSQL(
            "DELETE FROM heuristic_tags WHERE asset_id NOT IN (SELECT uri FROM media_index)"
        )
        removed += db.compileStatement("SELECT changes()").simpleQueryForLong().toInt()

        removed
    }
}
