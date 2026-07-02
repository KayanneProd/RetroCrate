package com.kayanne.retrocrate.feature.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kayanne.retrocrate.core.designsystem.Spacing
import com.kayanne.retrocrate.core.ui.CollectionCard
import com.kayanne.retrocrate.core.ui.GameCapsule
import com.kayanne.retrocrate.data.repository.SwitchSyncState
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.GameCollection
import com.kayanne.retrocrate.domain.model.Platform

@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    onOpenGame: (String) -> Unit,
    onOpenCollection: (String) -> Unit,
    viewModel: SearchViewModel = viewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val openGame: (String) -> Unit = { id ->
        viewModel.onResultOpened()
        onOpenGame(id)
    }

    val gridState = rememberLazyGridState()
    // Collapse the search field + filter chips while the user scrolls down through results, and
    // bring them back when scrolling up (or returning to the top) — so results get the whole screen
    // while browsing, and refining is one swipe up away. Steam Big Picture-style content-forward
    // chrome; nothing is permanently hidden.
    var headerVisible by remember { mutableStateOf(true) }
    val atTop by remember {
        derivedStateOf { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0 }
    }
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -SCROLL_HIDE_THRESHOLD) headerVisible = false
                else if (available.y > SCROLL_SHOW_THRESHOLD) headerVisible = true
                return Offset.Zero
            }
        }
    }
    val showHeader = headerVisible || atTop

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .nestedScroll(scrollConnection),
    ) {
        AnimatedVisibility(visible = showHeader) {
            Column {
                SearchField(
                    query = query,
                    onQueryChange = viewModel::onQueryChange,
                    onSearch = viewModel::onSearch,
                    onClear = viewModel::onClear,
                    modifier = Modifier.padding(
                        start = Spacing.l,
                        end = Spacing.l,
                        top = Spacing.s,
                        bottom = Spacing.xs,
                    ),
                )
                if (uiState.availablePlatforms.size > 1) {
                    PlatformFilterRow(
                        platforms = uiState.availablePlatforms,
                        selectedPlatform = uiState.selectedPlatform,
                        onPlatformToggle = viewModel::onPlatformToggle,
                    )
                }
                if (uiState.availableGenres.isNotEmpty()) {
                    GenreFilterRow(
                        genres = uiState.availableGenres,
                        selectedGenre = uiState.selectedGenre,
                        onGenreToggle = viewModel::onGenreToggle,
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                !uiState.hasFilter -> SearchStartContent(
                    recentSearches = recentSearches,
                    onRecentClick = viewModel::onRecentSelected,
                    catalogSize = uiState.catalogSize,
                    platformCount = uiState.availablePlatforms.size,
                    enrichedGenreCount = uiState.availableGenres.size,
                    switchSync = uiState.switchSync,
                    onRetrySync = viewModel::onRetrySwitchSync,
                )
                // Fresh search with nothing to show yet → full-screen spinner.
                uiState.searching && uiState.results.isEmpty() && uiState.collections.isEmpty() ->
                    SearchingIndicator()
                else -> ResultsGrid(
                    games = uiState.results,
                    collections = uiState.collections,
                    gridState = gridState,
                    onOpenGame = openGame,
                    onOpenCollection = onOpenCollection,
                    query = uiState.submittedQuery,
                    selectedGenre = uiState.selectedGenre,
                    selectedPlatform = uiState.selectedPlatform,
                    catalogSize = uiState.catalogSize,
                    searching = uiState.searching,
                    switchSync = uiState.switchSync,
                    liveSearch = uiState.liveSearch,
                    onSearchSources = viewModel::onSearchSources,
                    onRetrySync = viewModel::onRetrySwitchSync,
                )
            }
        }
    }
}

private const val SCROLL_HIDE_THRESHOLD = 8f
private const val SCROLL_SHOW_THRESHOLD = 4f

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search games — press enter") },
        leadingIcon = {
            Icon(Icons.Outlined.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Outlined.Close, contentDescription = "Clear")
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            onSearch()
            keyboard?.hide()
        }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun PlatformFilterRow(
    platforms: List<Platform>,
    selectedPlatform: Platform?,
    onPlatformToggle: (Platform) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.xs),
        contentPadding = PaddingValues(horizontal = Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        items(platforms, key = { it.name }) { platform ->
            val isSelected = platform == selectedPlatform
            FilterChip(
                selected = isSelected,
                onClick = { onPlatformToggle(platform) },
                label = { Text(platform.displayName) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun GenreFilterRow(
    genres: List<String>,
    selectedGenre: String?,
    onGenreToggle: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.s),
        contentPadding = PaddingValues(horizontal = Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        items(genres, key = { it }) { genre ->
            val isSelected = genre == selectedGenre
            FilterChip(
                selected = isSelected,
                onClick = { onGenreToggle(genre) },
                label = { Text(genre) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun ResultsGrid(
    games: List<Game>,
    collections: List<GameCollection>,
    gridState: LazyGridState,
    onOpenGame: (String) -> Unit,
    onOpenCollection: (String) -> Unit,
    query: String,
    selectedGenre: String?,
    selectedPlatform: Platform?,
    catalogSize: Int,
    searching: Boolean,
    switchSync: SwitchSyncState,
    liveSearch: LiveSearchState,
    onSearchSources: () -> Unit,
    onRetrySync: () -> Unit,
) {
    val liveResults = (liveSearch as? LiveSearchState.Loaded)?.results.orEmpty()
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 120.dp),
        contentPadding = PaddingValues(
            start = Spacing.l,
            end = Spacing.l,
            top = Spacing.s,
            bottom = Spacing.l,
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Refining over existing results (changed a chip / re-ran) → a thin bar so the previous
        // results stay visible while the new ranking runs.
        if (searching) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "searching-bar") {
                LinearProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (switchSync is SwitchSyncState.Updating || switchSync is SwitchSyncState.Failed) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "sync-banner") {
                SyncBanner(switchSync = switchSync, onRetry = onRetrySync)
            }
        }
        if (collections.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "collections") {
                CollectionResults(collections = collections, onOpenCollection = onOpenCollection)
            }
        }
        if (games.isNotEmpty()) {
            if (collections.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "games-header") {
                    SectionLabel("Games")
                }
            }
            items(items = games, key = { it.id }) { game ->
                GameCapsule(game = game, onClick = { onOpenGame(game.id) })
            }
        } else if (collections.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty-local") {
                EmptyLocalMessage(query, selectedGenre, selectedPlatform, catalogSize)
            }
        }

        // "Search beyond the catalog" — only meaningful when there's a typed query. Lets the user
        // reach the live download sources for anything the local catalog doesn't list.
        if (query.isNotBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "source-search") {
                SourceSearchControl(
                    query = query,
                    liveSearch = liveSearch,
                    onSearchSources = onSearchSources,
                )
            }
            if (liveResults.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "live-header") {
                    SectionLabel("From sources")
                }
                items(items = liveResults, key = { "live:${it.id}" }) { game ->
                    GameCapsule(game = game, onClick = { onOpenGame(game.id) })
                }
            }
        }
    }
}

@Composable
private fun SearchingIndicator() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Spacing.m))
            Text(
                text = "Searching…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SyncBanner(switchSync: SwitchSyncState, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (switchSync) {
            is SwitchSyncState.Updating -> {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Updating game library…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.s),
                )
            }
            is SwitchSyncState.Failed -> {
                Text(
                    text = switchSync.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            else -> Unit
        }
    }
}

// The escalation control: a button to search the live sources, plus the loading/empty/error states
// of that search. Results themselves render in the grid under a "From sources" header.
@Composable
private fun SourceSearchControl(
    query: String,
    liveSearch: LiveSearchState,
    onSearchSources: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.s)) {
        when (liveSearch) {
            is LiveSearchState.Loading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Searching sources…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Spacing.s),
                    )
                }
            }
            is LiveSearchState.Empty -> {
                Text(
                    text = "No additional results from the sources for \"$query\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.s))
                SourceSearchButton(query, onSearchSources)
            }
            is LiveSearchState.Error -> {
                Text(
                    text = "Couldn't reach the sources. Check your connection.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.s))
                SourceSearchButton(query, onSearchSources)
            }
            is LiveSearchState.Loaded -> Unit
            is LiveSearchState.Idle -> SourceSearchButton(query, onSearchSources)
        }
    }
}

@Composable
private fun SourceSearchButton(query: String, onSearchSources: () -> Unit) {
    FilledTonalButton(onClick = onSearchSources) {
        Icon(
            imageVector = Icons.Outlined.TravelExplore,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Search sources for \"$query\"",
            modifier = Modifier.padding(start = Spacing.s),
        )
    }
}

@Composable
private fun CollectionResults(
    collections: List<GameCollection>,
    onOpenCollection: (String) -> Unit,
) {
    Column {
        Text(
            text = "Collections",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = Spacing.xs),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            items(collections, key = { it.id }) { collection ->
                CollectionCard(
                    collection = collection,
                    onClick = { onOpenCollection(collection.id) },
                    width = 140.dp,
                )
            }
        }
    }
}

@Composable
private fun SearchStartContent(
    recentSearches: List<String>,
    onRecentClick: (String) -> Unit,
    catalogSize: Int,
    platformCount: Int,
    enrichedGenreCount: Int,
    switchSync: SwitchSyncState,
    onRetrySync: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (switchSync is SwitchSyncState.Updating || switchSync is SwitchSyncState.Failed) {
            Box(modifier = Modifier.padding(horizontal = Spacing.l)) {
                SyncBanner(switchSync = switchSync, onRetry = onRetrySync)
            }
        }
        if (recentSearches.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(start = Spacing.l, top = Spacing.s, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = Spacing.xs),
                )
                Text(
                    text = "Recent searches",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                items(recentSearches, key = { it }) { recent ->
                    SuggestionChip(
                        onClick = { onRecentClick(recent) },
                        label = { Text(recent) },
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            SearchPlaceholder(
                catalogSize = catalogSize,
                platformCount = platformCount,
                enrichedGenreCount = enrichedGenreCount,
            )
        }
    }
}

@Composable
private fun SearchPlaceholder(catalogSize: Int, platformCount: Int, enrichedGenreCount: Int) {
    val scopeLine = if (platformCount > 1) {
        "Searching across $catalogSize games on $platformCount platforms."
    } else {
        "Searching across $catalogSize games."
    }
    val hintLine = when {
        enrichedGenreCount == 0 -> "Type to filter by title."
        platformCount > 1 -> "Type to filter by title, or pick a platform or genre above."
        else -> "Type to filter by title, or pick a genre above."
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(Spacing.xl),
        ) {
            Text(
                text = scopeLine,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = hintLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.s),
            )
        }
    }
}

// Shown as a full-span grid row when nothing in the local catalog matches — the live source-search
// control renders just below it, so the user can always escalate the same query to the sources.
@Composable
private fun EmptyLocalMessage(
    query: String,
    selectedGenre: String?,
    selectedPlatform: Platform?,
    catalogSize: Int,
) {
    val message = buildString {
        append("No matches in your library")
        if (query.isNotBlank()) append(" for \"$query\"")
        if (selectedGenre != null) append(" in $selectedGenre")
        if (selectedPlatform != null) append(" on ${selectedPlatform.displayName}")
        append(" (across $catalogSize games).")
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.m),
    )
}
