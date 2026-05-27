package com.kayanne.retrocrate.feature.detail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.kayanne.retrocrate.data.download.DownloadCoordinator
import com.kayanne.retrocrate.data.repository.GameCatalogRepository
import com.kayanne.retrocrate.data.repository.LoadStatus
import com.kayanne.retrocrate.domain.model.DownloadState
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.navigation.GameDetailRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GameDetailViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val repository = GameCatalogRepository
    private val gameId: String = savedStateHandle.toRoute<GameDetailRoute>().gameId

    val uiState: StateFlow<GameDetailUiState> = combine(
        repository.observeGame(gameId),
        repository.status,
        DownloadCoordinator.downloads,
    ) { game, status, downloads ->
        val downloadState = downloads[gameId] ?: DownloadState.NotStarted
        when {
            game != null -> GameDetailUiState.Loaded(game = game, downloadState = downloadState)
            status is LoadStatus.Loading -> GameDetailUiState.Loading
            status is LoadStatus.Error -> GameDetailUiState.Error(status.message)
            else -> GameDetailUiState.NotFound(gameId)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GameDetailUiState.Loading,
    )

    init {
        viewModelScope.launch { repository.ensureLoaded() }
    }

    fun onInstall(context: Context) {
        val game = (uiState.value as? GameDetailUiState.Loaded)?.game ?: return
        DownloadCoordinator.startDownload(game, context)
    }
}

sealed interface GameDetailUiState {
    data object Loading : GameDetailUiState
    data class Loaded(
        val game: Game,
        val downloadState: DownloadState = DownloadState.NotStarted,
    ) : GameDetailUiState
    data class NotFound(val gameId: String) : GameDetailUiState
    data class Error(val message: String) : GameDetailUiState
}
