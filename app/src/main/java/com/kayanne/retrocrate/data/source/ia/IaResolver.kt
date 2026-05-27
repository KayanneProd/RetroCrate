package com.kayanne.retrocrate.data.source.ia

import android.util.Log
import com.kayanne.retrocrate.data.network.HttpClient
import com.kayanne.retrocrate.domain.model.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

// Resolves a Game to a downloadable ROM URL on Internet Archive. Public, no auth.
//
// Strategy:
// 1. If OpenVGDB gave us a romFileName (No-Intro standard name), use that as the search key.
// 2. Search IA: advancedsearch.php for items matching the filename or title with mediatype:software.
// 3. Fetch metadata for the chosen item to find a file with a known ROM extension.
// 4. Return the direct download URL: https://archive.org/download/<identifier>/<filename>
//
// First successful candidate wins.
object IaResolver {

    private const val TAG = "IaResolver"
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val ROM_EXTENSIONS = setOf(
        // Nintendo
        "nes", "smc", "sfc", "z64", "n64", "v64", "gb", "gbc", "gba", "nds", "iso", "wbfs", "rvz", "gcm",
        // Sega
        "md", "smd", "gen", "bin", "32x", "sms", "gg", "sat", "cdi", "chd",
        // Sony
        "img", "cue", "pbp",
        // Generic
        "zip", "7z",
    )

    data class ResolvedDownload(
        val identifier: String,
        val filename: String,
        val downloadUrl: String,
        val sizeBytes: Long?,
    )

    suspend fun resolve(game: Game): ResolvedDownload? = withContext(Dispatchers.IO) {
        val romFileName = game.sources.firstOrNull()?.resolveUrl
        val candidates = searchIa(game.title, romFileName)
        if (candidates.isEmpty()) {
            Log.w(TAG, "No IA search results for ${game.title}")
            return@withContext null
        }
        // Try each candidate until we find one with a matching ROM file.
        for (identifier in candidates.take(10)) {
            val file = pickRomFile(identifier, romFileName) ?: continue
            val url = "https://archive.org/download/$identifier/${urlEncode(file.name)}"
            Log.i(TAG, "Resolved ${game.title} -> $url (${file.size} bytes)")
            return@withContext ResolvedDownload(
                identifier = identifier,
                filename = file.name,
                downloadUrl = url,
                sizeBytes = file.size?.toLongOrNull(),
            )
        }
        Log.w(TAG, "No usable IA item with a ROM file for ${game.title}")
        null
    }

    private fun searchIa(title: String, romFileName: String?): List<String> {
        val query = buildString {
            append("mediatype:software AND (")
            // Search by ROM filename if we have it (best match for No-Intro dumps)…
            if (romFileName != null) {
                append("title:\"${titleFromFilename(romFileName)}\" OR ")
            }
            // …or by raw title as a fallback.
            append("title:\"${title.replace("\"", "")}\"")
            append(")")
        }
        val url = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("fl[]", "identifier")
            .addQueryParameter("fl[]", "title")
            .addQueryParameter("rows", "20")
            .addQueryParameter("output", "json")
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        return runCatching {
            HttpClient.get().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching emptyList()
                val body = response.body?.string() ?: return@runCatching emptyList()
                json.decodeFromString<IaSearchResponse>(body).response.docs.map { it.identifier }
            }
        }.getOrElse {
            Log.w(TAG, "IA search failed for \"$title\"", it)
            emptyList()
        }
    }

    private fun pickRomFile(identifier: String, preferredRomFileName: String?): IaFile? {
        val request = Request.Builder()
            .url("https://archive.org/metadata/$identifier")
            .header("Accept", "application/json")
            .build()
        val files = runCatching {
            HttpClient.get().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching emptyList()
                val body = response.body?.string() ?: return@runCatching emptyList()
                json.decodeFromString<IaMetadata>(body).files
            }
        }.getOrElse {
            Log.w(TAG, "IA metadata fetch failed for $identifier", it)
            return null
        }

        // If we know the No-Intro filename, prefer an exact (case-insensitive) match.
        if (preferredRomFileName != null) {
            files.firstOrNull { it.name.equals(preferredRomFileName, ignoreCase = true) }
                ?.let { return it }
            // Fall back to substring match on the title portion.
            val titlePart = titleFromFilename(preferredRomFileName)
            files.firstOrNull {
                hasRomExtension(it.name) && it.name.contains(titlePart, ignoreCase = true)
            }?.let { return it }
        }

        // Last resort: first file with a known ROM extension.
        return files.firstOrNull { hasRomExtension(it.name) }
    }

    private fun hasRomExtension(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return ext in ROM_EXTENSIONS
    }

    private fun titleFromFilename(romFileName: String): String {
        // "Super Mario 64 (USA).z64" -> "Super Mario 64"
        return romFileName
            .substringBeforeLast('.')
            .replace(Regex("\\s*\\([^)]*\\)"), "")
            .trim()
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

@Serializable
private data class IaSearchResponse(val response: IaSearchInnerResponse)

@Serializable
private data class IaSearchInnerResponse(val docs: List<IaSearchDoc> = emptyList())

@Serializable
private data class IaSearchDoc(val identifier: String, val title: String? = null)

@Serializable
private data class IaMetadata(val files: List<IaFile> = emptyList())

@Serializable
private data class IaFile(
    val name: String,
    val size: String? = null,
    val format: String? = null,
)
