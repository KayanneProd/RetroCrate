package com.kayanne.retrocrate

import android.app.Application
import com.kayanne.retrocrate.data.network.HttpClient
import com.kayanne.retrocrate.data.persistence.CatalogStore
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
        // OpenVGDB extraction is a one-time ~42 MB asset → filesDir copy. Async so cold launch
        // isn't blocked; the catalog repository awaits init on first use.
        appScope.launch { OpenVgdbSource.initialize(this@RetroCrateApp) }
    }
}
