package com.kayanne.retrocrate.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayanne.retrocrate.data.repository.GameCatalogRepository
import com.kayanne.retrocrate.data.repository.LoadStatus
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val GENRE_CARD_COUNT = 8

class HomeViewModel : ViewModel() {

    private val repository = GameCatalogRepository

    private val rails = combine(
        repository.observeFeatured(),
        repository.observeNewArrivals(),
        repository.observePopular(),
        repository.observeDiscover(),
    ) { featured, newArrivals, popular, discover ->
        Rails(featured, newArrivals, popular, discover)
    }

    private val browse = combine(
        repository.observeTopGenres(GENRE_CARD_COUNT),
        repository.observePlatformsInCatalog(),
    ) { genres, platforms -> genres to platforms }

    val uiState: StateFlow<HomeUiState> = combine(
        repository.status,
        rails,
        browse,
    ) { status, rails, (genres, platforms) ->
        HomeUiState(
            status = status,
            featured = rails.featured,
            newArrivals = rails.newArrivals,
            popular = rails.popular,
            discover = rails.discover,
            genres = genres,
            platforms = platforms,
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
            repository.ensureLoaded()
        }
    }

    private data class Rails(
        val featured: List<Game>,
        val newArrivals: List<Game>,
        val popular: List<Game>,
        val discover: List<Game>,
    )
}

data class HomeUiState(
    val status: LoadStatus = LoadStatus.Idle,
    val featured: List<Game> = emptyList(),
    val newArrivals: List<Game> = emptyList(),
    val popular: List<Game> = emptyList(),
    val discover: List<Game> = emptyList(),
    val genres: List<String> = emptyList(),
    val platforms: List<Platform> = emptyList(),
)
