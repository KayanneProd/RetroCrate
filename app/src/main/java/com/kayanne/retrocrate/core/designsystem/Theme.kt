package com.kayanne.retrocrate.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val SteamDarkColorScheme = darkColorScheme(
    primary = SteamCyan,
    onPrimary = SteamCyanOn,
    primaryContainer = SteamCyanContainer,
    onPrimaryContainer = SteamCyanOnContainer,

    secondary = SteamInstallGreen,
    onSecondary = SteamInstallGreenOn,
    secondaryContainer = SteamInstallGreenContainer,
    onSecondaryContainer = SteamInstallGreenOnContainer,

    tertiary = SteamGold,
    onTertiary = SteamGoldOn,

    background = SteamBackground,
    onBackground = SteamOnSurface,

    surface = SteamSurface,
    onSurface = SteamOnSurface,
    surfaceVariant = SteamSurfaceContainer,
    onSurfaceVariant = SteamOnSurfaceVariant,
    surfaceContainer = SteamSurfaceContainer,
    surfaceContainerHigh = SteamSurfaceContainerHigh,
    surfaceContainerHighest = SteamSurfaceContainerHigh,
    surfaceTint = SteamCyan,

    outline = SteamOutline,
    outlineVariant = SteamOutlineVariant,

    error = SteamError,
    onError = SteamErrorOn,
    errorContainer = SteamErrorContainer,
    onErrorContainer = SteamErrorOnContainer,
)

@Composable
fun RetroCrateTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = SteamDarkColorScheme,
        typography = Typography,
        content = content,
    )
}
