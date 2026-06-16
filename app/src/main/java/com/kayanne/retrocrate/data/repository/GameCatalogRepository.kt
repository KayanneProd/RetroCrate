package com.kayanne.retrocrate.data.repository

import android.content.Context
import android.util.Log
import com.kayanne.retrocrate.data.persistence.CatalogStore
import com.kayanne.retrocrate.data.source.PopularityService
import com.kayanne.retrocrate.data.source.RomMatcher
import com.kayanne.retrocrate.data.source.openvgdb.OpenVgdbSource
import com.kayanne.retrocrate.data.source.switchcatalog.SwitchCatalogSource
import com.kayanne.retrocrate.data.source.titledb.TitledbSource
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform
import com.kayanne.retrocrate.domain.repository.GameRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

sealed interface LoadStatus {
    data object Idle : LoadStatus
    data object Loading : LoadStatus
    data object Ready : LoadStatus
    data class Error(val message: String) : LoadStatus
}

// Cross-platform catalog. OpenVGDB is the source of truth for the retro platforms; the Nintendo
// Switch catalog comes from a bundled JSON asset (OpenVGDB has no Switch data). Everything merges
// into one observable catalog the whole app reads from.
//
// Home rails are entirely data-driven — no hardcoded title lists. "Popular" comes from a live
// Internet Archive popularity signal; the carousel and "Discover" are diverse samples reshuffled
// daily, so Home stays current and never shows the same thing every time.
object GameCatalogRepository : GameRepository {

    private const val TAG = "Catalog"

    private val _catalog = MutableStateFlow<List<Game>>(emptyList())
    private val _status = MutableStateFlow<LoadStatus>(LoadStatus.Idle)
    private val loadMutex = Mutex()
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var appContext: Context? = null
    @Volatile private var retroGames: List<Game> = emptyList()
    @Volatile private var retroTitles: Set<String> = emptySet()
    @Volatile private var switchGames: List<Game> = emptyList()
    @Volatile private var refreshingExternal = false

    val catalog: StateFlow<List<Game>> = _catalog.asStateFlow()
    val status: StateFlow<LoadStatus> = _status.asStateFlow()

    val browsablePlatforms: List<Platform>
        get() = (OpenVgdbSource.SUPPORTED_PLATFORMS + Platform.SWITCH).distinct().sortedBy { it.ordinal }

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun ensureLoaded() {
        loadMutex.withLock {
            if (_status.value is LoadStatus.Ready) {
                kickOffBackgroundRefresh()
                return
            }

            val supported = OpenVgdbSource.SUPPORTED_PLATFORMS
            switchGames = loadSwitchGames()

            val cached = supported.associateWith { CatalogStore.load(it) }
            val allCached = cached.values.all { it != null && it.isNotEmpty() }
            if (allCached) {
                setRetro(cached.values.filterNotNull().flatten())
                publishCatalog()
                _status.value = LoadStatus.Ready
                Log.i(TAG, "Catalog from cache: ${_catalog.value.size} games (${switchGames.size} Switch).")
                kickOffBackgroundRefresh()
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
                setRetro(all)
                if (retroGames.isEmpty() && switchGames.isEmpty()) {
                    _status.value = LoadStatus.Error("OpenVGDB returned no games for any supported platform.")
                    return
                }
                publishCatalog()
                _status.value = LoadStatus.Ready
                Log.i(TAG, "Catalog built: ${_catalog.value.size} games (${switchGames.size} Switch).")
                kickOffBackgroundRefresh()
            } catch (t: Throwable) {
                _status.value = LoadStatus.Error(t.message ?: "Failed to build catalog.")
                Log.w(TAG, "Catalog build failed", t)
            }
        }
    }

    private fun setRetro(games: List<Game>) {
        retroGames = games
        retroTitles = games.mapTo(HashSet()) { RomMatcher.normalizeTitle(it.title) }
    }

    private fun publishCatalog() {
        // Drop Switch entries that are really classics re-released on Switch (NSO / Virtual Console)
        // — if a title already exists in the retro catalog it isn't a "new" game. Keeps New Arrivals
        // about genuinely new releases, not 20-year-old games re-listed with a recent eShop date.
        val switchNew = switchGames.filter { RomMatcher.normalizeTitle(it.title) !in retroTitles }
        // distinctBy id: two OpenVGDB titles can slugify to the same id (e.g. "X: Y" vs "X - Y"),
        // and a duplicate key crashes any LazyColumn/Row/Grid that shows them (e.g. Search).
        _catalog.value = (retroGames + switchNew).distinctBy { it.id }
    }

    private fun kickOffBackgroundRefresh() {
        bgScope.launch { PopularityService.refresh() }
        bgScope.launch { refreshExternalReleases() }
    }

    // Pulls the latest Switch releases from titledb (at most once a week) so newly released games
    // show up automatically. Runs off the main load path; the bundled catalog covers us until it
    // completes, and a failure just leaves the existing catalog in place.
    private suspend fun refreshExternalReleases() {
        if (refreshingExternal) return
        val context = appContext ?: return
        refreshingExternal = true
        try {
            if (TitledbSource.refreshIfStale(context)) {
                switchGames = loadSwitchGames()
                publishCatalog()
                Log.i(TAG, "External releases refreshed: ${switchGames.size} Switch games now in catalog.")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "External release refresh failed", t)
        } finally {
            refreshingExternal = false
        }
    }

    private suspend fun loadSwitchGames(): List<Game> {
        val context = appContext ?: return emptyList()
        return runCatching { SwitchCatalogSource.fetchCatalog(context) }.getOrElse {
            Log.w(TAG, "Switch catalog load failed", it)
            emptyList()
        }
    }

    // The carousel: recognizable (live-popular) + newest downloadable games with art, sampled
    // across platforms and reshuffled daily — leads with games the user knows, stays current, and
    // is never the same set twice in a row.
    override fun observeFeatured(): Flow<List<Game>> =
        combine(_catalog, PopularityService.popularTitles) { games, popular ->
            val withArt = games.filter { it.boxArtUrl != null && it.sources.isNotEmpty() }
            val byNorm = HashMap<String, Game>()
            for (game in withArt) byNorm.putIfAbsent(RomMatcher.normalizeTitle(game.title), game)

            val popularGames = popular.mapNotNull { byNorm[RomMatcher.normalizeTitle(it)] }
            val recent = withArt.sortedByDescending { it.releaseYear ?: 0 }.take(40)
            val pool = (popularGames + recent).distinct()

            pool.diverseSample(FEATURED_COUNT, daySeed()).ifEmpty { withArt.take(FEATURED_COUNT) }
        }.flowOn(Dispatchers.Default)

    override fun observeNewArrivals(): Flow<List<Game>> =
        _catalog.map { games -> games.newest(RAIL_COUNT) }.flowOn(Dispatchers.Default)

    // Live popularity from Internet Archive download counts, intersected with our catalog and
    // topped up with a daily-rotating sample so the rail is always full.
    override fun observePopular(): Flow<List<Game>> =
        combine(_catalog, PopularityService.popularTitles) { games, popular ->
            val byNorm = HashMap<String, Game>()
            for (game in games) byNorm.putIfAbsent(RomMatcher.normalizeTitle(game.title), game)

            val ranked = LinkedHashSet<Game>()
            for (title in popular) byNorm[RomMatcher.normalizeTitle(title)]?.let { ranked.add(it) }

            val result = ranked.toMutableList()
            if (result.size < RAIL_COUNT) {
                val fill = games
                    .filter { it.boxArtUrl != null && it !in ranked }
                    .diverseSample(RAIL_COUNT - result.size, daySeed() + 3)
                result.addAll(fill)
            }
            result.take(RAIL_COUNT)
        }.flowOn(Dispatchers.Default)

    override fun observeDiscover(): Flow<List<Game>> =
        _catalog.map { games ->
            games.filter { it.boxArtUrl != null }.diverseSample(RAIL_COUNT, daySeed() + 7)
        }.flowOn(Dispatchers.Default)

    override fun observePlatform(platform: Platform): Flow<List<Game>> =
        _catalog.map { games -> games.filter { it.platform == platform }.take(RAIL_COUNT) }
            .flowOn(Dispatchers.Default)

    fun observeTopGenres(limit: Int): Flow<List<String>> =
        _catalog.map { games ->
            games.flatMap { it.genres }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key }
        }.flowOn(Dispatchers.Default)

    fun observePlatformsInCatalog(): Flow<List<Platform>> =
        _catalog.map { games -> games.map { it.platform }.distinct().sortedBy { it.ordinal } }
            .flowOn(Dispatchers.Default)

    override suspend fun getById(id: String): Game? =
        _catalog.value.find { it.id == id }

    fun observeGame(id: String): Flow<Game?> =
        _catalog.map { games -> games.find { it.id == id } }

    private const val FEATURED_COUNT = 8
    private const val RAIL_COUNT = 18

    // Stable within a day, changes daily — drives the reshuffle without a clock dependency.
    private fun daySeed(): Long = System.currentTimeMillis() / 86_400_000L

    // YYYYMMDD when known (titledb), else year×10000, else "oldest". Lets newest-first sort by
    // actual date so a June release beats a January one in the same year.
    private fun Game.sortDate(): Int = releaseDate ?: (releaseYear?.times(10000) ?: Int.MIN_VALUE)

    // Genuinely newest games, by actual release date. No per-platform cap: retro platforms' newest
    // titles are decades old and aren't "new arrivals", so the rail is just the most recent releases
    // (which in practice means current Switch games).
    private fun List<Game>.newest(count: Int): List<Game> =
        sortedWith(
            compareByDescending<Game> { it.sortDate() }
                .thenBy { it.title.lowercase() },
        ).take(count)

    // Round-robins one game from each platform per pass (platform order and within-platform order
    // both seeded) so a sample spans the collection instead of clumping on one platform.
    private fun List<Game>.diverseSample(count: Int, seed: Long): List<Game> {
        if (isEmpty()) return emptyList()
        val rnd = Random(seed)
        val queues = groupBy { it.platform }.values
            .map { it.shuffled(rnd).toMutableList() }
            .shuffled(rnd)
        val result = mutableListOf<Game>()
        var added = true
        while (result.size < count && added) {
            added = false
            for (queue in queues) {
                if (queue.isNotEmpty()) {
                    result.add(queue.removeAt(0))
                    added = true
                    if (result.size >= count) break
                }
            }
        }
        return result
    }
}
