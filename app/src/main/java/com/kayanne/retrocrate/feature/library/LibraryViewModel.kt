package com.kayanne.retrocrate.feature.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayanne.retrocrate.data.persistence.SettingsStore
import com.kayanne.retrocrate.domain.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel : ViewModel() {

    private val _files = MutableStateFlow<List<LibraryFile>>(emptyList())

    val uiState: StateFlow<LibraryUiState> = combine(
        SettingsStore.platformFolders,
        _files,
    ) { folders, files ->
        LibraryUiState(configuredPlatforms = folders.keys, files = files)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun refresh(context: Context) {
        viewModelScope.launch {
            val folders = SettingsStore.platformFolders.value
            if (folders.isEmpty()) {
                _files.value = emptyList()
                return@launch
            }
            _files.value = withContext(Dispatchers.IO) {
                folders.flatMap { (platform, uriString) ->
                    listFilesInTree(context, platform, Uri.parse(uriString))
                }.sortedBy { it.name.lowercase() }
            }
        }
    }

    private fun listFilesInTree(
        context: Context,
        platform: Platform,
        uri: Uri,
    ): List<LibraryFile> {
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return emptyList()
        return tree.listFiles()
            .filter { it.isFile }
            .mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                LibraryFile(
                    name = name,
                    platform = platform,
                    sizeBytes = file.length(),
                    uri = file.uri.toString(),
                )
            }
    }
}

data class LibraryUiState(
    val configuredPlatforms: Set<Platform> = emptySet(),
    val files: List<LibraryFile> = emptyList(),
)

data class LibraryFile(
    val name: String,
    val platform: Platform,
    val sizeBytes: Long,
    val uri: String,
)
