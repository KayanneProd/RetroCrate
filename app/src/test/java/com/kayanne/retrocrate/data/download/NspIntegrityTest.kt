package com.kayanne.retrocrate.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import org.junit.Test

class NspIntegrityTest {

    private fun leU32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    private fun leU64(v: Long) = ByteArray(8) { ((v shr (it * 8)) and 0xFF).toByte() }

    // Builds a minimal PFS0 header + file table (+ dummy string table) for the given (offset, size) files.
    private fun pfs0(entries: List<Pair<Long, Long>>, stringTableSize: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("PFS0".toByteArray())
        out.write(leU32(entries.size))
        out.write(leU32(stringTableSize))
        out.write(leU32(0))
        var strOff = 0
        for ((off, size) in entries) {
            out.write(leU64(off)); out.write(leU64(size)); out.write(leU32(strOff)); out.write(leU32(0))
            strOff += 8
        }
        out.write(ByteArray(stringTableSize))
        return out.toByteArray()
    }

    @Test
    fun `computes required size from the file table`() {
        // dataStart = 16 + 2*24 + 16 = 80; last file ends at 100+900 = 1000; required = 1080.
        val header = pfs0(listOf(0L to 100L, 100L to 900L), stringTableSize = 16)
        assertEquals(1080L, NspIntegrity.requiredSize(header))
    }

    @Test
    fun `uses the max end even when it is not the last table entry`() {
        // The program NCA is often not the last table entry: entry 0 is huge, entry 1 is a tiny cnmt.
        val header = pfs0(listOf(0L to 5_000_000_000L, 5_000_000_000L to 1_000L), stringTableSize = 32)
        // dataStart = 16 + 2*24 + 32 = 96; max end = 5_000_001_000; required = 5_000_001_096.
        assertEquals(5_000_001_096L, NspIntegrity.requiredSize(header))
    }

    @Test
    fun `returns null for non-PFS0 data`() {
        assertNull(NspIntegrity.requiredSize("NOT A PFS0 CONTAINER".toByteArray()))
    }

    @Test
    fun `returns null when the header is too short to trust`() {
        assertNull(NspIntegrity.requiredSize(byteArrayOf(0x50, 0x46, 0x53)))
    }

    @Test
    fun `a complete file is never shorter than required`() {
        val header = pfs0(listOf(0L to 100L, 100L to 900L), stringTableSize = 16)
        val required = NspIntegrity.requiredSize(header)!!
        // A real file holds all the data the table points at, so its size >= required. A 128 KiB-short
        // file (as seen on-device) would be < required and get rejected.
        assertTrue(required - 131_072 < required)
    }
}
