package com.kayanne.retrocrate.data.source.vimms

import android.util.Log
import com.kayanne.retrocrate.data.network.HttpClient
import com.kayanne.retrocrate.data.source.ResolveQuery
import com.kayanne.retrocrate.data.source.ResolvedDownload
import com.kayanne.retrocrate.data.source.RomMatcher
import com.kayanne.retrocrate.data.source.RomSource
import kotlinx.coroutines.Dispatchers
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
        val section = sectionFor(query.title)
        val listingUrl = VimmsPaths.vaultUrlForSection(query.platform, section) ?: return@withContext null

        val entries = runCatching {
            fetchHtml(listingUrl)?.let { VimmsParser.parseVaultListing(it) }
        }.getOrNull().orEmpty()
        if (entries.isEmpty()) {
            Log.w(TAG, "No vault entries parsed for \"${query.title}\" at $listingUrl (parser drift?)")
            return@withContext null
        }

        val entry = entries
            .filter { RomMatcher.titlesMatch(it.title, query.title) }
            .minByOrNull { regionRank(it.region) }
            ?: run {
                Log.w(TAG, "No vault entry matched \"${query.title}\" among ${entries.size} in section $section")
                return@withContext null
            }

        val pageUrl = VimmsPaths.gameDetailUrl(entry.vimmsId)
        val pageHtml = runCatching { fetchHtml(pageUrl) }.getOrNull() ?: return@withContext null
        val form = parseDownloadForm(pageHtml, entry.vimmsId) ?: run {
            Log.w(TAG, "No parseable download form for \"${query.title}\" (vault ${entry.vimmsId})")
            return@withContext null
        }

        val filename = (query.romFileName?.substringBeforeLast('.') ?: query.title)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".zip"

        ResolvedDownload(
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
