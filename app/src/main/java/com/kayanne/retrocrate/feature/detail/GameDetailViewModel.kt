package com.kayanne.retrocrate.feature.detail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.kayanne.retrocrate.data.download.DownloadCoordinator
import com.kayanne.retrocrate.data.persistence.LibraryStore
import com.kayanne.retrocrate.data.persistence.SettingsStore
import com.kayanne.retrocrate.data.repository.GameCatalogRepository
import com.kayanne.retrocrate.data.repository.LoadStatus
import com.kayanne.retrocrate.data.source.DownloadCandidate
import com.kayanne.retrocrate.data.source.LibretroThumbnails
import com.kayanne.retrocrate.data.source.RomSource
import com.kayanne.retrocrate.data.source.ddl.NxbrewSource
import com.kayanne.retrocrate.data.source.debrid.DebridRomSource
import com.kayanne.retrocrate.data.source.ia.InternetArchiveSource
import com.kayanne.retrocrate.data.source.toResolveQuery
import com.kayanne.retrocrate.data.source.vimms.VimmsDownloadSource
import com.kayanne.retrocrate.data.source.vimms.VimmsPaths
import com.kayanne.retrocrate.domain.model.DownloadState
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform
import com.kayanne.retrocrate.navigation.GameDetailRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class GameDetailViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val repository = GameCatalogRepository
    private val gameId: String = savedStateHandle.toRoute<GameDetailRoute>().gameId

    val uiState: StateFlow<GameDetailUiState> = combine(
        repository.observeGame(gameId),
        repository.status,
        DownloadCoordinator.downloads,
    ) { game, status, downloads ->
        val downloadState = downloads[gameId] ?: DownloadState.NotStarted
        when {
            game != null -> GameDetailUiState.Loaded(
                game = game,
                downloadState = downloadState,
                screenshots = screenshotsFor(game),
                trailerUrl = trailerUrlFor(game),
            )
            status is LoadStatus.Loading -> GameDetailUiState.Loading
            status is LoadStatus.Error -> GameDetailUiState.Error(status.message)
            else -> GameDetailUiState.NotFound(gameId)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GameDetailUiState.Loading,
    )

    private val _picker = MutableStateFlow<DownloadPicker>(DownloadPicker.Hidden)
    val picker: StateFlow<DownloadPicker> = _picker.asStateFlow()
    private var pickerJob: Job? = null

    val isWishlisted: StateFlow<Boolean> = LibraryStore.wishlist
        .map { gameId in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun onToggleWishlist() {
        viewModelScope.launch { LibraryStore.toggle(gameId) }
    }

    init {
        viewModelScope.launch { repository.ensureLoaded() }
    }

    // Sources offered for this game: Vimm's only where it actually vaults the platform; Debrid +
    // NXBrew only when a debrid key is configured (NXBrew needs debrid to unlock its file-host links,
    // and is Switch-only); Internet Archive always.
    private fun availableSources(game: Game): List<Pair<String, RomSource>> = buildList {
        if (VimmsPaths.vaults(game.platform)) add("Vimm's Lair" to VimmsDownloadSource)
        if (SettingsStore.debrid.value.anyConfigured) add("Debrid" to DebridRomSource)
        if (game.platform == Platform.SWITCH && SettingsStore.debrid.value.anyConfigured) {
            add(NxbrewSource.siteName to NxbrewSource)
        }
        add("Internet Archive" to InternetArchiveSource)
    }

    private fun loadedGame(): Game? = (uiState.value as? GameDetailUiState.Loaded)?.game

    fun onDownloadClicked() {
        val game = loadedGame() ?: return
        _picker.value = DownloadPicker.Sources(availableSources(game).map { it.first })
    }

    // "Auto (best match)" — the original behaviour: resolve the best source via the chain.
    fun onAuto(context: Context) {
        val game = loadedGame() ?: return
        DownloadCoordinator.startDownload(game, context)
        _picker.value = DownloadPicker.Hidden
    }

    fun onSourceSelected(sourceName: String) {
        val game = loadedGame() ?: return
        val source = availableSources(game).firstOrNull { it.first == sourceName }?.second ?: return
        pickerJob?.cancel()
        _picker.value = DownloadPicker.Loading(sourceName)
        pickerJob = viewModelScope.launch {
            val items = withTimeoutOrNull(SOURCE_LIST_TIMEOUT_MS) {
                runCatching { source.listCandidates(game.toResolveQuery()) }.getOrDefault(emptyList())
            }.orEmpty()
            _picker.value = if (items.isEmpty()) {
                DownloadPicker.Empty(sourceName)
            } else {
                DownloadPicker.Candidates(sourceName, items)
            }
        }
    }

    fun onCandidateSelected(candidate: DownloadCandidate, context: Context) {
        val game = loadedGame() ?: return
        // A DDL candidate's file sits behind an ad-shortener — hand off to the WebView unlock step
        // instead of downloading directly.
        candidate.unlockUrl?.let { url ->
            _picker.value = DownloadPicker.Unlock(url)
            return
        }
        DownloadCoordinator.startDownload(game, candidate, context)
        _picker.value = DownloadPicker.Hidden
    }

    // The WebView captured the real file-host link after the user cleared the ad page — unlock it via
    // debrid and download (handled inside the coordinator, shown as "Unlocking link…" then progress).
    fun onHosterCaptured(hosterUrl: String, context: Context) {
        val game = loadedGame() ?: return
        DownloadCoordinator.startDownloadFromLink(game, hosterUrl, context)
        _picker.value = DownloadPicker.Hidden
    }

    fun onUnlockCancelled() {
        _picker.value = DownloadPicker.Hidden
    }

    fun onBackToSources() {
        val game = loadedGame() ?: return
        pickerJob?.cancel()
        _picker.value = DownloadPicker.Sources(availableSources(game).map { it.first })
    }

    fun onDismissPicker() {
        pickerJob?.cancel()
        _picker.value = DownloadPicker.Hidden
    }

    // Switch gets real eShop screenshots from titledb; retro falls back to a libretro gameplay snap
    // derived from the exact No-Intro filename (blank/404 ones just don't render).
    private fun screenshotsFor(game: Game): List<String> {
        if (game.screenshots.isNotEmpty()) return game.screenshots
        val rom = game.sources.firstOrNull()?.resolveUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
        val base = rom.substringBeforeLast('.')
        return LibretroThumbnails.snapUrlFromBase(game.platform, base)?.let { listOf(it) }.orEmpty()
    }

    // Keyless trailer: a YouTube search the user's YouTube app/browser opens — keeps the app from
    // embedding Google while still getting them to the trailer in one tap.
    private fun trailerUrlFor(game: Game): String {
        val query = "${game.title} ${game.platform.displayName} trailer"
        return "https://www.youtube.com/results?search_query=" + Uri.encode(query)
    }
}

private const val SOURCE_LIST_TIMEOUT_MS = 45_000L

// State of the "choose where to download from" sheet.
sealed interface DownloadPicker {
    data object Hidden : DownloadPicker
    data class Sources(val sources: List<String>) : DownloadPicker
    data class Loading(val source: String) : DownloadPicker
    data class Candidates(val source: String, val items: List<DownloadCandidate>) : DownloadPicker
    data class Empty(val source: String) : DownloadPicker
    // A DDL pick whose file is behind an ad-shortener: the UI shows a WebView on this URL for one
    // human tap, then captures the file-host link it redirects to.
    data class Unlock(val shortenerUrl: String) : DownloadPicker
}

sealed interface GameDetailUiState {
    data object Loading : GameDetailUiState
    data class Loaded(
        val game: Game,
        val downloadState: DownloadState = DownloadState.NotStarted,
        val screenshots: List<String> = emptyList(),
        val trailerUrl: String = "",
    ) : GameDetailUiState
    data class NotFound(val gameId: String) : GameDetailUiState
    data class Error(val message: String) : GameDetailUiState
}
