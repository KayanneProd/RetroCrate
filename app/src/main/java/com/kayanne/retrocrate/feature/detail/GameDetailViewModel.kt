package com.kayanne.retrocrate.feature.detail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.kayanne.retrocrate.data.download.DownloadCoordinator
import com.kayanne.retrocrate.data.repository.GameCatalogRepository
import com.kayanne.retrocrate.data.repository.LoadStatus
import com.kayanne.retrocrate.data.source.LibretroThumbnails
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
            game != null -> GameDetailUiState.Loaded(
                game = game,
                downloadState = downloadState,
                screenshots = screenshotsFor(game),
                trailerUrl = trailerUrlFor(game),
            )
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

    // Switch gets real eShop screenshots from titledb; retro falls back to a libretro gameplay snap
    // derived from the exact No-Intro filename (blank/404 ones just don't render).
    private fun screenshotsFor(game: Game): List<String> {
        if (game.screenshots.isNotEmpty()) return game.screenshots
        val rom = game.sources.firstOrNull()?.resolveUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
        val base = rom.substringBeforeLast('.')
        return LibretroThumbnails.snapUrlFromBase(game.platform, base)?.let { listOf(it) }.orEmpty()
    }

    // Keyless trailer: a YouTube search the user's YouTube app/browser opens — keeps the app from
    // embedding Google while still getting them to the trailer in one tap.
    private fun trailerUrlFor(game: Game): String {
        val query = "${game.title} ${game.platform.displayName} trailer"
        return "https://www.youtube.com/results?search_query=" + Uri.encode(query)
    }
}

sealed interface GameDetailUiState {
    data object Loading : GameDetailUiState
    data class Loaded(
        val game: Game,
        val downloadState: DownloadState = DownloadState.NotStarted,
        val screenshots: List<String> = emptyList(),
        val trailerUrl: String = "",
    ) : GameDetailUiState
    data class NotFound(val gameId: String) : GameDetailUiState
    data class Error(val message: String) : GameDetailUiState
}
