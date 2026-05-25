package com.kayanne.retrocrate.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayanne.retrocrate.data.repository.FakeGameRepository
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.repository.GameRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class HomeViewModel : ViewModel() {

    private val repository: GameRepository = FakeGameRepository

    val uiState: StateFlow<HomeUiState> = combine(
        repository.observeFeatured(),
        repository.observeRecentlyAdded(),
        repository.observePopularRetro(),
    ) { featured, recent, popular ->
        HomeUiState(
            featured = featured,
            recentlyAdded = recent,
            popularRetro = popular,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )
}

data class HomeUiState(
    val featured: List<Game> = emptyList(),
    val recentlyAdded: List<Game> = emptyList(),
    val popularRetro: List<Game> = emptyList(),
)
