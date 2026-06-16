package com.kayanne.retrocrate.data.source

import android.util.Log
import com.kayanne.retrocrate.data.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

// Live, keyless popularity signal: the most-downloaded software items on Internet Archive, which is
// ROM-relevant (people downloading these are downloading games) and auto-updates over time. We pull
// the ranked titles once a day; the catalog repository intersects them with what we actually have,
// so "Popular" reflects what's genuinely being downloaded right now rather than a hardcoded list.
//
// No API key, no account. If the call fails the list stays empty and the repository falls back to a
// data-driven daily rotation, so Home is never blank.
object PopularityService {

    private const val TAG = "Popularity"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val _popularTitles = MutableStateFlow<List<String>>(emptyList())
    val popularTitles: StateFlow<List<String>> = _popularTitles.asStateFlow()

    @Volatile private var lastFetchDay = -1L

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val today = System.currentTimeMillis() / 86_400_000L
        if (today == lastFetchDay && _popularTitles.value.isNotEmpty()) return@withContext
        val titles = fetchMostDownloaded()
        if (titles.isNotEmpty()) {
            _popularTitles.value = titles
            lastFetchDay = today
            Log.i(TAG, "Loaded ${titles.size} popular titles from Internet Archive")
        }
    }

    private fun fetchMostDownloaded(): List<String> {
        val url = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter(
                "q",
                "mediatype:software AND (subject:(games OR \"video games\" OR nintendo OR " +
                    "playstation OR sega OR rom) OR collection:(softwarelibrary))",
            )
            .addQueryParameter("sort[]", "downloads desc")
            .addQueryParameter("fl[]", "title")
            .addQueryParameter("rows", "400")
            .addQueryParameter("output", "json")
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        return runCatching {
            HttpClient.get().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching emptyList()
                val body = response.body?.string() ?: return@runCatching emptyList()
                val docs = json.parseToJsonElement(body)
                    .jsonObject["response"]?.jsonObject?.get("docs")?.jsonArray
                    ?: return@runCatching emptyList()
                docs.mapNotNull { element ->
                    when (val t = element.jsonObject["title"]) {
                        is JsonPrimitive -> t.contentOrNull
                        is JsonArray -> t.firstOrNull()?.jsonPrimitive?.contentOrNull
                        else -> null
                    }
                }
            }
        }.getOrElse {
            Log.w(TAG, "Popularity fetch failed", it)
            emptyList()
        }
    }
}
