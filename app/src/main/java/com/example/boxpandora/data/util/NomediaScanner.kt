package com.example.boxpandora.data.util

import android.net.Uri
import android.os.Environment
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.local.entity.MediaItem
import java.io.File

class NomediaScanner {

    private val skipDirs = setOf(
        "Android", "data", "obb", ".trash", "lost+found", ".cache", "cache"
    )

    private val imageExts = setOf(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif"
    )
    
    private val videoExts = setOf(
        "mp4", "mov", "avi", "mkv", "webm"
    )

    data class ScanResult(
        val albums: List<Album>,
        val mediaItems: List<MediaItem>
    )

    fun scanForNomediaFolders(maxDepth: Int = 3): ScanResult {
        val root = Environment.getExternalStorageDirectory()
        val albums = mutableListOf<Album>()
        val items = mutableListOf<MediaItem>()
        walk(root, albums, items, 0, maxDepth)
        return ScanResult(albums, items)
    }

    private fun walk(dir: File, albums: MutableList<Album>, items: MutableList<MediaItem>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth || !dir.exists() || !dir.isDirectory) return

        val files = dir.listFiles() ?: return
        val hasNomedia = files.any { it.name == ".nomedia" && it.isFile }

        if (hasNomedia) {
            val mediaFiles = files.filter { file ->
                val ext = file.extension.lowercase()
                imageExts.contains(ext) || videoExts.contains(ext)
            }

            if (mediaFiles.isNotEmpty()) {
                val bucketId = dir.absolutePath.hashCode().toLong()
                val latest = mediaFiles.maxByOrNull { it.lastModified() }
                
                albums.add(
                    Album(
                        id = bucketId,
                        name = dir.name,
                        path = dir.absolutePath,
                        albumType = "Hidden",
                        mediaCount = mediaFiles.size,
                        photoCount = mediaFiles.count { imageExts.contains(it.extension.lowercase()) },
                        videoCount = mediaFiles.count { videoExts.contains(it.extension.lowercase()) },
                        coverUri = "file://${mediaFiles.first().absolutePath}",
                        coverFilePath = mediaFiles.first().absolutePath,
                        photoCoverUri = mediaFiles.find { imageExts.contains(it.extension.lowercase()) }?.let { "file://${it.absolutePath}" },
                        videoCoverUri = mediaFiles.find { videoExts.contains(it.extension.lowercase()) }?.let { "file://${it.absolutePath}" },
                        lastModifiedAt = latest?.lastModified(),
                        isHidden = true
                    )
                )

                mediaFiles.forEach { file ->
                    val isVideo = videoExts.contains(file.extension.lowercase())
                    items.add(
                        MediaItem(
                            uri = Uri.fromFile(file).toString(),
                            filename = file.name,
                            filePath = file.absolutePath,
                            fileSize = file.length(),
                            width = 0, // Would need metadata retriever
                            height = 0,
                            duration = null,
                            extension = file.extension,
                            mediaType = if (isVideo) "video" else "image",
                            deviceCreatedAt = file.lastModified() / 1000,
                            deviceModifiedAt = file.lastModified() / 1000,
                            indexedAt = System.currentTimeMillis(),
                            scannedAt = System.currentTimeMillis(),
                            albumId = bucketId,
                            albumName = dir.name,
                            isHidden = 1
                        )
                    )
                }
            }
            // Optimization: Don't recurse into already hidden folders
            return
        }

        for (file in files) {
            if (file.isDirectory && !file.name.startsWith(".") && !skipDirs.contains(file.name)) {
                walk(file, albums, items, depth + 1, maxDepth)
            }
        }
    }
}
