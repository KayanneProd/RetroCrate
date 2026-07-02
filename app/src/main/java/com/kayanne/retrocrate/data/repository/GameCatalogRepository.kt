package com.kayanne.retrocrate.data.repository

import android.content.Context
import android.util.Log
import com.kayanne.retrocrate.data.persistence.CatalogStore
import com.kayanne.retrocrate.data.persistence.SettingsStore
import com.kayanne.retrocrate.data.source.FranchiseBuilder
import com.kayanne.retrocrate.data.source.PopularityService
import com.kayanne.retrocrate.data.source.RomMatcher
import com.kayanne.retrocrate.data.source.RomSource
import com.kayanne.retrocrate.data.source.ShovelwareFilter
import com.kayanne.retrocrate.data.source.TitleSearch
import com.kayanne.retrocrate.data.source.isEnglishRegion
import com.kayanne.retrocrate.data.source.libretro.LibretroCatalogSource
import com.kayanne.retrocrate.data.source.openvgdb.OpenVgdbSource
import com.kayanne.retrocrate.data.source.switchcatalog.SwitchCatalogSource
import com.kayanne.retrocrate.data.source.titledb.TitledbSource
import com.kayanne.retrocrate.data.source.vimms.VimmsDownloadSource
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.GameCollection
import com.kayanne.retrocrate.domain.model.Platform
import com.kayanne.retrocrate.domain.repository.GameRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

// The Switch library is sourced live from titledb (the app is a downloader — network is a given), so
// it can be absent on a fresh device or after a failed fetch. This makes that state visible instead
// of silently collapsing the Switch catalog to the bundled handful of classics: Updating = we're
// pulling the full list; Failed = we're on the bundled fallback and the user can retry.
sealed interface SwitchSyncState {
    data object Idle : SwitchSyncState
    data object Updating : SwitchSyncState
    data object Ready : SwitchSyncState
    data class Failed(val message: String) : SwitchSyncState
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
    @Volatile private var libretroGames: List<Game> = emptyList()
    @Volatile private var refreshingExternal = false
    // Games found by live source search that aren't in the local catalog — kept by id so Detail can
    // resolve them when the user opens one. Accumulates within a session; tiny (single-user app).
    @Volatile private var liveResults: Map<String, Game> = emptyMap()

    private val _switchSync = MutableStateFlow<SwitchSyncState>(SwitchSyncState.Idle)

    val catalog: StateFlow<List<Game>> = _catalog.asStateFlow()
    val status: StateFlow<LoadStatus> = _status.asStateFlow()
    val switchSync: StateFlow<SwitchSyncState> = _switchSync.asStateFlow()

    // The single filtering chokepoint: the catalog with the user's visibility toggles applied
    // (English / USA only, hide shovelware). Every discovery surface (rails, search, collections)
    // reads from here, so the toggles take effect everywhere and live. Eagerly shared so `.value`
    // is always current for synchronous callers (collectionMembers). Detail lookups deliberately
    // use the unfiltered `_catalog` so a directly-opened game never 404s because of a filter.
    val filteredCatalog: StateFlow<List<Game>> =
        combine(_catalog, SettingsStore.filters) { games, filters ->
            games.filter { game ->
                (!filters.englishOnly || isEnglishRegion(game.sources.firstOrNull()?.region)) &&
                    (!filters.hideShovelware || !ShovelwareFilter.isShovelware(game))
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(bgScope, SharingStarted.Eagerly, emptyList())

    val browsablePlatforms: List<Platform>
        get() = (OpenVgdbSource.SUPPORTED_PLATFORMS + LibretroCatalogSource.PLATFORMS + Platform.SWITCH)
            .distinct().sortedBy { it.ordinal }

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
            // Group B (PS2/Dreamcast/Wii U/3DS/Vita) catalogs are cached from libretro; load whatever's
            // already cached now (instant, local). Missing ones are fetched in the background refresh.
            libretroGames = loadLibretroCached()

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
        _catalog.value = (retroGames + libretroGames + switchNew).distinctBy { it.id }
    }

    private fun kickOffBackgroundRefresh() {
        bgScope.launch { PopularityService.refresh() }
        bgScope.launch { refreshExternalReleases() }
        bgScope.launch { refreshLibretroCatalogs() }
    }

    private suspend fun loadLibretroCached(): List<Game> =
        LibretroCatalogSource.PLATFORMS.mapNotNull { CatalogStore.load(it) }.flatten()

    // Fetches the libretro catalog for any Group B platform not yet cached (cache-first — these are
    // legacy, effectively-static sets, so once cached we never re-fetch). Runs off the main load path;
    // platforms fill in as their box-art lists download. A failure just leaves that platform empty.
    private suspend fun refreshLibretroCatalogs() {
        var changed = false
        val all = LibretroCatalogSource.PLATFORMS.flatMap { platform ->
            val cached = CatalogStore.load(platform)
            if (cached != null && cached.isNotEmpty()) {
                cached
            } else {
                val fetched = runCatching { LibretroCatalogSource.fetchCatalog(platform) }.getOrElse {
                    Log.w(TAG, "Libretro fetch failed for $platform", it)
                    emptyList()
                }
                if (fetched.isNotEmpty()) {
                    CatalogStore.save(platform, fetched)
                    changed = true
                }
                fetched
            }
        }
        if (changed) {
            libretroGames = all
            publishCatalog()
            Log.i(TAG, "Libretro catalogs refreshed: ${all.size} games now in catalog.")
        }
    }

    // Force a Switch-library sync now (the user tapped retry after a failure). Bypasses the daily gate.
    fun retrySwitchSync() {
        bgScope.launch { refreshExternalReleases(force = true) }
    }

    // Pulls the latest Switch releases from titledb (at most once a day) so newly released games show
    // up automatically, and exposes the attempt as switchSync so the UI never silently runs on the
    // bundled handful of classics. Updating is only shown when there's no usable cache yet — a stale
    // cache stays visible while a background refresh runs.
    private suspend fun refreshExternalReleases(force: Boolean = false) {
        if (refreshingExternal) return
        val context = appContext ?: return
        refreshingExternal = true
        try {
            if (!TitledbSource.hasCache(context)) _switchSync.value = SwitchSyncState.Updating
            when (TitledbSource.sync(context, force)) {
                TitledbSource.SyncOutcome.UPDATED -> {
                    switchGames = loadSwitchGames()
                    publishCatalog()
                    _switchSync.value = SwitchSyncState.Ready
                    Log.i(TAG, "External releases refreshed: ${switchGames.size} Switch games now in catalog.")
                }
                TitledbSource.SyncOutcome.UP_TO_DATE -> _switchSync.value = SwitchSyncState.Ready
                TitledbSource.SyncOutcome.FAILED -> _switchSync.value =
                    if (TitledbSource.hasCache(context)) SwitchSyncState.Ready
                    else SwitchSyncState.Failed("Couldn't update the game library — check your connection and retry.")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "External release refresh failed", t)
            if (!TitledbSource.hasCache(context)) {
                _switchSync.value = SwitchSyncState.Failed(t.message ?: "Game library update failed.")
            }
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

    // Reshuffled each time Home is (re)opened (HomeViewModel) so the hero never feels static.
    private val featuredNonce = MutableStateFlow(Random.nextLong())

    fun reshuffleFeatured() {
        featuredNonce.value = Random.nextLong()
    }

    // The carousel: explicitly "new + popular", a fresh mix each visit. Half genuinely-newest
    // releases (Switch quality-gated so they're recognizable, not shovelware) + half live-popular
    // (IA download counts ∩ catalog), shuffled by a rotating nonce. Deliberately does NOT spread one
    // game per platform — that old `diverseSample` behaviour forced an entry from every platform onto
    // the hero (so a GameCube favourite like Harry Potter showed up every single time); the hero is
    // now whatever's new + popular, different on each open.
    override fun observeFeatured(): Flow<List<Game>> =
        combine(filteredCatalog, PopularityService.popularTitles, featuredNonce) { games, popular, nonce ->
            val withArt = games.filter { it.boxArtUrl != null && it.sources.isNotEmpty() }
            val byNorm = HashMap<String, Game>()
            for (game in withArt) byNorm.putIfAbsent(RomMatcher.normalizeTitle(game.title), game)

            val newest = withArt
                .filter { it.platform != Platform.SWITCH || TitledbSource.isNewArrivalQuality(it) }
                .sortedByDescending { it.sortDate() }
                .take(30)
            val popularGames = popular.mapNotNull { byNorm[RomMatcher.normalizeTitle(it)] }.take(30)

            val rnd = Random(nonce)
            val half = FEATURED_COUNT / 2
            val featured = (newest.shuffled(rnd).take(half) + popularGames.shuffled(rnd).take(FEATURED_COUNT - half))
                .distinctBy { it.id }
                .shuffled(rnd)
            featured.take(FEATURED_COUNT).ifEmpty { withArt.shuffled(rnd).take(FEATURED_COUNT) }
        }.flowOn(Dispatchers.Default)

    // The full catalog now carries the broad Switch library (so search/collections are complete), so
    // "New Arrivals" applies the strict quality cut to Switch here — recognizable publisher + real
    // description — to keep Home's newest rail free of shovelware. Retro platforms pass through (their
    // "newest" is decades old and never ranks into this rail anyway).
    override fun observeNewArrivals(): Flow<List<Game>> =
        filteredCatalog.map { newArrivalsFrom(it, RAIL_COUNT) }.flowOn(Dispatchers.Default)

    // The single definition of "New Arrivals", shared by the Home rail and the "See all" Browse list
    // so they can never diverge — the rail led with a real game (Wanderstop) while See-all led with
    // shovelware ("Tune My Car") because only the rail applied this quality cut.
    fun newArrivalsFrom(games: List<Game>, limit: Int): List<Game> =
        games.filter { it.platform != Platform.SWITCH || TitledbSource.isNewArrivalQuality(it) }
            .newest(limit)

    // Live popularity from Internet Archive download counts, intersected with our catalog and
    // topped up with a daily-rotating sample so the rail is always full.
    override fun observePopular(): Flow<List<Game>> =
        combine(filteredCatalog, PopularityService.popularTitles) { games, popular ->
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
        filteredCatalog.map { games ->
            games.filter { it.boxArtUrl != null }.diverseSample(RAIL_COUNT, daySeed() + 7)
        }.flowOn(Dispatchers.Default)

    override fun observePlatform(platform: Platform): Flow<List<Game>> =
        filteredCatalog.map { games -> games.filter { it.platform == platform }.take(RAIL_COUNT) }
            .flowOn(Dispatchers.Default)

    // Franchise collections, derived from the catalog (see FranchiseBuilder) and recomputed when the
    // catalog changes (e.g. after a titledb refresh). Cached so building only runs while observed.
    val collections: StateFlow<List<GameCollection>> = filteredCatalog
        .map { FranchiseBuilder.build(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(bgScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun observeCollections(): Flow<List<GameCollection>> = collections

    fun collectionMembers(keyword: String): List<Game> =
        FranchiseBuilder.members(filteredCatalog.value, keyword)

    fun observeTopGenres(limit: Int): Flow<List<String>> =
        filteredCatalog.map { games ->
            games.flatMap { it.genres }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key }
        }.flowOn(Dispatchers.Default)

    fun observePlatformsInCatalog(): Flow<List<Platform>> =
        filteredCatalog.map { games -> games.map { it.platform }.distinct().sortedBy { it.ordinal } }
            .flowOn(Dispatchers.Default)

    // Live "search beyond the catalog": ask the download sources themselves for titles matching the
    // query, so Search can surface (and download) games the local catalog doesn't list. Network, and
    // only ever on an explicit user action (Search's "Search sources" button). Results are de-duped
    // against the catalog and ranked by relevance, then cached by id so opening one resolves in Detail.
    suspend fun searchSources(query: String, platform: Platform?): List<Game> {
        if (query.isBlank()) return emptyList()
        val catalogKeys = filteredCatalog.value
            .asSequence()
            .filter { platform == null || it.platform == platform }
            .mapTo(HashSet()) { it.platform to RomMatcher.normalizeTitle(it.title) }

        val hits = liveSources.flatMap { source ->
            runCatching { source.searchTitles(query, platform) }.getOrElse {
                Log.w(TAG, "${source.siteName} search failed for \"$query\"", it)
                emptyList()
            }
        }
        val ranked = TitleSearch.rank(query, hits) { it.title }
            .distinctBy { it.platform to RomMatcher.normalizeTitle(it.title) }
            .filterNot { (it.platform to RomMatcher.normalizeTitle(it.title)) in catalogKeys }
            .take(MAX_LIVE_RESULTS)

        if (ranked.isNotEmpty()) liveResults = liveResults + ranked.associateBy { it.id }
        Log.i(TAG, "Live source search \"$query\": ${ranked.size} result(s) beyond the catalog.")
        return ranked
    }

    override suspend fun getById(id: String): Game? =
        _catalog.value.find { it.id == id } ?: liveResults[id]

    fun observeGame(id: String): Flow<Game?> =
        _catalog.map { games -> games.find { it.id == id } ?: liveResults[id] }

    private const val FEATURED_COUNT = 8
    private const val RAIL_COUNT = 18
    private const val MAX_LIVE_RESULTS = 60

    // Sources that can search their own index by free text (for "search beyond the catalog"). Vimm's
    // is exact and platform-scoped with clean metadata + libretro art; others can join as they gain a
    // title-search path.
    private val liveSources: List<RomSource> = listOf(VimmsDownloadSource)

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
