package com.kayanne.retrocrate.data.source.vimms

import android.util.Log
import com.kayanne.retrocrate.data.network.HttpClient
import com.kayanne.retrocrate.data.source.DownloadCandidate
import com.kayanne.retrocrate.data.source.LibretroThumbnails
import com.kayanne.retrocrate.data.source.ResolveQuery
import com.kayanne.retrocrate.data.source.ResolvedDownload
import com.kayanne.retrocrate.data.source.RomMatcher
import com.kayanne.retrocrate.data.source.RomSource
import com.kayanne.retrocrate.data.source.TitleSearch
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform
import com.kayanne.retrocrate.domain.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup

// Vimm's Lair as a download source. Vimm's catalogs each platform from the No-Intro DAT with one
// game per page, so matching is exact — far more accurate than Internet Archive's grab-bag items.
//
// Vimm's gates its download form behind a browser-like request and a Referer back at the vault
// page. We only return a download when we can actually parse a real form (mediaId + download host)
// off the page; if Vimm's changes shape or blocks us, we return null and the resolver falls back to
// Internet Archive. So this source can only ever add accuracy, never break a working download.
object VimmsDownloadSource : RomSource {

    private const val TAG = "VimmsSource"
    override val siteName: String = "Vimm's Lair"

    override suspend fun resolve(query: ResolveQuery): ResolvedDownload? = withContext(Dispatchers.IO) {
        val entries = matchingEntries(query)
        val entry = entries.minByOrNull { regionRank(it.region) } ?: run {
            Log.w(TAG, "No vault entry matched \"${query.title}\"")
            return@withContext null
        }
        resolveEntry(entry, query)
    }

    // Every vault entry that matches the title (often several regions), each a pickable candidate.
    override suspend fun listCandidates(query: ResolveQuery): List<DownloadCandidate> = withContext(Dispatchers.IO) {
        matchingEntries(query)
            .sortedBy { regionRank(it.region) }
            .map { entry ->
                DownloadCandidate(
                    sourceName = siteName,
                    label = entry.title,
                    region = entry.region,
                    sizeBytes = null,
                    extra = entry.year?.toString(),
                    resolve = { resolveEntry(entry, query) },
                )
            }
    }

    // Live "search beyond the catalog": query the Vimm's vault for titles matching free text and
    // return them as downloadable Games. A query hits one alphabetical section page per platform
    // (the section is fixed by the query's first letter), fanned out across the platforms Vimm's
    // vaults; the per-host rate limiter keeps that polite. Box art is the libretro cover for the
    // No-Intro title (Vimm's files article-last, which is exactly libretro's filename form).
    override suspend fun searchTitles(query: String, platform: Platform?): List<Game> {
        if (query.isBlank()) return emptyList()
        val platforms = when {
            platform != null -> if (VimmsPaths.vaults(platform)) listOf(platform) else emptyList()
            else -> Platform.entries.filter { VimmsPaths.vaults(it) }
        }
        if (platforms.isEmpty()) return emptyList()
        return coroutineScope {
            platforms
                .map { p -> async(Dispatchers.IO) { searchPlatform(query, p) } }
                .awaitAll()
                .flatten()
        }
    }

    private fun searchPlatform(query: String, platform: Platform): List<Game> {
        val section = sectionFor(query)
        val url = VimmsPaths.vaultUrlForSection(platform, section) ?: return emptyList()
        val entries = runCatching {
            fetchHtml(url)?.let { VimmsParser.parseVaultListing(it) }
        }.getOrNull().orEmpty()
        return entries
            .asSequence()
            .filter { TitleSearch.score(query, it.title) > 0.0 }
            .distinctBy { it.vimmsId }
            .map { it.toGame(platform) }
            .toList()
    }

    private fun VimmsVaultEntry.toGame(platform: Platform): Game {
        val slug = TitleSearch.normalize(title).replace(' ', '-').ifBlank { vimmsId }
        return Game(
            id = "vimms:${platform.name.lowercase()}:$slug",
            title = title,
            platform = platform,
            releaseYear = year,
            releaseDate = year?.times(10000),
            boxArtUrl = LibretroThumbnails.boxArtUrl(platform, title, artRegion(region)),
            sources = listOf(
                Source(
                    id = "vimms:$vimmsId",
                    siteName = siteName,
                    region = region,
                    sizeBytes = null,
                    resolveUrl = "",
                ),
            ),
        )
    }

    private fun artRegion(region: String?): String = when (region?.lowercase()) {
        "europe" -> "Europe"
        "japan" -> "Japan"
        else -> "USA"
    }

    private fun matchingEntries(query: ResolveQuery): List<VimmsVaultEntry> {
        val section = sectionFor(query.title)
        val listingUrl = VimmsPaths.vaultUrlForSection(query.platform, section) ?: return emptyList()
        val entries = runCatching {
            fetchHtml(listingUrl)?.let { VimmsParser.parseVaultListing(it) }
        }.getOrNull().orEmpty()
        if (entries.isEmpty()) {
            Log.w(TAG, "No vault entries parsed for \"${query.title}\" at $listingUrl (parser drift?)")
            return emptyList()
        }
        return entries.filter { RomMatcher.titlesMatch(it.title, query.title) }
    }

    private fun resolveEntry(entry: VimmsVaultEntry, query: ResolveQuery): ResolvedDownload? {
        val pageUrl = VimmsPaths.gameDetailUrl(entry.vimmsId)
        val pageHtml = runCatching { fetchHtml(pageUrl) }.getOrNull() ?: return null
        val form = parseDownloadForm(pageHtml, entry.vimmsId) ?: run {
            Log.w(TAG, "No parseable download form for \"${entry.title}\" (vault ${entry.vimmsId})")
            return null
        }
        val filename = (query.romFileName?.substringBeforeLast('.') ?: entry.title)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".zip"
        return ResolvedDownload(
            siteName = siteName,
            filename = filename,
            downloadUrl = form.downloadUrl,
            sizeBytes = null,
            headers = mapOf("Referer" to pageUrl),
        )
    }

    private data class DownloadForm(val downloadUrl: String)

    private fun parseDownloadForm(html: String, vimmsId: String): DownloadForm? {
        val doc = Jsoup.parse(html)
        val mediaId = doc.select("input[name=mediaId]").firstOrNull()?.attr("value")
            ?.takeIf { it.isNotBlank() } ?: vimmsId

        val form = doc.select("form").firstOrNull {
            it.select("input[name=mediaId]").isNotEmpty() ||
                it.attr("action").contains("vimm", ignoreCase = true)
        } ?: return null

        var action = form.attr("action").trim()
        if (action.isBlank()) return null
        if (action.startsWith("//")) action = "https:$action"
        else if (action.startsWith("/")) action = "${VimmsPaths.BASE}$action"
        if (!action.startsWith("http")) return null

        val sep = if (action.contains("?")) "&" else "?"
        return DownloadForm(downloadUrl = "$action${sep}mediaId=$mediaId")
    }

    private fun fetchHtml(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Referer", "${VimmsPaths.BASE}/")
            .build()
        HttpClient.get().newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    // Vimm's files titles article-last ("Legend of Zelda, The"), so strip a leading article before
    // taking the section letter. Digits/symbols live under '#'.
    private fun sectionFor(title: String): String {
        val stripped = title.trim()
            .replace(Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
        val first = stripped.firstOrNull() ?: return "#"
        return if (first.isLetter()) first.uppercaseChar().toString() else "#"
    }

    private fun regionRank(region: String?): Int = when (region?.lowercase()) {
        "united states", "usa" -> 0
        "world" -> 1
        "europe" -> 2
        "japan" -> 3
        else -> 9
    }
}
