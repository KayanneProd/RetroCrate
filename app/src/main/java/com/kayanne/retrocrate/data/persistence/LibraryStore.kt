package com.kayanne.retrocrate.data.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

// The user's wishlist: games they've added to their Library to grab later. RetroCrate is a downloader,
// not a launcher — so this is intentionally just "games I want", with no install-state or play-history
// tracking (the frontend emulator owns launching). Persisted, newest-added first.
object LibraryStore {

    private val KEY = stringPreferencesKey("wishlist_ids")
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var dataStore: DataStore<Preferences>

    // Game ids in add order (newest last); exposed newest-first to the UI.
    private val _wishlist = MutableStateFlow<List<String>>(emptyList())
    val wishlist: StateFlow<List<String>> = _wishlist.asStateFlow()

    fun initialize(context: Context) {
        if (::dataStore.isInitialized) return
        val appContext = context.applicationContext
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { appContext.preferencesDataStoreFile("library") },
        )
    }

    suspend fun load() {
        if (!::dataStore.isInitialized) return
        val raw = dataStore.data.first()[KEY] ?: return
        _wishlist.value = runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    fun isWishlisted(gameId: String): Boolean = gameId in _wishlist.value

    suspend fun toggle(gameId: String) {
        if (!::dataStore.isInitialized || gameId.isBlank()) return
        val current = _wishlist.value
        val updated = if (gameId in current) current - gameId else current + gameId
        _wishlist.value = updated
        dataStore.edit { it[KEY] = json.encodeToString(updated) }
    }
}
