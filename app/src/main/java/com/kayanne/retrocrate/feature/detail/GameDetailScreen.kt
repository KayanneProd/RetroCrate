package com.kayanne.retrocrate.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kayanne.retrocrate.core.designsystem.Spacing
import com.kayanne.retrocrate.core.ui.EmptyState
import com.kayanne.retrocrate.core.ui.MetadataChip
import com.kayanne.retrocrate.core.ui.SourceRow
import com.kayanne.retrocrate.domain.model.Game
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    viewModel: GameDetailViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    val title = (uiState as? GameDetailUiState.Loaded)?.game?.title ?: ""
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenDownloads) {
                        Icon(Icons.Outlined.Download, contentDescription = "Downloads")
                    }
                },
                expandedHeight = 44.dp,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val state = uiState) {
                GameDetailUiState.Loading -> LoadingContent()
                is GameDetailUiState.NotFound -> EmptyState(
                    title = "Game not found",
                    description = "We couldn't find game \"${state.gameId}\" in the catalog.",
                )
                is GameDetailUiState.Error -> EmptyState(
                    title = "Couldn't load the catalog",
                    description = state.message,
                )
                is GameDetailUiState.Loaded -> SplitDetailContent(
                    game = state.game,
                    onInstallClick = {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "Download queued — real downloads land in Branch 5.",
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SplitDetailContent(
    game: Game,
    onInstallClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        ArtPanel(
            game = game,
            modifier = Modifier
                .weight(0.45f)
                .fillMaxHeight()
                .padding(Spacing.l),
        )
        InfoPanel(
            game = game,
            onInstallClick = onInstallClick,
            modifier = Modifier
                .weight(0.55f)
                .fillMaxHeight()
                .padding(end = Spacing.l, top = Spacing.l, bottom = Spacing.l),
        )
    }
}

@Composable
private fun ArtPanel(
    game: Game,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            val artUrl = game.boxArtUrl
            if (artUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(artUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = game.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = game.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.m),
                )
            }
        }
    }
}

@Composable
private fun InfoPanel(
    game: Game,
    onInstallClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = game.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.xs))
        ChipRow(game = game)
        Spacer(Modifier.height(Spacing.m))
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            if (!game.description.isNullOrBlank()) {
                item("description") {
                    Text(
                        text = game.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (game.sources.isNotEmpty()) {
                item("sources-header") {
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        text = "Sources",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                items(game.sources, key = { it.id }) { source ->
                    SourceRow(source = source)
                }
            }
        }
        Spacer(Modifier.height(Spacing.m))
        Button(
            onClick = onInstallClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = "Install",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ChipRow(game: Game) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        MetadataChip(label = game.platform.displayName)
        game.releaseYear?.let { MetadataChip(label = it.toString()) }
        game.developer?.let { MetadataChip(label = it) }
    }
}
