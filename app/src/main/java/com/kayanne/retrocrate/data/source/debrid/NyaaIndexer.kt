package com.kayanne.retrocrate.data.source.debrid

import android.util.Log
import com.kayanne.retrocrate.data.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder

// Nyaa's public RSS feed — a second torrent indexer alongside The Pirate Bay. It carries a huge
// number of Switch NSP/XCI releases (including indies and Japanese titles TPB doesn't have), each
// with an info hash + seeders right in the feed, and there are no ad-gates to fight. This is what
// makes the long tail of smaller games downloadable through debrid.
object NyaaIndexer : TorrentIndexer {

    private const val TAG = "Nyaa"
    private const val BASE = "https://nyaa.si/"

    override val name = "Nyaa"

    override suspend fun search(query: String): List<TorrentCandidate> = withContext(Dispatchers.IO) {
        val url = "$BASE?page=rss&q=${URLEncoder.encode(query, "UTF-8")}&c=0_0&f=0"
        val request = Request.Builder().url(url).header("Accept", "application/rss+xml").build()
        val xml = runCatching {
            HttpClient.get().newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        }.getOrElse {
            Log.w(TAG, "search failed for \"$query\"", it)
            return@withContext emptyList()
        }

        runCatching {
            Jsoup.parse(xml, "", Parser.xmlParser()).select("item").mapNotNull { item ->
                val title = item.selectFirst("title")?.text()?.takeIf { it.isNotBlank() }
                val hash = item.tag("nyaa:infoHash")
                if (title == null || hash.isNullOrBlank()) return@mapNotNull null
                TorrentCandidate(
                    name = title,
                    infoHash = hash,
                    sizeBytes = parseSize(item.tag("nyaa:size")),
                    seeders = item.tag("nyaa:seeders")?.toIntOrNull() ?: 0,
                )
            }
        }.getOrElse {
            Log.w(TAG, "parse failed for \"$query\"", it)
            emptyList()
        }
    }

    private fun org.jsoup.nodes.Element.tag(name: String): String? =
        getElementsByTag(name).firstOrNull()?.text()?.takeIf { it.isNotBlank() }

    // Nyaa reports human-readable sizes like "1.2 GiB" / "512.0 MiB".
    private fun parseSize(raw: String?): Long? {
        val match = raw?.let { Regex("([0-9.]+)\\s*([KMGT])i?B", RegexOption.IGNORE_CASE).find(it) } ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].uppercase()) {
            "T" -> 1L shl 40
            "G" -> 1L shl 30
            "M" -> 1L shl 20
            "K" -> 1L shl 10
            else -> 1L
        }
        return (value * multiplier).toLong()
    }
}
