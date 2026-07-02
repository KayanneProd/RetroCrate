package com.kayanne.retrocrate.data.source.debrid

import android.util.Log
import com.kayanne.retrocrate.data.network.HttpClient
import com.kayanne.retrocrate.data.persistence.DebridSettings
import com.kayanne.retrocrate.data.source.ResolveQuery
import com.kayanne.retrocrate.data.source.ResolvedDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request

// Real-Debrid: add the magnet, select the ROM file, and unrestrict it to a direct link. RD has no
// instant-availability endpoint anymore, so we add then poll briefly — a cached torrent flips to
// "downloaded" within a couple of seconds; if it doesn't, we treat it as not instantly available,
// delete the transfer, and let the chain fall through. Only ever talks to real-debrid.com.
object RealDebridService : DebridService {

    private const val TAG = "RealDebrid"
    private const val BASE = "https://api.real-debrid.com/rest/1.0"
    private const val POLL_ATTEMPTS = 8
    private const val POLL_DELAY_MS = 800L
    private const val LONG_POLL_ATTEMPTS = 150
    private const val LONG_POLL_DELAY_MS = 4_000L

    private val json = Json { ignoreUnknownKeys = true }

    override val name = "Real-Debrid"

    override fun isConfigured(settings: DebridSettings): Boolean =
        !settings.realDebridApiKey.isNullOrBlank()

    override suspend fun resolve(
        candidate: TorrentCandidate,
        query: ResolveQuery,
        settings: DebridSettings,
    ): ResolvedDownload? = withContext(Dispatchers.IO) {
        val key = settings.realDebridApiKey?.takeIf { it.isNotBlank() } ?: return@withContext null

        var torrentId: String? = null
        try {
            val added = json.decodeFromString<AddMagnet>(
                post("/torrents/addMagnet", key, "magnet" to candidate.magnet),
            )
            torrentId = added.id.takeIf { it.isNotBlank() } ?: return@withContext null

            val info = awaitFilesListed(torrentId, key) ?: return@withContext fail(torrentId, key)
            val files = info.files.map {
                DebridFile(name = it.path.substringAfterLast('/'), sizeBytes = it.bytes, ref = it.id.toString())
            }
            val picked = pickDebridFile(files, query) ?: return@withContext fail(torrentId, key)

            post("/torrents/selectFiles/$torrentId", key, "files" to picked.ref)

            val ready = awaitDownloaded(torrentId, key) ?: return@withContext fail(torrentId, key)
            val link = ready.links.firstOrNull() ?: return@withContext fail(torrentId, key)

            val unrestricted = json.decodeFromString<Unrestrict>(
                post("/unrestrict/link", key, "link" to link),
            )
            val url = unrestricted.download.takeIf { it.isNotBlank() } ?: return@withContext fail(torrentId, key)

            ResolvedDownload(
                siteName = name,
                filename = unrestricted.filename.ifBlank { picked.name },
                downloadUrl = url,
                sizeBytes = unrestricted.filesize ?: picked.sizeBytes,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "resolve failed for \"${query.title}\"", t)
            torrentId?.let { runCatching { delete(it, key) } }
            null
        }
    }

    override suspend fun resolveNonCached(
        candidate: TorrentCandidate,
        query: ResolveQuery,
        settings: DebridSettings,
        onProgress: (Float?) -> Unit,
    ): ResolvedDownload? = withContext(Dispatchers.IO) {
        val key = settings.realDebridApiKey?.takeIf { it.isNotBlank() } ?: return@withContext null

        var torrentId: String? = null
        try {
            val added = json.decodeFromString<AddMagnet>(
                post("/torrents/addMagnet", key, "magnet" to candidate.magnet),
            )
            torrentId = added.id.takeIf { it.isNotBlank() } ?: return@withContext null
            Log.i(TAG, "Real-Debrid fetching \"${candidate.name}\" server-side (torrent $torrentId)")

            val info = awaitFilesListed(torrentId, key) ?: return@withContext fail(torrentId, key)
            val files = info.files.map {
                DebridFile(name = it.path.substringAfterLast('/'), sizeBytes = it.bytes, ref = it.id.toString())
            }
            val picked = pickDebridFile(files, query) ?: return@withContext fail(torrentId, key)
            post("/torrents/selectFiles/$torrentId", key, "files" to picked.ref)

            // Long poll while RD downloads it to its servers, surfacing progress.
            var ready: Info? = null
            for (attempt in 0 until LONG_POLL_ATTEMPTS) {
                val current = json.decodeFromString<Info>(get("/torrents/info/$torrentId", key))
                onProgress(current.progress / 100f)
                if (current.status == "downloaded" && current.links.isNotEmpty()) {
                    ready = current
                    break
                }
                if (current.status in DEAD_STATES) return@withContext fail(torrentId, key)
                delay(LONG_POLL_DELAY_MS)
            }
            val link = ready?.links?.firstOrNull() ?: return@withContext fail(torrentId, key)
            val unrestricted = json.decodeFromString<Unrestrict>(post("/unrestrict/link", key, "link" to link))
            val url = unrestricted.download.takeIf { it.isNotBlank() } ?: return@withContext fail(torrentId, key)
            ResolvedDownload(name, unrestricted.filename.ifBlank { picked.name }, url, unrestricted.filesize ?: picked.sizeBytes)
        } catch (t: Throwable) {
            Log.w(TAG, "non-cached resolve failed for \"${query.title}\"", t)
            torrentId?.let { runCatching { delete(it, key) } }
            null
        }
    }

    override suspend fun unlockHosterLink(url: String, settings: DebridSettings): ResolvedDownload? = withContext(Dispatchers.IO) {
        val key = settings.realDebridApiKey?.takeIf { it.isNotBlank() } ?: return@withContext null
        val unrestricted = runCatching {
            json.decodeFromString<Unrestrict>(post("/unrestrict/link", key, "link" to url))
        }.getOrElse {
            Log.w(TAG, "unrestrict hoster link failed for $url", it)
            return@withContext null
        }
        val direct = unrestricted.download.takeIf { it.isNotBlank() } ?: return@withContext null
        ResolvedDownload(
            siteName = name,
            filename = unrestricted.filename.ifBlank { fileNameFromUrl(url) },
            downloadUrl = direct,
            sizeBytes = unrestricted.filesize,
        )
    }

    private suspend fun awaitFilesListed(id: String, key: String): Info? {
        repeat(POLL_ATTEMPTS) {
            val info = json.decodeFromString<Info>(get("/torrents/info/$id", key))
            if (info.files.isNotEmpty()) return info
            if (info.status in DEAD_STATES) return null
            delay(POLL_DELAY_MS)
        }
        return null
    }

    // A cached torrent settles on "downloaded" within a few polls; anything still queued/downloading
    // means it isn't instantly available, so we give up rather than wait on a real RD download.
    private suspend fun awaitDownloaded(id: String, key: String): Info? {
        repeat(POLL_ATTEMPTS) {
            val info = json.decodeFromString<Info>(get("/torrents/info/$id", key))
            when {
                info.status == "downloaded" && info.links.isNotEmpty() -> return info
                info.status in DEAD_STATES -> return null
            }
            delay(POLL_DELAY_MS)
        }
        return null
    }

    private fun fail(id: String, key: String): ResolvedDownload? {
        runCatching { delete(id, key) }
        return null
    }

    private fun get(path: String, key: String): String {
        val request = Request.Builder().url("$BASE$path").header("Authorization", "Bearer $key").build()
        return HttpClient.get().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} GET $path")
            resp.body?.string().orEmpty()
        }
    }

    private fun post(path: String, key: String, vararg form: Pair<String, String>): String {
        val body = FormBody.Builder().apply { form.forEach { add(it.first, it.second) } }.build()
        val request = Request.Builder().url("$BASE$path").header("Authorization", "Bearer $key").post(body).build()
        return HttpClient.get().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} POST $path")
            resp.body?.string().orEmpty()
        }
    }

    private fun delete(id: String, key: String) {
        val request = Request.Builder()
            .url("$BASE/torrents/delete/$id")
            .header("Authorization", "Bearer $key")
            .delete()
            .build()
        HttpClient.get().newCall(request).execute().close()
    }

    private val DEAD_STATES = setOf("magnet_error", "error", "virus", "dead")

    @Serializable
    private data class AddMagnet(val id: String = "", val uri: String = "")

    @Serializable
    private data class Info(
        val status: String = "",
        val progress: Float = 0f,
        val files: List<RdFile> = emptyList(),
        val links: List<String> = emptyList(),
    )

    @Serializable
    private data class RdFile(val id: Int = 0, val path: String = "", val bytes: Long = 0, val selected: Int = 0)

    @Serializable
    private data class Unrestrict(
        val download: String = "",
        val filename: String = "",
        val filesize: Long? = null,
    )
}
