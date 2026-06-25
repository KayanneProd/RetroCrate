package com.kayanne.retrocrate.data.source.debrid

import android.util.Log
import com.kayanne.retrocrate.data.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.net.URLEncoder

// Finds candidate torrents for a game. Kept behind an interface so more indexers can be layered in;
// results feed a debrid service which turns the best cached one into a direct download link. Scraping
// only ever runs in response to a user pressing Install (LLM-CONTEXT §13).
interface TorrentIndexer {
    val name: String
    suspend fun search(query: String): List<TorrentCandidate>
}

// The Pirate Bay's public JSON endpoint (apibay) — keyless, returns an info hash + size + seeders
// directly, so there's no HTML to scrape and no API key to manage. A "no results" sentinel row
// (all-zero hash) is filtered out.
object ApibayIndexer : TorrentIndexer {

    private const val TAG = "Apibay"
    private const val BASE = "https://apibay.org/q.php"
    private const val EMPTY_HASH = "0000000000000000000000000000000000000000"

    private val json = Json { ignoreUnknownKeys = true }

    override val name = "The Pirate Bay"

    override suspend fun search(query: String): List<TorrentCandidate> = withContext(Dispatchers.IO) {
        val url = "$BASE?q=${URLEncoder.encode(query, "UTF-8")}&cat=0"
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val raw = runCatching {
            HttpClient.get().newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }.getOrElse {
            Log.w(TAG, "search failed for \"$query\"", it)
            return@withContext emptyList()
        }

        runCatching { json.decodeFromString<List<ApibayRow>>(raw) }
            .getOrElse {
                Log.w(TAG, "parse failed for \"$query\"", it)
                emptyList()
            }
            .asSequence()
            .filter { it.info_hash.isNotBlank() && !it.info_hash.equals(EMPTY_HASH, ignoreCase = true) }
            .map {
                TorrentCandidate(
                    name = it.name,
                    infoHash = it.info_hash,
                    sizeBytes = it.size.toLongOrNull(),
                    seeders = it.seeders.toIntOrNull() ?: 0,
                )
            }
            .toList()
    }

    @Serializable
    private data class ApibayRow(
        val name: String = "",
        val info_hash: String = "",
        val size: String = "0",
        val seeders: String = "0",
    )
}
