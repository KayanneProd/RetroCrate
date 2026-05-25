package com.kayanne.retrocrate.data.source.vimms

import com.kayanne.retrocrate.data.network.HttpClient
import com.kayanne.retrocrate.data.source.LibretroThumbnails
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform
import com.kayanne.retrocrate.domain.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Request

object VimmsSource {

    // Vimm's paginates by letter (A-Z + '#') per platform — we fan out across all sections in
    // parallel and rely on HttpClient's per-host Semaphore(2) to keep the request rate polite.
    suspend fun fetchPlatform(platform: Platform): List<Game> = coroutineScope {
        val entries = VimmsPaths.ALPHABET_SECTIONS
            .mapNotNull { section -> VimmsPaths.vaultUrlForSection(platform, section) }
            .map { url ->
                async(Dispatchers.IO) { fetchSectionEntries(url) }
            }
            .awaitAll()
            .flatten()

        if (entries.isEmpty()) {
            error("No games parsed from Vimm's vault for $platform — check selectors in VimmsParser.")
        }

        entries.map { it.toGame(platform) }
    }

    private fun fetchSectionEntries(url: String): List<VimmsVaultEntry> {
        val html = fetchHtml(url) ?: return emptyList()
        return VimmsParser.parseVaultListing(html)
    }

    // Returns null on 404 (a letter section may legitimately have no entries).
    private fun fetchHtml(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        HttpClient.get().newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) {
                error("HTTP ${response.code} fetching $url")
            }
            return response.body?.string()
                ?: error("Empty body fetching $url")
        }
    }

    private fun VimmsVaultEntry.toGame(platform: Platform): Game {
        val regionForArt = when (region?.lowercase()) {
            "united states", "usa" -> "USA"
            "europe" -> "Europe"
            "japan" -> "Japan"
            else -> "USA"
        }
        return Game(
            id = "vimms:$vimmsId",
            title = title,
            platform = platform,
            releaseYear = year,
            boxArtUrl = LibretroThumbnails.boxArtUrl(platform, title, regionForArt),
            heroArtUrl = LibretroThumbnails.snapUrl(platform, title, regionForArt),
            sources = listOf(
                Source(
                    id = "vimms:$vimmsId",
                    siteName = "Vimm's Lair",
                    region = region ?: "USA",
                    sizeBytes = null,
                    resolveUrl = VimmsPaths.gameDetailUrl(vimmsId),
                ),
            ),
        )
    }
}

