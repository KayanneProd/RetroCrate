package com.kayanne.retrocrate.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayanne.retrocrate.data.persistence.RecentSearchesStore
import com.kayanne.retrocrate.data.repository.GameCatalogRepository
import com.kayanne.retrocrate.data.repository.SwitchSyncState
import com.kayanne.retrocrate.data.source.TitleSearch
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.GameCollection
import com.kayanne.retrocrate.domain.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val MAX_RESULTS = 120
private const val MAX_GENRE_CHIPS = 24
private const val MAX_COLLECTIONS = 8
private const val MIN_SOURCE_SEARCH_LEN = 2

class SearchViewModel : ViewModel() {

    private val repository = GameCatalogRepository

    // Raw text-field value. Filtering does NOT key off this — ranking ~tens-of-thousands of games on
    // every keystroke lags the device — so it only drives what the field displays.
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    // The query that actually runs, set on Enter (or cleared to empty).
    private val _submittedQuery = MutableStateFlow("")

    private val _selectedGenre = MutableStateFlow<String?>(null)
    private val _selectedPlatform = MutableStateFlow<Platform?>(null)
    private val _liveSearch = MutableStateFlow<LiveSearchState>(LiveSearchState.Idle)

    // The ranked game results. Computed off the main thread by an explicit job (runSearch) so the UI
    // can show a spinner while it runs and keep the previous results visible. Held in the ViewModel,
    // so navigating to a game and back shows the same results instantly — no re-ranking.
    private val _gameResults = MutableStateFlow(GameResults())
    private var searchJob: Job? = null
    private var liveJob: Job? = null

    val recentSearches: StateFlow<List<String>> = RecentSearchesStore.recent

    // Filter chips depend only on the catalog, so a search doesn't recompute them. Off-main.
    private val facets: StateFlow<Facets> = repository.filteredCatalog
        .map { games ->
            Facets(
                genres = games.flatMap { it.genres }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(MAX_GENRE_CHIPS)
                    .map { it.key }
                    .sorted(),
                platforms = games.map { it.platform }.distinct().sorted(),
                catalogSize = games.size,
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, Facets())

    // Collection-name matches are cheap (a few hundred franchises), so they stay reactive on the query.
    private val collectionMatches = combine(_submittedQuery, repository.collections) { query, collections ->
        if (query.isBlank()) emptyList() else TitleSearch.rank(query, collections) { it.name }.take(MAX_COLLECTIONS)
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<SearchUiState> = combine(
        _gameResults,
        collectionMatches,
        combine(_selectedGenre, _selectedPlatform, facets) { genre, platform, facets ->
            Triple(genre, platform, facets)
        },
        repository.switchSync,
        _liveSearch,
    ) { gameResults, collections, (genre, platform, facets), switchSync, liveSearch ->
        SearchUiState(
            submittedQuery = gameResults.submittedQuery,
            selectedGenre = genre,
            selectedPlatform = platform,
            results = gameResults.results,
            collections = collections,
            availableGenres = facets.genres,
            availablePlatforms = facets.platforms,
            hasFilter = gameResults.hasFilter,
            catalogSize = facets.catalogSize,
            searching = gameResults.loading,
            switchSync = switchSync,
            liveSearch = liveSearch,
        )
    }.stateIn(
        scope = viewModelScope,
        // Lazily (not WhileSubscribed): keep the computed results cached for the ViewModel's lifetime,
        // so returning from a game's Detail page is instant instead of re-ranking the catalog.
        started = SharingStarted.Lazily,
        initialValue = SearchUiState(),
    )

    init {
        // Re-run the active search when the catalog changes (e.g. the Switch library finishes loading)
        // so newly-available games appear without the user re-typing.
        viewModelScope.launch {
            repository.filteredCatalog.collect {
                if (_gameResults.value.hasFilter) runSearch()
            }
        }
    }

    fun onQueryChange(query: String) {
        _query.value = query
        // Emptying the field returns to the start screen; otherwise typing doesn't run a search until
        // Enter, so we don't re-rank the whole catalog on every keystroke.
        if (query.isBlank() && _submittedQuery.value.isNotEmpty()) {
            _submittedQuery.value = ""
            resetLiveSearch()
            runSearch()
        }
    }

    // Run the typed query (keyboard "Search" / Enter). This is the only thing that filters by title.
    fun onSearch() {
        val q = _query.value.trim()
        _submittedQuery.value = q
        resetLiveSearch()
        runSearch()
        if (q.length >= MIN_SOURCE_SEARCH_LEN) {
            viewModelScope.launch { RecentSearchesStore.add(q) }
        }
    }

    // A recent-search chip both fills the field and runs immediately — the user already chose it.
    fun onRecentSelected(query: String) {
        _query.value = query
        onSearch()
    }

    fun onResultOpened() {
        val q = _submittedQuery.value
        if (q.isNotBlank()) viewModelScope.launch { RecentSearchesStore.add(q) }
    }

    fun onGenreToggle(genre: String) {
        _selectedGenre.value = if (_selectedGenre.value == genre) null else genre
        runSearch()
    }

    fun onPlatformToggle(platform: Platform) {
        _selectedPlatform.value = if (_selectedPlatform.value == platform) null else platform
        resetLiveSearch()
        runSearch()
    }

    fun onClear() {
        _query.value = ""
        _submittedQuery.value = ""
        _selectedGenre.value = null
        _selectedPlatform.value = null
        resetLiveSearch()
        runSearch()
    }

    // Filters + ranks the catalog off the main thread, flipping `loading` so the UI shows a spinner
    // while keeping the previous results visible. Cancels any in-flight search first.
    private fun runSearch() {
        val q = _submittedQuery.value
        val genre = _selectedGenre.value
        val platform = _selectedPlatform.value
        val hasFilter = q.isNotBlank() || genre != null || platform != null

        searchJob?.cancel()
        if (!hasFilter) {
            _gameResults.value = GameResults(submittedQuery = q, hasFilter = false)
            return
        }
        _gameResults.value = _gameResults.value.copy(submittedQuery = q, hasFilter = true, loading = true)
        searchJob = viewModelScope.launch {
            val results = withContext(Dispatchers.Default) {
                val games = repository.filteredCatalog.value
                val filtered = games.filter {
                    (genre == null || genre in it.genres) && (platform == null || it.platform == platform)
                }
                if (q.isBlank()) filtered.take(MAX_RESULTS)
                else TitleSearch.rank(q, filtered) { it.title }.take(MAX_RESULTS)
            }
            _gameResults.value = GameResults(
                submittedQuery = q,
                results = results,
                hasFilter = true,
                loading = false,
            )
        }
    }

    // Explicit "search beyond the catalog" — hits the live download sources for the current query.
    // User-triggered (never on every keystroke) to respect the scraping etiquette.
    fun onSearchSources() {
        val q = _submittedQuery.value.trim()
        if (q.length < MIN_SOURCE_SEARCH_LEN) return
        liveJob?.cancel()
        _liveSearch.value = LiveSearchState.Loading
        liveJob = viewModelScope.launch {
            val results = runCatching {
                repository.searchSources(q, _selectedPlatform.value)
            }.getOrElse {
                _liveSearch.value = LiveSearchState.Error
                return@launch
            }
            _liveSearch.value =
                if (results.isEmpty()) LiveSearchState.Empty else LiveSearchState.Loaded(results)
        }
    }

    fun onRetrySwitchSync() {
        repository.retrySwitchSync()
    }

    private fun resetLiveSearch() {
        liveJob?.cancel()
        if (_liveSearch.value != LiveSearchState.Idle) _liveSearch.value = LiveSearchState.Idle
    }
}

private data class Facets(
    val genres: List<String> = emptyList(),
    val platforms: List<Platform> = emptyList(),
    val catalogSize: Int = 0,
)

private data class GameResults(
    val submittedQuery: String = "",
    val results: List<Game> = emptyList(),
    val hasFilter: Boolean = false,
    val loading: Boolean = false,
)

sealed interface LiveSearchState {
    data object Idle : LiveSearchState
    data object Loading : LiveSearchState
    data class Loaded(val results: List<Game>) : LiveSearchState
    data object Empty : LiveSearchState
    data object Error : LiveSearchState
}

data class SearchUiState(
    val submittedQuery: String = "",
    val selectedGenre: String? = null,
    val selectedPlatform: Platform? = null,
    val results: List<Game> = emptyList(),
    val collections: List<GameCollection> = emptyList(),
    val availableGenres: List<String> = emptyList(),
    val availablePlatforms: List<Platform> = emptyList(),
    val hasFilter: Boolean = false,
    val catalogSize: Int = 0,
    val searching: Boolean = false,
    val switchSync: SwitchSyncState = SwitchSyncState.Idle,
    val liveSearch: LiveSearchState = LiveSearchState.Idle,
)
