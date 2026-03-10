package com.example.boxpandora

import android.app.Application
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.boxpandora.data.local.AppDatabase
import com.example.boxpandora.data.manager.MediaContentObserver
import com.example.boxpandora.data.manager.ThumbnailManager
import com.example.boxpandora.data.repository.MediaRepository
import com.example.boxpandora.data.repository.MediaStoreRepository

class PandoraApp : Application(), ImageLoaderFactory {
    lateinit var database: AppDatabase
    lateinit var repository: MediaRepository
    private lateinit var contentObserver: MediaContentObserver

    override fun onCreate() {
        super.onCreate()

        database = Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "pandora_db"
        )
        .fallbackToDestructiveMigration()
        .build()

        val thumbnailManager = ThumbnailManager(this)
        val mediaStoreRepository = MediaStoreRepository(this)

        repository = MediaRepository(
            database.mediaItemDao(),
            database.albumDao(),
            mediaStoreRepository,
            thumbnailManager
        )

        contentObserver = MediaContentObserver(this)
        contentObserver.register()
    }

    override fun onTerminate() {
        super.onTerminate()
        contentObserver.unregister()
    }

    override fun newImageLoader(): ImageLoader {
        // Cap memory cache to 25% of the app's heap and disk cache to 256 MB.
        // Default Coil limits are too generous for a media-heavy gallery that
        // already manages its own thumbnail cache.
        val memoryCacheBytes = (Runtime.getRuntime().maxMemory() * 0.25).toLong()

        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(memoryCacheBytes.toInt())
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024) // 256 MB
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
