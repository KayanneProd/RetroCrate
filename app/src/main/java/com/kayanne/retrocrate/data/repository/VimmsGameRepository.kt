package com.kayanne.retrocrate.data.repository

import android.util.Log
import com.kayanne.retrocrate.data.persistence.CatalogStore
import com.kayanne.retrocrate.data.source.vimms.VimmsSource
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform
import com.kayanne.retrocrate.domain.repository.GameRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface LoadStatus {
    data object Idle : LoadStatus
    data object Loading : LoadStatus
    data object Ready : LoadStatus
    data class Error(val message: String) : LoadStatus
}

object VimmsGameRepository : GameRepository {

    private const val TAG = "VimmsRepo"

    // For Branch 3 we ship N64 only. Multi-platform comes post-v1.
    private val targetPlatform = Platform.N64

    private val _catalog = MutableStateFlow<List<Game>>(emptyList())
    private val _status = MutableStateFlow<LoadStatus>(LoadStatus.Idle)
    private val loadMutex = Mutex()
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var backgroundRefreshJob: Job? = null

    val catalog: StateFlow<List<Game>> = _catalog.asStateFlow()
    val status: StateFlow<LoadStatus> = _status.asStateFlow()

    suspend fun ensureLoaded(forceRefresh: Boolean = false) {
        loadMutex.withLock {
            if (forceRefresh) {
                fetchFromNetwork()
                return
            }
            if (_status.value is LoadStatus.Ready) {
                // Already loaded for this session; trigger silent background refresh.
                triggerBackgroundRefresh()
                return
            }
            // First call this session — try cache.
            val cached = CatalogStore.load(targetPlatform)
            if (cached != null && cached.isNotEmpty()) {
                _catalog.value = cached
                _status.value = LoadStatus.Ready
                triggerBackgroundRefresh()
                return
            }
            // No cache; fetch synchronously and surface loading/error to UI.
            fetchFromNetwork()
        }
    }

    private suspend fun fetchFromNetwork() {
        _status.value = LoadStatus.Loading
        try {
            val games = VimmsSource.fetchPlatform(targetPlatform)
            _catalog.value = games
            _status.value = LoadStatus.Ready
            CatalogStore.save(targetPlatform, games)
        } catch (t: Throwable) {
            if (_catalog.value.isNotEmpty()) {
                // Keep showing what we have; demote to Ready silently.
                Log.w(TAG, "Refresh failed; keeping cached catalog", t)
                _status.value = LoadStatus.Ready
            } else {
                _status.value = LoadStatus.Error(t.message ?: "Failed to load catalog")
            }
        }
    }

    private fun triggerBackgroundRefresh() {
        backgroundRefreshJob?.cancel()
        backgroundRefreshJob = repoScope.launch {
            try {
                val games = VimmsSource.fetchPlatform(targetPlatform)
                _catalog.value = games
                CatalogStore.save(targetPlatform, games)
            } catch (t: Throwable) {
                Log.w(TAG, "Background refresh failed; cached catalog stays put", t)
            }
        }
    }

    override fun observeFeatured(): Flow<List<Game>> =
        _catalog.map { games -> games.filterFeatured() }

    override fun observeRecentlyAdded(): Flow<List<Game>> =
        _catalog.map { games -> games.takeLast(20).reversed() }

    override fun observePopularRetro(): Flow<List<Game>> =
        _catalog.map { games -> games.filterPopular() }

    override suspend fun getById(id: String): Game? = _catalog.value.find { it.id == id }

    // Curated rails — hand-picked title matches against the live catalog.
    private val featuredTitles = setOf(
        "Super Mario 64",
        "The Legend of Zelda: Ocarina of Time",
        "GoldenEye 007",
        "Mario Kart 64",
        "Banjo-Kazooie",
    )

    private val popularTitles = setOf(
        "Super Smash Bros.",
        "Star Fox 64",
        "Paper Mario",
        "Donkey Kong 64",
        "Perfect Dark",
        "Conker's Bad Fur Day",
        "Pokemon Stadium",
        "F-Zero X",
    )

    private fun List<Game>.filterFeatured(): List<Game> =
        filter { it.title in featuredTitles }.ifEmpty { take(5) }

    private fun List<Game>.filterPopular(): List<Game> =
        filter { it.title in popularTitles }.ifEmpty { take(12) }
}
