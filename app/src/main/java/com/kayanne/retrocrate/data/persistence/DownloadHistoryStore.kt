package com.kayanne.retrocrate.data.persistence

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.documentfile.provider.DocumentFile
import com.kayanne.retrocrate.domain.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Persistent record of completed downloads, newest first. Survives leaving the app and updates via
// DataStore; survives a full reinstall via a manifest written into each platform's SAF download
// folder (those folders are the user's, so they outlive the app — once a folder is re-granted, its
// history merges back in).
object DownloadHistoryStore {

    private const val TAG = "DownloadHistory"
    private const val MANIFEST = "retrocrate_history.json"
    private val KEY = stringPreferencesKey("download_history")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private lateinit var dataStore: DataStore<Preferences>

    private val _history = MutableStateFlow<List<Entry>>(emptyList())
    val history: StateFlow<List<Entry>> = _history.asStateFlow()

    @Serializable
    data class Entry(
        val gameId: String,
        val title: String,
        val platform: String,
        val filename: String,
        val sizeBytes: Long? = null,
        val completedAt: Long,
    )

    fun initialize(context: Context) {
        if (::dataStore.isInitialized) return
        val appContext = context.applicationContext
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { appContext.preferencesDataStoreFile("download_history") },
        )
    }

    suspend fun load(context: Context) {
        if (!::dataStore.isInitialized) return
        val stored = dataStore.data.first()[KEY]
            ?.let { runCatching { json.decodeFromString<List<Entry>>(it) }.getOrNull() }
            .orEmpty()
        val fromFolders = SettingsStore.platformFolders.value.values
            .flatMap { readManifest(context, Uri.parse(it)) }
        publish(stored + fromFolders)
        persist()
    }

    suspend fun add(context: Context, entry: Entry) {
        if (!::dataStore.isInitialized) return
        publish(_history.value + entry)
        persist()
        runCatching { writeManifestFor(context, entry.platform) }
            .onFailure { Log.w(TAG, "Couldn't write history manifest", it) }
    }

    // Pull in a folder's history right after it's (re)granted — restores history after a reinstall.
    suspend fun mergeFolder(context: Context, folderUri: Uri) {
        if (!::dataStore.isInitialized) return
        val fromFolder = readManifest(context, folderUri)
        if (fromFolder.isEmpty()) return
        publish(_history.value + fromFolder)
        persist()
    }

    private fun publish(all: List<Entry>) {
        _history.value = all
            .groupBy { it.gameId }
            .map { (_, dupes) -> dupes.maxBy { it.completedAt } }
            .sortedByDescending { it.completedAt }
    }

    private suspend fun persist() {
        dataStore.edit { it[KEY] = json.encodeToString(_history.value) }
    }

    private suspend fun writeManifestFor(context: Context, platform: String) = withContext(Dispatchers.IO) {
        val folderUri = SettingsStore.platformFolders.value[runCatching { Platform.valueOf(platform) }.getOrNull()]
            ?: return@withContext
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return@withContext
        val entries = _history.value.filter { it.platform == platform }
        tree.findFile(MANIFEST)?.delete()
        val file = tree.createFile("application/json", MANIFEST) ?: return@withContext
        context.contentResolver.openOutputStream(file.uri)?.use { out ->
            out.write(json.encodeToString(entries).toByteArray())
        }
    }

    private suspend fun readManifest(context: Context, folderUri: Uri): List<Entry> = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext emptyList()
        val file = tree.findFile(MANIFEST)?.takeIf { it.isFile } ?: return@withContext emptyList()
        runCatching {
            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().decodeToString() }
                ?.let { json.decodeFromString<List<Entry>>(it) }
        }.getOrNull().orEmpty()
    }
}
