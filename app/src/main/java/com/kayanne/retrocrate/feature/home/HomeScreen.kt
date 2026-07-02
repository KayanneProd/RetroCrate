package com.kayanne.retrocrate.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kayanne.retrocrate.core.designsystem.Spacing
import com.kayanne.retrocrate.core.ui.CategoryCard
import com.kayanne.retrocrate.core.ui.CategoryVisuals
import com.kayanne.retrocrate.core.ui.CollectionCard
import com.kayanne.retrocrate.core.ui.GameRail
import com.kayanne.retrocrate.core.ui.HeroCarousel
import com.kayanne.retrocrate.core.ui.SectionHeader
import com.kayanne.retrocrate.data.repository.LoadStatus
import com.kayanne.retrocrate.data.repository.SwitchSyncState
import com.kayanne.retrocrate.domain.model.GameCollection

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onOpenGame: (String) -> Unit,
    onOpenBrowse: (kind: String, value: String) -> Unit,
    onOpenCollections: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Reshuffle the hero each time Home becomes visible (cold start, app foreground, or returning
    // from a game) so "new + popular" is a fresh set every visit rather than the same games.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        when {
            uiState.status is LoadStatus.Loading && uiState.featured.isEmpty() -> LoadingState()
            uiState.status is LoadStatus.Error && uiState.featured.isEmpty() -> ErrorState(
                message = (uiState.status as LoadStatus.Error).message,
                onRetry = viewModel::refresh,
            )
            else -> CatalogContent(
                uiState = uiState,
                onOpenGame = onOpenGame,
                onOpenBrowse = onOpenBrowse,
                onOpenCollections = onOpenCollections,
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Spacing.m))
            Text(
                text = "Loading catalog…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(Spacing.xl),
        ) {
            Text(
                text = "Couldn't load the catalog",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.l))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun CatalogContent(
    uiState: HomeUiState,
    onOpenGame: (String) -> Unit,
    onOpenBrowse: (kind: String, value: String) -> Unit,
    onOpenCollections: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 0.dp, bottom = Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        // Always present (even before featured loads) so the carousel fills in place rather than
        // being prepended later — a keyed LazyColumn would otherwise anchor on the platform row and
        // push a late-inserted hero off-screen above. HeroCarousel renders nothing when empty.
        item("hero") {
            HeroCarousel(featured = uiState.featured, onGameClick = { onOpenGame(it.id) })
        }
        if (uiState.platforms.isNotEmpty()) {
            item("platform-cards") {
                CategoryRow(
                    title = "Browse by Platform",
                    cards = uiState.platforms.map { platform ->
                        CategoryCardData(
                            label = platform.displayName,
                            icon = CategoryVisuals.platformIcon(platform),
                            accent = CategoryVisuals.accentFor(platform.name),
                            onClick = { onOpenBrowse("platform", platform.name) },
                        )
                    },
                )
            }
        }
        if (uiState.collections.isNotEmpty()) {
            item("collections") {
                CollectionRail(
                    collections = uiState.collections,
                    onOpenCollection = { onOpenBrowse("collection", it) },
                    onSeeAll = onOpenCollections,
                )
            }
        }
        if (uiState.switchSync is SwitchSyncState.Updating) {
            item("updating-notice") { UpdatingNotice() }
        }
        if (uiState.newArrivals.isNotEmpty()) {
            item("new-arrivals") {
                GameRail(
                    title = "New Arrivals",
                    games = uiState.newArrivals,
                    onGameClick = { onOpenGame(it.id) },
                    onSeeAllClick = { onOpenBrowse("new", "New Arrivals") },
                )
            }
        }
        if (uiState.genres.isNotEmpty()) {
            item("genre-cards") {
                CategoryRow(
                    title = "Browse by Genre",
                    cards = uiState.genres.map { genre ->
                        CategoryCardData(
                            label = genre,
                            icon = CategoryVisuals.genreIcon(genre),
                            accent = CategoryVisuals.accentFor(genre),
                            onClick = { onOpenBrowse("genre", genre) },
                        )
                    },
                )
            }
        }
        if (uiState.popular.isNotEmpty()) {
            item("popular") {
                GameRail(title = "Popular Now", games = uiState.popular, onGameClick = { onOpenGame(it.id) })
            }
        }
        if (uiState.discover.isNotEmpty()) {
            item("discover") {
                GameRail(title = "Discover", games = uiState.discover, onGameClick = { onOpenGame(it.id) })
            }
        }
    }
}

// Shown while the live Switch library is downloading for the first time, so "New Arrivals" briefly
// running on the bundled classics reads as "still loading" rather than "this is wrong".
@Composable
private fun UpdatingNotice() {
    Row(
        modifier = Modifier.padding(horizontal = Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
}

private data class CategoryCardData(
    val label: String,
    val icon: ImageVector,
    val accent: androidx.compose.ui.graphics.Color,
    val onClick: () -> Unit,
)

@Composable
private fun CollectionRail(
    collections: List<GameCollection>,
    onOpenCollection: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    Column {
        SectionHeader(title = "Collections", onSeeAllClick = onSeeAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            items(collections, key = { it.id }) { collection ->
                CollectionCard(
                    collection = collection,
                    onClick = { onOpenCollection(collection.id) },
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(title: String, cards: List<CategoryCardData>) {
    Column {
        SectionHeader(title = title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            items(cards, key = { it.label }) { card ->
                CategoryCard(
                    label = card.label,
                    icon = card.icon,
                    accent = card.accent,
                    onClick = card.onClick,
                )
            }
        }
    }
}
