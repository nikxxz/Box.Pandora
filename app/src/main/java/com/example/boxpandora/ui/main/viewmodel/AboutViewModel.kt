package com.example.boxpandora.ui.main.viewmodel

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.boxpandora.data.local.AppDatabase
import com.example.boxpandora.data.manager.ThumbnailManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AboutStats(
    val mediaCount: Int       = 0,
    val tagCount: Int         = 0,
    val cacheSizeBytes: Long  = 0L,
    val cacheFileCount: Int   = 0,
    val dbSizeBytes: Long     = 0L,
    val versionName: String   = "",
    val versionCode: Long     = 0L,
    val isLoaded: Boolean     = false
)

class AboutViewModel(
    private val context: Context,
    private val database: AppDatabase,
    private val thumbnailManager: ThumbnailManager
) : ViewModel() {

    private val _stats = MutableStateFlow(AboutStats())
    val stats: StateFlow<AboutStats> = _stats

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val s = withContext(Dispatchers.IO) {
                // ── Version ──────────────────────────────────────────────────
                val pm = context.packageManager
                val pkgInfo = runCatching {
                    pm.getPackageInfo(context.packageName, 0)
                }.getOrNull()
                val versionName = pkgInfo?.versionName ?: "1.0"
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    pkgInfo?.longVersionCode ?: 1L
                else
                    @Suppress("DEPRECATION")
                    pkgInfo?.versionCode?.toLong() ?: 1L

                // ── Media & tag counts (raw PRAGMA queries, no new DAO needed) ─
                val db = database.openHelper.readableDatabase
                val mediaCount = db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM media_index"))
                    .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                val tagCount = db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM tags"))
                    .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

                // ── Database file size via PRAGMA ─────────────────────────────
                val pageCount = db.query(SimpleSQLiteQuery("PRAGMA page_count"))
                    .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                val pageSize = db.query(SimpleSQLiteQuery("PRAGMA page_size"))
                    .use { c -> if (c.moveToFirst()) c.getLong(0) else 4096L }
                val dbSizeBytes = pageCount * pageSize

                // ── Thumbnail cache on disk ───────────────────────────────────
                val cacheSizeBytes = thumbnailManager.cacheSize()
                val cacheFileCount = thumbnailManager.cacheFileCount()

                AboutStats(
                    mediaCount    = mediaCount,
                    tagCount      = tagCount,
                    cacheSizeBytes = cacheSizeBytes,
                    cacheFileCount = cacheFileCount,
                    dbSizeBytes   = dbSizeBytes,
                    versionName   = versionName,
                    versionCode   = versionCode,
                    isLoaded      = true
                )
            }
            _stats.value = s
        }
    }
}

class AboutViewModelFactory(
    private val context: Context,
    private val database: AppDatabase,
    private val thumbnailManager: ThumbnailManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AboutViewModel::class.java)) {
            return AboutViewModel(context, database, thumbnailManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
