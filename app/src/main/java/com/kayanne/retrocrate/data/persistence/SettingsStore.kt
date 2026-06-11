package com.kayanne.retrocrate.data.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.kayanne.retrocrate.domain.model.Platform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Persists one SAF DocumentTree URI per Platform. N64 ROMs go to the user's N64 folder,
// SNES to SNES, etc. — no shared bucket.
object SettingsStore {

    private lateinit var dataStore: DataStore<Preferences>

    private val _platformFolders = MutableStateFlow<Map<Platform, String>>(emptyMap())
    val platformFolders: StateFlow<Map<Platform, String>> = _platformFolders.asStateFlow()

    fun initialize(context: Context) {
        if (::dataStore.isInitialized) return
        val appContext = context.applicationContext
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { appContext.preferencesDataStoreFile("settings") },
        )
    }

    suspend fun load() {
        if (!::dataStore.isInitialized) return
        val prefs = dataStore.data.first()
        val map = HashMap<Platform, String>()
        for (platform in Platform.entries) {
            prefs[platformKey(platform)]?.let { map[platform] = it }
        }
        _platformFolders.value = map
    }

    suspend fun setPlatformFolder(platform: Platform, uri: String?) {
        if (!::dataStore.isInitialized) return
        dataStore.edit { prefs ->
            val key = platformKey(platform)
            if (uri == null) prefs.remove(key) else prefs[key] = uri
        }
        _platformFolders.value = _platformFolders.value.toMutableMap().apply {
            if (uri == null) remove(platform) else put(platform, uri!!)
        }
    }

    suspend fun folderFor(platform: Platform): String? =
        _platformFolders.value[platform]
            ?: run {
                // First call before load() finished — fall back to a direct read.
                if (!::dataStore.isInitialized) return null
                dataStore.data.first()[platformKey(platform)]
            }

    fun observeFolder(platform: Platform): Flow<String?> =
        platformFolders.map { it[platform] }

    private fun platformKey(platform: Platform) =
        stringPreferencesKey("storage_${platform.name.lowercase()}")
}
