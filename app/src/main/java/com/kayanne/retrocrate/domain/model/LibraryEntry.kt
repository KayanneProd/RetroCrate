package com.kayanne.retrocrate.domain.model

import java.time.Instant

data class LibraryEntry(
    val gameId: String,
    val addedAt: Instant,
    val downloadState: DownloadState,
    val lastPlayedAt: Instant? = null,
)
