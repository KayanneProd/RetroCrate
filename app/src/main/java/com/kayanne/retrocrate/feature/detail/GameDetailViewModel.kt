package com.kayanne.retrocrate.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.navigation.toRoute
import com.kayanne.retrocrate.navigation.GameDetailRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GameDetailViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val route: GameDetailRoute = savedStateHandle.toRoute<GameDetailRoute>()

    private val _uiState = MutableStateFlow(GameDetailUiState(gameId = route.gameId))
    val uiState: StateFlow<GameDetailUiState> = _uiState.asStateFlow()
}

data class GameDetailUiState(
    val gameId: String,
    val isLoading: Boolean = false,
)
