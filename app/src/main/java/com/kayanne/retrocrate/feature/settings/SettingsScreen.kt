package com.kayanne.retrocrate.feature.settings

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
fun SettingsScreen(
    contentPadding: PaddingValues,
    viewModel: SettingsViewModel = viewModel(),
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                expandedHeight = 52.dp,
            )
        },
    ) { innerPadding ->
        EmptyState(
            title = "Settings are coming soon",
            description = "Sources, storage location, theme, and accessibility toggles will live here.",
            modifier = Modifier.padding(innerPadding),
        )
    }
}
