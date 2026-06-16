package com.kayanne.retrocrate.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Game(
    val id: String,
    val title: String,
    val platform: Platform,
    val releaseYear: Int? = null,
    // Full release date as YYYYMMDD when known (titledb), else year×10000. Sorts "New Arrivals" by
    // actual recency instead of falling back to alphabetical within a year.
    val releaseDate: Int? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    // Nintendo Switch title ID (16-hex). Switch dumps on Internet Archive are catalogued by this,
    // not the game name, so it's the key that actually finds them.
    val titleId: String? = null,
    val description: String? = null,
    val boxArtUrl: String? = null,
    val heroArtUrl: String? = null,
    val screenshots: List<String> = emptyList(),
    val sources: List<Source> = emptyList(),
)
