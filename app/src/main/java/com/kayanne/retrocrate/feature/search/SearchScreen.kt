package com.kayanne.retrocrate.feature.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kayanne.retrocrate.core.ui.EmptyState

@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    onOpenGame: (String) -> Unit,
    viewModel: SearchViewModel = viewModel(),
) {
    EmptyState(
        title = "Search is coming soon",
        description = "Filter by platform, genre, decade, and source.",
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    )
}
