package com.kayanne.retrocrate.data.download

import com.kayanne.retrocrate.data.source.RomMatcher
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.InputStream

// Internet Archive (and others) usually ship a game as an archive — a .tar.gz or .zip wrapping the
// actual ROM — not a bare file. This unwraps it *during* the download: the returned stream yields
// the ROM's decompressed bytes, so DownloadCoordinator writes the real ROM to the user's folder and
// never lands a useless .tar.gz on disk. No intermediate archive file is stored.
object ArchiveExtractor {

    data class RomStream(val romName: String, val stream: InputStream)

    private val SIDECAR_EXTENSIONS = setOf(
        "txt", "md5", "sha1", "sha256", "nfo", "url", "jpg", "jpeg", "png", "gif",
        "xml", "sqlite", "torrent", "db", "json", "log", "dat",
        // non-game payloads that can be large and would otherwise be mistaken for a ROM
        "stl", "obj", "3mf", "gcode", "pdf", "mp3", "wav", "flac", "mp4", "mkv", "ai", "psd",
    )

    // `raw` should be the raw (still-compressed) network stream — callers wrap it in a counting
    // stream first so download progress tracks bytes actually pulled over the wire.
    fun open(filename: String, raw: InputStream): RomStream? {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ->
                firstRom(TarArchiveInputStream(GzipCompressorInputStream(raw)))
            lower.endsWith(".tar") ->
                firstRom(TarArchiveInputStream(raw))
            lower.endsWith(".zip") ->
                firstRom(ZipArchiveInputStream(raw))
            lower.endsWith(".gz") ->
                RomStream(filename.dropLast(3), GzipCompressorInputStream(raw))
            else ->
                RomStream(filename, raw)
        }
    }

    fun isArchive(filename: String): Boolean {
        val lower = filename.lowercase()
        return lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".tar") ||
            lower.endsWith(".zip") || lower.endsWith(".gz")
    }

    // Advances the archive to the first entry that's an actual ROM and returns a stream that reads
    // exactly that entry's bytes. Returns null if the archive holds no ROM (so we fail honestly
    // instead of extracting, say, a 3D-print .stl that happened to be large).
    private fun firstRom(archive: ArchiveInputStream<*>): RomStream? {
        var entry = archive.nextEntry
        while (entry != null) {
            val name = entry.name
            if (!entry.isDirectory && isRomCandidate(name)) {
                return RomStream(baseName(name), archive)
            }
            entry = archive.nextEntry
        }
        return null
    }

    private fun isRomCandidate(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in SIDECAR_EXTENSIONS) return false
        return RomMatcher.hasRomExtension(name)
    }

    private fun baseName(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { "rom.bin" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
}
