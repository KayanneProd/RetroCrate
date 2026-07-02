package com.kayanne.retrocrate.feature.library

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kayanne.retrocrate.core.designsystem.Spacing
import com.kayanne.retrocrate.core.ui.GameCapsule

private enum class LibraryTab { WISHLIST, ON_DEVICE }

@Composable
fun LibraryScreen(
    contentPadding: PaddingValues,
    onOpenGame: (String) -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by remember { mutableStateOf(LibraryTab.WISHLIST) }

    LaunchedEffect(uiState.configuredPlatforms) {
        viewModel.refresh(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = Spacing.l)
            .padding(top = Spacing.l),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            LibraryFilterChip("Wishlist (${uiState.wishlist.size})", tab == LibraryTab.WISHLIST) {
                tab = LibraryTab.WISHLIST
            }
            LibraryFilterChip("On device (${uiState.files.size})", tab == LibraryTab.ON_DEVICE) {
                tab = LibraryTab.ON_DEVICE
            }
        }
        Spacer(Modifier.height(Spacing.m))
        when (tab) {
            LibraryTab.WISHLIST -> WishlistContent(uiState, onOpenGame)
            LibraryTab.ON_DEVICE -> OnDeviceContent(uiState, context)
        }
    }
}

@Composable
private fun WishlistContent(uiState: LibraryUiState, onOpenGame: (String) -> Unit) {
    if (uiState.wishlist.isEmpty()) {
        EmptyLibraryHint("Nothing in your Library yet. Tap the bookmark on any game to add it here.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
        contentPadding = PaddingValues(bottom = Spacing.l),
        modifier = Modifier.fillMaxSize(),
    ) {
        gridItems(items = uiState.wishlist, key = { it.id }) { game ->
            GameCapsule(game = game, onClick = { onOpenGame(game.id) })
        }
    }
}

@Composable
private fun OnDeviceContent(uiState: LibraryUiState, context: android.content.Context) {
    when {
        uiState.configuredPlatforms.isEmpty() ->
            EmptyLibraryHint("Pick a download folder for at least one platform in Settings.")
        uiState.files.isEmpty() ->
            EmptyLibraryHint("No downloads yet. Tap Download on any game to fill this in.")
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
            contentPadding = PaddingValues(bottom = Spacing.l),
        ) {
            items(uiState.files, key = { it.uri }) { file ->
                LibraryRow(
                    file = file,
                    onOpen = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(file.uri), "application/octet-stream")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(intent) }
                    },
                )
            }
        }
    }
}

@Composable
private fun LibraryFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun LibraryRow(
    file: LibraryFile,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${file.platform.displayName} · ${formatSize(file.sizeBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpen) { Text("Open") }
        }
    }
}

@Composable
private fun EmptyLibraryHint(reason: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return when {
        mb < 1.0 -> "${bytes / 1024} KB"
        mb < 1024.0 -> "%.1f MB".format(mb)
        else -> "%.2f GB".format(mb / 1024.0)
    }
}
