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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.example.boxpandora.data.local.AppDatabase
import com.example.boxpandora.data.manager.FileSystemManager
import com.example.boxpandora.data.manager.MediaContentObserver
import com.example.boxpandora.data.manager.ThumbnailManager
import com.example.boxpandora.data.repository.MediaRepository
import com.example.boxpandora.data.repository.MediaStoreRepository
import com.example.boxpandora.data.repository.TagRepository
import com.example.boxpandora.ml.config.AiSettingsRepository
import com.example.boxpandora.ml.manager.ModelManager
import com.example.boxpandora.worker.AiIndexScheduler
import com.example.boxpandora.worker.IndexingStatsStore

private const val TAG = "PandoraApp"

class PandoraApp : Application(), ImageLoaderFactory {
    lateinit var database: AppDatabase
    lateinit var repository: MediaRepository
    lateinit var thumbnailManager: ThumbnailManager
    lateinit var modelManager: ModelManager
    lateinit var aiSettingsRepository: AiSettingsRepository
    lateinit var indexingStatsStore: IndexingStatsStore
    private lateinit var contentObserver: MediaContentObserver
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Initializing PandoraApp")

        database = Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "pandora_db"
        )
        .addMigrations(
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10
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

        modelManager = ModelManager(this)
        aiSettingsRepository = AiSettingsRepository(this)
        indexingStatsStore = IndexingStatsStore(this)

        contentObserver = MediaContentObserver(this)
        contentObserver.register()

        // Trigger startup resync fallback
        Log.d(TAG, "Triggering startup resync fallback")
        contentObserver.triggerSync("App Startup")

        // Schedule scene indexing on startup if enabled in settings.
        // Uses KEEP policy — safe to call every launch; no-ops if already running.
        appScope.launch {
            val settings = aiSettingsRepository.settings.first()
            AiIndexScheduler.scheduleSceneIndexIfEnabled(this@PandoraApp, settings)
            AiIndexScheduler.scheduleFaceIndexIfEnabled(this@PandoraApp, settings)
            AiIndexScheduler.schedulePersonProfileIfEnabled(this@PandoraApp, settings)
            AiIndexScheduler.schedulePersonSuggestionsIfEnabled(this@PandoraApp, settings)
        }
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
