package com.kayanne.retrocrate.feature.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.kayanne.retrocrate.data.repository.GameCatalogRepository
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform
import com.kayanne.retrocrate.navigation.BrowseRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BrowseViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val repository = GameCatalogRepository
    private val route = savedStateHandle.toRoute<BrowseRoute>()

    val uiState: StateFlow<BrowseUiState> = repository.filteredCatalog
        .map { games ->
            when (route.kind) {
                "new" -> BrowseUiState(
                    title = route.value,
                    // Same "New Arrivals" definition as the Home rail (shared so they can't diverge),
                    // just a deeper list — newest first, Switch shovelware gated out.
                    games = repository.newArrivalsFrom(games, NEW_LIMIT),
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
                "collection" -> {
                    val name = repository.collections.value
                        .find { it.id == route.value }?.name
                        ?: route.value.replaceFirstChar { it.uppercase() }
                    BrowseUiState(
                        title = name,
                        // Members in series order — by actual release date, oldest first — so a
                        // franchise reads chronologically and the user can pick where to start.
                        games = repository.collectionMembers(route.value)
                            .sortedWith(
                                compareBy<Game> { it.releaseDate ?: (it.releaseYear?.times(10000) ?: Int.MAX_VALUE) }
                                    .thenBy { it.title.lowercase() },
                            ),
                    )
                }
                else -> BrowseUiState(title = route.value, games = emptyList())
            }
        }
        .flowOn(Dispatchers.Default)
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
