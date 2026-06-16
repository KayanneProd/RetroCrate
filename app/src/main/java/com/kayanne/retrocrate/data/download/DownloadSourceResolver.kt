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
// Order: Internet Archive first (always up, broad coverage, now strict-matching). Vimm's Lair
// second — its No-Intro-catalogued, one-game-per-page vault matches exactly, but it gates downloads
// behind a browser session, so it's time-boxed and only consulted when IA comes up empty.
object DownloadSourceResolver {

    private const val TAG = "DownloadResolver"
    private const val SLOW_SOURCE_TIMEOUT_MS = 12_000L

    private val sources: List<Pair<RomSource, Long?>> = listOf(
        InternetArchiveSource to null,
        VimmsDownloadSource to SLOW_SOURCE_TIMEOUT_MS,
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
