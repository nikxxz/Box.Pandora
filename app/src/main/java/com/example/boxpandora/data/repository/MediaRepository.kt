package com.example.boxpandora.data.repository

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
import com.example.boxpandora.data.manager.FileSystemManager
import com.example.boxpandora.data.manager.ThumbnailManager
import com.example.boxpandora.data.util.NomediaScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

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
        showHidden: Boolean = false
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val candidates = mediaItemDao.searchFiltered(
            showHidden  = showHidden,
            albumId     = albumId ?: -1L,
            type        = params.type,
            format      = params.format.lowercase(),
            tagCategory = params.tagCategory,
            query       = params.query.trim()
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

    fun getMediaByAlbumPaged(albumId: Long, showHidden: Boolean): Flow<PagingData<MediaItem>> {
        return Pager(
            config = PagingConfig(pageSize = 60, enablePlaceholders = true),
            pagingSourceFactory = { mediaItemDao.getMediaByAlbumPaged(albumId, showHidden) }
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

    fun getMediaByAlbumIdFlow(albumId: Long, showHidden: Boolean = false): Flow<List<MediaItem>> {
        return mediaItemDao.getMediaByAlbumIdFlow(albumId, showHidden)
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

    suspend fun copyMediaItems(uris: List<String>, destinationPath: String): Boolean = withContext(Dispatchers.IO) {
        val items = uris.mapNotNull { mediaItemDao.getByUri(it) }
        val destFolder = File(destinationPath)
        val results = fileSystemManager.copyMediaItems(items, destFolder)
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

    suspend fun moveMediaItems(uris: List<String>, destinationPath: String): Boolean = withContext(Dispatchers.IO) {
        val items = uris.mapNotNull { mediaItemDao.getByUri(it) }
        val destFolder = File(destinationPath)
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
        syncMediaStore()
        results.isNotEmpty()
    }

    private suspend fun transferMetadata(
        oldItem: MediaItem,
        newItemFromStore: MediaItem,
        deleteOld: Boolean
    ) {
        val oldUri = oldItem.uri
        val newUri = newItemFromStore.uri

        val updatedNewItem = newItemFromStore.copy(
            rating     = oldItem.rating,
            isFavorite = oldItem.isFavorite,
            notes      = oldItem.notes,
            isHidden   = oldItem.isHidden
        )
        mediaItemDao.insertAll(listOf(updatedNewItem))
        mediaItemDao.updateAll(listOf(updatedNewItem))

        if (oldUri == newUri) return

        database.withTransaction {
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
            val album = albumDao.getById(id)
            if (album != null && fileSystemManager.setAlbumHidden(album, hidden)) {
                albumDao.setHidden(album.name, hidden)
            }
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
        onProgress: ((String, Float) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        try {
            onProgress?.invoke("Fetching MediaStore content...", 0.1f)
            val mediaStoreItems = mediaStoreRepository.fetchAllMedia()

            onProgress?.invoke("Scanning for hidden folders...", 0.3f)
            val nomediaResults = nomediaScanner.scanForNomediaFolders()
            
            val allScannedItems = mediaStoreItems + nomediaResults.mediaItems
            val allScannedUris = allScannedItems.map { it.uri }.toSet()

            onProgress?.invoke("Comparing with database...", 0.5f)
            val existingUris = mediaItemDao.getAllUris().toSet()

            val urisToDelete = existingUris.filter { !allScannedUris.contains(it) }
            if (urisToDelete.isNotEmpty()) {
                mediaItemDao.deleteByUris(urisToDelete)
                urisToDelete.forEach { thumbnailManager.deleteThumbnail(it) }
            }

            onProgress?.invoke("Updating media index...", 0.7f)
            mediaItemDao.insertAll(allScannedItems)
            mediaItemDao.updateAll(allScannedItems)

            onProgress?.invoke("Updating albums...", 0.9f)
            val albums = allScannedItems.groupBy { it.albumId to it.albumName }
                .mapNotNull { (key, items) ->
                    val (albumId, albumName) = key
                    if (albumId == null || albumName == null) return@mapNotNull null
                    
                    val coverItem = items.maxByOrNull { it.deviceCreatedAt ?: 0L } ?: items.first()
                    val photoItems = items.filter { it.mediaType == "image" }
                    val videoItems = items.filter { it.mediaType == "video" }
                    
                    Album(
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

            albumDao.upsertAll(albums)
            if (albums.isNotEmpty()) {
                albumDao.deleteStaleAlbums(albums.map { it.id })
            }

            onProgress?.invoke("Sync complete", 1.0f)
        } catch (e: Exception) {
            e.printStackTrace()
            onProgress?.invoke("Error: ${e.message}", -1f)
        }
    }

    suspend fun forceRecheck(onProgress: (String, Float) -> Unit) = withContext(Dispatchers.IO) {
        onProgress("Clearing cache and database...", 0.05f)
        thumbnailManager.clearAll()
        syncMediaStore(isFullScan = true, onProgress = onProgress)
    }
}
