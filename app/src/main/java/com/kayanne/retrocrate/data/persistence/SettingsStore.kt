package com.kayanne.retrocrate.data.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Holds the user's downloads-folder URI (a SAF DocumentTree URI, persisted across launches).
object SettingsStore {

    private val STORAGE_TREE_URI = stringPreferencesKey("storage_tree_uri")

    private lateinit var dataStore: DataStore<Preferences>

    private val _storageTreeUri = MutableStateFlow<String?>(null)
    val storageTreeUri: Flow<String?> = _storageTreeUri

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
        _storageTreeUri.value = prefs[STORAGE_TREE_URI]
    }

    suspend fun setStorageTreeUri(uri: String?) {
        if (!::dataStore.isInitialized) return
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(STORAGE_TREE_URI)
            else prefs[STORAGE_TREE_URI] = uri
        }
        _storageTreeUri.value = uri
    }

    fun observeStorageTreeUri(): Flow<String?> =
        if (::dataStore.isInitialized) dataStore.data.map { it[STORAGE_TREE_URI] }
        else _storageTreeUri
}
