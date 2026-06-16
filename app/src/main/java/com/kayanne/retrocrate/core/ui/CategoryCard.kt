package com.kayanne.retrocrate.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SportsKabaddi
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kayanne.retrocrate.core.designsystem.Spacing
import com.kayanne.retrocrate.domain.model.Platform
import kotlin.math.absoluteValue

// Steam-style category tile: a coloured icon over a subtle accent gradient with the label below.
// Used for the genre and platform shortcut rows on Home.
@Composable
fun CategoryCard(
    label: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .width(150.dp)
            .height(84.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.06f)),
                    ),
                )
                .padding(Spacing.m),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(30.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

// Icon + accent lookups. Accents are picked from a small palette that sits well on the Steam navy
// surfaces; the choice is stable per name (hash-indexed) so a genre always gets the same colour.
object CategoryVisuals {

    private val accents = listOf(
        Color(0xFF66C0F4), // Steam cyan
        Color(0xFFA4D007), // install green
        Color(0xFFFFC83D), // gold
        Color(0xFF7FD1C4), // teal
        Color(0xFFB39DFF), // violet
        Color(0xFFFF9E80), // coral
        Color(0xFF80D8FF), // sky
        Color(0xFFFFB74D), // amber
    )

    fun accentFor(key: String): Color = accents[(key.hashCode().absoluteValue) % accents.size]

    fun genreIcon(genre: String): ImageVector = when (genre.lowercase()) {
        "action" -> Icons.Filled.Bolt
        "adventure" -> Icons.Filled.Explore
        "role-playing (rpg)", "role-playing", "rpg" -> Icons.Filled.Shield
        "platform", "platformer" -> Icons.Filled.SportsEsports
        "racing", "driving" -> Icons.Filled.DirectionsCar
        "shooter", "fps" -> Icons.Filled.GpsFixed
        "puzzle" -> Icons.Filled.Extension
        "strategy", "tactics" -> Icons.Filled.Lightbulb
        "simulation", "sim" -> Icons.Filled.Public
        "fighting", "fighter", "beat 'em up" -> Icons.Filled.SportsKabaddi
        "sports" -> Icons.Filled.SportsSoccer
        "party" -> Icons.Filled.Celebration
        "roguelike", "roguelite" -> Icons.Filled.Casino
        else -> Icons.Filled.SportsEsports
    }

    // Disc-based systems get a disc icon; cartridge/digital systems get a cartridge/controller.
    fun platformIcon(platform: Platform): ImageVector = when (platform) {
        Platform.PS1, Platform.PS2, Platform.PSP, Platform.PS_VITA,
        Platform.GAMECUBE, Platform.WII, Platform.WII_U,
        Platform.SATURN, Platform.DREAMCAST,
        -> Icons.Filled.Album
        else -> Icons.Filled.VideogameAsset
    }
}
