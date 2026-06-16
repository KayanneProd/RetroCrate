package com.kayanne.retrocrate.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kayanne.retrocrate.core.designsystem.Spacing
import com.kayanne.retrocrate.feature.browse.BrowseScreen
import com.kayanne.retrocrate.feature.detail.GameDetailScreen
import com.kayanne.retrocrate.feature.downloads.DownloadsScreen
import com.kayanne.retrocrate.feature.home.HomeScreen
import com.kayanne.retrocrate.feature.library.LibraryScreen
import com.kayanne.retrocrate.feature.search.SearchScreen
import com.kayanne.retrocrate.feature.settings.SettingsScreen

@Composable
fun RetroCrateApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (currentDestination.isTopLevel()) {
                RetroCrateTopTabs(
                    currentDestination = currentDestination,
                    onNavigate = { destination ->
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenDownloads = { navController.navigate(DownloadsRoute) },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable<HomeRoute> {
                HomeScreen(
                    contentPadding = padding,
                    onOpenGame = { gameId -> navController.navigate(GameDetailRoute(gameId)) },
                    onOpenBrowse = { kind, value -> navController.navigate(BrowseRoute(kind, value)) },
                )
            }
            composable<BrowseRoute> {
                BrowseScreen(
                    onBack = { navController.popBackStack() },
                    onOpenGame = { gameId -> navController.navigate(GameDetailRoute(gameId)) },
                )
            }
            composable<LibraryRoute> {
                LibraryScreen(
                    contentPadding = padding,
                    onOpenGame = { gameId -> navController.navigate(GameDetailRoute(gameId)) },
                )
            }
            composable<SearchRoute> {
                SearchScreen(
                    contentPadding = padding,
                    onOpenGame = { gameId -> navController.navigate(GameDetailRoute(gameId)) },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(contentPadding = padding)
            }
            composable<DownloadsRoute> {
                DownloadsScreen(onBack = { navController.popBackStack() })
            }
            composable<GameDetailRoute> {
                GameDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDownloads = { navController.navigate(DownloadsRoute) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RetroCrateTopTabs(
    currentDestination: NavDestination?,
    onNavigate: (TopLevelDestination) -> Unit,
    onOpenDownloads: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .height(48.dp)
                .padding(horizontal = Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "RETROCRATE",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(Spacing.xl))
            TopLevelDestination.entries.forEach { destination ->
                val selected = currentDestination
                    ?.hierarchy
                    ?.any { it.hasRoute(destination.route::class) } == true
                TopTab(
                    label = destination.label.uppercase(),
                    selected = selected,
                    onClick = { onNavigate(destination) },
                )
                Spacer(Modifier.width(Spacing.s))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenDownloads) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = "Downloads",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun TopTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.s, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .height(2.dp)
                .width(24.dp)
                .background(
                    color = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                    shape = RoundedCornerShape(1.dp),
                ),
        )
    }
}

private fun NavDestination?.isTopLevel(): Boolean {
    if (this == null) return false
    return TopLevelDestination.entries.any { this.hierarchy.any { dest -> dest.hasRoute(it.route::class) } }
}
