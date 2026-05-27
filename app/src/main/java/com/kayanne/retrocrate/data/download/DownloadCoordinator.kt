package com.kayanne.retrocrate.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.kayanne.retrocrate.data.network.HttpClient
import com.kayanne.retrocrate.data.persistence.SettingsStore
import com.kayanne.retrocrate.data.source.ia.IaResolver
import com.kayanne.retrocrate.domain.model.DownloadState
import com.kayanne.retrocrate.domain.model.Game
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Request

// Singleton download orchestrator. Lives outside the activity / ViewModel lifecycle so
// downloads keep running when the user navigates away. Writes directly to the SAF tree the
// user picked in Settings (via SettingsStore).
object DownloadCoordinator {

    private const val TAG = "Download"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

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
        val storageUriString = SettingsStore.observeStorageTreeUri().first()
        if (storageUriString.isNullOrBlank()) {
            update(game.id, DownloadState.Failed("Pick a download folder in Settings first."))
            return
        }
        val storageUri = Uri.parse(storageUriString)
        val tree = DocumentFile.fromTreeUri(context, storageUri)
        if (tree == null || !tree.canWrite()) {
            update(game.id, DownloadState.Failed("The chosen download folder is no longer accessible."))
            return
        }

        update(game.id, DownloadState.InProgress(bytesDone = 0, bytesTotal = null))

        val resolved = IaResolver.resolve(game)
        if (resolved == null) {
            update(game.id, DownloadState.Failed("No download source found on Internet Archive."))
            return
        }

        val existing = tree.findFile(resolved.filename)
        existing?.delete()

        val mimeType = mimeFor(resolved.filename)
        val outFile = tree.createFile(mimeType, resolved.filename)
        if (outFile == null) {
            update(game.id, DownloadState.Failed("Couldn't create ${resolved.filename} in the chosen folder."))
            return
        }

        val request = Request.Builder()
            .url(resolved.downloadUrl)
            .header("Accept", "*/*")
            .build()

        HttpClient.get().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                outFile.delete()
                update(game.id, DownloadState.Failed("HTTP ${response.code} from Internet Archive"))
                return
            }
            val body = response.body ?: run {
                outFile.delete()
                update(game.id, DownloadState.Failed("Empty response body"))
                return
            }
            val total = body.contentLength().takeIf { it > 0 } ?: resolved.sizeBytes
            val output = context.contentResolver.openOutputStream(outFile.uri) ?: run {
                outFile.delete()
                update(game.id, DownloadState.Failed("Couldn't open output stream"))
                return
            }
            output.use { sink ->
                body.byteStream().use { src ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReported = 0L
                    while (true) {
                        val n = src.read(buf)
                        if (n == -1) break
                        sink.write(buf, 0, n)
                        done += n
                        if (done - lastReported >= 65_536L) {
                            update(game.id, DownloadState.InProgress(done, total))
                            lastReported = done
                        }
                    }
                }
            }
            update(game.id, DownloadState.Completed(outFile.uri.toString()))
            Log.i(TAG, "Downloaded ${game.title} -> ${outFile.uri}")
        }
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
