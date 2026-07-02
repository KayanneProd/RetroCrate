package com.kayanne.retrocrate.data.source.libretro

import android.util.JsonReader
import android.util.Log
import com.kayanne.retrocrate.data.network.HttpClient
import com.kayanne.retrocrate.data.source.LibretroThumbnails
import com.kayanne.retrocrate.data.source.RomMatcher
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform
import com.kayanne.retrocrate.domain.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

// Catalog source for the popular consoles OpenVGDB has no metadata for (PS2, Dreamcast, Wii U, 3DS,
// Vita). Each of these has a libretro-thumbnails repo, and that repo's Named_Boxarts directory is a
// complete, art-backed title list: every filename is a No-Intro/Redump game name with its region,
// and points straight at the box art. So listing that directory gives us a browsable, searchable,
// downloadable catalog with art — without any metadata DB. There are no descriptions/genres for
// these (libretro has none); the Detail page shows art + a gameplay snap.
//
// Downloads still go through the normal resolver chain: PS2 / Dreamcast / 3DS are vaulted on Vimm's
// (exact), while Wii U / Vita fall to debrid or Internet Archive.
object LibretroCatalogSource {

    private const val TAG = "LibretroCatalog"
    private const val API = "https://api.github.com/repos/libretro-thumbnails"

    // Consoles catalogued from libretro (no OpenVGDB metadata). Vita/Wii U aren't on Vimm's, so they
    // download via debrid/IA; the rest resolve on Vimm's.
    val PLATFORMS: List<Platform> = listOf(
        Platform.PS2,
        Platform.DREAMCAST,
        Platform.NINTENDO_3DS,
        Platform.WII_U,
        Platform.PS_VITA,
    )

    // No-Intro dump tags marking non-retail entries we don't want in the catalog.
    private val JUNK_TAG = Regex("\\((demo|beta|proto|prototype|sample|debug|test)", RegexOption.IGNORE_CASE)

    suspend fun fetchCatalog(platform: Platform): List<Game> = withContext(Dispatchers.IO) {
        val repo = LibretroThumbnails.REPO_FOR[platform] ?: return@withContext emptyList()
        val boxartsSha = runCatching { findBoxartsSha(repo) }.getOrElse {
            Log.w(TAG, "Failed to read tree for $repo", it); null
        } ?: return@withContext emptyList()

        val files = runCatching { listBoxartFilenames(repo, boxartsSha) }.getOrElse {
            Log.w(TAG, "Failed to list boxarts for $repo", it); emptyList()
        }
        if (files.isEmpty()) return@withContext emptyList()

        // One game per title, English/USA region preferred when several regional box arts exist — so
        // the same game's USA/Europe/Japan variants collapse to one entry (the English filter then
        // hides any title whose only variant is non-English).
        val byTitle = LinkedHashMap<String, Entry>()
        for (filename in files) {
            if (JUNK_TAG.containsMatchIn(filename)) continue
            val base = filename.removeSuffix(".png")
            val title = titleOf(base)
            if (title.isBlank()) continue
            val region = regionOf(base)
            val key = RomMatcher.normalizeTitle(title)
            val existing = byTitle[key]
            if (existing == null || regionRank(region) < regionRank(existing.region)) {
                byTitle[key] = Entry(title, region, filename, base)
            }
        }

        val vaultedOnVimms = platform == Platform.PS2 ||
            platform == Platform.DREAMCAST ||
            platform == Platform.NINTENDO_3DS
        val siteName = if (vaultedOnVimms) "Vimm's Lair" else "Internet Archive"

        byTitle.values.map { e ->
            Game(
                id = "libretro:${platform.name.lowercase()}:${slug(e.title)}",
                title = e.title,
                platform = platform,
                boxArtUrl = LibretroThumbnails.boxArtUrlForFile(platform, e.filename),
                heroArtUrl = LibretroThumbnails.boxArtUrlForFile(platform, e.filename),
                sources = listOf(
                    Source(
                        id = "libretro-rom:${e.base}",
                        siteName = siteName,
                        region = e.region,
                        sizeBytes = null,
                        // No-Intro/Redump name (carries the region) — RomMatcher's matching key.
                        resolveUrl = e.base,
                    ),
                ),
            )
        }.also { Log.i(TAG, "$platform: ${files.size} box arts -> ${it.size} games") }
    }

    private data class Entry(val title: String, val region: String?, val filename: String, val base: String)

    private fun findBoxartsSha(repo: String): String? {
        readTree("$API/$repo/git/trees/master") { path, type, sha ->
            if (type == "tree" && path == "Named_Boxarts") return@readTree sha
            null
        }?.let { return it }
        return null
    }

    private fun listBoxartFilenames(repo: String, sha: String): List<String> {
        val out = ArrayList<String>(8192)
        readTree("$API/$repo/git/trees/$sha") { path, _, _ ->
            if (path.endsWith(".png", ignoreCase = true)) out.add(path)
            null
        }
        return out
    }

    // Streams a GitHub git-tree response, invoking [onEntry] for each tree element. If onEntry returns
    // a non-null value, parsing stops and that value is returned (used to find a subtree sha early).
    private fun readTree(url: String, onEntry: (path: String, type: String, sha: String) -> String?): String? {
        val client = HttpClient.get().newBuilder().callTimeout(2, TimeUnit.MINUTES).build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            val body = response.body ?: error("empty body for $url")
            JsonReader(body.charStream().buffered()).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() != "tree") { reader.skipValue(); continue }
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var path = ""; var type = ""; var sha = ""
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "path" -> path = reader.nextString()
                                "type" -> type = reader.nextString()
                                "sha" -> sha = reader.nextString()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        onEntry(path, type, sha)?.let { return it }
                    }
                    reader.endArray()
                }
                reader.endObject()
            }
        }
        return null
    }

    // "Final Fantasy X (USA)" -> "Final Fantasy X"; strips the trailing region/flag parentheticals.
    private fun titleOf(base: String): String =
        base.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").let {
            // Drop any remaining trailing parens groups (e.g. "(USA) (Rev 1)").
            it.replace(Regex("(\\s*\\([^)]*\\))+$"), "")
        }.trim()

    private fun regionOf(base: String): String? =
        Regex("\\(([^)]*)\\)").findAll(base)
            .map { it.groupValues[1] }
            .firstOrNull { group ->
                group.split(',').map { it.trim().lowercase() }.any { it in KNOWN_REGIONS }
            }

    private fun regionRank(region: String?): Int {
        val r = region?.lowercase() ?: return 5
        return when {
            "usa" in r -> 0
            "world" in r -> 1
            "europe" in r || "uk" in r || "australia" in r || "canada" in r -> 2
            else -> 4
        }
    }

    private fun slug(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}

private val KNOWN_REGIONS = setOf(
    "usa", "europe", "japan", "world", "australia", "germany", "france", "spain",
    "italy", "korea", "china", "asia", "canada", "brazil", "netherlands", "sweden", "uk",
)
