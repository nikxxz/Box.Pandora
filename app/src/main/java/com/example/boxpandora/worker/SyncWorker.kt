package com.example.boxpandora.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import com.example.boxpandora.PandoraApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PandoraApp
        val repository = app.repository

        try {
            // repository.syncMediaStore() now handles both MediaStore and .nomedia (Hidden) folders internally
            repository.syncMediaStore()

            // Trigger ML Indexing (Placeholder for now)
            // MLIndexer.run(applicationContext)

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
