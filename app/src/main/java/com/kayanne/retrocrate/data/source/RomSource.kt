package com.kayanne.retrocrate.data.source

import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform

// A place we can fetch a downloadable ROM file from. Implementations resolve a Game to a direct
// download URL on their host; the DownloadSourceResolver tries them in order. A source that has
// nothing for a game (or whose host is unreachable) returns null and the chain moves on.
interface RomSource {
    val siteName: String

    // The "Auto" path: this source's single best confident match, or null.
    suspend fun resolve(query: ResolveQuery): ResolvedDownload?

    // The "choose a source" path: every option this source offers for the game, with display
    // metadata, so the user can pick the right one (and back out if they're all junk). Listing is
    // cheap — the expensive final step (Vimm's form fetch, debrid unlock) runs in each candidate's
    // lazy [DownloadCandidate.resolve], only when the user actually picks it.
    suspend fun listCandidates(query: ResolveQuery): List<DownloadCandidate> = emptyList()

    // "Search beyond the catalog": ask this source's own index for titles matching a free-text query,
    // returned as downloadable Games. Lets Search reach games our local catalog doesn't list (the
    // download then resolves through the normal chain by title). Network — only ever on an explicit
    // user action. Default empty for sources that can't cheaply search by title.
    suspend fun searchTitles(query: String, platform: Platform?): List<Game> = emptyList()
}

// One selectable download option from a source. Carries enough metadata to show the user what they'd
// get (filename/title, region, size, an extra hint like seeders) and a thunk that produces the actual
// download when picked.
//
// [unlockUrl] flags a candidate whose real file sits behind an ad-shortener (DDL sites): instead of
// the [resolve] thunk, the UI opens this URL in a WebView for one human tap, captures the file-host
// link it redirects to, and unlocks that via debrid. When set, [resolve] is unused.
class DownloadCandidate(
    val sourceName: String,
    val label: String,
    val region: String? = null,
    val sizeBytes: Long? = null,
    val extra: String? = null,
    val unlockUrl: String? = null,
    val resolve: suspend () -> ResolvedDownload?,
) {
    val id: String get() = "$sourceName|$label|$region|$extra|$unlockUrl"
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
