package com.example.boxpandora.data.util

import android.os.Environment
import com.example.boxpandora.data.local.entity.Album
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

    fun scanForNomediaFolders(maxDepth: Int = 3): List<Album> {
        val root = Environment.getExternalStorageDirectory()
        val results = mutableListOf<Album>()
        walk(root, results, 0, maxDepth)
        return results
    }

    private fun walk(dir: File, results: MutableList<Album>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth || !dir.exists() || !dir.isDirectory) return

        val files = dir.listFiles() ?: return
        val hasNomedia = files.any { it.name == ".nomedia" && it.isFile }

        if (hasNomedia) {
            val mediaFiles = files.filter { file ->
                val ext = file.extension.lowercase()
                imageExts.contains(ext) || videoExts.contains(ext)
            }

            if (mediaFiles.isNotEmpty()) {
                val latest = mediaFiles.maxByOrNull { it.lastModified() }
                results.add(
                    Album(
                        id = dir.absolutePath.hashCode().toLong(),
                        name = dir.name,
                        path = dir.absolutePath,
                        albumType = "Hidden",
                        mediaCount = mediaFiles.size,
                        photoCount = mediaFiles.count { imageExts.contains(it.extension.lowercase()) },
                        videoCount = mediaFiles.count { videoExts.contains(it.extension.lowercase()) },
                        coverUri = "file://${mediaFiles.first().absolutePath}",
                        photoCoverUri = mediaFiles.find { imageExts.contains(it.extension.lowercase()) }?.let { "file://${it.absolutePath}" },
                        videoCoverUri = mediaFiles.find { videoExts.contains(it.extension.lowercase()) }?.let { "file://${it.absolutePath}" },
                        lastModifiedAt = latest?.lastModified(),
                        isHidden = true
                    )
                )
            }
            // Optimization: Don't recurse into already hidden folders
            return
        }

        for (file in files) {
            if (file.isDirectory && !file.name.startsWith(".") && !skipDirs.contains(file.name)) {
                walk(file, results, depth + 1, maxDepth)
            }
        }
    }
}
