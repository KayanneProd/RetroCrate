package com.kayanne.retrocrate.feature.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kayanne.retrocrate.core.ui.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    onOpenGame: (String) -> Unit,
    viewModel: SearchViewModel = viewModel(),
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                expandedHeight = 52.dp,
            )
        },
    ) { innerPadding ->
        EmptyState(
            title = "Search is coming soon",
            description = "Filter by platform, genre, decade, and source.",
            modifier = Modifier.padding(innerPadding),
        )
    }
}
