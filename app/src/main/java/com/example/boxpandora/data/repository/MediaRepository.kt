package com.example.boxpandora.data.repository

import com.example.boxpandora.data.local.dao.AlbumDao
import com.example.boxpandora.data.local.dao.MediaItemDao
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.manager.ThumbnailManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MediaRepository(
    private val mediaItemDao: MediaItemDao,
    private val albumDao: AlbumDao,
    private val mediaStoreRepository: MediaStoreRepository,
    private val thumbnailManager: ThumbnailManager
) {
    val allMediaPaged = mediaItemDao.getAllMediaPaged()
    val albumsFlow = albumDao.getAllAlbumsFlow()

    fun getMediaByAlbumFlow(albumName: String): Flow<List<MediaItem>> {
        return mediaItemDao.getMediaByAlbumFlow(albumName)
    }

    suspend fun syncMediaStore() = withContext(Dispatchers.IO) {
        val mediaFromStore = mediaStoreRepository.fetchAllMedia()
        val currentUris = mediaItemDao.getAllUris().toSet()
        val storeUris = mediaFromStore.map { it.uri }.toSet()

        // 1. Remove items deleted from the device
        val deletedUris = currentUris.filter { !storeUris.contains(it) }
        if (deletedUris.isNotEmpty()) {
            mediaItemDao.deleteByUris(deletedUris)
            deletedUris.forEach { thumbnailManager.deleteThumbnail(it) }
        }

        // 2. Build and upsert albums FIRST — MediaItem has a FK on album_id so the
        //    parent rows must exist before any child inserts are attempted.
        val albums = mediaFromStore
            .filter { it.albumId != null }
            .groupBy { it.albumId }
            .map { (id, items) ->
                val latest = items.maxByOrNull { it.deviceCreatedAt ?: 0L }
                Album(
                    id            = id!!,
                    name          = items.first().albumName ?: "Unknown",
                    path          = null,
                    albumType     = "Album",
                    mediaCount    = items.size,
                    photoCount    = items.count { it.mediaType == "image" },
                    videoCount    = items.count { it.mediaType == "video" },
                    coverUri      = items.firstOrNull()?.uri,
                    coverFilePath = items.firstOrNull()?.filePath,
                    photoCoverUri = items.find { it.mediaType == "image" }?.uri,
                    videoCoverUri = items.find { it.mediaType == "video" }?.uri,
                    lastModifiedAt = (latest?.deviceCreatedAt ?: 0L) * 1000L,
                    lastScannedAt  = System.currentTimeMillis()
                )
            }
        albumDao.insertAll(albums)

        // 3. Insert / update media items — album parent rows now guaranteed to exist
        mediaItemDao.insertAll(mediaFromStore)

        // 4. Prune albums that no longer exist in MediaStore
        albumDao.deleteStaleAlbums(albums.map { it.name })
    }
}
