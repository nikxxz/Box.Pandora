package com.example.boxpandora.data.manager

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.boxpandora.worker.SyncWorker
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "MediaContentObserver"
private const val SYNC_WORK_NAME = "pandora_media_sync"

class MediaContentObserver(
    private val context: Context,
    handler: Handler = Handler(Looper.getMainLooper())
) : ContentObserver(handler) {

    companion object {
        /**
         * Set to true whenever a media-change event arrives, and cleared to false at the
         * start of each SyncWorker run.  If the flag is still true when a run finishes it
         * means at least one new change arrived while the sync was in progress; SyncWorker
         * schedules a single follow-up sync in that case so no event is silently dropped.
         */
        val pendingRetrigger = AtomicBoolean(false)
    }

    override fun onChange(selfChange: Boolean) {
        handleMediaChange(selfChange, null, 0, "onChange(Boolean)")
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        handleMediaChange(selfChange, uri, 0, "onChange(Boolean, Uri?)")
    }

    override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) {
        handleMediaChange(selfChange, uri, flags, "onChange(Boolean, Uri?, Int)")
    }

    override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
        Log.d(TAG, "Observer callback: onChange(Boolean, Collection<Uri>, Int), selfChange=$selfChange, count=${uris.size}, flags=$flags")
        triggerSync("Batch change (${uris.size} uris)")
    }

    private fun handleMediaChange(selfChange: Boolean, uri: Uri?, flags: Int, source: String) {
        Log.d(TAG, "Observer callback: $source, selfChange=$selfChange, uri=$uri, flags=$flags, ts=${System.currentTimeMillis()}")
        triggerSync(uri?.toString() ?: "null uri")
    }

    fun triggerSync(reason: String = "manual") {
        // Always record that a sync is wanted before trying to enqueue, so SyncWorker can
        // detect events that arrived while a run was already in progress.
        pendingRetrigger.set(true)
        Log.d(TAG, "Enqueuing sync work. Reason: $reason. Policy: KEEP")
        val syncWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>().build()

        // KEEP: do not cancel a sync that is already running mid-DB-write.
        // SyncWorker checks pendingRetrigger after it finishes and schedules a follow-up
        // sync if new events arrived during the run, so no change is ever silently lost.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(SYNC_WORK_NAME, ExistingWorkPolicy.KEEP, syncWorkRequest)
    }

    fun register() {
        Log.d(TAG, "Registering MediaStore observers")
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
        Log.d(TAG, "Unregistering MediaStore observers")
        context.contentResolver.unregisterContentObserver(this)
    }
}
