package com.kayanne.retrocrate.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
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
        bottomBar = {
            if (currentDestination.isTopLevel()) {
                RetroCrateBottomBar(
                    currentDestination = currentDestination,
                    onNavigate = { destination ->
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
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
                    onOpenDownloads = { navController.navigate(DownloadsRoute) },
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
            composable<GameDetailRoute> { backEntry ->
                val route = backEntry.toRoute<GameDetailRoute>()
                GameDetailScreen(
                    gameId = route.gameId,
                    onBack = { navController.popBackStack() },
                    onOpenDownloads = { navController.navigate(DownloadsRoute) },
                )
            }
        }
    }
}

@Composable
private fun RetroCrateBottomBar(
    currentDestination: NavDestination?,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    NavigationBar {
        TopLevelDestination.entries.forEach { destination ->
            val selected = currentDestination
                ?.hierarchy
                ?.any { it.hasRoute(destination.route::class) } == true
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(destination) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label) },
                alwaysShowLabel = true,
            )
        }
    }
}

private fun NavDestination?.isTopLevel(): Boolean {
    if (this == null) return false
    return TopLevelDestination.entries.any { this.hierarchy.any { dest -> dest.hasRoute(it.route::class) } }
}
