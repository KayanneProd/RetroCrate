package com.kayanne.retrocrate.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.kayanne.retrocrate.data.repository.FakeGameRepository
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.repository.GameRepository
import com.kayanne.retrocrate.navigation.GameDetailRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GameDetailViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val repository: GameRepository = FakeGameRepository
    private val gameId: String = savedStateHandle.toRoute<GameDetailRoute>().gameId

    private val _uiState = MutableStateFlow<GameDetailUiState>(GameDetailUiState.Loading)
    val uiState: StateFlow<GameDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val game = repository.getById(gameId)
            _uiState.value = if (game != null) {
                GameDetailUiState.Loaded(game)
            } else {
                GameDetailUiState.NotFound(gameId)
            }
        }
    }
}

sealed interface GameDetailUiState {
    data object Loading : GameDetailUiState
    data class Loaded(val game: Game) : GameDetailUiState
    data class NotFound(val gameId: String) : GameDetailUiState
}
