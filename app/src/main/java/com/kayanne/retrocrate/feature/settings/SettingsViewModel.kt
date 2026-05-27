package com.kayanne.retrocrate.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayanne.retrocrate.data.persistence.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = SettingsStore.observeStorageTreeUri()
        .map { uri -> SettingsUiState(storageTreeUri = uri) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onFolderPicked(context: Context, uri: Uri) {
        // Persist access across reboots / process death.
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        viewModelScope.launch { SettingsStore.setStorageTreeUri(uri.toString()) }
    }

    fun onClearFolder() {
        viewModelScope.launch { SettingsStore.setStorageTreeUri(null) }
    }
}

data class SettingsUiState(
    val storageTreeUri: String? = null,
)
