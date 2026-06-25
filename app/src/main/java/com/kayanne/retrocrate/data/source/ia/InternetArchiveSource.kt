package com.kayanne.retrocrate.data.source.ia

import android.util.Log
import com.kayanne.retrocrate.data.network.HttpClient
import com.kayanne.retrocrate.data.source.ResolveQuery
import com.kayanne.retrocrate.data.source.ResolvedDownload
import com.kayanne.retrocrate.data.source.RomMatcher
import com.kayanne.retrocrate.data.source.RomSource
import com.kayanne.retrocrate.domain.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

// Resolves a game to a downloadable ROM on Internet Archive. Public, no auth.
//
// Two complementary strategies, both gated on confidence so we never hand back a random file:
//  1. File match — score every file in an item against the requested game (exact No-Intro name or
//     normalized-title match). This nails multi-ROM "full set" items, returning the one right file.
//  2. Item-title trust — when an item's *title* clearly matches the game, take its largest real
//     payload even if the file is an archive (.tar.gz, .zip, .xci, .nsp). This is how big Switch /
//     disc dumps live on IA, packaged as one archive per item. Junk items (amiibo prints, mods,
//     wikis) fail the title match, so they're still rejected.
object InternetArchiveSource : RomSource {

    private const val TAG = "IaSource"
    override val siteName: String = "Internet Archive"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    // Archive wrappers a whole-game dump is commonly packaged as on IA, beyond bare ROM extensions.
    // Restricted to formats ArchiveExtractor can actually unwrap (tar/gz/zip) — returning a .rar/.7z
    // would just land a useless archive on disk, so we'd rather fall through to another source.
    private val ARCHIVE_EXTENSIONS = setOf("tar", "gz", "tgz", "zip")

    override suspend fun resolve(query: ResolveQuery): ResolvedDownload? = withContext(Dispatchers.IO) {
        // Switch dumps on IA are catalogued by Title ID, not the game name — try that first.
        query.titleId?.takeIf { it.isNotBlank() }?.let { tid ->
            resolveByTitleId(tid)?.let { return@withContext it }
        }

        val docs = search(query).filterNot { isJunkItem(it) }.take(12)
        if (docs.isEmpty()) {
            Log.w(TAG, "No IA search results for \"${query.title}\"")
            return@withContext null
        }

        val filesByDoc = HashMap<String, List<IaFile>>()
        fun filesFor(doc: IaSearchDoc) = filesByDoc.getOrPut(doc.identifier) { metadataFiles(doc.identifier) }

        // Strategy 1 (all items first): an exact / strongly-scored file that carries the requested
        // platform's extension. This is the strongest, platform-safe signal, so a real N64 .z64 in
        // any item beats a title-only archive guess from a Game Boy Color item of the same name.
        for (doc in docs) {
            val files = filesFor(doc)
            if (files.isEmpty()) continue
            RomMatcher.bestMatch(
                title = query.title,
                platform = query.platform,
                romFileName = query.romFileName,
                preferredRegion = query.preferredRegion,
                candidates = files.map { RomMatcher.Candidate(it.name, it.size?.toLongOrNull()) },
            )?.takeIf { isUsablePayload(it.filename, query.platform) }
                ?.let { return@withContext download(doc.identifier, it.filename, it.sizeBytes) }
        }

        // Strategy 2: the item's *title* clearly matches — take its main payload (how big disc/Switch
        // dumps live on IA, packaged as one archive per item). pickPayload still enforces platform.
        for (doc in docs) {
            if (doc.title == null || !RomMatcher.titlesMatch(doc.title, query.title)) continue
            val files = filesFor(doc)
            if (files.isEmpty()) continue
            pickPayload(files, query.platform)?.let {
                return@withContext download(doc.identifier, it.name, it.size?.toLongOrNull())
            }
        }
        Log.w(TAG, "No IA item confidently matched \"${query.title}\"")
        null
    }

    private fun download(identifier: String, filename: String, size: Long?): ResolvedDownload {
        val url = "https://archive.org/download/$identifier/${urlEncodePath(filename)}"
        Log.i(TAG, "Resolved -> $url")
        return ResolvedDownload(siteName = siteName, filename = filename, downloadUrl = url, sizeBytes = size)
    }

    // Largest genuine payload in an item, ignoring IA's derived sidecar files (_meta.xml, torrents,
    // checksums, etc.). A file carrying this platform's own extension always wins — so a title-matched
    // item that turns out to be the wrong console's version (its files don't fit the platform) is
    // rejected, not downloaded. Only when no native-extension file exists do we fall back to a generic
    // archive wrapper (how disc dumps ship). EXCEPT for Switch: there we require a direct
    // .nsp/.xci/.nsz/.xcz, because IA's Switch "dumps" are routinely unpacked CDN folders (.app files)
    // or Wii U versions inside a multi-GB archive with no usable ROM — streaming all of it only to
    // find nothing is far worse than honestly reporting "no source found".
    private fun pickPayload(files: List<IaFile>, platform: Platform): IaFile? {
        val platformExts = RomMatcher.extensionsFor(platform)
        val real = files.filterNot { isSidecar(it.name) }
        val native = real.filter { extensionOf(it.name) in platformExts }
        val pool = when {
            native.isNotEmpty() -> native
            platform == Platform.SWITCH -> emptyList()
            else -> real.filter {
                extensionOf(it.name) in ARCHIVE_EXTENSIONS &&
                    RomMatcher.isPlausibleSize(it.size?.toLongOrNull(), platform)
            }
        }
        return pool
            // Prefer an English/USA copy, then fall back to the largest payload.
            .sortedWith(
                compareBy<IaFile> { RomMatcher.regionRank(it.name) }
                    .thenByDescending { it.size?.toLongOrNull() ?: 0L },
            )
            .firstOrNull()
    }

    // A file we can actually use: this platform's native ROM extension, or — for non-Switch — an
    // archive ArchiveExtractor can unwrap. Switch is never an archive here (IA's Switch archives are
    // routinely unextractable .rar / unpacked CDN folders, and we can't pull an NSP out of those);
    // requiring a direct .nsp/.xci lets the chain fall through to debrid for the real file.
    private fun isUsablePayload(filename: String, platform: Platform): Boolean {
        val ext = extensionOf(filename)
        if (ext in RomMatcher.extensionsFor(platform)) return true
        if (platform == Platform.SWITCH) return false
        return ext in ARCHIVE_EXTENSIONS
    }

    private fun isSidecar(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.endsWith("_meta.xml") || lower.endsWith("_files.xml") ||
            lower.endsWith("_meta.sqlite") || lower.endsWith("_reviews.xml") ||
            lower.endsWith("_archive.torrent")
        ) return true
        return extensionOf(name) in setOf("md5", "sha1", "xml", "sqlite", "torrent")
    }

    // Internet Archive search for a game title is full of non-game items that merely mention it:
    // Thingiverse 3D prints, Garry's Mod addons, wikis, soundtracks, amiibo/figure scans, fan mods.
    // Drop them so item-title-trust can't grab, say, a "Breath of the Wild Logo" .stl.
    private val JUNK_ID_PREFIXES = listOf("thingiverse-", "gmod_", "miraheze-", "wiki-")
    private val JUNK_TITLE_WORDS = setOf(
        "logo", "amiibo", "soundtrack", "ost", "lithophane", "papercraft", "keychain", "figure",
        "figurine", "cosplay", "plush", "wallpaper", "ringtone", "sticker", "decal", "stencil",
        "bookmark", "magnet", "cursor", "wiki", "tas", "speedrun", "trailer", "unboxing", "review",
        "walkthrough", "guide", "manual", "scan", "papercraft", "diorama", "statue", "pendant",
    )

    private fun isJunkItem(doc: IaSearchDoc): Boolean {
        if (JUNK_ID_PREFIXES.any { doc.identifier.startsWith(it, ignoreCase = true) }) return true
        val title = doc.title?.lowercase() ?: return false
        val words = title.split(Regex("[^a-z0-9]+")).toSet()
        return words.any { it in JUNK_TITLE_WORDS }
    }

    // Finds the IA item whose title/identifier carries the Switch Title ID and returns its main
    // payload (the dump, usually a .zip/.nsp). Title-ID match is unambiguous, so we trust the
    // archive and let ArchiveExtractor pull the .nsp out of it.
    private fun resolveByTitleId(titleId: String): ResolvedDownload? {
        val tid = titleId.lowercase()
        for (doc in fetchDocs(titleId).take(8)) {
            if (tid !in alnum(doc.identifier) && tid !in alnum(doc.title ?: "")) continue
            val files = metadataFiles(doc.identifier)
            val file = pickTitleIdPayload(files, tid) ?: continue
            val url = "https://archive.org/download/${doc.identifier}/${urlEncodePath(file.name)}"
            Log.i(TAG, "Resolved Title ID $titleId -> $url")
            return ResolvedDownload(siteName, file.name, url, file.size?.toLongOrNull())
        }
        return null
    }

    // Largest payload that carries the Title ID (so we get the right game, not a DLC/other title in
    // a shared item), falling back to the largest payload overall.
    private fun pickTitleIdPayload(files: List<IaFile>, tid: String): IaFile? {
        // A direct Switch ROM, or an extractable archive wrapping one — never a .rar/.7z we can't open.
        val switchExts = RomMatcher.extensionsFor(Platform.SWITCH)
        val candidates = files.filter {
            !isSidecar(it.name) &&
                (extensionOf(it.name) in switchExts || extensionOf(it.name) in ARCHIVE_EXTENSIONS)
        }
        val withTid = candidates.filter { tid in alnum(it.name) }
        return withTid.ifEmpty { candidates }.maxByOrNull { it.size?.toLongOrNull() ?: 0L }
    }

    private fun alnum(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun search(query: ResolveQuery): List<IaSearchDoc> {
        val titleClause = "title:(${quote(query.title)})"
        val fileClause = query.romFileName?.let { "title:(${quote(baseName(it))})" }
        val q = buildString {
            append("mediatype:(software OR data) AND (")
            append(titleClause)
            if (fileClause != null && fileClause != titleClause) append(" OR ").append(fileClause)
            append(")")
        }
        return fetchDocs(q)
    }

    private fun fetchDocs(q: String): List<IaSearchDoc> {
        val url = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fl[]", "identifier")
            .addQueryParameter("fl[]", "title")
            .addQueryParameter("rows", "25")
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
                    val obj = element.jsonObject
                    val id = obj["identifier"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val title = when (val t = obj["title"]) {
                        is JsonPrimitive -> t.contentOrNull
                        is JsonArray -> t.firstOrNull()?.jsonPrimitive?.contentOrNull
                        else -> null
                    }
                    IaSearchDoc(id, title)
                }
            }
        }.getOrElse {
            Log.w(TAG, "IA search failed for query \"$q\"", it)
            emptyList()
        }
    }

    private fun metadataFiles(identifier: String): List<IaFile> {
        val request = Request.Builder()
            .url("https://archive.org/metadata/$identifier")
            .header("Accept", "application/json")
            .build()
        return runCatching {
            HttpClient.get().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching emptyList()
                val body = response.body?.string() ?: return@runCatching emptyList()
                json.decodeFromString<IaMetadata>(body).files
            }
        }.getOrElse {
            Log.w(TAG, "IA metadata fetch failed for $identifier", it)
            emptyList()
        }
    }

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase().trim()

    private fun quote(s: String): String = "\"${s.replace("\"", "")}\""

    private fun baseName(romFileName: String): String =
        romFileName.substringBeforeLast('.').replace(Regex("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]"), "").trim()

    private fun urlEncodePath(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

private data class IaSearchDoc(val identifier: String, val title: String? = null)

@Serializable
private data class IaMetadata(val files: List<IaFile> = emptyList())

@Serializable
private data class IaFile(
    val name: String,
    val size: String? = null,
    val format: String? = null,
)
