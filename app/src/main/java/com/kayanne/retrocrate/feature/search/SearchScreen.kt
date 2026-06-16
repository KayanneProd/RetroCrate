package com.kayanne.retrocrate.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kayanne.retrocrate.core.designsystem.Spacing
import com.kayanne.retrocrate.core.ui.GameCapsule
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform

@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    onOpenGame: (String) -> Unit,
    viewModel: SearchViewModel = viewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val openGame: (String) -> Unit = { id ->
        viewModel.onResultOpened()
        onOpenGame(id)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        SearchField(
            query = query,
            onQueryChange = viewModel::onQueryChange,
            onClear = viewModel::onClear,
            modifier = Modifier.padding(
                start = Spacing.l,
                end = Spacing.l,
                top = Spacing.m,
                bottom = Spacing.s,
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
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                !uiState.hasFilter -> SearchStartContent(
                    recentSearches = recentSearches,
                    onRecentClick = viewModel::onQueryChange,
                    catalogSize = uiState.catalogSize,
                    platformCount = uiState.availablePlatforms.size,
                    enrichedGenreCount = uiState.availableGenres.size,
                )
                uiState.results.isEmpty() -> EmptyResults(
                    query = uiState.debouncedQuery,
                    selectedGenre = uiState.selectedGenre,
                    selectedPlatform = uiState.selectedPlatform,
                    catalogSize = uiState.catalogSize,
                )
                else -> ResultsGrid(
                    games = uiState.results,
                    onOpenGame = openGame,
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search games…") },
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
    onOpenGame: (String) -> Unit,
) {
    LazyVerticalGrid(
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
        items(items = games, key = { it.id }) { game ->
            GameCapsule(
                game = game,
                onClick = { onOpenGame(game.id) },
            )
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
) {
    Column(modifier = Modifier.fillMaxSize()) {
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

@Composable
private fun EmptyResults(
    query: String,
    selectedGenre: String?,
    selectedPlatform: Platform?,
    catalogSize: Int,
) {
    val message = buildString {
        append("No matches")
        if (query.isNotBlank()) append(" for \"$query\"")
        if (selectedGenre != null) append(" in $selectedGenre")
        if (selectedPlatform != null) append(" on ${selectedPlatform.displayName}")
        append(" across $catalogSize games.")
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(Spacing.xl),
        )
    }
}
