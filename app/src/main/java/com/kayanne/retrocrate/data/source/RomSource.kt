package com.kayanne.retrocrate.data.source

import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform

// A place we can fetch a downloadable ROM file from. Implementations resolve a Game to a direct
// download URL on their host; the DownloadSourceResolver tries them in order. A source that has
// nothing for a game (or whose host is unreachable) returns null and the chain moves on.
interface RomSource {
    val siteName: String
    suspend fun resolve(query: ResolveQuery): ResolvedDownload?
}

// Everything a source needs to find the right file, derived from a Game.
data class ResolveQuery(
    val title: String,
    val platform: Platform,
    // No-Intro / Redump filename when we have one (e.g. "Super Mario 64 (USA).z64"). This is the
    // single most reliable matching key — an exact filename match is never the wrong game.
    val romFileName: String?,
    val preferredRegion: String,
    // Switch title ID (16-hex) — the key Internet Archive's Switch dumps are catalogued under.
    val titleId: String? = null,
)

data class ResolvedDownload(
    val siteName: String,
    val filename: String,
    val downloadUrl: String,
    val sizeBytes: Long?,
    // Extra request headers the host needs for the actual file fetch (e.g. Vimm's requires a
    // Referer pointing back at the vault page or it serves an error instead of the ROM).
    val headers: Map<String, String> = emptyMap(),
)

fun Game.toResolveQuery(): ResolveQuery {
    val romFileName = sources.firstOrNull()?.resolveUrl?.takeIf { it.isNotBlank() }
    return ResolveQuery(
        title = title,
        platform = platform,
        romFileName = romFileName,
        preferredRegion = romFileName?.let { regionFromFilename(it) } ?: "USA",
        titleId = titleId,
    )
}

private fun regionFromFilename(filename: String): String {
    val parens = Regex("\\(([^)]*)\\)").findAll(filename).map { it.groupValues[1] }.toList()
    val region = parens.firstOrNull { group ->
        group.split(',').map { it.trim().lowercase() }.any { it in KNOWN_REGIONS }
    }
    return region ?: "USA"
}

private val KNOWN_REGIONS = setOf(
    "usa", "europe", "japan", "world", "australia", "germany", "france", "spain",
    "italy", "korea", "china", "asia", "canada", "brazil", "netherlands", "sweden",
)
