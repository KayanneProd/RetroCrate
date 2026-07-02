package com.kayanne.retrocrate

import android.app.Application
import com.kayanne.retrocrate.data.network.HttpClient
import com.kayanne.retrocrate.data.persistence.CatalogStore
import com.kayanne.retrocrate.data.persistence.DownloadHistoryStore
import com.kayanne.retrocrate.data.persistence.LibraryStore
import com.kayanne.retrocrate.data.persistence.RecentSearchesStore
import com.kayanne.retrocrate.data.persistence.SettingsStore
import com.kayanne.retrocrate.data.repository.GameCatalogRepository
import com.kayanne.retrocrate.data.source.openvgdb.OpenVgdbSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RetroCrateApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        HttpClient.initialize(this)
        CatalogStore.initialize(this)
        SettingsStore.initialize(this)
        RecentSearchesStore.initialize(this)
        DownloadHistoryStore.initialize(this)
        LibraryStore.initialize(this)
        GameCatalogRepository.initialize(this)
        // OpenVGDB extraction is a one-time ~42 MB asset → filesDir copy. Async so cold launch
        // isn't blocked; the catalog repository awaits init on first use.
        appScope.launch {
            SettingsStore.load()
            RecentSearchesStore.load()
            LibraryStore.load()
            // After SettingsStore so configured folders' history manifests can be merged in.
            DownloadHistoryStore.load(this@RetroCrateApp)
            OpenVgdbSource.initialize(this@RetroCrateApp)
        }
    }
}
