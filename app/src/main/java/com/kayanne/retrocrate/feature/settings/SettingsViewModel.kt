package com.kayanne.retrocrate.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayanne.retrocrate.data.persistence.DownloadHistoryStore
import com.kayanne.retrocrate.data.persistence.SettingsStore
import com.kayanne.retrocrate.data.repository.GameCatalogRepository
import com.kayanne.retrocrate.domain.model.Platform
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = SettingsStore.platformFolders
        .map { folders ->
            SettingsUiState(
                rows = GameCatalogRepository.browsablePlatforms.map { platform ->
                    PlatformFolderRow(platform = platform, uri = folders[platform])
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onFolderPicked(context: Context, platform: Platform, uri: Uri) {
        val appContext = context.applicationContext
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        viewModelScope.launch {
            SettingsStore.setPlatformFolder(platform, uri.toString())
            // Pull any download history this folder already holds (restores it after a reinstall).
            DownloadHistoryStore.mergeFolder(appContext, uri)
        }
    }

    fun onClearPlatformFolder(platform: Platform) {
        viewModelScope.launch { SettingsStore.setPlatformFolder(platform, null) }
    }
}

data class SettingsUiState(
    val rows: List<PlatformFolderRow> = emptyList(),
)

data class PlatformFolderRow(
    val platform: Platform,
    val uri: String?,
)
