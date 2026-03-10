package com.example.boxpandora.data.manager

import android.content.ContentResolver
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.util.Log
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.local.entity.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.resume

class FileSystemManager(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

    suspend fun deleteMediaItems(items: List<MediaItem>): Boolean = withContext(Dispatchers.IO) {
        var allDeleted = true
        items.forEach { item ->
            try {
                val file = item.filePath?.let { File(it) }
                val deleted = file?.delete() ?: false
                
                if (!deleted) {
                    val rows = contentResolver.delete(Uri.parse(item.uri), null, null)
                    if (rows <= 0) {
                        allDeleted = false
                        Log.e("FileSystemManager", "Failed to delete: ${item.uri}")
                    }
                } else {
                    scanFile(file!!)
                }
            } catch (e: Exception) {
                Log.e("FileSystemManager", "Error deleting item: ${item.uri}", e)
                allDeleted = false
            }
        }
        allDeleted
    }

    suspend fun renameMediaItem(item: MediaItem, newName: String): File? = withContext(Dispatchers.IO) {
        try {
            val file = item.filePath?.let { File(it) } ?: return@withContext null
            val parent = file.parentFile ?: return@withContext null
            val extension = file.extension
            val newFile = File(parent, if (extension.isNotEmpty()) "$newName.$extension" else newName)
            
            if (file.renameTo(newFile)) {
                scanFile(file)
                scanFile(newFile)
                return@withContext newFile
            }
        } catch (e: Exception) {
            Log.e("FileSystemManager", "Error renaming item: ${item.uri}", e)
        }
        null
    }

    suspend fun copyMediaItems(items: List<MediaItem>, destinationFolder: File): List<Pair<MediaItem, File>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Pair<MediaItem, File>>()
        if (!destinationFolder.exists()) destinationFolder.mkdirs()

        items.forEach { item ->
            try {
                val sourceFile = item.filePath?.let { File(it) } ?: return@forEach
                var destFile = File(destinationFolder, sourceFile.name)
                
                if (destFile.exists()) {
                    val baseName = sourceFile.nameWithoutExtension
                    val ext = sourceFile.extension
                    var counter = 1
                    while (destFile.exists()) {
                        destFile = File(destinationFolder, "${baseName}_$counter.$ext")
                        counter++
                    }
                }

                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                scanFile(destFile)
                results.add(item to destFile)
            } catch (e: Exception) {
                Log.e("FileSystemManager", "Error copying item: ${item.uri}", e)
            }
        }
        results
    }

    suspend fun moveMediaItems(items: List<MediaItem>, destinationFolder: File): List<Pair<MediaItem, File>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Pair<MediaItem, File>>()
        if (!destinationFolder.exists()) destinationFolder.mkdirs()

        items.forEach { item ->
            try {
                val sourceFile = item.filePath?.let { File(it) } ?: return@forEach
                var destFile = File(destinationFolder, sourceFile.name)
                
                if (destFile.exists()) {
                    val baseName = sourceFile.nameWithoutExtension
                    val ext = sourceFile.extension
                    var counter = 1
                    while (destFile.exists()) {
                        destFile = File(destinationFolder, "${baseName}_$counter.$ext")
                        counter++
                    }
                }
                
                if (sourceFile.renameTo(destFile)) {
                    scanFile(sourceFile)
                    scanFile(destFile)
                    results.add(item to destFile)
                } else {
                    FileInputStream(sourceFile).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (sourceFile.delete()) {
                        scanFile(sourceFile)
                        scanFile(destFile)
                        results.add(item to destFile)
                    }
                }
            } catch (e: Exception) {
                Log.e("FileSystemManager", "Error moving item: ${item.uri}", e)
            }
        }
        results
    }

    suspend fun deleteAlbums(albums: List<Album>): Boolean = withContext(Dispatchers.IO) {
        var allDeleted = true
        albums.forEach { album ->
            try {
                val folder = album.path?.let { File(it) }
                if (folder != null && folder.exists() && folder.isDirectory) {
                    val deleted = folder.deleteRecursively()
                    if (deleted) {
                        folder.parentFile?.let { scanFile(it) }
                    } else {
                        allDeleted = false
                    }
                } else {
                    allDeleted = false
                }
            } catch (e: Exception) {
                Log.e("FileSystemManager", "Error deleting album: ${album.name}", e)
                allDeleted = false
            }
        }
        allDeleted
    }

    suspend fun renameAlbum(album: Album, newName: String): File? = withContext(Dispatchers.IO) {
        try {
            val folder = album.path?.let { File(it) } ?: return@withContext null
            val parent = folder.parentFile ?: return@withContext null
            val newFolder = File(parent, newName)
            
            if (folder.renameTo(newFolder)) {
                scanFile(folder)
                scanFile(newFolder)
                return@withContext newFolder
            }
        } catch (e: Exception) {
            Log.e("FileSystemManager", "Error renaming album: ${album.name}", e)
        }
        null
    }

    suspend fun setAlbumHidden(album: Album, hidden: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val folder = album.path?.let { File(it) } ?: return@withContext false
            if (!folder.exists() || !folder.isDirectory) return@withContext false
            
            val nomediaFile = File(folder, ".nomedia")
            if (hidden) {
                if (!nomediaFile.exists()) {
                    val created = nomediaFile.createNewFile()
                    if (created) scanFile(folder)
                    created
                } else true
            } else {
                if (nomediaFile.exists()) {
                    val deleted = nomediaFile.delete()
                    if (deleted) scanFile(folder)
                    deleted
                } else true
            }
        } catch (e: Exception) {
            Log.e("FileSystemManager", "Error toggling hidden state for album: ${album.name}", e)
            false
        }
    }

    suspend fun setMediaItemHidden(item: MediaItem, hidden: Boolean): File? = withContext(Dispatchers.IO) {
        try {
            val file = item.filePath?.let { File(it) } ?: return@withContext null
            val parent = file.parentFile ?: return@withContext null
            val name = file.name
            
            val newFile = if (hidden) {
                if (name.startsWith(".")) return@withContext file
                File(parent, ".$name")
            } else {
                if (!name.startsWith(".")) return@withContext file
                File(parent, name.substring(1))
            }
            
            if (file.renameTo(newFile)) {
                scanFile(file)
                scanFile(newFile)
                return@withContext newFile
            }
        } catch (e: Exception) {
            Log.e("FileSystemManager", "Error toggling hidden state for item: ${item.uri}", e)
        }
        null
    }

    private fun scanFile(file: File) {
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
    }

    suspend fun scanFileWait(file: File): Uri? = suspendCancellableCoroutine { continuation ->
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, uri ->
            continuation.resume(uri)
        }
    }
}
