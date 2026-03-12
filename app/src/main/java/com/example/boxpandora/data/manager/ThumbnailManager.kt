package com.example.boxpandora.data.manager

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.core.net.toUri
import com.example.boxpandora.data.local.entity.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

// Videos larger than this are skipped to avoid OOM during thumbnail generation.
private const val MAX_VIDEO_SIZE_FOR_THUMB = 2L * 1024 * 1024 * 1024 // 2 GB

class ThumbnailManager(private val context: Context) {

    private val thumbDir = File(context.cacheDir, "thumbnails").apply {
        if (!exists()) mkdirs()
    }

    private fun getThumbFileName(uri: String): String {
        val md = MessageDigest.getInstance("MD5")
        val hash = md.digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
        return "$hash.jpg"
    }

    suspend fun getOrCreateThumbnail(item: MediaItem): String? = withContext(Dispatchers.IO) {
        // If thumb_uri already exists and points to a valid file, return it
        item.thumbUri?.let { uri ->
            val file = File(uri.toUri().path ?: "")
            if (file.exists()) return@withContext uri
        }

        // Skip very large video files — loadThumbnail decodes a frame which
        // can exhaust heap on devices with limited RAM.
        if (item.mediaType == "video" && item.fileSize > MAX_VIDEO_SIZE_FOR_THUMB) {
            return@withContext null
        }

        val fileName = getThumbFileName(item.uri)
        val thumbFile = File(thumbDir, fileName)

        if (thumbFile.exists()) return@withContext "file://${thumbFile.absolutePath}"

        var bitmap: Bitmap? = null
        try {
            val contentUri = item.uri.toUri()
            bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(contentUri, Size(320, 320), null)
            } else {
                null
            }

            bitmap?.let {
                FileOutputStream(thumbFile).use { out ->
                    it.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                return@withContext "file://${thumbFile.absolutePath}"
            }
        } catch (e: Throwable) {
            // Catches Exception and OutOfMemoryError; clean up any partial file.
            Log.w("ThumbnailManager", "Failed to generate thumbnail for ${item.uri}", e)
            if (thumbFile.exists()) thumbFile.delete()
        } finally {
            // Always recycle the bitmap so native memory is freed promptly.
            bitmap?.recycle()
        }

        null
    }

    fun deleteThumbnail(uri: String) {
        val fileName = getThumbFileName(uri)
        val file = File(thumbDir, fileName)
        if (file.exists()) file.delete()
    }

    fun clearAll() {
        thumbDir.listFiles()?.forEach { it.delete() }
    }

    /** Returns the total bytes occupied by all cached thumbnail files. */
    fun cacheSize(): Long =
        thumbDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Returns the number of thumbnail files currently on disk. */
    fun cacheFileCount(): Int =
        thumbDir.listFiles()?.size ?: 0
}
