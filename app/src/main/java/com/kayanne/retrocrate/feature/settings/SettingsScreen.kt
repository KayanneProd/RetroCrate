package com.kayanne.retrocrate.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kayanne.retrocrate.core.designsystem.Spacing
import com.kayanne.retrocrate.data.persistence.CatalogFilters
import com.kayanne.retrocrate.data.persistence.DebridProvider
import com.kayanne.retrocrate.data.persistence.DebridSettings
import com.kayanne.retrocrate.domain.model.Platform

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    viewModel: SettingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val debrid by viewModel.debrid.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Shared SAF launcher; tracks which platform we're picking for so the callback can route.
    var pickingFor by remember { mutableStateOf<Platform?>(null) }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        val target = pickingFor
        pickingFor = null
        if (uri != null && target != null) {
            viewModel.onFolderPicked(context, target, uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(Spacing.l),
    ) {
        SectionLabel("Download folders")
        Text(
            text = "Each platform downloads to its own folder.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = Spacing.s),
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            items(uiState.rows, key = { it.platform.name }) { row ->
                PlatformFolderCard(
                    row = row,
                    onPick = {
                        pickingFor = row.platform
                        folderPicker.launch(null)
                    },
                    onClear = { viewModel.onClearPlatformFolder(row.platform) },
                )
            }
            item("filters") {
                Spacer(Modifier.height(Spacing.l))
                SectionLabel("Catalog filters")
                Text(
                    text = "Trim what shows up in search and browse.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = Spacing.s),
                )
                CatalogFiltersCard(
                    filters = filters,
                    onEnglishOnlyChange = viewModel::onSetEnglishOnly,
                    onHideShovelwareChange = viewModel::onSetHideShovelware,
                )
            }
            item("debrid") {
                Spacer(Modifier.height(Spacing.l))
                SectionLabel("Debrid (optional)")
                Text(
                    text = "Add a Premiumize or Real-Debrid key to download Switch and other hard-to-find games. " +
                        "When set, RetroCrate finds torrents for a game and unlocks the best cached one automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = Spacing.s),
                )
                DebridCard(settings = debrid, onSave = viewModel::onSetDebridKey)
            }
            item("about") {
                Spacer(Modifier.height(Spacing.l))
                SectionLabel("About")
                Spacer(Modifier.height(Spacing.s))
                AboutCard()
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun PlatformFolderCard(
    row: PlatformFolderRow,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.platform.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = row.uri ?: "No folder picked",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.uri != null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (row.uri != null) {
                TextButton(onClick = onClear) { Text("Clear") }
            }
            Button(
                onClick = onPick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(if (row.uri == null) "Pick" else "Change")
            }
        }
    }
}

@Composable
private fun CatalogFiltersCard(
    filters: CatalogFilters,
    onEnglishOnlyChange: (Boolean) -> Unit,
    onHideShovelwareChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs)) {
            FilterToggleRow(
                title = "English / USA only",
                subtitle = "Hide Japanese, European-language and other regional duplicates.",
                checked = filters.englishOnly,
                onCheckedChange = onEnglishOnlyChange,
            )
            FilterToggleRow(
                title = "Hide shovelware",
                subtitle = "Drop low-effort Switch eShop filler from unknown publishers.",
                checked = filters.hideShovelware,
                onCheckedChange = onHideShovelwareChange,
            )
        }
    }
}

@Composable
private fun FilterToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(Spacing.m))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun DebridCard(
    settings: DebridSettings,
    onSave: (DebridProvider, String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(Spacing.m)) {
            DebridKeyField(
                label = "Premiumize API key",
                current = settings.premiumizeApiKey,
                onSave = { onSave(DebridProvider.PREMIUMIZE, it) },
            )
            Spacer(Modifier.height(Spacing.m))
            DebridKeyField(
                label = "Real-Debrid API key",
                current = settings.realDebridApiKey,
                onSave = { onSave(DebridProvider.REAL_DEBRID, it) },
            )
        }
    }
}

@Composable
private fun DebridKeyField(
    label: String,
    current: String?,
    onSave: (String) -> Unit,
) {
    var text by remember(current) { mutableStateOf(current.orEmpty()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (!current.isNullOrBlank()) {
                Text(
                    text = "Connected",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Paste key") },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.s))
            Button(
                onClick = { onSave(text) },
                enabled = text != current.orEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun AboutCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(Spacing.l)) {
            Text(
                text = "RetroCrate",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Catalog: OpenVGDB + live Switch list (titledb). Downloads: Vimm's Lair, debrid (if set), then Internet Archive.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
