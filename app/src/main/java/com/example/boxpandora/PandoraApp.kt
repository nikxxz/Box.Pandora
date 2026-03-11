package com.example.boxpandora

import android.app.Application
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.Dispatchers
import com.example.boxpandora.data.local.AppDatabase
import com.example.boxpandora.data.manager.FileSystemManager
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
        val fileSystemManager = FileSystemManager(this)

        repository = MediaRepository(
            database = database,
            mediaItemDao = database.mediaItemDao(),
            albumDao = database.albumDao(),
            mediaTagDao = database.mediaTagDao(),
            imageEmbeddingDao = database.imageEmbeddingDao(),
            faceDao = database.faceDao(),
            tagSuggestionDao = database.tagSuggestionDao(),
            heuristicTagDao = database.heuristicTagDao(),
            tagRejectionDao = database.tagRejectionDao(),
            mediaStoreRepository = mediaStoreRepository,
            thumbnailManager = thumbnailManager,
            fileSystemManager = fileSystemManager
        )

        contentObserver = MediaContentObserver(this)
        contentObserver.register()
    }

    override fun onTerminate() {
        super.onTerminate()
        contentObserver.unregister()
    }

    override fun newImageLoader(): ImageLoader {
        val memoryCacheBytes = (Runtime.getRuntime().maxMemory() * 0.25).toLong()

        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            // Cap the number of concurrent image/video decodes.
            // VideoFrameDecoder creates a MediaMetadataRetriever per request which
            // allocates a MediaCodec slot. On Qualcomm hardware, spawning many
            // codec instances simultaneously causes all of them to stall until the
            // hardware scheduler grants them time, producing the "blank then all
            // pop in at once" effect. 4 concurrent decoders is a safe ceiling.
            // decoderDispatcher is the Coil 2.x API (renamed to decoderCoroutineContext in 3.x)
            .decoderDispatcher(Dispatchers.IO.limitedParallelism(4))
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
