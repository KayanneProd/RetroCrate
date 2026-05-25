package com.kayanne.retrocrate.domain.model

data class Game(
    val id: String,
    val title: String,
    val platform: Platform,
    val releaseYear: Int? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val description: String? = null,
    val boxArtUrl: String? = null,
    val heroArtUrl: String? = null,
    val screenshots: List<String> = emptyList(),
    val sources: List<Source> = emptyList(),
)
