package com.example.boxpandora.ui.main.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.boxpandora.data.local.AppDatabase
import com.example.boxpandora.data.repository.TagRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

sealed class BackupOpState {
    object Idle    : BackupOpState()
    data class Running(val label: String)  : BackupOpState()
    data class Done (val message: String)  : BackupOpState()
    data class Error(val message: String)  : BackupOpState()
}

class BackupDataViewModel(
    private val context: Context,
    private val database: AppDatabase,
    private val tagRepository: TagRepository
) : ViewModel() {

    private val _opState = MutableStateFlow<BackupOpState>(BackupOpState.Idle)
    val opState: StateFlow<BackupOpState> = _opState

    // ── Suggested SAF file names ──────────────────────────────────────────────

    private fun dateSuffix() =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun suggestedTagsExportName() = "PandoraTagMetadata_${dateSuffix()}.json"
    fun suggestedDbBackupName()   = "PandoraBackup_${dateSuffix()}.db"

    // ── Export tag metadata ───────────────────────────────────────────────────

    fun exportTagMetadata(destUri: Uri) {
        if (_opState.value is BackupOpState.Running) return
        viewModelScope.launch {
            _opState.value = BackupOpState.Running("Exporting tag metadata…")
            try {
                val json = withContext(Dispatchers.IO) { buildTagMetadataJson() }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destUri)
                        ?.use { out -> out.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: error("Unable to open output stream")
                }
                _opState.value = BackupOpState.Done("Tag metadata exported successfully.")
            } catch (e: Exception) {
                _opState.value = BackupOpState.Error("Export failed: ${e.message}")
            }
        }
    }

    private fun buildTagMetadataJson(): String {
        val db = database.openHelper.readableDatabase

        val tagsArr = JSONArray()
        db.query(SimpleSQLiteQuery(
            "SELECT name, normalized_name, color, icon, category, description FROM tags"
        )).use { c ->
            while (c.moveToNext()) {
                tagsArr.put(JSONObject().apply {
                    put("name",           c.getString(0) ?: "")
                    put("normalizedName", c.getString(1) ?: "")
                    put("color",          c.getString(2) ?: "#888888")
                    putOpt("icon",        c.getString(3))
                    put("category",       c.getString(4) ?: "misc")
                    putOpt("description", c.getString(5))
                })
            }
        }

        val mappingsArr = JSONArray()
        db.query(SimpleSQLiteQuery(
            "SELECT mt.media_uri, t.name FROM media_tags mt INNER JOIN tags t ON t.id = mt.tag_id"
        )).use { c ->
            while (c.moveToNext()) {
                mappingsArr.put(JSONObject().apply {
                    put("mediaUri", c.getString(0) ?: "")
                    put("tagName",  c.getString(1) ?: "")
                })
            }
        }

        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

        return JSONObject().apply {
            put("version",          "1")
            put("exportedAt",       ts)
            put("tags",             tagsArr)
            put("mediaTagMappings", mappingsArr)
        }.toString(2)
    }

    // ── Import tag metadata ───────────────────────────────────────────────────

    fun importTagMetadata(srcUri: Uri) {
        if (_opState.value is BackupOpState.Running) return
        viewModelScope.launch {
            _opState.value = BackupOpState.Running("Importing tag metadata…")
            try {
                val (tagsRestored, mappingsRestored) = withContext(Dispatchers.IO) {
                    val raw = context.contentResolver.openInputStream(srcUri)
                        ?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: error("Unable to open input stream")
                    applyTagMetadataJson(raw)
                }
                _opState.value = BackupOpState.Done(
                    "Restored $tagsRestored tag(s) and $mappingsRestored assignment(s)."
                )
            } catch (e: Exception) {
                _opState.value = BackupOpState.Error("Import failed: ${e.message}")
            }
        }
    }

    /** Applies the JSON payload. Returns (tagsRestored, mappingsRestored). */
    private suspend fun applyTagMetadataJson(json: String): Pair<Int, Int> {
        val root     = JSONObject(json)
        val tagsArr  = root.optJSONArray("tags")             ?: JSONArray()
        val mapsArr  = root.optJSONArray("mediaTagMappings") ?: JSONArray()

        var tagsRestored = 0
        for (i in 0 until tagsArr.length()) {
            val t = tagsArr.getJSONObject(i)
            tagRepository.getOrCreateTag(
                name     = t.getString("name"),
                category = t.optString("category", "misc")
            )
            tagsRestored++
        }

        var mappingsRestored = 0
        for (i in 0 until mapsArr.length()) {
            val m       = mapsArr.getJSONObject(i)
            val uri     = m.optString("mediaUri", "")
            val tagName = m.optString("tagName",  "")
            if (uri.isBlank() || tagName.isBlank()) continue
            try {
                tagRepository.attachTagToMedia(uri, tagName)
                mappingsRestored++
            } catch (_: Exception) {
                // URI may not yet exist in media_index — skip silently
            }
        }
        return tagsRestored to mappingsRestored
    }

    // ── Backup database ───────────────────────────────────────────────────────

    fun backupDatabase(destUri: Uri) {
        if (_opState.value is BackupOpState.Running) return
        viewModelScope.launch {
            _opState.value = BackupOpState.Running("Backing up database…")
            try {
                withContext(Dispatchers.IO) {
                    // Flush pending WAL pages into the main db file
                    database.openHelper.writableDatabase
                        .execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                    val dbFile = context.getDatabasePath("pandora_db")
                    context.contentResolver.openOutputStream(destUri)
                        ?.use { out -> dbFile.inputStream().use { it.copyTo(out) } }
                        ?: error("Unable to open output stream")
                }
                _opState.value = BackupOpState.Done("Database backup created successfully.")
            } catch (e: Exception) {
                _opState.value = BackupOpState.Error("Backup failed: ${e.message}")
            }
        }
    }

    // ── Restore database ──────────────────────────────────────────────────────

    fun restoreDatabase(srcUri: Uri) {
        if (_opState.value is BackupOpState.Running) return
        viewModelScope.launch {
            _opState.value = BackupOpState.Running("Restoring database…")
            try {
                withContext(Dispatchers.IO) {
                    val dbFile = context.getDatabasePath("pandora_db")
                    // Close Room before overwriting the file on disk
                    database.close()
                    context.contentResolver.openInputStream(srcUri)
                        ?.use { inp -> dbFile.outputStream().use { inp.copyTo(it) } }
                        ?: error("Unable to open input stream")
                    // Delete stale WAL / SHM so old pending writes don't interfere
                    context.getDatabasePath("pandora_db-wal").delete()
                    context.getDatabasePath("pandora_db-shm").delete()
                }
                _opState.value = BackupOpState.Done(
                    "Restore complete. The app will now close — please reopen it."
                )
                // Let the snackbar be visible briefly, then terminate
                delay(3_000)
                android.os.Process.killProcess(android.os.Process.myPid())
            } catch (e: Exception) {
                _opState.value = BackupOpState.Error("Restore failed: ${e.message}")
            }
        }
    }

    fun clearResult() { _opState.value = BackupOpState.Idle }
}

class BackupDataViewModelFactory(
    private val context: Context,
    private val database: AppDatabase,
    private val tagRepository: TagRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BackupDataViewModel::class.java)) {
            return BackupDataViewModel(context, database, tagRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
