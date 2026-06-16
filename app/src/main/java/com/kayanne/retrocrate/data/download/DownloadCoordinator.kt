package com.kayanne.retrocrate.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.kayanne.retrocrate.data.network.HttpClient
import com.kayanne.retrocrate.data.persistence.DownloadHistoryStore
import com.kayanne.retrocrate.data.persistence.SettingsStore
import com.kayanne.retrocrate.domain.model.DownloadState
import com.kayanne.retrocrate.domain.model.Game
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit

// Singleton download orchestrator. Lives outside the activity / ViewModel lifecycle so
// downloads keep running when the user navigates away. Writes directly to the SAF tree the
// user picked in Settings (via SettingsStore).
object DownloadCoordinator {

    private const val TAG = "Download"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The shared HttpClient caps every call at 60s (fine for scraping, fatal for a multi-GB ROM).
    // Downloads get no overall call timeout — only a stall timeout: fail if the stream goes quiet
    // for a while, but never just because the transfer is large and slow.
    private val downloadClient: OkHttpClient by lazy {
        HttpClient.get().newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    // The actual file each game resolves to (the extracted ROM name) and its download size, so the
    // Downloads screen can show "what am I getting and how big is it".
    private val _info = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
    val info: StateFlow<Map<String, DownloadInfo>> = _info.asStateFlow()

    data class DownloadInfo(val filename: String, val totalBytes: Long?)

    fun stateFor(gameId: String): DownloadState =
        _downloads.value[gameId] ?: DownloadState.NotStarted

    fun startDownload(game: Game, context: Context) {
        val current = _downloads.value[game.id]
        if (current is DownloadState.InProgress || current is DownloadState.Queued) {
            Log.i(TAG, "Already downloading ${game.title}; ignoring duplicate request.")
            return
        }
        val appContext = context.applicationContext
        update(game.id, DownloadState.Queued(position = 0))
        // Start the foreground service while we're still in the foreground (user just tapped
        // Install) so the download survives the screen turning off or the app being backgrounded.
        DownloadService.start(appContext)
        scope.launch {
            try {
                runDownload(game, appContext)
            } catch (t: Throwable) {
                Log.w(TAG, "Download failed for ${game.title}", t)
                update(game.id, DownloadState.Failed(t.message ?: "Download failed"))
            }
        }
    }

    private suspend fun runDownload(game: Game, context: Context) {
        val storageUriString = SettingsStore.folderFor(game.platform)
        if (storageUriString.isNullOrBlank()) {
            update(
                game.id,
                DownloadState.Failed(
                    "Pick a ${game.platform.displayName} folder in Settings first."
                ),
            )
            return
        }
        val storageUri = Uri.parse(storageUriString)
        val tree = DocumentFile.fromTreeUri(context, storageUri)
        if (tree == null || !tree.canWrite()) {
            update(
                game.id,
                DownloadState.Failed(
                    "Your ${game.platform.displayName} folder is no longer accessible. Pick it again in Settings."
                ),
            )
            return
        }

        update(game.id, DownloadState.InProgress(bytesDone = 0, bytesTotal = null))

        val resolved = DownloadSourceResolver.resolve(game)
        if (resolved == null) {
            update(game.id, DownloadState.Failed("No download source found for ${game.title}."))
            return
        }

        val request = Request.Builder()
            .url(resolved.downloadUrl)
            .header("Accept", "*/*")
            .apply { resolved.headers.forEach { (name, value) -> header(name, value) } }
            .build()

        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                update(game.id, DownloadState.Failed("HTTP ${response.code} from ${resolved.siteName}"))
                return
            }
            val body = response.body ?: run {
                update(game.id, DownloadState.Failed("Empty response body"))
                return
            }
            // Progress tracks raw bytes pulled over the wire (the compressed total for archives).
            val total = body.contentLength().takeIf { it > 0 } ?: resolved.sizeBytes
            val counting = CountingInputStream(body.byteStream())

            // Unwrap archives on the fly so the real ROM lands in the folder, not a .tar.gz.
            val rom = ArchiveExtractor.open(resolved.filename, counting) ?: run {
                update(game.id, DownloadState.Failed("Couldn't find a ROM inside ${resolved.filename}."))
                return
            }

            _info.value = _info.value + (game.id to DownloadInfo(rom.romName, total))

            tree.findFile(rom.romName)?.delete()
            val outFile = tree.createFile(mimeFor(rom.romName), rom.romName) ?: run {
                update(game.id, DownloadState.Failed("Couldn't create ${rom.romName} in the chosen folder."))
                return
            }
            val output = context.contentResolver.openOutputStream(outFile.uri) ?: run {
                outFile.delete()
                update(game.id, DownloadState.Failed("Couldn't open output stream"))
                return
            }

            try {
                output.use { sink ->
                    rom.stream.use { src ->
                        val buf = ByteArray(64 * 1024)
                        var lastReported = 0L
                        while (true) {
                            val n = src.read(buf)
                            if (n == -1) break
                            sink.write(buf, 0, n)
                            val pulled = counting.count
                            if (pulled - lastReported >= 262_144L) {
                                update(game.id, DownloadState.InProgress(pulled, total))
                                lastReported = pulled
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                outFile.delete()
                throw t
            }
            update(game.id, DownloadState.Completed(outFile.uri.toString()))
            DownloadHistoryStore.add(
                context,
                DownloadHistoryStore.Entry(
                    gameId = game.id,
                    title = game.title,
                    platform = game.platform.name,
                    filename = rom.romName,
                    sizeBytes = total,
                    completedAt = System.currentTimeMillis(),
                ),
            )
            Log.i(TAG, "Downloaded ${game.title} -> ${outFile.uri} (${rom.romName})")
        }
    }

    // Counts raw bytes read so download progress reflects network transfer even when the stream is
    // wrapped in gzip/tar decoders that emit a different number of decompressed bytes.
    private class CountingInputStream(private val wrapped: InputStream) : InputStream() {
        @Volatile var count: Long = 0L
            private set

        override fun read(): Int {
            val b = wrapped.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = wrapped.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun available(): Int = wrapped.available()
        override fun close() = wrapped.close()
    }

    private fun update(gameId: String, state: DownloadState) {
        _downloads.value = _downloads.value + (gameId to state)
    }

    private fun mimeFor(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "zip" -> "application/zip"
            "7z" -> "application/x-7z-compressed"
            else -> "application/octet-stream"
        }
    }
}
