package com.kayanne.retrocrate.domain.model

data class Game(
    val id: String,
    val title: String,
    val platform: Platform,
    val releaseYear: Int? = null,
    val genres: List<String> = emptyList(),
    val description: String? = null,
    val boxArtUrl: String? = null,
    val screenshots: List<String> = emptyList(),
    val sources: List<Source> = emptyList(),
)
