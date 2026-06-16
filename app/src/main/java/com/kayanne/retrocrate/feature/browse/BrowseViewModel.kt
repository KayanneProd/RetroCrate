package com.kayanne.retrocrate.feature.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.kayanne.retrocrate.data.repository.GameCatalogRepository
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform
import com.kayanne.retrocrate.navigation.BrowseRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BrowseViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val repository = GameCatalogRepository
    private val route = savedStateHandle.toRoute<BrowseRoute>()

    val uiState: StateFlow<BrowseUiState> = repository.catalog
        .map { games ->
            when (route.kind) {
                "new" -> BrowseUiState(
                    title = route.value,
                    // Full newest list (no per-platform cap, unlike the Home rail) so the user can
                    // scroll through every recent release, newest first by actual date.
                    games = games
                        .sortedByDescending { it.releaseDate ?: (it.releaseYear?.times(10000) ?: Int.MIN_VALUE) }
                        .take(NEW_LIMIT),
                )
                "genre" -> BrowseUiState(
                    title = route.value,
                    games = games
                        .filter { game -> game.genres.any { it.equals(route.value, ignoreCase = true) } }
                        .sortedBy { it.title.lowercase() },
                )
                "platform" -> {
                    val platform = runCatching { Platform.valueOf(route.value) }.getOrNull()
                    BrowseUiState(
                        title = platform?.displayName ?: route.value,
                        games = games
                            .filter { it.platform == platform }
                            .sortedBy { it.title.lowercase() },
                    )
                }
                else -> BrowseUiState(title = route.value, games = emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState())

    init {
        viewModelScope.launch { repository.ensureLoaded() }
    }
}

private const val NEW_LIMIT = 300

data class BrowseUiState(
    val title: String = "",
    val games: List<Game> = emptyList(),
)
