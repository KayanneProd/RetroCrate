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

// Personal-backup debrid services (Premiumize / Real-Debrid). Keys are user-supplied and only ever
// sent to the service they belong to.
data class DebridSettings(
    val premiumizeApiKey: String? = null,
    val realDebridApiKey: String? = null,
) {
    val anyConfigured: Boolean get() = !premiumizeApiKey.isNullOrBlank() || !realDebridApiKey.isNullOrBlank()
}

enum class DebridProvider { PREMIUMIZE, REAL_DEBRID }

// Persists one SAF DocumentTree URI per Platform. N64 ROMs go to the user's N64 folder,
// SNES to SNES, etc. — no shared bucket. Also holds optional debrid API keys.
object SettingsStore {

    private lateinit var dataStore: DataStore<Preferences>

    private val _platformFolders = MutableStateFlow<Map<Platform, String>>(emptyMap())
    val platformFolders: StateFlow<Map<Platform, String>> = _platformFolders.asStateFlow()

    private val _debrid = MutableStateFlow(DebridSettings())
    val debrid: StateFlow<DebridSettings> = _debrid.asStateFlow()

    private val premiumizeKey = stringPreferencesKey("debrid_premiumize")
    private val realDebridKey = stringPreferencesKey("debrid_realdebrid")

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
        _debrid.value = DebridSettings(
            premiumizeApiKey = prefs[premiumizeKey]?.takeIf { it.isNotBlank() },
            realDebridApiKey = prefs[realDebridKey]?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun setDebridKey(provider: DebridProvider, key: String?) {
        if (!::dataStore.isInitialized) return
        val trimmed = key?.trim()?.takeIf { it.isNotEmpty() }
        val prefKey = when (provider) {
            DebridProvider.PREMIUMIZE -> premiumizeKey
            DebridProvider.REAL_DEBRID -> realDebridKey
        }
        dataStore.edit { prefs -> if (trimmed == null) prefs.remove(prefKey) else prefs[prefKey] = trimmed }
        _debrid.value = when (provider) {
            DebridProvider.PREMIUMIZE -> _debrid.value.copy(premiumizeApiKey = trimmed)
            DebridProvider.REAL_DEBRID -> _debrid.value.copy(realDebridApiKey = trimmed)
        }
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
