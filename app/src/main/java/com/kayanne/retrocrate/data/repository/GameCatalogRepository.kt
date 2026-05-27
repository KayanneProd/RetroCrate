package com.kayanne.retrocrate.data.repository

import android.util.Log
import com.kayanne.retrocrate.data.persistence.CatalogStore
import com.kayanne.retrocrate.data.source.openvgdb.OpenVgdbSource
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform
import com.kayanne.retrocrate.domain.repository.GameRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface LoadStatus {
    data object Idle : LoadStatus
    data object Loading : LoadStatus
    data object Ready : LoadStatus
    data class Error(val message: String) : LoadStatus
}

// Cross-platform OpenVGDB-backed catalog. Loads every supported platform on first launch,
// caches per-platform via CatalogStore. Subsequent launches read all caches off disk (~50-100ms).
// ROM source resolution (Vimm's, etc.) happens only on Install press — Branch 5 work.
object GameCatalogRepository : GameRepository {

    private const val TAG = "Catalog"

    private val _catalog = MutableStateFlow<List<Game>>(emptyList())
    private val _status = MutableStateFlow<LoadStatus>(LoadStatus.Idle)
    private val loadMutex = Mutex()

    val catalog: StateFlow<List<Game>> = _catalog.asStateFlow()
    val status: StateFlow<LoadStatus> = _status.asStateFlow()

    suspend fun ensureLoaded() {
        loadMutex.withLock {
            if (_status.value is LoadStatus.Ready) return

            val supported = OpenVgdbSource.SUPPORTED_PLATFORMS

            // Cache-first per platform. If every supported platform has a snapshot, render
            // instantly; otherwise fall through and fetch missing platforms.
            val cached = supported.associateWith { CatalogStore.load(it) }
            val allCached = cached.values.all { it != null && it.isNotEmpty() }
            if (allCached) {
                val merged = cached.values.filterNotNull().flatten()
                _catalog.value = merged
                _status.value = LoadStatus.Ready
                Log.i(TAG, "Catalog loaded from per-platform cache: ${merged.size} games across ${supported.size} platforms.")
                return
            }

            _status.value = LoadStatus.Loading
            try {
                val all = mutableListOf<Game>()
                for (platform in supported) {
                    val existing = cached[platform]
                    val games = if (existing != null && existing.isNotEmpty()) {
                        existing
                    } else {
                        val fetched = OpenVgdbSource.fetchCatalog(platform)
                        CatalogStore.save(platform, fetched)
                        fetched
                    }
                    all.addAll(games)
                }
                if (all.isEmpty()) {
                    _status.value = LoadStatus.Error("OpenVGDB returned no games for any supported platform.")
                    return
                }
                _catalog.value = all
                _status.value = LoadStatus.Ready
                Log.i(TAG, "Catalog built from OpenVGDB: ${all.size} games across ${supported.size} platforms.")
            } catch (t: Throwable) {
                _status.value = LoadStatus.Error(t.message ?: "Failed to build catalog from OpenVGDB.")
                Log.w(TAG, "Catalog build failed", t)
            }
        }
    }

    override fun observeFeatured(): Flow<List<Game>> =
        _catalog.map { games -> games.filterFeatured() }

    override fun observeAction(): Flow<List<Game>> =
        _catalog.map { games -> games.filterByGenre("Action", 16) }

    override fun observePopularClassics(): Flow<List<Game>> =
        _catalog.map { games -> games.filterPopular() }

    override suspend fun getById(id: String): Game? =
        _catalog.value.find { it.id == id }

    fun observeGame(id: String): Flow<Game?> =
        _catalog.map { games -> games.find { it.id == id } }

    // Cross-platform curated rails. Titles must match OpenVGDB's spelling — standard
    // canonical names work for these picks.
    private val featuredTitles = setOf(
        "Super Mario 64",
        "The Legend of Zelda: Ocarina of Time",
        "Chrono Trigger",
        "Final Fantasy VII",
        "Super Metroid",
    )

    private val popularTitles = setOf(
        "Super Mario World",
        "The Legend of Zelda: A Link to the Past",
        "Donkey Kong Country",
        "EarthBound",
        "Mario Kart 64",
        "GoldenEye 007",
        "Banjo-Kazooie",
        "Castlevania: Symphony of the Night",
        "Metal Gear Solid",
        "Crash Bandicoot",
    )

    private fun List<Game>.filterFeatured(): List<Game> =
        filter { it.title in featuredTitles }.ifEmpty { take(5) }

    private fun List<Game>.filterPopular(): List<Game> =
        filter { it.title in popularTitles }.ifEmpty { take(12) }

    private fun List<Game>.filterByGenre(genre: String, count: Int): List<Game> =
        filter { game -> game.genres.any { it.equals(genre, ignoreCase = true) } }
            .take(count)
}
