package com.example.boxpandora.data.manager

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.boxpandora.worker.SyncWorker

private const val SYNC_WORK_NAME = "pandora_media_sync"

class MediaContentObserver(
    private val context: Context,
    handler: Handler = Handler(Looper.getMainLooper())
) : ContentObserver(handler) {

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        if (uri != null) {
            triggerSync()
        }
    }

    private fun triggerSync() {
        val syncWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>().build()
        // KEEP means if a sync is already queued or running, this change is
        // dropped — prevents sync storms when a folder full of videos is added.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(SYNC_WORK_NAME, ExistingWorkPolicy.KEEP, syncWorkRequest)
    }

    fun register() {
        context.contentResolver.registerContentObserver(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            this
        )
        context.contentResolver.registerContentObserver(
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            this
        )
    }

    fun unregister() {
        context.contentResolver.unregisterContentObserver(this)
    }
}
