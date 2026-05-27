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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kayanne.retrocrate.core.designsystem.Spacing
import com.kayanne.retrocrate.core.ui.GameRail
import com.kayanne.retrocrate.core.ui.HeroCarousel
import com.kayanne.retrocrate.data.repository.LoadStatus

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onOpenGame: (String) -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        when {
            uiState.status is LoadStatus.Loading && uiState.featured.isEmpty() -> {
                LoadingState()
            }
            uiState.status is LoadStatus.Error && uiState.featured.isEmpty() -> {
                ErrorState(
                    message = (uiState.status as LoadStatus.Error).message,
                    onRetry = viewModel::refresh,
                )
            }
            else -> {
                CatalogContent(
                    uiState = uiState,
                    onOpenGame = onOpenGame,
                )
            }
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
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
) {
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
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun CatalogContent(
    uiState: HomeUiState,
    onOpenGame: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 0.dp, bottom = Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        if (uiState.featured.isNotEmpty()) {
            item("hero") {
                HeroCarousel(
                    featured = uiState.featured,
                    onGameClick = { onOpenGame(it.id) },
                )
            }
        }
        if (uiState.action.isNotEmpty()) {
            item("action") {
                GameRail(
                    title = "Action Games",
                    games = uiState.action,
                    onGameClick = { onOpenGame(it.id) },
                )
            }
        }
        if (uiState.popularClassics.isNotEmpty()) {
            item("popular-classics") {
                GameRail(
                    title = "Popular Classics",
                    games = uiState.popularClassics,
                    onGameClick = { onOpenGame(it.id) },
                )
            }
        }
    }
}

