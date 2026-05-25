package com.kayanne.retrocrate.feature.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kayanne.retrocrate.core.ui.EmptyState

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    viewModel: SettingsViewModel = viewModel(),
) {
    EmptyState(
        title = "Settings are coming soon",
        description = "Sources, storage location, theme, and accessibility toggles will live here.",
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    )
}
