package com.kayanne.retrocrate.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kayanne.retrocrate.core.designsystem.Spacing
import com.kayanne.retrocrate.data.source.DownloadCandidate

// "Choose where to download from." Two steps: pick a source (or Auto), then pick one of that source's
// candidate files — with metadata — or back out and try another. Solves "the first auto-match was the
// wrong game / a junk file": the user sees the options and decides.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSourceSheet(
    state: DownloadPicker,
    onAuto: () -> Unit,
    onSourceSelected: (String) -> Unit,
    onCandidateSelected: (DownloadCandidate) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Hidden shows nothing; Unlock is rendered separately as a full-screen WebView dialog.
    if (state is DownloadPicker.Hidden || state is DownloadPicker.Unlock) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.l)
                .padding(bottom = Spacing.l),
        ) {
            when (state) {
                is DownloadPicker.Hidden, is DownloadPicker.Unlock -> Unit
                is DownloadPicker.Sources -> SourcesStep(state.sources, onAuto, onSourceSelected)
                is DownloadPicker.Loading -> LoadingStep(state.source)
                is DownloadPicker.Candidates -> CandidatesStep(state.source, state.items, onCandidateSelected, onBack)
                is DownloadPicker.Empty -> EmptyStep(state.source, onBack)
            }
        }
    }
}

@Composable
private fun SourcesStep(
    sources: List<String>,
    onAuto: () -> Unit,
    onSourceSelected: (String) -> Unit,
) {
    SheetTitle("Download from")
    AutoRow(onAuto)
    Spacer(Modifier.height(Spacing.s))
    Text(
        text = "Or choose a source",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = Spacing.xs),
    )
    sources.forEach { source ->
        SheetRow(onClick = { onSourceSelected(source) }) {
            Text(
                text = source,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AutoRow(onAuto: () -> Unit) {
    Surface(
        onClick = onAuto,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        color = MaterialTheme.colorScheme.secondary,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondary,
            )
            Spacer(Modifier.width(Spacing.s))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Auto (best match)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondary,
                )
                Text(
                    text = "Try every source and pick the best file",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun LoadingStep(source: String) {
    SheetTitle(source)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.height(22.dp).width(22.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(Spacing.m))
            Text(
                text = "Searching $source…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CandidatesStep(
    source: String,
    items: List<DownloadCandidate>,
    onCandidateSelected: (DownloadCandidate) -> Unit,
    onBack: () -> Unit,
) {
    BackHeader(source, onBack)
    LazyColumn(
        modifier = Modifier.heightIn(max = 380.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        items(items, key = { it.id }) { candidate ->
            SheetRow(onClick = { onCandidateSelected(candidate) }) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = candidate.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(
                        candidate.region,
                        candidate.sizeBytes?.let { formatSize(it) },
                        candidate.extra,
                    ).joinToString(" • ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStep(source: String, onBack: () -> Unit) {
    BackHeader(source, onBack)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No results from $source.\nGo back and try another source.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BackHeader(source: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onBack),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = "Back to sources",
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(Spacing.s))
        Text(
            text = source,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = Spacing.s),
    )
}

@Composable
private fun SheetRow(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

private fun formatSize(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> String.format("%.1f GB", bytes / gb)
        bytes >= mb -> String.format("%.0f MB", bytes / mb)
        bytes >= kb -> String.format("%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}
