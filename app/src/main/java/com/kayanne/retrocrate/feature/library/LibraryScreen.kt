package com.kayanne.retrocrate.feature.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kayanne.retrocrate.core.ui.EmptyState

@Composable
fun LibraryScreen(
    contentPadding: PaddingValues,
    onOpenGame: (String) -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    EmptyState(
        title = "Your library is empty",
        description = "Games you add, download, or play will appear here.",
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    )
}
