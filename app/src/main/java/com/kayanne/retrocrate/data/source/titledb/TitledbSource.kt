package com.kayanne.retrocrate.data.source.titledb

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import com.kayanne.retrocrate.data.network.HttpClient
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform
import com.kayanne.retrocrate.domain.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

// Live, auto-updating Switch releases sourced from the public blawar/titledb database (the same
// database every Switch library tool uses). It's the authoritative list of what Switch games exist
// and their eShop art, and it updates as new games release — so when a new title comes out, it
// shows up here without anyone editing the app.
//
// The file is large (~86 MB), so we stream-parse it with android.util.JsonReader (constant memory,
// proper JSON), keep only recent, real games, and cache the small result. The download runs in the
// background at most once a week; the bundled Switch catalog is the always-available fallback.
object TitledbSource {

    private const val TAG = "Titledb"
    private const val URL = "https://raw.githubusercontent.com/blawar/titledb/master/US.en.json"
    // Versioned filename: bump the suffix to invalidate stale caches when the inclusion gate or the
    // cap changes (old file is simply ignored; a fresh refresh rebuilds with the new rules).
    // v2 = broad searchable catalog (publisher/description gate moved to New Arrivals only).
    private const val CACHE_FILE = "titledb_switch_v2.json"
    private const val REFRESH_INTERVAL_MS = 24L * 60 * 60 * 1000
    // The full searchable Switch catalog — every real English retail base title, not just the recent
    // ones. Generous cap so older games (e.g. a 2019 Layton) are present and findable. The curated
    // "New Arrivals" rail is a strict, recent subset computed at selection time (isNewArrivalQuality),
    // so a broad catalog here doesn't put shovelware on Home.
    private const val MAX_CATALOG_GAMES = 8000

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun loadCached(context: Context): List<Game> {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<Cache>(file.readText()).games.map { it.toGame() }
        }.getOrElse {
            Log.w(TAG, "Failed to read titledb cache", it)
            emptyList()
        }
    }

    // The strict cut for the curated "New Arrivals" rail: a recognizable publisher and a real
    // description, and not a re-release line / "Switch 2 Edition". Applied at selection time so the
    // broad catalog (which includes indie/older titles for search) doesn't put shovelware on Home.
    fun isNewArrivalQuality(game: Game): Boolean =
        isNotablePublisher(game.publisher) &&
            (game.description?.length ?: 0) >= 80 &&
            !isReReleaseLine(game.title) &&
            !isSwitch2Edition(game.title)

    // Returns true if the cache was refreshed (caller should reload the Switch catalog).
    suspend fun refreshIfStale(context: Context): Boolean = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, CACHE_FILE)
        val fresh = file.exists() && (System.currentTimeMillis() - file.lastModified() < REFRESH_INTERVAL_MS)
        if (fresh) return@withContext false

        val entries = runCatching { downloadAndParse() }.getOrElse {
            Log.w(TAG, "titledb refresh failed", it)
            emptyList()
        }
        if (entries.isEmpty()) return@withContext false

        // Drop announced-but-unreleased titles (titledb carries future release dates) — a game the
        // user can't download yet shouldn't lead "New Arrivals". Then collapse the per-language SKUs
        // of the same game (e.g. Pokémon FireRed / Rouge Feu / Rojo Fuego) by their shared box art,
        // keeping the newest, so New Arrivals isn't six copies of one game.
        val today = todayStamp()
        val newest = entries
            .filter { (it.releaseDate ?: 0) <= today }
            .sortedByDescending { it.releaseDate ?: 0 }
            .distinctBy { it.name.lowercase().replace(Regex("[^a-z0-9]+"), "") }
            .take(MAX_CATALOG_GAMES)
        runCatching {
            file.writeText(json.encodeToString(Cache(games = newest)))
        }.onFailure { Log.w(TAG, "Failed to write titledb cache", it) }
        Log.i(TAG, "Refreshed titledb cache: ${newest.size} Switch games")
        true
    }

    private fun downloadAndParse(): List<Entry> {
        val client = HttpClient.get().newBuilder()
            .callTimeout(8, TimeUnit.MINUTES)
            .readTimeout(2, TimeUnit.MINUTES)
            .build()
        val request = Request.Builder().url(URL).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} fetching titledb")
            val body = response.body ?: error("empty titledb body")
            return JsonReader(body.charStream().buffered()).use { reader -> parse(reader) }
        }
    }

    // titledb is one big object keyed by nsuId; each value is a game record.
    private fun parse(reader: JsonReader): List<Entry> {
        val out = ArrayList<Entry>(4096)
        reader.beginObject()
        while (reader.hasNext()) {
            reader.nextName() // nsuId key (discarded)
            val entry = readEntry(reader)
            if (entry != null && entry.isCatalogGame()) out.add(entry)
        }
        reader.endObject()
        Log.i(TAG, "Parsed ${out.size} candidate Switch games from titledb")
        return out
    }

    private fun readEntry(reader: JsonReader): Entry? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        var id: String? = null
        var name: String? = null
        var icon: String? = null
        var publisher: String? = null
        var description: String? = null
        var releaseDate: Int? = null
        var players: Int? = null
        val genres = ArrayList<String>()
        val screenshots = ArrayList<String>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextStringOrNull()
                "name" -> name = reader.nextStringOrNull()
                "iconUrl" -> icon = reader.nextStringOrNull()
                "publisher" -> publisher = reader.nextStringOrNull()
                "description" -> description = reader.nextStringOrNull()
                // titledb stores the date as YYYYMMDD; keep the whole thing so newest sorts first.
                "releaseDate" -> releaseDate = reader.nextStringOrNull()?.filter { it.isDigit() }?.take(8)?.toIntOrNull()
                "numberOfPlayers" -> players = reader.nextIntOrNull()
                "category" -> genres.addAll(reader.readStringArray())
                "screenshots" -> screenshots.addAll(reader.readStringArray())
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (id == null || name == null) return null
        return Entry(id, cleanName(name), icon, publisher, description, releaseDate, players, genres, screenshots)
    }

    private fun JsonReader.readStringArray(): List<String> {
        if (peek() == JsonToken.NULL) { nextNull(); return emptyList() }
        val items = ArrayList<String>()
        beginArray()
        while (hasNext()) nextStringOrNull()?.let { items.add(it) }
        endArray()
        return items
    }

    private fun JsonReader.nextStringOrNull(): String? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()

    private fun JsonReader.nextIntOrNull(): Int? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextInt()

    private fun cleanName(raw: String): String {
        val cleaned = raw.replace("™", "").replace("®", "")
            // titledb prefixes per-language SKUs with "(English) " / "(French) " etc. Strip a leading
            // parenthetical so language variants collapse to one base name (and so the re-release
            // filter can match NSO classics against the retro catalog).
            .replace(Regex("^\\s*\\([^)]*\\)\\s*"), "")
            .trim()
        return cleaned.ifBlank { raw.replace("™", "").replace("®", "").trim() }
    }

    private fun todayStamp(): Int {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.YEAR) * 10000 +
            (c.get(java.util.Calendar.MONTH) + 1) * 100 +
            c.get(java.util.Calendar.DAY_OF_MONTH)
    }

    @Serializable
    private data class Cache(val games: List<Entry>)

    @Serializable
    private data class Entry(
        val id: String,
        val name: String,
        val icon: String? = null,
        val publisher: String? = null,
        val description: String? = null,
        val releaseDate: Int? = null,
        val players: Int? = null,
        val genres: List<String> = emptyList(),
        val screenshots: List<String> = emptyList(),
    ) {
        // A real retail Switch title worth putting in the *searchable catalog*: a base release (IDs
        // end in 000) with eShop art, a plausible release date, an English title, and not a
        // duplicate-y re-release line or "Switch 2 Edition". Deliberately broad — no publisher or
        // description-length gate — so older and indie games (a 2019 Layton, a small puzzle game) are
        // present and findable. The strict, recent "New Arrivals" cut is isNewArrivalQuality below.
        fun isCatalogGame(): Boolean =
            id.endsWith("000", ignoreCase = true) &&
                !icon.isNullOrBlank() &&
                releaseDate != null && releaseDate in 20170101..20991231 &&
                isEnglishTitle(name) &&
                !isReReleaseLine(name) &&
                !isSwitch2Edition(name)

        fun toGame(): Game = Game(
            id = "switch:${name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}",
            title = name,
            platform = Platform.SWITCH,
            titleId = id,
            releaseYear = releaseDate?.div(10000),
            releaseDate = releaseDate,
            publisher = publisher,
            genres = genres,
            description = description,
            boxArtUrl = icon,
            heroArtUrl = icon,
            screenshots = screenshots,
            sources = listOf(
                Source(
                    id = "switch-rom:$name",
                    siteName = "Internet Archive",
                    region = "World",
                    sizeBytes = null,
                    resolveUrl = "$name (World).nsp",
                ),
            ),
        )
    }
}

// Recognizable publishers (major labels + notable indie publishers/self-publishers). Matched as a
// case-insensitive substring of titledb's publisher field. Broad on purpose — better to let a
// borderline game in than to miss a real new release — but enough to drop one-off shovelware.
private val NOTABLE_PUBLISHERS = setOf(
    "nintendo", "sega", "capcom", "square enix", "bandai namco", "konami", "atlus", "koei tecmo",
    "ubisoft", "electronic arts", "sony", "playstation", "microsoft", "xbox", "2k", "take-two",
    "rockstar", "activision", "bethesda", "warner", "disney", "gameloft", "level-5", "level5",
    "snk", "taito", "arc system", "spike chunsoft", "nis america", "nippon ichi", "marvelous",
    "xseed", "aksys", "natsume", "wayforward", "thq", "505 games", "focus", "deep silver", "plaion",
    "koch media", "devolver", "team17", "annapurna", "raw fury", "curve", "chucklefish", "paradox",
    "gearbox", "dotemu", "digital eclipse", "hamster", "inti creates", "idea factory", "compile heart",
    "kemco", "pqube", "modus", "maximum games", "merge games", "soedesco", "astragon", "humble",
    "playism", "dangen", "gungho", "nicalis", "fangamer", "limited run", "super rare", "skybound",
    "frontier", "klei", "coffee stain", "re-logic", "motion twin", "supergiant", "mojang", "innersloth",
    "hello games", "mediatonic", "playtonic", "yacht club", "tinybuild", "no more robots", "landfall",
    "daedalic", "saber", "nacon", "microids", "wired productions", "fireshine", "secret mode", "kepler",
    "finji", "team cherry", "concernedape", "cd projekt", "thatgamecompany", "house house", "neowiz",
    "wb games", "outright games", "namco", "koei", "atari", "g-mode", "d3 publisher", "spike",
)

private fun isNotablePublisher(publisher: String?): Boolean {
    if (publisher.isNullOrBlank()) return false
    val p = publisher.lowercase()
    return NOTABLE_PUBLISHERS.any { p.contains(it) }
}

// titledb's US listing carries per-language SKUs of the same game (e.g. NSO classics shipped as
// "FireRed" / "Rouge Feu" / "Rojo Fuego"). These distinctive French/Spanish/Italian/Portuguese/
// German title words almost never appear in an English game title, so they're a cheap way to keep
// only the English SKU and stop New Arrivals filling with six copies of one game.
private val NON_ENGLISH_TITLE_WORDS = setOf(
    "rouge", "feu", "feuille", "verte", "bleu", "bleue", "noir", "jaune", "édition", "argent",
    "edición", "edicion", "rojo", "fuego", "verde", "hoja", "plata", "versión", "versión",
    "edizione", "versione", "rosso", "giallo", "bianco", "argento",
    "edição", "versão", "vermelho", "preto", "prata",
    "fassung", "ausgabe", "smaragd",
)

private fun isEnglishTitle(name: String): Boolean {
    val words = name.lowercase().split(Regex("[^a-zà-ÿ0-9]+")).filter { it.isNotBlank() }
    return words.none { it in NON_ENGLISH_TITLE_WORDS }
}

// Prolific arcade/classic re-release lines (Hamster's Arcade Archives & ACA NEOGEO, Sega Ages, etc.)
// ship a new title most weeks and otherwise bury genuinely new games in "New Arrivals".
private val RE_RELEASE_LINE_PREFIXES = listOf(
    "arcade archives", "aca neogeo", "arcade game series", "sega ages", "johnny turbo", "g-mode archives",
)

private fun isReReleaseLine(name: String): Boolean {
    val n = name.lowercase().trim()
    return RE_RELEASE_LINE_PREFIXES.any { n.startsWith(it) }
}

// "… – Nintendo Switch 2 Edition" / "… Switch 2 Edition" are enhanced re-releases of existing games,
// not new titles — drop them so they don't duplicate the base game in New Arrivals.
private fun isSwitch2Edition(name: String): Boolean =
    name.contains("switch 2", ignoreCase = true)
