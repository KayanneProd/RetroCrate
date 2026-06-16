package com.kayanne.retrocrate.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

enum class TopLevelDestination(
    val route: Any,
    val label: String,
    val icon: ImageVector,
) {
    HOME(HomeRoute, "Home", Icons.Outlined.Home),
    LIBRARY(LibraryRoute, "Library", Icons.AutoMirrored.Outlined.LibraryBooks),
    SEARCH(SearchRoute, "Search", Icons.Outlined.Search),
    SETTINGS(SettingsRoute, "Settings", Icons.Outlined.Settings),
}

@Serializable
data object HomeRoute

@Serializable
data object LibraryRoute

@Serializable
data object SearchRoute

@Serializable
data object SettingsRoute

@Serializable
data object DownloadsRoute

@Serializable
data class GameDetailRoute(val gameId: String)

// kind = "genre" | "platform"; value = the genre name or Platform.name.
@Serializable
data class BrowseRoute(val kind: String, val value: String)
