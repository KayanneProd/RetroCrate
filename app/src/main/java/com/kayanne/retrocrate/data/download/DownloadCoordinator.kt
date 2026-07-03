package com.kayanne.retrocrate.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.kayanne.retrocrate.data.network.HttpClient
import com.kayanne.retrocrate.data.persistence.DownloadHistoryStore
import com.kayanne.retrocrate.data.persistence.SettingsStore
import com.kayanne.retrocrate.data.source.DownloadCandidate
import com.kayanne.retrocrate.data.source.debrid.DebridRomSource
import com.kayanne.retrocrate.data.source.toResolveQuery
import com.kayanne.retrocrate.domain.model.DownloadState
import com.kayanne.retrocrate.domain.model.Game
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.kayanne.retrocrate.domain.model.Platform
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.ISeekableStream
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.simple.ISimpleInArchiveItem
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
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

    // Per-game handles so a download can be stopped: the coroutine (cancels the resolve/suspend work)
    // and the in-flight OkHttp call (cancels the blocking transfer read, which a coroutine cancel alone
    // can't interrupt). `cancelling` marks a stop in progress so the coroutine's own error/progress
    // updates are suppressed and the cancel sets the final state.
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, Call>()
    private val cancelling = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun stateFor(gameId: String): DownloadState =
        _downloads.value[gameId] ?: DownloadState.NotStarted

    // Stop an active download: interrupt the transfer + resolve, drop it from the active list (so the UI
    // shows Install again), and let the coroutine's finally clean up any partial file / temp archive.
    fun cancel(gameId: String) {
        if (_downloads.value[gameId] == null) return
        Log.i(TAG, "Cancelling download $gameId")
        cancelling.add(gameId)
        runCatching { calls.remove(gameId)?.cancel() }
        jobs.remove(gameId)?.cancel()
        _downloads.value = _downloads.value - gameId
        _info.value = _info.value - gameId
    }

    // Auto: resolve the best source via the chain (with the debrid non-cached fallback).
    fun startDownload(game: Game, context: Context) = start(game, context, candidate = null)

    // Manual: download the specific source/file the user picked in the source sheet.
    fun startDownload(game: Game, candidate: DownloadCandidate, context: Context) =
        start(game, context, candidate)

    // DDL path: a file-host link captured from a site (after its ad-shortener) — unlock it via debrid,
    // then download + extract like anything else. [subfolder] routes updates/DLC into their own folder
    // under the platform tree (base games pass null and land at the root).
    fun startDownloadFromLink(game: Game, hosterUrl: String, context: Context, subfolder: String? = null) =
        start(game, context, candidate = null, hosterLink = hosterUrl, subfolder = subfolder)

    private fun start(
        game: Game,
        context: Context,
        candidate: DownloadCandidate?,
        hosterLink: String? = null,
        subfolder: String? = null,
    ) {
        val current = _downloads.value[game.id]
        if (current is DownloadState.InProgress || current is DownloadState.Queued) {
            Log.i(TAG, "Already downloading ${game.title}; ignoring duplicate request.")
            return
        }
        val appContext = context.applicationContext
        cancelling.remove(game.id) // fresh start clears any stale stop flag from a prior cancel
        update(game.id, DownloadState.Queued(position = 0))
        // Start the foreground service while we're still in the foreground (user just tapped
        // Install) so the download survives the screen turning off or the app being backgrounded.
        DownloadService.start(appContext)
        val job = scope.launch {
            try {
                runDownload(game, appContext, candidate, hosterLink, subfolder)
            } catch (t: Throwable) {
                if (game.id in cancelling) {
                    Log.i(TAG, "Download stopped for ${game.title}")
                } else {
                    Log.w(TAG, "Download failed for ${game.title}", t)
                    update(game.id, DownloadState.Failed(t.message ?: "Download failed"))
                }
            } finally {
                jobs.remove(game.id)
                calls.remove(game.id)
            }
            // One place to fire the terminal notification: the user may have walked away (screen off,
            // another game), so tell them here rather than relying on an in-app snackbar.
            when (val terminal = _downloads.value[game.id]) {
                is DownloadState.Completed ->
                    DownloadNotifier.notifyComplete(appContext, game, _info.value[game.id]?.filename)
                is DownloadState.Failed ->
                    DownloadNotifier.notifyFailed(appContext, game, terminal.reason)
                else -> Unit
            }
        }
        jobs[game.id] = job
    }

    private suspend fun runDownload(
        game: Game,
        context: Context,
        candidate: DownloadCandidate?,
        hosterLink: String?,
        subfolder: String? = null,
    ) {
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
        val root = DocumentFile.fromTreeUri(context, storageUri)
        if (root == null || !root.canWrite()) {
            update(
                game.id,
                DownloadState.Failed(
                    "Your ${game.platform.displayName} folder is no longer accessible. Pick it again in Settings."
                ),
            )
            return
        }
        // Updates / DLC land in their own subfolder of the platform tree; base games at the root.
        val tree = if (subfolder != null) {
            (root.findFile(subfolder)?.takeIf { it.isDirectory } ?: root.createDirectory(subfolder)) ?: root
        } else {
            root
        }

        update(
            game.id,
            DownloadState.Preparing(
                when {
                    hosterLink != null -> "Unlocking link…"
                    candidate != null -> "Getting ${candidate.sourceName}…"
                    else -> "Finding source…"
                },
            ),
        )

        var resolved = when {
            // DDL: unlock the captured file-host link via the user's debrid service.
            hosterLink != null -> DebridRomSource.unlockHosterLink(hosterLink)
            // Manual pick: resolve exactly what the user chose. No chain, no fallback — if it doesn't
            // resolve, tell them, so they can pick another option.
            candidate != null -> runCatching { candidate.resolve() }.getOrNull()
                ?: run {
                    update(game.id, DownloadState.Failed("Couldn't get \"${candidate.label}\" from ${candidate.sourceName}. Try another option."))
                    return
                }
            // Auto, fast path: a directly-downloadable URL (Vimm's, a cached debrid torrent, or IA).
            else -> DownloadSourceResolver.resolve(game)
        }

        // Auto, slow path: nothing was instantly available — have debrid fetch the torrent server-side
        // (shown as "Preparing"), then download the finished file. Only does anything if the user
        // configured a debrid service and a matching torrent exists.
        if (resolved == null && candidate == null && hosterLink == null) {
            update(game.id, DownloadState.Preparing("Preparing on debrid…"))
            resolved = DebridRomSource.resolveNonCached(game.toResolveQuery()) { progress ->
                update(game.id, DownloadState.Preparing("Preparing on debrid…", progress))
            }
        }

        if (resolved == null) {
            val message = if (hosterLink != null) {
                "Couldn't unlock that link with your debrid service — it may not support the file host, or you've hit a limit."
            } else {
                "No download source found for ${game.title}."
            }
            update(game.id, DownloadState.Failed(message))
            return
        }
        Log.i(TAG, "Downloading ${game.title} from ${resolved.siteName}: ${resolved.downloadUrl}")

        update(game.id, DownloadState.InProgress(bytesDone = 0, bytesTotal = null))

        val request = Request.Builder()
            .url(resolved.downloadUrl)
            .header("Accept", "*/*")
            .apply { resolved.headers.forEach { (name, value) -> header(name, value) } }
            .build()

        val call = downloadClient.newCall(request)
        calls[game.id] = call // so cancel() can interrupt the blocking transfer read
        call.execute().use { response ->
            if (!response.isSuccessful) {
                update(game.id, DownloadState.Failed("HTTP ${response.code} from ${resolved.siteName}"))
                return
            }
            val body = response.body ?: run {
                update(game.id, DownloadState.Failed("Empty response body"))
                return
            }
            // Some hosts answer 200 with an HTML page instead of the file — Vimm's free tier serves a
            // download-limit / "please wait" notice this way, and Internet Archive a soft error page.
            // Unzipping that throws a cryptic ZipException, so detect it and fail with something the
            // user can act on (the source picker lets them just back out and try another source).
            val contentType = response.header("Content-Type").orEmpty()
            Log.i(TAG, "${resolved.siteName} responded ${response.code} $contentType (${body.contentLength()} bytes)")
            if (contentType.contains("html", ignoreCase = true)) {
                Log.w(TAG, "${resolved.siteName} returned a web page, not a file, for ${game.title}")
                update(
                    game.id,
                    DownloadState.Failed(
                        "${resolved.siteName} returned a web page, not the file — it may be rate-limiting " +
                            "downloads. Try another source.",
                    ),
                )
                return
            }
            // Progress tracks raw bytes pulled over the wire (the compressed total for archives).
            val total = body.contentLength().takeIf { it > 0 } ?: resolved.sizeBytes
            val counting = CountingInputStream(body.byteStream())

            // Trust the server's real filename for format detection — Vimm's serves larger/disc games
            // as .7z (not the .zip our resolver guesses), and the extension decides the decompressor.
            val archiveName = filenameFromContentDisposition(response.header("Content-Disposition"))
                ?: resolved.filename

            // Disc systems can ship a game as a multi-file set (a .cue + its .bin tracks) or as several
            // discs in one archive — we must extract the whole set into a per-game folder, not just the
            // first file. Cartridge systems are always one file, so they keep the fast single-file path.
            val multiFile = isDiscBased(game.platform)

            // .7z and .rar are random-access (index at the end), so they buffer to disk first, then
            // decode with the native 7-Zip engine. Native decoding is what makes this safe on a
            // handheld: a 7z can carry a very large LZMA dictionary (Vimm's ships 3DS/DS/disc games as
            // .7z with a 256 MB dictionary) that the native engine holds in native memory — the
            // pure-Java XZ decoder allocated it on the ~256 MB app heap and threw OutOfMemoryError.
            if (archiveName.endsWith(".7z", ignoreCase = true) ||
                archiveName.endsWith(".rar", ignoreCase = true)
            ) {
                extractNativeArchive(game, context, tree, archiveName, counting, total, multiFile)
                return
            }

            // Disc set in a .zip/.tar(.gz): extract every part into a folder named after the game.
            if (multiFile && ArchiveExtractor.isContainer(archiveName)) {
                val container = ArchiveExtractor.newContainerStream(archiveName, counting)
                if (container != null) {
                    container.use { ais ->
                        extractSet(
                            game, context, tree, folderName(archiveName), total, counting,
                            nextEntry = { ais.nextEntry?.let { Ent(it.name, it.isDirectory) } },
                            readEntry = { ais.read(it) },
                        )
                    }
                    return
                }
            }

            // Unwrap streamable archives on the fly so the real ROM lands in the folder, not a .tar.gz.
            val rom = ArchiveExtractor.open(archiveName, counting) ?: run {
                update(game.id, DownloadState.Failed("Couldn't find a ROM inside $archiveName."))
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
            // Guard against a silently-short transfer writing a truncated ROM an emulator then rejects
            // ("no bootable game present"). Log the byte counts so a recurrence pins the cause, and
            // reject a file that's provably incomplete.
            Log.i(TAG, "Wrote ${rom.romName}: ${counting.count} wire bytes (expected ${total ?: "?"})")
            if (total != null && counting.count < total) {
                outFile.delete()
                update(
                    game.id,
                    DownloadState.Failed(
                        "Download was incomplete (${counting.count} of $total bytes). Try again, or pick another source.",
                    ),
                )
                return
            }
            if (!verifyRom(game, context, outFile, rom.romName)) return
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

    // Random-access archives (.7z, .rar) can't be unwrapped from a stream — their index lives at the
    // end — so we first buffer the whole download to a temp file on the user's chosen volume (same
    // volume as the final ROM, so there's room and no internal-storage cap), tracking wire progress.
    // Returns the temp DocumentFile, or null after reporting failure (caller must delete it when done).
    private suspend fun bufferArchiveToTemp(
        game: Game,
        context: Context,
        tree: DocumentFile,
        archiveName: String,
        mimeType: String,
        counting: CountingInputStream,
        total: Long?,
    ): DocumentFile? {
        // Keep the original archive extension so SAF doesn't append a second one (a name ending in
        // ".part" makes it create "name.part.7z"); the "rc_tmp_" prefix marks it ours so a leftover
        // from a killed download can be cleaned.
        val tmpName = "rc_tmp_" + archiveName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        // Sweep any leftover temp archives from an extraction that was killed before its cleanup ran
        // (e.g. the process was frozen while the screen was off) so they don't accumulate in the folder.
        tree.listFiles().forEach { f ->
            if (f.name?.startsWith("rc_tmp_") == true) runCatching { f.delete() }
        }
        val tmpDoc = tree.createFile(mimeType, tmpName) ?: run {
            update(game.id, DownloadState.Failed("Couldn't create a temp file in the chosen folder."))
            return null
        }
        val sink = context.contentResolver.openOutputStream(tmpDoc.uri) ?: run {
            tmpDoc.delete()
            update(game.id, DownloadState.Failed("Couldn't open output stream"))
            return null
        }
        sink.use { out ->
            val buf = ByteArray(64 * 1024)
            var lastReported = 0L
            while (true) {
                val n = counting.read(buf)
                if (n == -1) break
                out.write(buf, 0, n)
                val pulled = counting.count
                if (pulled - lastReported >= 262_144L) {
                    update(game.id, DownloadState.InProgress(pulled, total))
                    lastReported = pulled
                }
            }
        }
        // A short buffer (stream ended before Content-Length) would extract to a truncated ROM. Catch it
        // here rather than handing a corrupt archive to the extractor.
        Log.i(TAG, "Buffered $archiveName: ${counting.count} wire bytes (expected ${total ?: "?"})")
        if (total != null && counting.count < total) {
            tmpDoc.delete()
            update(
                game.id,
                DownloadState.Failed(
                    "Download was incomplete (${counting.count} of $total bytes). Try again, or pick another source.",
                ),
            )
            return null
        }
        return tmpDoc
    }

    // .7z and .rar are random-access (index at the end), so they buffer to disk first, then decode with
    // the native 7-Zip engine. Native decoding is what makes this safe on a handheld: a 7z can carry a
    // very large LZMA dictionary (Vimm's ships 3DS/DS/disc games as .7z with a 256 MB dictionary) that
    // the native engine holds in native memory — the pure-Java XZ decoder allocated it on the ~256 MB
    // app heap and threw OutOfMemoryError, so every Vimm's .7z game failed. The native engine is also
    // the only thing that reads RAR5 (modern Switch/Wii U releases). Single-volume only: a multi-part
    // set needs every volume present, which we don't download, so it fails honestly.
    private suspend fun extractNativeArchive(
        game: Game,
        context: Context,
        tree: DocumentFile,
        archiveName: String,
        counting: CountingInputStream,
        total: Long?,
        multiFile: Boolean,
    ) {
        val mime = if (archiveName.endsWith(".rar", ignoreCase = true)) {
            "application/x-rar-compressed"
        } else {
            "application/x-7z-compressed"
        }
        val tmpDoc = bufferArchiveToTemp(game, context, tree, archiveName, mime, counting, total)
            ?: return
        try {
            update(game.id, DownloadState.Preparing("Extracting…"))
            context.contentResolver.openFileDescriptor(tmpDoc.uri, "r")?.use { pfd ->
                val channel = FileInputStream(pfd.fileDescriptor).channel
                val archive = SevenZip.openInArchive(null, ChannelInStream(channel))
                try {
                    val items = archive.simpleInterface.archiveItems
                    if (multiFile) {
                        extractNativeSet(game, context, tree, folderName(archiveName), total, items)
                        return
                    }
                    val item = items.firstOrNull {
                        val p = it.path
                        !it.isFolder && p != null && ArchiveExtractor.isRomEntry(p)
                    } ?: run {
                        update(game.id, DownloadState.Failed("Couldn't find a ROM inside $archiveName."))
                        return
                    }
                    val romName = ArchiveExtractor.romName(item.path)
                    _info.value = _info.value + (game.id to DownloadInfo(romName, total))
                    tree.findFile(romName)?.delete()
                    val outFile = tree.createFile(mimeFor(romName), romName) ?: run {
                        update(game.id, DownloadState.Failed("Couldn't create $romName in the chosen folder."))
                        return
                    }
                    var written = 0L
                    val extracted = context.contentResolver.openOutputStream(outFile.uri)?.use { out ->
                        item.extractSlow { data -> written += data.size; out.write(data); data.size } == ExtractOperationResult.OK
                    }
                    if (extracted != true) {
                        outFile.delete()
                        update(game.id, DownloadState.Failed("Couldn't extract $romName from $archiveName (corrupt, or a multi-part archive)."))
                        return
                    }
                    // If the engine reports OK but wrote fewer bytes than the archive declares, the source
                    // archive was truncated — writing that half-file would boot to "no bootable game".
                    val declared = runCatching { item.size }.getOrNull()
                    Log.i(TAG, "Extracted $romName: wrote $written bytes (archive declares ${declared ?: "?"})")
                    if (declared != null && written < declared) {
                        outFile.delete()
                        update(
                            game.id,
                            DownloadState.Failed(
                                "Download was incomplete — the archive was truncated ($written of $declared bytes). " +
                                    "Try again, or pick another host/source.",
                            ),
                        )
                        return
                    }
                    if (!verifyRom(game, context, outFile, romName)) return
                    update(game.id, DownloadState.Completed(outFile.uri.toString()))
                    DownloadHistoryStore.add(
                        context,
                        DownloadHistoryStore.Entry(
                            gameId = game.id,
                            title = game.title,
                            platform = game.platform.name,
                            filename = romName,
                            sizeBytes = total,
                            completedAt = System.currentTimeMillis(),
                        ),
                    )
                    Log.i(TAG, "Extracted ${game.title} from ${archiveName.substringAfterLast('.')} -> ${outFile.uri} ($romName)")
                    return
                } finally {
                    archive.close()
                }
            }
            update(game.id, DownloadState.Failed("Couldn't open $archiveName."))
        } catch (t: Throwable) {
            Log.w(TAG, "Native extraction failed for ${game.title}", t)
            update(
                game.id,
                DownloadState.Failed(
                    "Couldn't open $archiveName — it may be a multi-part archive (only single-part is " +
                        "supported) or corrupt.",
                ),
            )
        } finally {
            tmpDoc.delete()
        }
    }

    // Disc-set .7z/.rar decoded by the native engine (a PS2/GameCube/Saturn disc image shipped as one
    // random-access archive): extract every keepable part into a per-game folder, mirroring the zip set path.
    private suspend fun extractNativeSet(
        game: Game,
        context: Context,
        tree: DocumentFile,
        folderName: String,
        total: Long?,
        items: Array<ISimpleInArchiveItem>,
    ) {
        tree.findFile(folderName)?.delete()
        val subDir = tree.createDirectory(folderName) ?: run {
            update(game.id, DownloadState.Failed("Couldn't create a folder for ${game.title}."))
            return
        }
        val written = ArrayList<String>()
        for (item in items) {
            val path = item.path
            if (item.isFolder || path == null || !ArchiveExtractor.isKeepableDiscEntry(path)) continue
            val outName = ArchiveExtractor.romName(path)
            val outFile = subDir.createFile(mimeFor(outName), outName) ?: run {
                subDir.delete()
                update(game.id, DownloadState.Failed("Couldn't write $outName for ${game.title}."))
                return
            }
            val ok = context.contentResolver.openOutputStream(outFile.uri)?.use { out ->
                item.extractSlow { data -> out.write(data); data.size } == ExtractOperationResult.OK
            }
            if (ok != true) {
                subDir.delete()
                update(game.id, DownloadState.Failed("Couldn't extract ${game.title} from $folderName."))
                return
            }
            written.add(outName)
        }
        if (written.isEmpty()) {
            subDir.delete()
            update(game.id, DownloadState.Failed("Couldn't find a ROM inside ${game.title}."))
            return
        }
        writeM3uIfMultiDisc(context, subDir, folderName, written)
        _info.value = _info.value + (game.id to DownloadInfo(folderName, total))
        update(game.id, DownloadState.Completed(subDir.uri.toString()))
        DownloadHistoryStore.add(
            context,
            DownloadHistoryStore.Entry(
                gameId = game.id,
                title = game.title,
                platform = game.platform.name,
                filename = folderName,
                sizeBytes = total,
                completedAt = System.currentTimeMillis(),
            ),
        )
        Log.i(TAG, "Extracted ${game.title} -> ${subDir.uri} (${written.size} files)")
    }

    // Tiny archive-entry descriptor shared by the zip/tar and 7z extraction loops.
    private data class Ent(val name: String, val isDirectory: Boolean)

    // Extracts an entire disc set into a per-game subfolder of the platform's download tree: every
    // keepable part (the .cue + its .bin tracks, multiple discs, audio tracks, an .m3u). For a genuine
    // multi-disc set with no playlist of its own, writes an .m3u so the emulator can swap discs.
    // [counting] non-null → progress reflects wire bytes (streaming archives); null → already on disk (7z).
    private suspend fun extractSet(
        game: Game,
        context: Context,
        tree: DocumentFile,
        folderName: String,
        total: Long?,
        counting: CountingInputStream?,
        nextEntry: () -> Ent?,
        readEntry: (ByteArray) -> Int,
    ) {
        tree.findFile(folderName)?.delete()
        val subDir = tree.createDirectory(folderName) ?: run {
            update(game.id, DownloadState.Failed("Couldn't create a folder for ${game.title}."))
            return
        }
        val written = ArrayList<String>()
        var lastReported = 0L
        var entry = nextEntry()
        while (entry != null) {
            val e = entry
            if (!e.isDirectory && ArchiveExtractor.isKeepableDiscEntry(e.name)) {
                val outName = ArchiveExtractor.romName(e.name)
                val outFile = subDir.createFile(mimeFor(outName), outName)
                if (outFile == null) {
                    subDir.delete()
                    update(game.id, DownloadState.Failed("Couldn't write $outName for ${game.title}."))
                    return
                }
                context.contentResolver.openOutputStream(outFile.uri)?.use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = readEntry(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (counting != null) {
                            val pulled = counting.count
                            if (pulled - lastReported >= 1_048_576L) {
                                update(game.id, DownloadState.InProgress(pulled, total))
                                lastReported = pulled
                            }
                        }
                    }
                }
                written.add(outName)
            }
            entry = nextEntry()
        }
        if (written.isEmpty()) {
            subDir.delete()
            update(game.id, DownloadState.Failed("Couldn't find a ROM inside ${game.title}."))
            return
        }
        writeM3uIfMultiDisc(context, subDir, folderName, written)
        _info.value = _info.value + (game.id to DownloadInfo(folderName, total))
        update(game.id, DownloadState.Completed(subDir.uri.toString()))
        DownloadHistoryStore.add(
            context,
            DownloadHistoryStore.Entry(
                gameId = game.id,
                title = game.title,
                platform = game.platform.name,
                filename = folderName,
                sizeBytes = total,
                completedAt = System.currentTimeMillis(),
            ),
        )
        Log.i(TAG, "Extracted ${game.title} -> ${subDir.uri} (${written.size} files)")
    }

    // RetroArch swaps discs from an .m3u listing each disc. If the set has ≥2 loadable disc images and
    // didn't already ship a playlist, write one (relative names; the .m3u lives beside the discs).
    private fun writeM3uIfMultiDisc(
        context: Context,
        subDir: DocumentFile,
        folderName: String,
        written: List<String>,
    ) {
        if (written.any { it.endsWith(".m3u", ignoreCase = true) }) return
        val discs = written.filter { ArchiveExtractor.isDiscImageEntry(it) }.sorted()
        if (discs.size < 2) return
        val m3u = subDir.createFile("application/octet-stream", "$folderName.m3u") ?: return
        runCatching {
            context.contentResolver.openOutputStream(m3u.uri)?.use { it.write(discs.joinToString("\n").toByteArray()) }
        }
    }

    // The game folder name for a multi-file extract — the archive's base name, minus archive
    // extensions and illegal characters (e.g. "Final Fantasy IX (USA).7z" -> "Final Fantasy IX (USA)").
    private fun folderName(archiveName: String): String {
        var name = archiveName
        for (ext in listOf(".tar.gz", ".tgz", ".tar", ".zip", ".7z", ".gz")) {
            if (name.endsWith(ext, ignoreCase = true)) { name = name.dropLast(ext.length); break }
        }
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "game" }
    }

    private fun isDiscBased(platform: Platform): Boolean = when (platform) {
        Platform.PS1, Platform.PS2, Platform.PSP, Platform.SATURN, Platform.DREAMCAST,
        Platform.SEGA_CD, Platform.THREEDO, Platform.GAMECUBE, Platform.WII -> true
        else -> false
    }

    // Pulls the real filename out of a Content-Disposition header (e.g. attachment; filename="X.7z").
    private fun filenameFromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val match = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)
        return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
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

    // Bridges a SAF temp file's FileChannel to the native 7-Zip engine's random-access input, so a
    // multi-GB .rar is read straight off disk (never loaded into memory) and needs no real path.
    private class ChannelInStream(private val channel: FileChannel) : IInStream {
        override fun seek(offset: Long, seekOrigin: Int): Long = try {
            val newPos = when (seekOrigin) {
                ISeekableStream.SEEK_SET -> offset
                ISeekableStream.SEEK_CUR -> channel.position() + offset
                ISeekableStream.SEEK_END -> channel.size() + offset
                else -> throw SevenZipException("Unknown seek origin: $seekOrigin")
            }
            channel.position(newPos)
            newPos
        } catch (e: IOException) {
            throw SevenZipException("seek failed", e)
        }

        override fun read(data: ByteArray): Int = try {
            val buf = ByteBuffer.wrap(data)
            var totalRead = 0
            while (buf.hasRemaining()) {
                val n = channel.read(buf)
                if (n < 0) break
                totalRead += n
            }
            totalRead
        } catch (e: IOException) {
            throw SevenZipException("read failed", e)
        }

        // No-op: the underlying file descriptor is owned by the caller's `openFileDescriptor(...).use`,
        // which closes it. Closing the channel here would close that fd early and double-close it.
        override fun close() {}
    }

    // Verify a freshly-written ROM isn't truncated before we call it done. Today: Switch .nsp (PFS0) — a
    // cut-off program NCA is exactly why an emulator reports "no bootable game present". Reads the small
    // header, compares the file's actual size against the size the container's table requires, and on a
    // shortfall deletes the file + marks Failed. Returns true when OK or not checkable (never blocks a
    // format we can't parse). Cheap: only the leading bytes are read, not the multi-GB body.
    private fun verifyRom(game: Game, context: Context, outFile: DocumentFile, romName: String): Boolean {
        if (!romName.endsWith(".nsp", ignoreCase = true)) return true
        val header = runCatching {
            context.contentResolver.openInputStream(outFile.uri)?.use { input ->
                val buf = ByteArray(64 * 1024)
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                buf.copyOf(read)
            }
        }.getOrNull() ?: return true
        val required = NspIntegrity.requiredSize(header) ?: return true
        val actual = outFile.length()
        if (actual < required) {
            Log.w(TAG, "Truncated NSP for ${game.title}: $actual bytes, container needs $required (short ${required - actual})")
            outFile.delete()
            update(
                game.id,
                DownloadState.Failed(
                    "The downloaded game file is incomplete (truncated). Try again, or pick another host/source.",
                ),
            )
            return false
        }
        Log.i(TAG, "NSP integrity OK for ${game.title}: $actual bytes >= required $required")
        return true
    }

    private fun update(gameId: String, state: DownloadState) {
        // Once a stop is in flight, ignore the coroutine's trailing progress/error updates — cancel()
        // has already set the final (absent) state and these would just flicker it back.
        if (gameId in cancelling) return
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
