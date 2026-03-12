package com.example.boxpandora.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.manager.MediaContentObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SyncWorker"
private const val SYNC_WORK_NAME = "pandora_media_sync"

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Clear the flag before work begins. Any new MediaStore events that arrive while
        // this sync is running will re-set it, and we'll schedule a follow-up below.
        MediaContentObserver.pendingRetrigger.set(false)

        val app = applicationContext as PandoraApp
        val repository = app.repository

        try {
            repository.syncMediaStore()

            // If new media events arrived while we were running, schedule one follow-up
            // sync so those changes are not silently lost. Using KEEP here is safe because
            // no sync is currently running at this point.
            if (MediaContentObserver.pendingRetrigger.getAndSet(false)) {
                Log.d(TAG, "New media events arrived during sync run; scheduling follow-up sync.")
                WorkManager.getInstance(applicationContext)
                    .enqueueUniqueWork(
                        SYNC_WORK_NAME,
                        ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<SyncWorker>().build()
                    )
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
