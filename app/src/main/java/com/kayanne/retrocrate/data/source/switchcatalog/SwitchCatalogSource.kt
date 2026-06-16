package com.kayanne.retrocrate.data.source.switchcatalog

import android.content.Context
import android.util.Log
import com.kayanne.retrocrate.data.source.RomMatcher
import com.kayanne.retrocrate.data.source.titledb.TitledbSource
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform
import com.kayanne.retrocrate.domain.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// OpenVGDB has no Nintendo Switch data (it stops at the retro era), so the Switch catalog ships as
// a hand-curated JSON asset. Same Game shape as everything else, so the rest of the app — rails,
// search, detail, downloads, per-platform folders — treats Switch titles identically.
object SwitchCatalogSource {

    private const val TAG = "SwitchCatalog"
    private const val ASSET = "switch_catalog.json"

    private val json = Json { ignoreUnknownKeys = true }

    // Bundled, hand-curated classics merged with the live titledb cache (new releases, refreshed in
    // the background). The curated entry wins on a title collision so its editorial description and
    // genres are kept; titledb fills in everything else and keeps the catalog current.
    suspend fun fetchCatalog(context: Context): List<Game> = withContext(Dispatchers.IO) {
        val bundled = loadBundled(context)
        val live = TitledbSource.loadCached(context)

        val byTitle = LinkedHashMap<String, Game>()
        for (game in live) byTitle[RomMatcher.normalizeTitle(game.title)] = game
        for (game in bundled) byTitle[RomMatcher.normalizeTitle(game.title)] = game

        val merged = byTitle.values.toList()
        Log.i(TAG, "Switch catalog: ${merged.size} (${bundled.size} curated + ${live.size} live)")
        merged
    }

    private fun loadBundled(context: Context): List<Game> {
        val raw = runCatching {
            context.assets.open(ASSET).use { it.readBytes().decodeToString() }
        }.getOrElse {
            Log.w(TAG, "Failed to read $ASSET", it)
            return emptyList()
        }
        return runCatching { json.decodeFromString<List<SwitchEntry>>(raw) }
            .getOrElse {
                Log.w(TAG, "Failed to parse $ASSET", it)
                emptyList()
            }
            .map { it.toGame() }
    }

    @Serializable
    private data class SwitchEntry(
        val title: String,
        val titleId: String? = null,
        val year: Int? = null,
        val developer: String? = null,
        val publisher: String? = null,
        val genres: List<String> = emptyList(),
        val description: String? = null,
        val boxArtUrl: String? = null,
        val romFileName: String? = null,
    )

    private fun SwitchEntry.toGame(): Game = Game(
        id = "switch:${title.toSlug()}",
        title = title,
        platform = Platform.SWITCH,
        titleId = titleId,
        releaseYear = year,
        releaseDate = year?.times(10000),
        developer = developer,
        publisher = publisher,
        genres = genres,
        description = description,
        boxArtUrl = boxArtUrl,
        heroArtUrl = boxArtUrl,
        sources = romFileName?.let {
            listOf(
                Source(
                    id = "switch-rom:$it",
                    siteName = "Internet Archive",
                    region = "World",
                    sizeBytes = null,
                    resolveUrl = it,
                ),
            )
        }.orEmpty(),
    )

    private fun String.toSlug(): String =
        lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
