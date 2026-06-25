package com.kayanne.retrocrate.data.source.debrid

import android.util.Log
import com.kayanne.retrocrate.data.persistence.SettingsStore
import com.kayanne.retrocrate.data.source.ResolveQuery
import com.kayanne.retrocrate.data.source.ResolvedDownload
import com.kayanne.retrocrate.data.source.RomSource
import com.kayanne.retrocrate.domain.model.Platform

// "Click a game, find the torrents, unlock the best one" — the Diggz/Kodi-style path. Searches public
// torrent indexers, ranks the results against the requested game, and asks the user's configured
// debrid service(s) for a direct link to the best instantly-available one. Entirely off unless a
// debrid key is set (returns null immediately, so it adds no latency to the chain otherwise).
//
// Correctness leans on RomMatcher at the file level (pickDebridFile), so even a multi-game pack can't
// hand back the wrong game or wrong console — the same guarantee the other sources use.
object DebridRomSource : RomSource {

    private const val TAG = "Debrid"
    private const val MAX_CANDIDATES = 5
    private const val TITLE_MATCH_THRESHOLD = 0.8

    private val indexers: List<TorrentIndexer> = listOf(ApibayIndexer, NyaaIndexer)

    // Premiumize first (cache-instant, single request); Real-Debrid second (adds + polls). Both
    // honoured if configured.
    private val allServices: List<DebridService> = listOf(PremiumizeService, RealDebridService)

    override val siteName = "Debrid"

    override suspend fun resolve(query: ResolveQuery): ResolvedDownload? {
        val settings = SettingsStore.debrid.value
        if (!settings.anyConfigured) return null

        val services = allServices.filter { it.isConfigured(settings) }
        if (services.isEmpty()) return null

        val candidates = gatherCandidates(query)
        if (candidates.isEmpty()) {
            Log.i(TAG, "No torrent candidates for \"${query.title}\" (not on the indexers)")
            return null
        }
        Log.i(TAG, "Found ${candidates.size} candidate(s) for \"${query.title}\", checking ${services.size} debrid service(s)")

        for (service in services) {
            for (candidate in candidates) {
                val resolved = runCatching { service.resolve(candidate, query, settings) }.getOrNull()
                if (resolved != null) {
                    Log.i(TAG, "Resolved \"${query.title}\" via ${service.name}: ${candidate.name}")
                    return resolved
                }
            }
        }
        Log.i(TAG, "No cached torrent for \"${query.title}\" across ${services.size} service(s)")
        return null
    }

    // Slow fallback used by DownloadCoordinator only when the fast (cached) chain found nothing:
    // ask a configured debrid service to fetch the best-seeded matching torrent server-side, then
    // resolve the finished file. Reports prep progress so the UI can show "Preparing…".
    suspend fun resolveNonCached(query: ResolveQuery, onProgress: (Float?) -> Unit): ResolvedDownload? {
        val settings = SettingsStore.debrid.value
        val services = allServices.filter { it.isConfigured(settings) }
        if (services.isEmpty()) return null

        val candidate = gatherCandidates(query).maxByOrNull { it.seeders } ?: run {
            Log.i(TAG, "No torrent to fetch server-side for \"${query.title}\"")
            return null
        }
        for (service in services) {
            val resolved = runCatching {
                service.resolveNonCached(candidate, query, settings, onProgress)
            }.getOrElse {
                Log.w(TAG, "${service.name} non-cached failed for \"${query.title}\"", it)
                null
            }
            if (resolved != null) {
                Log.i(TAG, "Fetched \"${query.title}\" via ${service.name}: ${candidate.name}")
                return resolved
            }
        }
        return null
    }

    private suspend fun gatherCandidates(query: ResolveQuery): List<TorrentCandidate> {
        val seen = HashSet<String>()
        val all = mutableListOf<TorrentCandidate>()
        for (indexer in indexers) {
            for (term in searchTerms(query)) {
                val results = runCatching { indexer.search(term) }.getOrElse { emptyList() }
                for (c in results) if (seen.add(c.infoHash.lowercase())) all.add(c)
            }
        }
        return all
            .filter { it.seeders > 0 && matchesGame(it, query) }
            .sortedWith(
                compareByDescending<TorrentCandidate> { titleScore(it.name, query.title) }
                    .thenByDescending { it.seeders },
            )
            .take(MAX_CANDIDATES)
    }

    // Several terms to maximize recall: the bare title surfaces "[Switch NSP] X" / "X [titleid].xci"
    // torrents, "+ switch" narrows for common-word titles, and the simplified core title (subtitle /
    // edition stripped) catches the verbose names titledb uses. The Switch-hint filter in matchesGame
    // keeps non-Switch junk (PC scene releases, the DS version, etc.) out regardless of search term.
    private fun searchTerms(query: ResolveQuery): List<String> = when (query.platform) {
        Platform.SWITCH -> listOf(query.title, "${query.title} switch", coreTitle(query.title)).distinct()
        else -> listOf("${query.title} ${query.platform.displayName}", query.title).distinct()
    }

    private fun matchesGame(candidate: TorrentCandidate, query: ResolveQuery): Boolean {
        if (titleScore(candidate.name, coreTitle(query.title)) < TITLE_MATCH_THRESHOLD) return false
        if (query.platform == Platform.SWITCH) {
            val n = candidate.name.lowercase()
            return SWITCH_HINTS.any { it in n }
        }
        return true
    }

    // The matchable core of a title: parentheticals removed, and the subtitle after a ":"/" - "
    // dropped only when the prefix is itself substantial (so "Layton's Mystery Journey: Katrielle…"
    // → "Layton's Mystery Journey", but "Pokémon: Let's Go" keeps its distinguishing subtitle).
    private fun coreTitle(title: String): String {
        val noParens = title.replace(Regex("\\([^)]*\\)"), " ").trim()
        val prefix = noParens.split(Regex(":| - ")).first().trim()
        return if (tokenize(prefix).size >= 2) prefix else noParens
    }

    // Fraction of the game's title words present in the torrent name — keeps "Mario Kart 8" from
    // matching a "Mario Party" torrent while tolerating extra tags (region, format, "Switch", …).
    private fun titleScore(name: String, title: String): Double {
        val nameTokens = tokenize(name)
        val titleTokens = tokenize(title)
        if (titleTokens.isEmpty()) return 0.0
        return titleTokens.count { it in nameTokens }.toDouble() / titleTokens.size
    }

    private fun tokenize(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet()

    private val SWITCH_HINTS = listOf("switch", "nsp", "xci", "nsz")
}
