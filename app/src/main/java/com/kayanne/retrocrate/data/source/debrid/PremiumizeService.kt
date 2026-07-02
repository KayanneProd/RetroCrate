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

// Premiumize. Cached path: "direct download" returns file links immediately for an already-cached
// torrent. Non-cached path: create a cloud transfer, wait while Premiumize downloads it server-side
// (its pipes grab low-seed torrents far better than a handheld), then resolve the finished file's
// direct link. Only ever talks to premiumize.me, with the user's own API key.
object PremiumizeService : DebridService {

    private const val TAG = "Premiumize"
    private const val DIRECTDL = "https://www.premiumize.me/api/transfer/directdl"
    private const val CREATE = "https://www.premiumize.me/api/transfer/create"
    private const val LIST = "https://www.premiumize.me/api/transfer/list"
    private const val FOLDER_LIST = "https://www.premiumize.me/api/folder/list"
    private const val ITEM_DETAILS = "https://www.premiumize.me/api/item/details"
    private const val DELETE = "https://www.premiumize.me/api/transfer/delete"

    private const val MAX_POLLS = 150
    private const val POLL_MS = 4_000L
    // If a transfer makes no progress at all for this many polls (~90s), the swarm is effectively
    // dead (e.g. a 1-seeder torrent whose only seeder is offline) — give up rather than hang.
    private const val STALL_POLLS = 22

    private val json = Json { ignoreUnknownKeys = true }

    override val name = "Premiumize"

    override fun isConfigured(settings: DebridSettings): Boolean =
        !settings.premiumizeApiKey.isNullOrBlank()

    override suspend fun resolve(
        candidate: TorrentCandidate,
        query: ResolveQuery,
        settings: DebridSettings,
    ): ResolvedDownload? = withContext(Dispatchers.IO) {
        val key = settings.premiumizeApiKey?.takeIf { it.isNotBlank() } ?: return@withContext null
        val body = FormBody.Builder().add("apikey", key).add("src", candidate.magnet).build()

        val response = runCatching {
            postForm(DIRECTDL, body) { json.decodeFromString<DirectDl>(it) }
        }.getOrElse {
            Log.w(TAG, "directdl failed for \"${query.title}\"", it)
            return@withContext null
        }
        if (response.status != "success" || response.content.isEmpty()) {
            Log.i(TAG, "Not cached: ${candidate.name} (${response.status})")
            return@withContext null
        }

        val picked = pickDebridFile(
            response.content.map { DebridFile(it.path.substringAfterLast('/'), it.size, it.link) },
            query,
        ) ?: return@withContext null
        ResolvedDownload(name, picked.name, picked.ref, picked.sizeBytes)
    }

    override suspend fun resolveNonCached(
        candidate: TorrentCandidate,
        query: ResolveQuery,
        settings: DebridSettings,
        onProgress: (Float?) -> Unit,
    ): ResolvedDownload? = withContext(Dispatchers.IO) {
        val key = settings.premiumizeApiKey?.takeIf { it.isNotBlank() } ?: return@withContext null

        val created = runCatching {
            val body = FormBody.Builder().add("apikey", key).add("src", candidate.magnet).build()
            postForm(CREATE, body) { json.decodeFromString<Create>(it) }
        }.getOrElse {
            Log.w(TAG, "transfer/create failed for \"${query.title}\"", it)
            return@withContext null
        }
        val transferId = created.id?.takeIf { it.isNotBlank() }
        if (created.status != "success" || transferId == null) {
            Log.i(TAG, "transfer/create not accepted: ${created.status} ${created.message}")
            return@withContext null
        }
        Log.i(TAG, "Premiumize fetching \"${candidate.name}\" server-side (transfer $transferId)")

        try {
            var stalledPolls = 0
            var lastStatus = ""
            repeat(MAX_POLLS) {
                delay(POLL_MS)
                val transfer = runCatching {
                    getJson("$LIST?apikey=$key") { json.decodeFromString<TransferList>(it) }
                }.getOrNull()?.transfers?.firstOrNull { it.id == transferId }

                if (transfer != null) {
                    onProgress(transfer.progress)
                    if (transfer.status != lastStatus) {
                        Log.i(TAG, "transfer ${transfer.status} (${transfer.progress}) for \"${query.title}\"")
                        lastStatus = transfer.status
                    }
                    when (transfer.status) {
                        "finished", "seeding" -> {
                            val resolved = resolveFinished(key, transfer, query)
                            runCatching { deleteTransfer(key, transferId) }
                            return@withContext resolved
                        }
                        "error", "timeout", "banned", "deleted" -> {
                            Log.w(TAG, "transfer ended: ${transfer.status} ${transfer.message}")
                            runCatching { deleteTransfer(key, transferId) }
                            return@withContext null
                        }
                    }
                    stalledPolls = if ((transfer.progress ?: 0f) <= 0f) stalledPolls + 1 else 0
                    if (stalledPolls >= STALL_POLLS) {
                        Log.w(TAG, "no peers / no progress for \"${query.title}\" — giving up (dead swarm)")
                        runCatching { deleteTransfer(key, transferId) }
                        return@withContext null
                    }
                }
            }
            Log.w(TAG, "Premiumize transfer timed out for \"${query.title}\"")
            runCatching { deleteTransfer(key, transferId) }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "non-cached resolve failed for \"${query.title}\"", t)
            runCatching { deleteTransfer(key, transferId) }
            null
        }
    }

    override suspend fun unlockHosterLink(url: String, settings: DebridSettings): ResolvedDownload? = withContext(Dispatchers.IO) {
        val key = settings.premiumizeApiKey?.takeIf { it.isNotBlank() } ?: return@withContext null
        val body = FormBody.Builder().add("apikey", key).add("src", url).build()
        val response = runCatching {
            postForm(DIRECTDL, body) { json.decodeFromString<DirectDl>(it) }
        }.getOrElse {
            Log.w(TAG, "directdl hoster link failed for $url", it)
            return@withContext null
        }
        if (response.status != "success" || response.content.isEmpty()) {
            Log.i(TAG, "Premiumize can't unlock $url (${response.status})")
            return@withContext null
        }
        val file = response.content.firstOrNull { it.link.isNotBlank() } ?: return@withContext null
        ResolvedDownload(
            siteName = name,
            filename = file.path.substringAfterLast('/').ifBlank { fileNameFromUrl(url) },
            downloadUrl = file.link,
            sizeBytes = file.size,
        )
    }

    private fun resolveFinished(key: String, transfer: Transfer, query: ResolveQuery): ResolvedDownload? {
        val fileId = transfer.file_id
        if (!fileId.isNullOrBlank()) {
            val item = runCatching {
                getJson("$ITEM_DETAILS?apikey=$key&id=$fileId") { json.decodeFromString<ItemDetails>(it) }
            }.getOrNull()
            val link = item?.link?.takeIf { it.isNotBlank() }
            Log.i(TAG, "finished via file_id=$fileId -> link=${link != null}")
            if (link != null) return ResolvedDownload(name, (item.name ?: query.title), link, item.size)
        }
        val folderId = transfer.folder_id?.takeIf { it.isNotBlank() } ?: return null
        // The torrent's files may sit in a subfolder, so descend folders to collect all files.
        val files = collectFiles(key, folderId, depth = 0)
        Log.i(TAG, "finished folder=$folderId -> ${files.size} files: " +
            files.take(6).joinToString { "${it.name}(link=${it.ref.isNotBlank()})" })
        val picked = pickDebridFile(files.filter { it.ref.isNotBlank() }, query) ?: return null
        return ResolvedDownload(name, picked.name, picked.ref, picked.sizeBytes)
    }

    private fun collectFiles(key: String, folderId: String, depth: Int): List<DebridFile> {
        if (depth > 3) return emptyList()
        val folder = runCatching {
            getJson("$FOLDER_LIST?apikey=$key&id=$folderId") { json.decodeFromString<FolderList>(it) }
        }.getOrNull() ?: return emptyList()
        val out = mutableListOf<DebridFile>()
        for (item in folder.content) {
            when (item.type) {
                "file" -> out.add(DebridFile(item.name, item.size, item.link.orEmpty()))
                "folder" -> item.id?.let { out.addAll(collectFiles(key, it, depth + 1)) }
            }
        }
        return out
    }

    private fun deleteTransfer(key: String, id: String) {
        postForm(DELETE, FormBody.Builder().add("apikey", key).add("id", id).build()) { it }
    }

    private fun <T> getJson(url: String, parse: (String) -> T): T {
        val request = Request.Builder().url(url).build()
        return HttpClient.get().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} GET")
            parse(resp.body?.string().orEmpty())
        }
    }

    private fun <T> postForm(url: String, body: FormBody, parse: (String) -> T): T {
        val request = Request.Builder().url(url).post(body).build()
        return HttpClient.get().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} POST")
            parse(resp.body?.string().orEmpty())
        }
    }

    @Serializable
    private data class DirectDl(val status: String = "", val content: List<Content> = emptyList())

    @Serializable
    private data class Content(val path: String = "", val size: Long? = null, val link: String = "")

    @Serializable
    private data class Create(val status: String = "", val id: String? = null, val message: String? = null)

    @Serializable
    private data class TransferList(val transfers: List<Transfer> = emptyList())

    @Serializable
    private data class Transfer(
        val id: String = "",
        val status: String = "",
        val progress: Float? = null,
        val message: String? = null,
        val folder_id: String? = null,
        val file_id: String? = null,
    )

    @Serializable
    private data class FolderList(val content: List<FolderItem> = emptyList())

    @Serializable
    private data class FolderItem(
        val id: String? = null,
        val type: String = "",
        val name: String = "",
        val size: Long? = null,
        val link: String? = null,
    )

    @Serializable
    private data class ItemDetails(val name: String? = null, val link: String? = null, val size: Long? = null)
}
