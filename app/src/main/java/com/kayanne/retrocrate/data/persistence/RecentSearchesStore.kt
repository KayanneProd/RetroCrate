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

// Recent search queries, persisted so they survive leaving the app and app updates. Most recent
// first, de-duplicated, capped.
object RecentSearchesStore {

    private const val MAX = 12
    private val KEY = stringPreferencesKey("recent_searches")
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var dataStore: DataStore<Preferences>

    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    fun initialize(context: Context) {
        if (::dataStore.isInitialized) return
        val appContext = context.applicationContext
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { appContext.preferencesDataStoreFile("recent_searches") },
        )
    }

    suspend fun load() {
        if (!::dataStore.isInitialized) return
        val raw = dataStore.data.first()[KEY] ?: return
        _recent.value = runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun add(query: String) {
        val q = query.trim()
        if (q.isBlank() || !::dataStore.isInitialized) return
        val ql = q.lowercase()
        // Drop existing entries that are a prefix of this query, so incremental typing
        // ("m" → "ma" → "mario") collapses to the final word instead of stacking up.
        val updated = (listOf(q) + _recent.value.filterNot { ql.startsWith(it.lowercase()) }).take(MAX)
        _recent.value = updated
        dataStore.edit { it[KEY] = json.encodeToString(updated) }
    }

    suspend fun clear() {
        if (!::dataStore.isInitialized) return
        _recent.value = emptyList()
        dataStore.edit { it.remove(KEY) }
    }
}
