package com.kayanne.retrocrate

import android.app.Application
import com.kayanne.retrocrate.data.network.HttpClient
import com.kayanne.retrocrate.data.persistence.CatalogStore

class RetroCrateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HttpClient.initialize(this)
        CatalogStore.initialize(this)
    }
}
