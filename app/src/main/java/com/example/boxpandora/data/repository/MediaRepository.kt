package com.example.boxpandora.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.example.boxpandora.data.local.AppDatabase
import com.example.boxpandora.data.local.dao.AlbumDao
import com.example.boxpandora.data.local.dao.FaceDao
import com.example.boxpandora.data.local.dao.HeuristicTagDao
import com.example.boxpandora.data.local.dao.ImageEmbeddingDao
import com.example.boxpandora.data.local.dao.MediaItemDao
import com.example.boxpandora.data.local.dao.MediaTagDao
import com.example.boxpandora.data.local.dao.TagRejectionDao
import com.example.boxpandora.data.local.dao.TagSuggestionDao
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
    private val mediaTagDao: MediaTagDao,
    private val imageEmbeddingDao: ImageEmbeddingDao,
    private val faceDao: FaceDao,
    private val tagSuggestionDao: TagSuggestionDao,
    private val heuristicTagDao: HeuristicTagDao,
    private val tagRejectionDao: TagRejectionDao,
    private val mediaStoreRepository: MediaStoreRepository,
    private val thumbnailManager: ThumbnailManager,
    private val fileSystemManager: FileSystemManager
) {
    private val nomediaScanner = NomediaScanner()

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

    /** One-time lookup — resolves albumName to its stable MediaStore bucket ID. */
    suspend fun getAlbumIdByName(albumName: String): Long? {
        return albumDao.getByName(albumName)?.id
    }

    /** Flow that watches ONLY media_index — never re-fires on albums table writes. */
    fun getMediaByAlbumIdFlow(albumId: Long, showHidden: Boolean = false): Flow<List<MediaItem>> {
        return mediaItemDao.getMediaByAlbumIdFlow(albumId, showHidden)
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

        if (oldUri == newUri) return

        val tags = mediaTagDao.getMediaTagsForUri(oldUri)
        if (tags.isNotEmpty()) mediaTagDao.insertAll(tags.map { it.copy(mediaUri = newUri) })

        val embeddings = imageEmbeddingDao.getForAsset(oldUri)
        if (embeddings.isNotEmpty()) imageEmbeddingDao.insertAll(embeddings.map { it.copy(assetId = newUri) })

        val faces = faceDao.getFacesForAsset(oldUri)
        faces.forEach { face ->
            val newFaceId = face.faceId.replace(oldUri, newUri)
            val embedding = faceDao.getEmbeddingForFace(face.faceId)
            faceDao.insertFace(face.copy(faceId = newFaceId, assetId = newUri))
            embedding?.let { faceDao.insertEmbedding(it.copy(faceId = newFaceId)) }
        }

        val suggestions = tagSuggestionDao.getForAsset(oldUri)
        if (suggestions.isNotEmpty()) tagSuggestionDao.insertAll(suggestions.map { it.copy(id = 0, assetId = newUri) })

        val heuristicTags = heuristicTagDao.getForAsset(oldUri)
        if (heuristicTags.isNotEmpty()) heuristicTagDao.insertAll(heuristicTags.map { it.copy(assetId = newUri) })

        val rejections = tagRejectionDao.getForAsset(oldUri)
        if (rejections.isNotEmpty()) tagRejectionDao.insertAll(rejections.map { it.copy(assetId = newUri) })

        if (deleteOld) mediaItemDao.deleteByUris(listOf(oldUri))
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
            val newFile = fileSystemManager.setMediaItemHidden(item, hidden) ?: return@forEach
            val newUri = fileSystemManager.scanFileWait(newFile) ?: return@forEach
            val newItemFromStore = mediaStoreRepository.fetchMediaByPath(newFile.absolutePath)
            if (newItemFromStore != null) {
                transferMetadata(item, newItemFromStore, deleteOld = true)
                thumbnailManager.deleteThumbnail(uri)
            }
        }
        syncMediaStore()
    }

    suspend fun syncMediaStore() = withContext(Dispatchers.IO) {
        val mediaFromStore = mediaStoreRepository.fetchAllMedia()
        val scanResult = nomediaScanner.scanForNomediaFolders()
        val allMediaItemsFromFilesystem = mediaFromStore + scanResult.mediaItems

        val currentUris = mediaItemDao.getAllUris().toSet()
        val storeUris = allMediaItemsFromFilesystem.map { it.uri }.toSet()

        database.withTransaction {
            // 1. Remove items deleted from the device
            val deletedUris = currentUris.filter { !storeUris.contains(it) }
            if (deletedUris.isNotEmpty()) {
                mediaItemDao.deleteByUris(deletedUris)
                deletedUris.forEach { thumbnailManager.deleteThumbnail(it) }
            }

            // 2. Upsert ALBUMS FIRST
            val albums = allMediaItemsFromFilesystem
                .filter { it.albumId != null }
                .groupBy { it.albumId }
                .map { (id, items) ->
                    val latest = items.maxByOrNull { it.deviceCreatedAt ?: 0L }
                    val dbAlbum = albumDao.getById(id!!)
                    val isHidden = scanResult.albums.any { it.id == id } || dbAlbum?.isHidden == true
                    Album(
                        id             = id,
                        name           = items.first().albumName ?: "Unknown",
                        path           = items.firstOrNull { it.filePath != null }
                                             ?.filePath?.let { java.io.File(it).parent },
                        albumType      = if (isHidden) "Hidden" else "Album",
                        mediaCount     = items.size,
                        photoCount     = items.count { it.mediaType == "image" },
                        videoCount     = items.count { it.mediaType == "video" },
                        coverUri       = items.firstOrNull()?.uri,
                        coverFilePath  = items.firstOrNull()?.filePath,
                        photoCoverUri  = items.find { it.mediaType == "image" }?.uri,
                        videoCoverUri  = items.find { it.mediaType == "video" }?.uri,
                        lastModifiedAt = (latest?.deviceCreatedAt ?: 0L) * 1000L,
                        lastScannedAt  = System.currentTimeMillis(),
                        isHidden       = isHidden
                    )
                }
            albumDao.upsertAll(albums)

            val albumIds = albums.map { it.id }
            if (albumIds.isNotEmpty()) albumDao.deleteStaleAlbums(albumIds) else albumDao.clearAll()

            // 3. Upsert media items
            val existingMetadata = mediaItemDao.getAllUserMetadata().associateBy { it.uri }
            val mergedItems = allMediaItemsFromFilesystem.map { newItem ->
                val meta = existingMetadata[newItem.uri]
                if (meta != null) {
                    newItem.copy(
                        rating     = meta.rating,
                        isFavorite = meta.favorite,
                        notes      = meta.notes,
                        isHidden   = if (meta.hidden == 1 || newItem.isHidden == 1) 1 else 0
                    )
                } else {
                    newItem
                }
            }
            mediaItemDao.insertAll(mergedItems)
        }
    }
}
