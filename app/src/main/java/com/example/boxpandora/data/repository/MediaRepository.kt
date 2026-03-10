package com.example.boxpandora.data.repository

import com.example.boxpandora.data.local.dao.AlbumDao
import com.example.boxpandora.data.local.dao.MediaItemDao
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.manager.ThumbnailManager
import com.example.boxpandora.data.util.NomediaScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MediaRepository(
    private val mediaItemDao: MediaItemDao,
    private val albumDao: AlbumDao,
    private val mediaStoreRepository: MediaStoreRepository,
    private val thumbnailManager: ThumbnailManager
) {
    private val nomediaScanner = NomediaScanner()

    fun getAllMediaPaged(showHidden: Boolean) = mediaItemDao.getAllMediaPaged(showHidden)
    val albumsFlow = albumDao.getAllAlbumsFlow()

    fun getAlbumsFlow(showHidden: Boolean): Flow<List<Album>> {
        return albumDao.getAlbumsFlow(showHidden)
    }

    fun getMediaByAlbumFlow(albumName: String, showHidden: Boolean = false): Flow<List<MediaItem>> {
        return mediaItemDao.getMediaByAlbumFlow(albumName, showHidden)
    }

    suspend fun syncMediaStore() = withContext(Dispatchers.IO) {
        val mediaFromStore = mediaStoreRepository.fetchAllMedia()
        
        // Scan for .nomedia folders (Hidden Albums & Items)
        val scanResult = nomediaScanner.scanForNomediaFolders()
        
        val allMedia = mediaFromStore + scanResult.mediaItems
        
        val currentUris = mediaItemDao.getAllUris().toSet()
        val storeUris = allMedia.map { it.uri }.toSet()

        // 1. Remove items deleted from the device
        val deletedUris = currentUris.filter { !storeUris.contains(it) }
        if (deletedUris.isNotEmpty()) {
            mediaItemDao.deleteByUris(deletedUris)
            deletedUris.forEach { thumbnailManager.deleteThumbnail(it) }
        }

        // 2. Build and upsert albums
        val albums = allMedia
            .filter { it.albumId != null }
            .groupBy { it.albumId }
            .map { (id, items) ->
                val latest = items.maxByOrNull { it.deviceCreatedAt ?: 0L }
                val isHidden = scanResult.albums.any { it.id == id }
                Album(
                    id            = id!!,
                    name          = items.first().albumName ?: "Unknown",
                    path          = items.firstOrNull { it.isHidden == 1 }?.filePath?.let { java.io.File(it).parent },
                    albumType     = if (isHidden) "Hidden" else "Album",
                    mediaCount    = items.size,
                    photoCount    = items.count { it.mediaType == "image" },
                    videoCount    = items.count { it.mediaType == "video" },
                    coverUri      = items.firstOrNull()?.uri,
                    coverFilePath = items.firstOrNull()?.filePath,
                    photoCoverUri = items.find { it.mediaType == "image" }?.uri,
                    videoCoverUri = items.find { it.mediaType == "video" }?.uri,
                    lastModifiedAt = (latest?.deviceCreatedAt ?: 0L) * 1000L,
                    lastScannedAt  = System.currentTimeMillis(),
                    isHidden       = isHidden
                )
            }
        
        albumDao.insertAll(albums)

        // 3. Insert / update all media items
        mediaItemDao.insertAll(allMedia)

        // 4. Prune albums that no longer exist in MediaStore
        val albumIds = albums.map { it.id }
        if (albumIds.isNotEmpty()) albumDao.deleteStaleAlbums(albumIds) else albumDao.clearAll()
    }
}
