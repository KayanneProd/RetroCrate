package com.kayanne.retrocrate.data.source.debrid

import com.kayanne.retrocrate.data.persistence.DebridSettings
import com.kayanne.retrocrate.data.source.ResolveQuery
import com.kayanne.retrocrate.data.source.ResolvedDownload
import com.kayanne.retrocrate.data.source.RomMatcher

// A debrid provider (Premiumize, Real-Debrid) that turns a cached torrent into a direct HTTPS link
// the normal download pipeline can fetch. Returns null when the user hasn't configured it or the
// torrent isn't instantly available — the resolver chain then moves on.
interface DebridService {
    val name: String
    fun isConfigured(settings: DebridSettings): Boolean

    // Fast path: resolve only if the torrent is already cached on the service.
    suspend fun resolve(
        candidate: TorrentCandidate,
        query: ResolveQuery,
        settings: DebridSettings,
    ): ResolvedDownload?

    // Slow path: have the service fetch the (not-yet-cached) torrent server-side, reporting progress
    // (0..1, or null when unknown), then resolve the finished file. Minutes, not seconds.
    suspend fun resolveNonCached(
        candidate: TorrentCandidate,
        query: ResolveQuery,
        settings: DebridSettings,
        onProgress: (Float?) -> Unit,
    ): ResolvedDownload?
}

// One file inside a torrent. `ref` is provider-specific: a direct URL for Premiumize, the RD file id
// for Real-Debrid.
internal data class DebridFile(val name: String, val sizeBytes: Long?, val ref: String)

// Picks the correct file inside a (possibly multi-file) torrent, reusing RomMatcher's platform /
// title / size guarantees so a multi-game pack can't hand back the wrong game or wrong console. Falls
// back to the single platform-typed file when the torrent already title-matched and holds just one.
internal fun pickDebridFile(files: List<DebridFile>, query: ResolveQuery): DebridFile? {
    if (files.isEmpty()) return null
    val candidates = files.map { RomMatcher.Candidate(it.name, it.sizeBytes) }
    val best = RomMatcher.bestMatch(
        query.title, query.platform, query.romFileName, query.preferredRegion, candidates,
    )
    if (best != null) {
        return files.first { it.name == best.filename && it.sizeBytes == best.sizeBytes }
    }
    val exts = RomMatcher.extensionsFor(query.platform)
    val native = files.filter { it.name.substringAfterLast('.', "").lowercase() in exts }
    return native.maxByOrNull { it.sizeBytes ?: 0L }
}
