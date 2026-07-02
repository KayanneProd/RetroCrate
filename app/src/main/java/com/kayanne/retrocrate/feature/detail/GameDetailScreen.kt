package com.kayanne.retrocrate.feature.detail

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kayanne.retrocrate.core.designsystem.Spacing
import com.kayanne.retrocrate.core.ui.EmptyState
import com.kayanne.retrocrate.core.ui.MetadataChip
import com.kayanne.retrocrate.core.ui.SourceRow
import com.kayanne.retrocrate.data.persistence.SettingsStore
import com.kayanne.retrocrate.domain.model.DownloadState
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
    val picker by viewModel.picker.collectAsStateWithLifecycle()
    val isWishlisted by viewModel.isWishlisted.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Index of the screenshot being viewed fullscreen (null = viewer closed).
    var screenshotViewerIndex by remember { mutableStateOf<Int?>(null) }

    // SAF folder picker — launched when Install is pressed for a platform that has no folder
    // configured yet. Saves the picked URI as that platform's folder, then starts the install.
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            scope.launch {
                val game = (uiState as? GameDetailUiState.Loaded)?.game
                if (game != null) {
                    SettingsStore.setPlatformFolder(game.platform, uri.toString())
                    viewModel.onDownloadClicked()
                }
            }
        }
    }

    // Show snackbar when a download finishes successfully.
    LaunchedEffect(uiState) {
        val download = (uiState as? GameDetailUiState.Loaded)?.downloadState
        if (download is DownloadState.Completed) {
            snackbarHostState.showSnackbar("Download complete.")
        } else if (download is DownloadState.Failed) {
            snackbarHostState.showSnackbar(download.reason)
        }
    }

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
                    if (uiState is GameDetailUiState.Loaded) {
                        IconButton(onClick = viewModel::onToggleWishlist) {
                            Icon(
                                imageVector = if (isWishlisted) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = if (isWishlisted) "Remove from Library" else "Add to Library",
                                tint = if (isWishlisted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
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
                    downloadState = state.downloadState,
                    screenshots = state.screenshots,
                    trailerUrl = state.trailerUrl,
                    onOpenUrl = { url ->
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)),
                            )
                        }
                    },
                    onScreenshotClick = { index -> screenshotViewerIndex = index },
                    onInstallClick = {
                        scope.launch {
                            val storedUri = SettingsStore.folderFor(state.game.platform)
                            if (storedUri.isNullOrBlank()) {
                                folderPicker.launch(null)
                            } else {
                                viewModel.onDownloadClicked()
                            }
                        }
                    },
                )
            }
        }
    }

    DownloadSourceSheet(
        state = picker,
        onAuto = { viewModel.onAuto(context) },
        onSourceSelected = viewModel::onSourceSelected,
        onCandidateSelected = { viewModel.onCandidateSelected(it, context) },
        onBack = viewModel::onBackToSources,
        onDismiss = viewModel::onDismissPicker,
    )

    (picker as? DownloadPicker.Unlock)?.let { unlock ->
        LinkUnlockDialog(
            shortenerUrl = unlock.shortenerUrl,
            onCaptured = { url -> viewModel.onHosterCaptured(url, context) },
            onDismiss = viewModel::onUnlockCancelled,
        )
    }

    val loaded = uiState as? GameDetailUiState.Loaded
    val viewerIndex = screenshotViewerIndex
    if (viewerIndex != null && loaded != null && loaded.screenshots.isNotEmpty()) {
        ScreenshotViewer(
            screenshots = loaded.screenshots,
            initialIndex = viewerIndex.coerceIn(0, loaded.screenshots.lastIndex),
            title = loaded.game.title,
            onDismiss = { screenshotViewerIndex = null },
        )
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
    downloadState: DownloadState,
    screenshots: List<String>,
    trailerUrl: String,
    onOpenUrl: (String) -> Unit,
    onScreenshotClick: (Int) -> Unit,
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
            downloadState = downloadState,
            screenshots = screenshots,
            trailerUrl = trailerUrl,
            onOpenUrl = onOpenUrl,
            onScreenshotClick = onScreenshotClick,
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
    downloadState: DownloadState,
    screenshots: List<String>,
    trailerUrl: String,
    onOpenUrl: (String) -> Unit,
    onScreenshotClick: (Int) -> Unit,
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
            if (screenshots.isNotEmpty()) {
                item("screenshots") {
                    ScreenshotRail(screenshots = screenshots, title = game.title, onScreenshotClick = onScreenshotClick)
                }
            }
            if (trailerUrl.isNotBlank()) {
                item("trailer") {
                    TrailerButton(onClick = { onOpenUrl(trailerUrl) })
                }
            }
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
        InstallButton(downloadState = downloadState, onClick = onInstallClick)
    }
}

@Composable
private fun InstallButton(downloadState: DownloadState, onClick: () -> Unit) {
    val (label, enabled) = when (downloadState) {
        DownloadState.NotStarted -> "Install" to true
        is DownloadState.Queued -> "Queued…" to false
        is DownloadState.Preparing -> {
            val pct = downloadState.progress?.let { " ${(it * 100).toInt()}%" }.orEmpty()
            "${downloadState.message}$pct" to false
        }
        is DownloadState.InProgress -> {
            val total = downloadState.bytesTotal
            val pct = if (total != null && total > 0) {
                ((downloadState.bytesDone * 100) / total).toInt()
            } else null
            (if (pct != null) "Downloading… $pct%" else "Downloading…") to false
        }
        is DownloadState.Failed -> "Retry" to true
        is DownloadState.Completed -> "Installed ✓" to false
    }

    Column {
        if (downloadState is DownloadState.Preparing) {
            val p = downloadState.progress
            if (p != null) {
                LinearProgressIndicator(
                    progress = { p.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xs),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xs),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (downloadState is DownloadState.InProgress) {
            val total = downloadState.bytesTotal
            if (total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { (downloadState.bytesDone.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.xs),
                    color = MaterialTheme.colorScheme.secondary,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.xs),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (downloadState is DownloadState.Completed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ScreenshotRail(
    screenshots: List<String>,
    title: String,
    onScreenshotClick: (Int) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        itemsIndexed(screenshots, key = { _, url -> url }) { index, url ->
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(true)
                    .build(),
                contentDescription = "$title screenshot ${index + 1}",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .height(108.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onScreenshotClick(index) },
            )
        }
    }
}

// Fullscreen, swipeable screenshot viewer. Opens at the tapped index; swipe between shots; close via
// the X or the system back gesture (the Dialog routes back to onDismiss).
@Composable
private fun ScreenshotViewer(
    screenshots: List<String>,
    initialIndex: Int,
    title: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { screenshots.size })
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(screenshots[page])
                        .crossfade(true)
                        .build(),
                    contentDescription = "$title screenshot ${page + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.m),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White)
            }
            if (screenshots.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${screenshots.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(Spacing.l),
                )
            }
        }
    }
}

@Composable
private fun TrailerButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(40.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        shape = RoundedCornerShape(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.PlayCircle,
            contentDescription = null,
            modifier = Modifier.height(20.dp),
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = "Watch trailer",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(game: Game) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        MetadataChip(label = game.platform.displayName)
        game.releaseYear?.let { MetadataChip(label = it.toString()) }
        game.genres.take(3).forEach { MetadataChip(label = it) }
        game.developer?.let { MetadataChip(label = it) }
        game.publisher?.let { publisher ->
            if (publisher != game.developer) MetadataChip(label = publisher)
        }
    }
}
