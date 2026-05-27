package com.kayanne.retrocrate.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kayanne.retrocrate.core.designsystem.Spacing
import com.kayanne.retrocrate.domain.model.DownloadState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    viewModel: DownloadsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                expandedHeight = 44.dp,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Spacing.l),
        ) {
            if (uiState.items.isEmpty()) {
                Text(
                    text = "No downloads yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    items(uiState.items, key = { it.game.id }) { row ->
                        DownloadRowCard(row)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRowCard(row: DownloadRow) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(Spacing.m)) {
            Text(
                text = row.game.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.game.platform.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xs))
            StateLine(state = row.state)
        }
    }
}

@Composable
private fun StateLine(state: DownloadState) {
    when (state) {
        DownloadState.NotStarted -> {
            Text("Idle", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is DownloadState.Queued -> {
            Text("Queued…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        is DownloadState.InProgress -> {
            val total = state.bytesTotal
            val pct = if (total != null && total > 0) ((state.bytesDone * 100) / total).toInt() else null
            val label = if (pct != null) "Downloading… $pct%" else "Downloading…"
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Spacing.xs))
            if (total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { (state.bytesDone.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondary,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        is DownloadState.Failed -> {
            Text(
                "Failed: ${state.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        is DownloadState.Completed -> {
            Text(
                "Completed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}
