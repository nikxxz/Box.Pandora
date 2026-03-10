package com.example.boxpandora.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.util.NomediaScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PandoraApp
        val repository = app.repository
        val albumDao = app.database.albumDao()

        try {
            // 1. Sync from MediaStore (Native Android Gallery)
            repository.syncMediaStore()

            // 2. Scan for .nomedia (Hidden) folders
            val scanner = NomediaScanner()
            val hiddenAlbums = scanner.scanForNomediaFolders()
            if (hiddenAlbums.isNotEmpty()) {
                albumDao.insertAll(hiddenAlbums)
            }

            // 3. Trigger ML Indexing (Placeholder for now)
            // MLIndexer.run(applicationContext)

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
