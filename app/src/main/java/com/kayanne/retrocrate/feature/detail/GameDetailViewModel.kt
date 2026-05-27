package com.kayanne.retrocrate.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.kayanne.retrocrate.data.repository.GameCatalogRepository
import com.kayanne.retrocrate.data.repository.LoadStatus
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.navigation.GameDetailRoute
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val _initialError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<GameDetailUiState> = combine(
        repository.observeGame(gameId),
        repository.status,
        _initialError,
    ) { game, status, errorMessage ->
        when {
            game != null -> GameDetailUiState.Loaded(game)
            status is LoadStatus.Loading -> GameDetailUiState.Loading
            errorMessage != null -> GameDetailUiState.Error(errorMessage)
            status is LoadStatus.Error -> GameDetailUiState.Error(status.message)
            else -> GameDetailUiState.NotFound(gameId)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GameDetailUiState.Loading,
    )

    init {
        viewModelScope.launch {
            repository.ensureLoaded()
        }
    }
}

sealed interface GameDetailUiState {
    data object Loading : GameDetailUiState
    data class Loaded(val game: Game) : GameDetailUiState
    data class NotFound(val gameId: String) : GameDetailUiState
    data class Error(val message: String) : GameDetailUiState
}
