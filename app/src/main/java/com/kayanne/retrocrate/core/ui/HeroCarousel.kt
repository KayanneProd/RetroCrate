package com.kayanne.retrocrate.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kayanne.retrocrate.core.designsystem.Spacing
import com.kayanne.retrocrate.domain.model.Game
import kotlinx.coroutines.delay

private const val AUTO_ADVANCE_MS = 6_000L
private val HERO_HEIGHT = 160.dp

@Composable
fun HeroCarousel(
    featured: List<Game>,
    onGameClick: (Game) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (featured.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { featured.size })

    LaunchedEffect(pagerState, featured.size) {
        while (true) {
            delay(AUTO_ADVANCE_MS)
            val next = (pagerState.currentPage + 1) % featured.size
            pagerState.animateScrollToPage(next)
        }
    }

    Column(modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(HERO_HEIGHT),
        ) { page ->
            HeroSlide(
                game = featured[page],
                onClick = { onGameClick(featured[page]) },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.s, bottom = Spacing.xs),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(featured.size) { i ->
                val color = if (i == pagerState.currentPage) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                }
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }
    }
}

@Composable
private fun HeroSlide(
    game: Game,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
    ) {
        val artUrl = game.heroArtUrl ?: game.boxArtUrl
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.75f),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(Spacing.l),
        ) {
            Text(
                text = game.title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val year = game.releaseYear?.toString().orEmpty()
            val subtitle = listOf(game.platform.displayName, year).filter { it.isNotBlank() }.joinToString(" · ")
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}
