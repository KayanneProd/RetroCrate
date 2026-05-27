package com.kayanne.retrocrate.feature.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayanne.retrocrate.data.persistence.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel : ViewModel() {

    private val _files = MutableStateFlow<List<LibraryFile>>(emptyList())

    val uiState: StateFlow<LibraryUiState> = combine(
        SettingsStore.observeStorageTreeUri(),
        _files,
    ) { storageUri, files ->
        LibraryUiState(storageUri = storageUri, files = files)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun refresh(context: Context) {
        viewModelScope.launch {
            val storedUriString = SettingsStore.observeStorageTreeUri().first()
            if (storedUriString.isNullOrBlank()) {
                _files.value = emptyList()
                return@launch
            }
            _files.value = withContext(Dispatchers.IO) {
                listFilesInTree(context, Uri.parse(storedUriString))
            }
        }
    }

    private fun listFilesInTree(context: Context, uri: Uri): List<LibraryFile> {
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return emptyList()
        return tree.listFiles()
            .filter { it.isFile }
            .mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                LibraryFile(
                    name = name,
                    sizeBytes = file.length(),
                    uri = file.uri.toString(),
                )
            }
            .sortedBy { it.name.lowercase() }
    }
}

data class LibraryUiState(
    val storageUri: String? = null,
    val files: List<LibraryFile> = emptyList(),
)

data class LibraryFile(
    val name: String,
    val sizeBytes: Long,
    val uri: String,
)
