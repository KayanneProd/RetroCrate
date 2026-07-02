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
import org.jsoup.nodes.Element

// NXBrew — a Switch DDL index. Its per-game pages list host-labelled download links, but each href is
// an ouo.io ad-shortener, not a direct file-host URL (and debrid unlocks hosts, not shorteners). So
// NXBrew is offered only through the **source picker** (never the Auto chain): the candidate carries
// the ouo.io URL in [DownloadCandidate.unlockUrl], the UI opens it in a WebView for one human ad-tap,
// captures the file-host link it redirects to, and that gets unlocked via debrid. This keeps the
// no-phone-home rule intact (a human clears the gate, not a bot).
//
// Selectors here key on stable signals (the `ouo.io` href, the host keyword in the link text, the
// `/<slug>/` game-page shape) rather than brittle CSS classes — but the live site still drifts, so
// failures log loudly for on-device hardening (the way the Vimm's parser was fixed). Switch-only.
object NxbrewSource : RomSource {

    private const val TAG = "Nxbrew"
    private const val BASE = "https://nxbrew.net"

    override val siteName = "NXBrew (DDL)"

    // Needs the WebView ad-tap, which the headless Auto chain can't do — picker only.
    override suspend fun resolve(query: ResolveQuery): ResolvedDownload? = null

    override suspend fun listCandidates(query: ResolveQuery): List<DownloadCandidate> = withContext(Dispatchers.IO) {
        if (query.platform != Platform.SWITCH) return@withContext emptyList()
        val page = findGamePage(query.title) ?: run {
            Log.i(TAG, "No NXBrew page matched \"${query.title}\"")
            return@withContext emptyList()
        }
        val doc = fetchDoc(page) ?: return@withContext emptyList()
        val links = hostLinks(doc)
        if (links.isEmpty()) Log.w(TAG, "NXBrew page $page had no recognizable host links")
        links.map { (host, ouoUrl) ->
            DownloadCandidate(
                sourceName = siteName,
                label = "$host — ${query.title}",
                extra = "opens one ad page, then unlocks via debrid",
                unlockUrl = ouoUrl,
                resolve = { null },
            )
        }
    }

    // WordPress search → the game page whose result title best covers the requested title.
    private fun findGamePage(title: String): String? {
        val url = "$BASE/".toHttpUrl().newBuilder().addQueryParameter("s", title).build().toString()
        val doc = fetchDoc(url) ?: return null
        // Select all anchors and filter in code (a Jsoup [href^=…] selector on a URL with `://` is a
        // parse-edge case); keep only same-host game-page links.
        val candidates = doc.select("a[href]")
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
        Log.i(TAG, "NXBrew search \"$title\": ${candidates.size} game-page candidate(s)")
        val hit = candidates.firstOrNull { titleCovered(it.second, title) }
        if (hit == null && candidates.isNotEmpty()) {
            Log.w(TAG, "No title match for \"$title\" among: ${candidates.take(6).joinToString { "\"${it.second}\"" }}")
        }
        return hit?.first
    }

    // Host-labelled ouo.io links on the game page, deduped by host, 1Fichier first (most debrid-ready).
    private fun hostLinks(doc: Document): List<Pair<String, String>> {
        val byHost = LinkedHashMap<String, String>()
        for (a in doc.select("a[href*=ouo.io]")) {
            val ouo = a.absUrl("href").ifBlank { a.attr("href") }
            if (ouo.isBlank()) continue
            val host = HosterLinks.hostLabel(a.text()) ?: HosterLinks.hostLabel(nearbyText(a)) ?: continue
            byHost.putIfAbsent(host, ouo)
        }
        return byHost.entries
            .sortedBy { if (it.key == "1Fichier") 0 else 1 }
            .map { it.key to it.value }
    }

    private fun nearbyText(a: Element): String =
        a.parent()?.text().orEmpty() + " " + a.previousElementSibling()?.text().orEmpty()

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

    private fun titleCovered(resultTitle: String, queryTitle: String): Boolean {
        val q = tokenize(stripNoise(queryTitle))
        if (q.isEmpty()) return false
        val r = tokenize(resultTitle)
        return q.count { it in r }.toDouble() / q.size >= 0.85
    }

    private fun stripNoise(s: String): String = s.replace(Regex("\\([^)]*\\)"), " ")

    private fun tokenize(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet()

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
