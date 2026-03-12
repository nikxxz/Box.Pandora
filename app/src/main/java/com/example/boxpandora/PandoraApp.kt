package com.example.boxpandora

import android.app.Application
import android.os.Build.VERSION.SDK_INT
import android.util.Log
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
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
import com.example.boxpandora.data.repository.TagRepository

private const val TAG = "PandoraApp"

class PandoraApp : Application(), ImageLoaderFactory {
    lateinit var database: AppDatabase
    lateinit var repository: MediaRepository
    lateinit var thumbnailManager: ThumbnailManager
    private lateinit var contentObserver: MediaContentObserver

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Initializing PandoraApp")

        database = Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "pandora_db"
        )
        .fallbackToDestructiveMigration()
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()

        thumbnailManager = ThumbnailManager(this)
        val mediaStoreRepository = MediaStoreRepository(this)
        val fileSystemManager = FileSystemManager(this)
        
        val tagRepository = TagRepository(
            database = database,
            tagDao = database.tagDao(),
            mediaTagDao = database.mediaTagDao(),
            tagAliasDao = database.tagAliasDao(),
            tagSuggestionDao = database.tagSuggestionDao(),
            heuristicTagDao = database.heuristicTagDao(),
            tagRejectionDao = database.tagRejectionDao(),
            tagChangeHistoryDao = database.tagChangeHistoryDao(),
            tagCooccurrenceDao = database.tagCooccurrenceDao()
        )

        repository = MediaRepository(
            database = database,
            mediaItemDao = database.mediaItemDao(),
            albumDao = database.albumDao(),
            imageEmbeddingDao = database.imageEmbeddingDao(),
            faceDao = database.faceDao(),
            mediaStoreRepository = mediaStoreRepository,
            thumbnailManager = thumbnailManager,
            fileSystemManager = fileSystemManager,
            tagRepository = tagRepository
        )

        contentObserver = MediaContentObserver(this)
        contentObserver.register()

        // Trigger startup resync fallback
        Log.d(TAG, "Triggering startup resync fallback")
        contentObserver.triggerSync("App Startup")
    }

    override fun onTerminate() {
        super.onTerminate()
        contentObserver.unregister()
    }

    override fun newImageLoader(): ImageLoader {
        val memoryCacheBytes = (Runtime.getRuntime().maxMemory() * 0.25).toLong()

        return ImageLoader.Builder(this)
            .components {
                if (SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(VideoFrameDecoder.Factory())
            }
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
