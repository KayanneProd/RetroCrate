package com.kayanne.retrocrate.data.download

import android.util.Log
import com.kayanne.retrocrate.data.source.ResolvedDownload
import com.kayanne.retrocrate.data.source.RomSource
import com.kayanne.retrocrate.data.source.debrid.DebridRomSource
import com.kayanne.retrocrate.data.source.ia.InternetArchiveSource
import com.kayanne.retrocrate.data.source.vimms.VimmsDownloadSource
import com.kayanne.retrocrate.data.source.toResolveQuery
import com.kayanne.retrocrate.domain.model.Game
import kotlinx.coroutines.withTimeoutOrNull

// Tries each ROM source in priority order and returns the first confident match. A source that
// throws, times out, or simply has nothing is skipped — so adding a flaky source can only help,
// never break the working path.
//
// Order: Vimm's Lair first. Its vault is platform-scoped and No-Intro/Redump-catalogued, one game
// per page, so it structurally can't return the wrong platform or a grab-bag mismatch (the whole
// class of bugs that plague IA's cross-platform search). It's time-boxed so a slow/hung resolve
// can't stall the chain, and it self-skips platforms it doesn't vault (Switch, Wii U, 3DS, Vita) by
// returning null instantly — so those fall straight to the next source with no added latency.
//
// Debrid second: a Diggz-style "find the torrents, unlock the best" source, off unless the user has
// configured Premiumize/Real-Debrid (returns null instantly otherwise). This is what finally makes
// Switch reliable — Vimm's doesn't vault it and IA is hit-or-miss — while staying behind Vimm's for
// the retro platforms Vimm's already nails. Time-boxed since it does a search + debrid round-trip.
//
// Internet Archive last: the always-on fallback for whatever the first two don't resolve, kept honest
// by the platform/size/junk guards in InternetArchiveSource + RomMatcher.
object DownloadSourceResolver {

    private const val TAG = "DownloadResolver"
    private const val VIMMS_RESOLVE_TIMEOUT_MS = 12_000L
    private const val DEBRID_RESOLVE_TIMEOUT_MS = 30_000L

    private val sources: List<Pair<RomSource, Long?>> = listOf(
        VimmsDownloadSource to VIMMS_RESOLVE_TIMEOUT_MS,
        DebridRomSource to DEBRID_RESOLVE_TIMEOUT_MS,
        InternetArchiveSource to null,
    )

    suspend fun resolve(game: Game): ResolvedDownload? {
        val query = game.toResolveQuery()
        for ((source, timeoutMs) in sources) {
            val result = runCatching {
                if (timeoutMs == null) source.resolve(query)
                else withTimeoutOrNull(timeoutMs) { source.resolve(query) }
            }.getOrElse {
                Log.w(TAG, "${source.siteName} failed for \"${game.title}\"", it)
                null
            }
            if (result != null) {
                Log.i(TAG, "Resolved \"${game.title}\" via ${result.siteName}")
                return result
            }
        }
        Log.w(TAG, "No source could resolve \"${game.title}\"")
        return null
    }
}
