package com.kayanne.retrocrate.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayanne.retrocrate.data.download.DownloadCoordinator
import com.kayanne.retrocrate.data.repository.GameCatalogRepository
import com.kayanne.retrocrate.domain.model.DownloadState
import com.kayanne.retrocrate.domain.model.Game
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class DownloadsViewModel : ViewModel() {

    val uiState: StateFlow<DownloadsUiState> = combine(
        DownloadCoordinator.downloads,
        GameCatalogRepository.catalog,
    ) { downloads, catalog ->
        val items = downloads.mapNotNull { (gameId, state) ->
            val game = catalog.find { it.id == gameId } ?: return@mapNotNull null
            DownloadRow(game = game, state = state)
        }.sortedBy { it.game.title.lowercase() }
        DownloadsUiState(items = items)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())
}

data class DownloadsUiState(
    val items: List<DownloadRow> = emptyList(),
)

data class DownloadRow(
    val game: Game,
    val state: DownloadState,
)
