package com.kayanne.retrocrate.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayanne.retrocrate.data.repository.GameCatalogRepository
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn

private const val DEBOUNCE_MS = 300L
private const val MAX_RESULTS = 120
private const val MAX_GENRE_CHIPS = 24

@OptIn(FlowPreview::class)
class SearchViewModel : ViewModel() {

    private val repository = GameCatalogRepository

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _selectedGenre = MutableStateFlow<String?>(null)
    private val _selectedPlatform = MutableStateFlow<Platform?>(null)

    val uiState: StateFlow<SearchUiState> = combine(
        _query.debounce(DEBOUNCE_MS),
        _selectedGenre,
        _selectedPlatform,
        repository.catalog,
    ) { debouncedQuery, selectedGenre, selectedPlatform, games ->
        val availableGenres = games.flatMap { it.genres }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(MAX_GENRE_CHIPS)
            .map { it.key }
            .sorted()

        val availablePlatforms = games.map { it.platform }.distinct().sorted()

        val hasFilter = debouncedQuery.isNotBlank() ||
            selectedGenre != null ||
            selectedPlatform != null

        val results = if (!hasFilter) {
            emptyList()
        } else {
            games.asSequence()
                .filter { debouncedQuery.isBlank() || it.title.contains(debouncedQuery, ignoreCase = true) }
                .filter { selectedGenre == null || selectedGenre in it.genres }
                .filter { selectedPlatform == null || it.platform == selectedPlatform }
                .take(MAX_RESULTS)
                .toList()
        }

        SearchUiState(
            debouncedQuery = debouncedQuery,
            selectedGenre = selectedGenre,
            selectedPlatform = selectedPlatform,
            results = results,
            availableGenres = availableGenres,
            availablePlatforms = availablePlatforms,
            hasFilter = hasFilter,
            catalogSize = games.size,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SearchUiState(),
    )

    fun onQueryChange(query: String) {
        _query.value = query
    }

    fun onGenreToggle(genre: String) {
        _selectedGenre.value = if (_selectedGenre.value == genre) null else genre
    }

    fun onPlatformToggle(platform: Platform) {
        _selectedPlatform.value = if (_selectedPlatform.value == platform) null else platform
    }

    fun onClear() {
        _query.value = ""
        _selectedGenre.value = null
        _selectedPlatform.value = null
    }
}

data class SearchUiState(
    val debouncedQuery: String = "",
    val selectedGenre: String? = null,
    val selectedPlatform: Platform? = null,
    val results: List<Game> = emptyList(),
    val availableGenres: List<String> = emptyList(),
    val availablePlatforms: List<Platform> = emptyList(),
    val hasFilter: Boolean = false,
    val catalogSize: Int = 0,
)
