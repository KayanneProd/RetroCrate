package com.kayanne.retrocrate.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kayanne.retrocrate.core.designsystem.Spacing
import com.kayanne.retrocrate.core.ui.GameRail
import com.kayanne.retrocrate.core.ui.HeroCarousel

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onOpenGame: (String) -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + Spacing.l,
        ),
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
        if (uiState.recentlyAdded.isNotEmpty()) {
            item("recently-added") {
                GameRail(
                    title = "Recently Added",
                    games = uiState.recentlyAdded,
                    onGameClick = { onOpenGame(it.id) },
                )
            }
        }
        if (uiState.popularRetro.isNotEmpty()) {
            item("popular-retro") {
                GameRail(
                    title = "Popular Retro",
                    games = uiState.popularRetro,
                    onGameClick = { onOpenGame(it.id) },
                )
            }
        }
    }
}
