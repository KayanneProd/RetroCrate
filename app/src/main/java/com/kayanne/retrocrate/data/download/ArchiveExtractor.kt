package com.kayanne.retrocrate.data.download

import com.kayanne.retrocrate.data.source.RomMatcher
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.InputStream
import java.io.OutputStream

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

    // Copies the opened ROM entry to [sink], then pulls the rest of [raw] to EOF. The drain matters:
    // the entry read stops before an archive's trailing bytes (a zip's central directory, any entries
    // after the ROM), so a completeness check comparing wire bytes to Content-Length would otherwise
    // flag every fully-transferred archive as truncated and delete a good ROM.
    fun copyRomAndDrain(rom: InputStream, raw: InputStream, sink: OutputStream, onChunk: () -> Unit = {}) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = rom.read(buf)
            if (n == -1) break
            sink.write(buf, 0, n)
            onChunk()
        }
        while (raw.read(buf) != -1) Unit
    }

    // A per-game folder is only for genuine disc sets (a .cue + its .bin tracks, several discs).
    // A single-file image (a lone GameCube .iso) must land directly in the platform folder —
    // frontends scan that folder for loadable files and don't look inside a nested one.
    fun needsSetFolder(entryNames: List<String>): Boolean =
        entryNames.count { isKeepableDiscEntry(it) } > 1

    fun isArchive(filename: String): Boolean {
        val lower = filename.lowercase()
        return lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".tar") ||
            lower.endsWith(".zip") || lower.endsWith(".gz") || lower.endsWith(".7z")
    }

    // 7z can't be stream-extracted (its index lives at the end of the file), so DownloadCoordinator
    // buffers it to disk first and drives extraction itself — these expose the same ROM-entry rules.
    fun isRomEntry(name: String): Boolean = isRomCandidate(name)

    fun romName(path: String): String = baseName(path)

    // A multi-entry archive container (vs a single .gz or a bare file). Disc games on Vimm's / IA ship
    // as one of these holding the whole set (a .cue + its .bin tracks, or several discs).
    fun isContainer(filename: String): Boolean {
        val lower = filename.lowercase()
        return lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".tar") ||
            lower.endsWith(".zip")
    }

    fun newContainerStream(filename: String, raw: InputStream): ArchiveInputStream<*>? {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> TarArchiveInputStream(GzipCompressorInputStream(raw))
            lower.endsWith(".tar") -> TarArchiveInputStream(raw)
            lower.endsWith(".zip") -> ZipArchiveInputStream(raw)
            else -> null
        }
    }

    // For a disc set we keep ALL the parts, not just the first ROM file: the .cue plus its .bin/track
    // files, multiple discs, an .m3u, audio tracks. Only obvious non-game sidecars are dropped (scans,
    // docs, checksums, 3D-print/media junk) — drop the wrong file and the set won't load.
    fun isKeepableDiscEntry(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return false
        return ext !in DISC_JUNK_EXTENSIONS
    }

    // The directly-loadable disc files an emulator would open — used to decide whether a set is
    // genuinely multi-disc (≥2) and warrants an auto-generated .m3u playlist.
    fun isDiscImageEntry(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in DISC_IMAGE_EXTENSIONS

    private val DISC_JUNK_EXTENSIONS = setOf(
        "txt", "nfo", "url", "jpg", "jpeg", "png", "gif", "bmp", "pdf", "html", "htm", "xml", "sqlite",
        "torrent", "db", "log", "dat", "md5", "sha1", "sha256", "crc", "sfv", "ini", "cab",
        "stl", "obj", "3mf", "gcode", "ai", "psd", "mp4", "mkv", "avi", "mov", "exe", "dll",
    )

    private val DISC_IMAGE_EXTENSIONS = setOf("cue", "chd", "iso", "gdi", "cdi", "pbp", "ccd")

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
