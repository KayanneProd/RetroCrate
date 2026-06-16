package com.kayanne.retrocrate.data.download

import android.util.Log
import com.kayanne.retrocrate.data.source.ResolvedDownload
import com.kayanne.retrocrate.data.source.RomSource
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
// returning null instantly — so those fall straight to IA with no added latency. Internet Archive
// second: the fallback for whatever Vimm's doesn't have (incl. Switch), kept honest by the
// platform/size/junk guards in InternetArchiveSource + RomMatcher.
object DownloadSourceResolver {

    private const val TAG = "DownloadResolver"
    private const val VIMMS_RESOLVE_TIMEOUT_MS = 12_000L

    private val sources: List<Pair<RomSource, Long?>> = listOf(
        VimmsDownloadSource to VIMMS_RESOLVE_TIMEOUT_MS,
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
