package com.kayanne.retrocrate.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.kayanne.retrocrate.data.repository.LoadStatus
import com.kayanne.retrocrate.data.repository.VimmsGameRepository
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.navigation.GameDetailRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GameDetailViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val repository = VimmsGameRepository
    private val gameId: String = savedStateHandle.toRoute<GameDetailRoute>().gameId

    private val _uiState = MutableStateFlow<GameDetailUiState>(GameDetailUiState.Loading)
    val uiState: StateFlow<GameDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureLoaded()
            val game = repository.getById(gameId)
            _uiState.value = when {
                game != null -> GameDetailUiState.Loaded(game)
                repository.status.value is LoadStatus.Error -> {
                    val msg = (repository.status.value as LoadStatus.Error).message
                    GameDetailUiState.Error(msg)
                }
                else -> GameDetailUiState.NotFound(gameId)
            }
        }
    }
}

sealed interface GameDetailUiState {
    data object Loading : GameDetailUiState
    data class Loaded(val game: Game) : GameDetailUiState
    data class NotFound(val gameId: String) : GameDetailUiState
    data class Error(val message: String) : GameDetailUiState
}
