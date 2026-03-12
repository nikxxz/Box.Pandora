package com.example.boxpandora.data.repository

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.example.boxpandora.data.local.entity.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "MediaStoreRepository"

class MediaStoreRepository(private val context: Context) {

    @SuppressLint("InlinedApi") // DATA is deprecated for writes but safe to read for display
    suspend fun fetchAllMedia(): List<MediaItem> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Sync Start: Fetching all media from MediaStore")
        
        val mediaList = mutableListOf<MediaItem>()
        val contentResolver: ContentResolver = context.contentResolver

        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,       // actual file path
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            "bucket_display_name",
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.MEDIA_TYPE
        )

        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"

        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

        var totalFetched = 0
        val mimeDistribution = mutableMapOf<String, Int>()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                var offset = 0
                val limit = 500
                var hasMore = true

                while (hasMore) {
                    val queryArgs = Bundle().apply {
                        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                        putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                        putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    }

                    val countBefore = mediaList.size
                    contentResolver.query(uri, projection, queryArgs, null)?.use { cursor ->
                        fetchFromCursor(cursor, mediaList, mimeDistribution)
                    }
                    val fetched = mediaList.size - countBefore
                    totalFetched += fetched
                    hasMore = (fetched + (limit - fetched)) >= limit && fetched > 0 // Approximation for paging
                    // More accurately, if we got a cursor but it was smaller than limit, we might be at the end, 
                    // but we also skip bad rows.
                    
                    // Re-checking hasMore logic: if the cursor has fewer rows than we asked for, we are done.
                    // But fetchFromCursor doesn't return cursor size. Let's simplify.
                    offset += limit
                    
                    // If we didn't find any more rows in the last page, stop.
                    if (fetched == 0 && offset > limit * 2) hasMore = false 
                    // Actually, the original logic was: hasMore = fetched >= limit && fetched > 0
                    // But if we skip rows, fetched might be < limit even if more rows exist.
                }
            } else {
                contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                    fetchFromCursor(cursor, mediaList, mimeDistribution)
                }
                totalFetched = mediaList.size
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error during media fetch", e)
        }

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Sync End: Fetched $totalFetched items in ${duration}ms")
        Log.d(TAG, "MIME Distribution: $mimeDistribution")

        mediaList
    }

    suspend fun fetchMediaByPath(path: String): MediaItem? = withContext(Dispatchers.IO) {
        val contentResolver: ContentResolver = context.contentResolver
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            "bucket_display_name",
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.MEDIA_TYPE
        )

        val selection = "${MediaStore.Files.FileColumns.DATA} = ?"
        val selectionArgs = arrayOf(path)

        contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            val list = mutableListOf<MediaItem>()
            fetchFromCursor(cursor, list, mutableMapOf())
            return@withContext list.firstOrNull()
        }
        null
    }

    @SuppressLint("InlinedApi")
    private fun fetchFromCursor(
        cursor: android.database.Cursor, 
        mediaList: MutableList<MediaItem>,
        mimeDistribution: MutableMap<String, Int>
    ) {
        val idCol       = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
        val nameCol     = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val dataCol     = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
        val sizeCol     = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
        val widthCol    = cursor.getColumnIndex(MediaStore.Files.FileColumns.WIDTH)
        val heightCol   = cursor.getColumnIndex(MediaStore.Files.FileColumns.HEIGHT)
        val dateCol     = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
        val modifiedCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
        val mimeCol     = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
        val bucketNameCol = cursor.getColumnIndex("bucket_display_name")
        val bucketIdCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_ID)
        val durationCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DURATION)
        val typeCol     = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)

        var rowsParsed = 0
        var rowsSkipped = 0

        while (cursor.moveToNext()) {
            val currentPos = cursor.position
            try {
                val id   = if (idCol != -1) cursor.getLong(idCol) else -1L
                if (id == -1L) {
                    Log.w(TAG, "Skipping row at $currentPos: Missing ID")
                    rowsSkipped++
                    continue
                }

                val rawName = if (nameCol != -1) cursor.getString(nameCol) else null
                val name = rawName ?: "Unknown"
                if (rawName == null) {
                    Log.d(TAG, "Row $id at $currentPos has null display name, using 'Unknown'")
                }

                val path = if (dataCol != -1) cursor.getString(dataCol) else null
                if (path == null) {
                    Log.d(TAG, "Row $id at $currentPos has null DATA/path")
                }

                val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                val width  = if (widthCol != -1) cursor.getInt(widthCol) else 0
                val height = if (heightCol != -1) cursor.getInt(heightCol) else 0
                val date     = if (dateCol != -1) cursor.getLong(dateCol) else 0L
                val modified = if (modifiedCol != -1) cursor.getLong(modifiedCol) else null
                
                val rawMime = if (mimeCol != -1) cursor.getString(mimeCol) else null
                val mime = rawMime ?: "image/jpeg"
                if (rawMime == null) {
                    Log.d(TAG, "Row $id at $currentPos has null MIME type, falling back to image/jpeg")
                }
                mimeDistribution[mime] = mimeDistribution.getOrDefault(mime, 0) + 1

                val bucketName = if (bucketNameCol != -1) cursor.getString(bucketNameCol) ?: "Internal" else "Internal"
                val bucketId   = if (bucketIdCol != -1) cursor.getLong(bucketIdCol) else 0L
                val duration = if (durationCol != -1 && !cursor.isNull(durationCol))
                    cursor.getLong(durationCol).toDouble() / 1000.0 else null
                val mediaType = if (typeCol != -1) cursor.getInt(typeCol)
                    else MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE

                val contentUri = if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                    Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                } else {
                    Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                }

                mediaList.add(
                    MediaItem(
                        uri              = contentUri.toString(),
                        filename         = name,
                        filePath         = path,
                        fileSize         = size,
                        width            = width,
                        height           = height,
                        duration         = duration,
                        extension        = mime.substringAfterLast("/", ""),
                        mediaType        = if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) "video" else "image",
                        deviceCreatedAt  = date,
                        deviceModifiedAt = modified,
                        indexedAt        = System.currentTimeMillis(),
                        scannedAt        = System.currentTimeMillis(),
                        rating           = 0,
                        isFavorite       = 0,
                        isHidden         = 0,
                        notes            = null,
                        thumbUri         = null,
                        albumId          = bucketId,
                        albumName        = bucketName
                    )
                )
                rowsParsed++
            } catch (e: Exception) {
                // Collect as much context as possible
                val idVal = try { if (idCol != -1) cursor.getLong(idCol) else "N/A" } catch (ex: Exception) { "Err" }
                val nameVal = try { if (nameCol != -1) cursor.getString(nameCol) else "N/A" } catch (ex: Exception) { "Err" }
                val mimeVal = try { if (mimeCol != -1) cursor.getString(mimeCol) else "N/A" } catch (ex: Exception) { "Err" }
                val typeVal = try { if (typeCol != -1) cursor.getInt(typeCol) else "N/A" } catch (ex: Exception) { "Err" }
                val bucketVal = try { if (bucketNameCol != -1) cursor.getString(bucketNameCol) else "N/A" } catch (ex: Exception) { "Err" }

                Log.e(TAG, "Error reading row at position $currentPos (ID: $idVal, Name: $nameVal, MIME: $mimeVal, Type: $typeVal, Bucket: $bucketVal)", e)
                
                rowsSkipped++
                // HARDENING: We no longer 'break' on CursorWindow IllegalStateException.
                // We skip this row and attempt to continue to the next one.
            }
        }
        Log.d(TAG, "Cursor scan complete. Parsed: $rowsParsed, Skipped: $rowsSkipped")
    }
}
