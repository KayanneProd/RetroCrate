package com.kayanne.retrocrate.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayanne.retrocrate.data.repository.LoadStatus
import com.kayanne.retrocrate.data.repository.VimmsGameRepository
import com.kayanne.retrocrate.domain.model.Game
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    private val repository = VimmsGameRepository

    val uiState: StateFlow<HomeUiState> = combine(
        repository.status,
        repository.observeFeatured(),
        repository.observeRecentlyAdded(),
        repository.observePopularRetro(),
    ) { status, featured, recent, popular ->
        HomeUiState(
            status = status,
            featured = featured,
            recentlyAdded = recent,
            popularRetro = popular,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.ensureLoaded(forceRefresh = true)
        }
    }
}

data class HomeUiState(
    val status: LoadStatus = LoadStatus.Idle,
    val featured: List<Game> = emptyList(),
    val recentlyAdded: List<Game> = emptyList(),
    val popularRetro: List<Game> = emptyList(),
)
