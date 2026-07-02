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

    // Turn a plain file-host link (1fichier, mega, …) into a direct HTTPS link — the same unrestrict
    // endpoints the torrent path already uses, just fed a hoster URL instead of a torrent-internal one.
    // This is the DDL path (a link captured from a site behind its ad-shortener). Null if this service
    // can't unlock that host or isn't configured.
    suspend fun unlockHosterLink(url: String, settings: DebridSettings): ResolvedDownload?
}

// One file inside a torrent. `ref` is provider-specific: a direct URL for Premiumize, the RD file id
// for Real-Debrid.
internal data class DebridFile(val name: String, val sizeBytes: Long?, val ref: String)

// Last-resort filename when a debrid unlock doesn't hand one back. The coordinator still prefers the
// server's Content-Disposition, so this only seeds the temp name.
internal fun fileNameFromUrl(url: String): String =
    url.substringBefore('?').substringBefore('#').substringAfterLast('/').ifBlank { "download.bin" }

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
