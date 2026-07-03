package com.kayanne.retrocrate.data.source.ddl

import android.util.Log
import com.kayanne.retrocrate.data.network.HttpClient
import com.kayanne.retrocrate.data.source.DownloadCandidate
import com.kayanne.retrocrate.data.source.ResolveQuery
import com.kayanne.retrocrate.data.source.ResolvedDownload
import com.kayanne.retrocrate.data.source.RomSource
import com.kayanne.retrocrate.domain.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// NXBrew — a Switch DDL index. Its per-game pages list host-labelled download links (Base / Update /
// DLC), but each href is an ouo.io ad-shortener, not a direct file-host URL (and debrid unlocks hosts,
// not shorteners). So NXBrew is offered only through the **source picker** (never the Auto chain) and
// drives its own two-step flow: [searchGames] returns the matching game pages (the picker disambiguates
// when there's more than one — base vs. sequel vs. edition), then [loadFiles] returns that page's
// section-tagged links. Picking a link opens it in a WebView for one human ad-tap; the app captures the
// file-host link it redirects to and unlocks it via debrid. A human clears the gate, not a bot — the
// no-phone-home rule holds.
//
// Selectors here key on stable signals (the `ouo.io` href, the host keyword in the link text, the
// `/<slug>/` game-page shape) rather than brittle CSS classes — but the live site still drifts, so
// failures log loudly for on-device hardening (the way the Vimm's parser was fixed). Switch-only.
object NxbrewSource : RomSource {

    private const val TAG = "Nxbrew"
    private const val BASE = "https://nxbrew.net"

    override val siteName = "NXBrew (DDL)"

    private const val MAX_GAME_RESULTS = 8

    // Needs the WebView ad-tap, which the headless Auto chain can't do — picker only.
    override suspend fun resolve(query: ResolveQuery): ResolvedDownload? = null

    // Kept for the generic source-picker contract, but the detail flow drives NXBrew through
    // [searchGames] + [loadFiles] instead so it can disambiguate the game and split Base/Update/DLC.
    override suspend fun listCandidates(query: ResolveQuery): List<DownloadCandidate> = emptyList()

    // NXBrew search → every plausible game page, confident (real title match) ones first. Returning
    // several lets the UI disambiguate: "Kill It With Fire" and "Kill It With Fire 2" both surface, so
    // the user picks the one they mean instead of the resolver guessing (and guessing the sequel).
    suspend fun searchGames(title: String): List<NxbrewGame> = withContext(Dispatchers.IO) {
        val url = "$BASE/".toHttpUrl().newBuilder().addQueryParameter("s", searchTerm(title)).build().toString()
        val doc = fetchDoc(url) ?: return@withContext emptyList()
        // Select all anchors and filter in code (a Jsoup [href^=…] selector on a URL with `://` is a
        // parse-edge case); keep only same-host game-page links.
        val results = doc.select("a[href]")
            .mapNotNull { a ->
                val href = a.absUrl("href").substringBefore('#').substringBefore('?')
                val text = a.text().trim()
                if (text.isBlank() || !href.startsWith(BASE) || !isGamePagePath(href)) null else href to text
            }
            // A search result links its game page from several anchors — a thumbnail (no text) and a
            // comment-count "0" (the `#respond` link, which collapses onto the game href once the
            // fragment is stripped) both precede the title link. Keep the richest text per href so we
            // match against the actual title, not the "0" comment link that happens to come first.
            .groupBy({ it.first }, { it.second })
            .map { (href, texts) -> href to texts.maxByOrNull { it.length }.orEmpty() }
        Log.i(TAG, "NXBrew search \"$title\": ${results.size} game-page candidate(s)")
        val games = results
            .map { (href, resultTitle) ->
                Triple(href, resultTitle, coverage(resultTitle, title))
            }
            // Drop clearly-unrelated results, but keep partial matches so the user can still pick if our
            // strict matcher missed the real title (the disambiguation fallback the user asked for).
            .filter { it.third >= 0.5 }
            .sortedByDescending { it.third }
            .take(MAX_GAME_RESULTS)
            .map { (href, resultTitle, _) ->
                NxbrewGame(title = resultTitle, pageUrl = href, confident = titleCovered(resultTitle, title))
            }
        if (games.none { it.confident } && games.isNotEmpty()) {
            Log.w(TAG, "No confident title match for \"$title\" among: ${games.take(6).joinToString { "\"${it.title}\"" }}")
        }
        games
    }

    // NXBrew's WordPress search chokes on punctuation: searching the titledb title verbatim —
    // "Kill It With Fire! 2" — returns nothing (the "!" breaks it), while "Kill It With Fire 2" finds
    // the game. Reduce the query to letters/digits/spaces (keeping accented letters, so "Pokémon"
    // survives) so the search actually matches. Matching against the raw title still happens later.
    internal fun searchTerm(title: String): String =
        title.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().ifBlank { title }

    // A game page's download links: every Base / Update / DLC variant, per host, USA region preferred.
    suspend fun loadFiles(pageUrl: String): List<NxbrewFile> = withContext(Dispatchers.IO) {
        val doc = fetchDoc(pageUrl) ?: return@withContext emptyList()
        val files = parseFiles(doc)
        if (files.isEmpty()) Log.w(TAG, "NXBrew page $pageUrl had no recognizable host links")
        else Log.i(TAG, "NXBrew page $pageUrl: ${files.size} link(s) across ${files.map { it.variantLabel }.distinct().size} variant(s); regions=${files.mapNotNull { it.region }.distinct()}")
        files
    }

    // NXBrew's download area is a flat run of <p>s: region headers ("Region USA [titleid]"), variant
    // headers ("Base Game NSP (443 MB)", "Update v1.0.2 (v131072)", "DLC Pack (5 DLCs)"), and link rows.
    // A link row is either one host per <p> (host in a <strong>, anchor text "Download") or several
    // comma-separated hosts in one <p> (host = anchor text). So we walk the <p>s in document order,
    // tracking the current region + variant, and emit a file per (region, variant, host). Tracking the
    // full variant label is what keeps three different update versions from collapsing into one.
    internal fun parseFiles(doc: Document): List<NxbrewFile> {
        var region: String? = null
        var section: NxbrewSection? = null
        var variantLabel: String? = null
        var variantIndex = 0
        val seen = LinkedHashSet<String>() // "region|variantLabel|host" — one link per host per variant
        val out = ArrayList<IndexedFile>()

        // Scope to the post body so a sidebar / related-post / comment link can't be mis-attributed to
        // the current section.
        val content = doc.selectFirst("article") ?: doc.body() ?: doc
        for (p in content.select("p, h2, h3, h4")) {
            val anchors = p.select("a[href*=ouo.io]")
            if (anchors.isEmpty()) {
                // A header row: a region header (can precede a Base header) or a variant header.
                val text = p.ownText().ifBlank { p.text() }.trim()
                val r = regionFrom(text)
                if (r != null) {
                    region = r
                } else {
                    sectionOf(text)?.let { sec ->
                        section = sec
                        variantLabel = text
                        variantIndex++
                    }
                }
                continue
            }
            val sec = section ?: continue
            val label = variantLabel ?: continue
            val strongHost = HosterLinks.hostLabel(p.selectFirst("strong")?.text().orEmpty())
            for (a in anchors) {
                val ouo = a.absUrl("href").ifBlank { a.attr("href") }
                if (ouo.isBlank()) continue
                val host = HosterLinks.hostLabel(a.text()) ?: strongHost ?: continue
                if (!seen.add("$region|$label|$host")) continue
                out.add(IndexedFile(variantIndex, NxbrewFile(sec, label, region, host, ouo)))
            }
        }

        // "Always go for USA": if any USA variant exists, keep only USA; otherwise keep every region.
        val usable = if (out.any { it.file.region == "USA" }) out.filter { it.file.region == "USA" } else out
        return usable
            .sortedWith(
                compareBy<IndexedFile> { it.file.section.ordinal }
                    .thenBy { it.variantIndex }
                    .thenBy { if (it.file.host == "1Fichier") 0 else 1 }
                    .thenBy { it.file.host },
            )
            .map { it.file }
    }

    private data class IndexedFile(val variantIndex: Int, val file: NxbrewFile)

    // The region a header names ("Region USA [0100…]" → "USA"), or null if it isn't a region header.
    private fun regionFrom(text: String): String? {
        val t = text.lowercase()
        if ("region" !in t) return null
        return when {
            "usa" in t || "america" in t || "ntsc-u" in t -> "USA"
            "europe" in t || "eur" in t || "pal" in t -> "Europe"
            "japan" in t || "jpn" in t -> "Japan"
            "asia" in t -> "Asia"
            "world" in t -> "World"
            "korea" in t -> "Korea"
            else -> "Other"
        }
    }

    // The section a variant header names, or null if the text isn't a variant header. Length-capped so a
    // prose paragraph mentioning "update"/"dlc" isn't mistaken for one. DLC before Update ("DLC Update"
    // is DLC); a header with none of these but starting "Base Game" is the base release.
    private fun sectionOf(text: String): NxbrewSection? {
        val t = text.trim().lowercase()
        if (t.isBlank() || t.length > 64) return null
        return when {
            "dlc" in t -> NxbrewSection.DLC
            "update" in t -> NxbrewSection.UPDATE
            "base game" in t || t == "base" -> NxbrewSection.BASE
            else -> null
        }
    }

    // Game pages are a single top-level hyphenated slug (`/cuphead-switch-nsp-eshop/`); WordPress
    // structural paths (categories, tags, pagination) are excluded.
    private fun isGamePagePath(url: String): Boolean {
        val path = url.removePrefix(BASE).trim('/')
        if (path.isBlank() || '/' in path) return false
        if (path in NON_GAME_SEGMENTS) return false
        return '-' in path
    }

    private val NON_GAME_SEGMENTS = setOf(
        "switch-games", "category", "tag", "page", "author", "feed", "comments",
        "about", "contact", "privacy-policy", "dmca", "wp-login.php",
    )

    // A result title matches only when the query's words are covered AND the sequel signatures agree.
    // The coverage test alone let "Kill It With Fire" match "Kill It With Fire 2" — the tokenizer drops
    // the lone "2", so every word of the base title is present in the sequel's. Comparing the numeric /
    // roman-numeral markers (which the tokenizer discards) rejects that: {} vs {2} disagree.
    internal fun titleCovered(resultTitle: String, queryTitle: String): Boolean {
        if (coverage(resultTitle, queryTitle) < 0.85) return false
        return sequelMarkers(queryTitle) == sequelMarkers(resultTitle)
    }

    // Fraction of the query's words present in the result title (ignoring format noise), used both to
    // gate confident matches and to rank the disambiguation list.
    private fun coverage(resultTitle: String, queryTitle: String): Double {
        val q = tokenize(stripNoise(queryTitle))
        if (q.isEmpty()) return 0.0
        val r = tokenize(resultTitle)
        return q.count { it in r }.toDouble() / q.size
    }

    private fun stripNoise(s: String): String = s.replace(Regex("\\([^)]*\\)"), " ")

    private fun tokenize(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet()

    // The set of sequel numbers a title carries (a bare title implies none). Arabic 1..99 and a
    // conservative set of unambiguous roman numerals — deliberately excludes solo "v"/"x" (version
    // markers / the letter X in names). Used to keep a base game apart from its numbered sequels.
    //
    // Only the part of the title *before* the first format/section word ("Switch", "NSP", "Update",
    // "+", …) is scanned: a sequel number sits in the name ("Kill It With Fire 2 Switch NSP"), whereas
    // a version like "…Switch NSP + Update v1.0.1" comes after — counting its digits would wrongly
    // flag a base game as a mismatch.
    private fun sequelMarkers(title: String): Set<Int> {
        val core = MARKER_CUT.split(stripNoise(title)).firstOrNull().orEmpty()
        return core.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .mapNotNull { token -> token.toIntOrNull()?.takeIf { it in 1..99 } ?: ROMAN_NUMERALS[token] }
            .toSet()
    }

    private val MARKER_CUT = Regex(
        "\\b(switch|nsp|nsz|xci|xcz|eshop|update|dlc|repack|rom)\\b|\\+",
        RegexOption.IGNORE_CASE,
    )

    private val ROMAN_NUMERALS = mapOf(
        "ii" to 2, "iii" to 3, "iv" to 4, "vi" to 6, "vii" to 7, "viii" to 8,
        "ix" to 9, "xi" to 11, "xii" to 12, "xiii" to 13,
    )

    private fun fetchDoc(url: String): Document? = runCatching {
        val request = Request.Builder().url(url).header("Accept", "text/html").build()
        HttpClient.get().newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful || body == null) {
                Log.w(TAG, "fetch $url -> HTTP ${resp.code} (${resp.header("Server")}), ${body?.length ?: 0} chars")
                return@runCatching null
            }
            Log.i(TAG, "fetch $url -> HTTP ${resp.code}, ${body.length} chars")
            Jsoup.parse(body, url)
        }
    }.getOrElse {
        Log.w(TAG, "fetch failed: $url", it)
        null
    }
}
