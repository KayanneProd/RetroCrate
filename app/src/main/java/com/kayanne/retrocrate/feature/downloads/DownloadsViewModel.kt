package com.kayanne.retrocrate.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayanne.retrocrate.data.download.DownloadCoordinator
import com.kayanne.retrocrate.data.persistence.DownloadHistoryStore
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
        DownloadCoordinator.info,
        GameCatalogRepository.catalog,
        DownloadHistoryStore.history,
    ) { downloads, info, catalog, history ->
        // Active = anything still queued / downloading / failed this session.
        val active = downloads
            .filterValues { it !is DownloadState.Completed }
            .mapNotNull { (gameId, state) ->
                val game = catalog.find { it.id == gameId } ?: return@mapNotNull null
                DownloadRow(game = game, state = state, info = info[gameId])
            }
            .sortedBy { it.game.title.lowercase() }

        // History = everything ever completed (persisted, newest first).
        DownloadsUiState(active = active, history = history)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())
}

data class DownloadsUiState(
    val active: List<DownloadRow> = emptyList(),
    val history: List<DownloadHistoryStore.Entry> = emptyList(),
)

data class DownloadRow(
    val game: Game,
    val state: DownloadState,
    val info: DownloadCoordinator.DownloadInfo? = null,
)
