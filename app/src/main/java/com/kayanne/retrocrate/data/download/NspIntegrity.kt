package com.kayanne.retrocrate.data.download

// A Switch .nsp is a PFS0 container: a header + a file table + a string table, then the files' data laid
// out contiguously. If a download or extraction is truncated, the file is shorter than the table says it
// must be — the cut-off program NCA then makes an emulator report "no bootable game present". This parses
// the leading bytes to compute the minimum total size the container implies, so the coordinator can catch
// a truncated NSP and fail honestly instead of writing a silently-broken game.
object NspIntegrity {

    // Minimum total file size implied by a PFS0 header, or null if these bytes aren't a parseable PFS0
    // (then we can't judge — treat as fine). A complete NSP has fileSize >= this; smaller is truncated.
    // [header] must cover the header + full file table (16 + 24*count bytes); 64 KiB is ample.
    fun requiredSize(header: ByteArray): Long? {
        if (header.size < 16) return null
        if (header[0] != 'P'.code.toByte() || header[1] != 'F'.code.toByte() ||
            header[2] != 'S'.code.toByte() || header[3] != '0'.code.toByte()
        ) {
            return null
        }
        val count = leU32(header, 4)
        val stringTableSize = leU32(header, 8)
        if (count <= 0 || count > 100_000) return null
        val tableEnd = 16 + count * 24
        if (tableEnd > header.size) return null // not enough bytes to trust the parse
        val dataStart = 16L + count.toLong() * 24L + stringTableSize.toLong()
        var maxEnd = 0L
        for (i in 0 until count) {
            val base = 16 + i * 24
            val offset = leU64(header, base)
            val size = leU64(header, base + 8)
            val end = offset + size
            if (end > maxEnd) maxEnd = end
        }
        return dataStart + maxEnd
    }

    private fun leU32(b: ByteArray, off: Int): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((b[off + i].toInt() and 0xFF) shl (i * 8))
        return v
    }

    private fun leU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (i * 8))
        return v
    }
}
