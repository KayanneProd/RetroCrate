package com.kayanne.retrocrate.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kayanne.retrocrate.core.designsystem.Spacing
import com.kayanne.retrocrate.domain.model.Game

@Composable
fun GameRail(
    title: String,
    games: List<Game>,
    onGameClick: (Game) -> Unit,
    modifier: Modifier = Modifier,
    onSeeAllClick: (() -> Unit)? = null,
) {
    Column(modifier = modifier) {
        SectionHeader(
            title = title,
            onSeeAllClick = onSeeAllClick,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            items(items = games, key = { it.id }) { game ->
                GameCapsule(
                    game = game,
                    onClick = { onGameClick(game) },
                )
            }
        }
    }
}
