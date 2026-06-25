package com.kayanne.retrocrate.data.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

// Persists parsed catalogs per platform as a JSON blob in DataStore Preferences.
// Cheap to read on cold start (~1 MB read off disk) so the user sees games immediately
// while a silent re-fetch refreshes the cache in the background.
//
// The version int lets us invalidate stale snapshots when the Game schema changes
// (kotlinx-serialization will happily decode old shapes but fields may be missing).
object CatalogStore {

    // Bump on any change to how Game is built so old snapshots get re-fetched.
    // v1 = Vimm's-shaped catalog (no genres). v2 = OpenVGDB-shaped (genres, descriptions, etc.).
    // v3 = real-art only (verified cover required; junk/fake titles filtered out).
    // v4 = adds Game.releaseDate (YYYYMMDD) for accurate newest-first sorting.
    // v5 = broadened catalog (cover-less real titles with description+genre now included).
    private const val CURRENT_VERSION = 5
    private lateinit var dataStore: DataStore<Preferences>

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun initialize(context: Context) {
        if (::dataStore.isInitialized) return
        val appContext = context.applicationContext
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { appContext.preferencesDataStoreFile("catalog") },
        )
    }

    suspend fun load(platform: Platform): List<Game>? {
        if (!::dataStore.isInitialized) return null
        val prefs = dataStore.data.first()
        val storedVersion = prefs[versionKey(platform)] ?: return null
        if (storedVersion != CURRENT_VERSION) return null
        val raw = prefs[catalogKey(platform)] ?: return null
        return runCatching { json.decodeFromString<List<Game>>(raw) }.getOrNull()
    }

    suspend fun save(platform: Platform, games: List<Game>) {
        if (!::dataStore.isInitialized) return
        val raw = json.encodeToString(games)
        dataStore.edit { prefs ->
            prefs[catalogKey(platform)] = raw
            prefs[versionKey(platform)] = CURRENT_VERSION
        }
    }

    private fun catalogKey(platform: Platform) =
        stringPreferencesKey("catalog_${platform.name.lowercase()}")

    private fun versionKey(platform: Platform) =
        intPreferencesKey("catalog_version_${platform.name.lowercase()}")
}
