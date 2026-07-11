package com.kayanne.retrocrate.data.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveExtractorTest {

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private class Counting(private val wrapped: InputStream) : InputStream() {
        var count = 0L
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
    }

    // Incompressible payloads (like real ROM data) — deflate can't shrink these, so trailing
    // entries stay large on the wire instead of vanishing into the zip reader's read-ahead buffer.
    private fun noise(size: Int, seed: Long) = ByteArray(size).also { java.util.Random(seed).nextBytes(it) }

    // Regression: Vimm's zips failed "Download was incomplete" because reading just the ROM entry
    // never pulls the zip's trailing bytes, so wire count landed short of the archive's full size.
    @Test
    fun `rom entry read alone leaves a zip's trailing bytes unpulled`() {
        val romBytes = noise(200_000, seed = 1)
        val zipBytes = zip(
            "Pokemon - SoulSilver Version (USA).nds" to romBytes,
            "trailing-artwork.jpg" to noise(256_000, seed = 2),
        )
        val counting = Counting(zipBytes.inputStream())
        val rom = ArchiveExtractor.open("Pokemon - SoulSilver Version (USA).zip", counting)!!
        val buf = ByteArray(64 * 1024)
        while (rom.stream.read(buf) != -1) Unit
        assertTrue(
            "expected the entry read to stop before the zip's end (got ${counting.count} of ${zipBytes.size})",
            counting.count < zipBytes.size.toLong(),
        )
    }

    @Test
    fun `copyRomAndDrain writes the rom intact and consumes the whole zip`() {
        val romBytes = noise(200_000, seed = 1)
        val zipBytes = zip(
            "Pokemon - SoulSilver Version (USA).nds" to romBytes,
            "trailing-artwork.jpg" to noise(256_000, seed = 2),
        )
        val counting = Counting(zipBytes.inputStream())
        val rom = ArchiveExtractor.open("Pokemon - SoulSilver Version (USA).zip", counting)!!
        assertEquals("Pokemon - SoulSilver Version (USA).nds", rom.romName)

        val sink = ByteArrayOutputStream()
        ArchiveExtractor.copyRomAndDrain(rom.stream, counting, sink)

        assertArrayEquals(romBytes, sink.toByteArray())
        // The completeness guard compares wire bytes to Content-Length — after the drain they must
        // match exactly for a fully-transferred archive, or good downloads get deleted as truncated.
        assertEquals(zipBytes.size.toLong(), counting.count)
    }

    @Test
    fun `copyRomAndDrain on a bare file consumes exactly the file`() {
        val romBytes = ByteArray(50_000) { (it % 127).toByte() }
        val counting = Counting(romBytes.inputStream())
        val rom = ArchiveExtractor.open("Game (USA).nds", counting)!!

        val sink = ByteArrayOutputStream()
        ArchiveExtractor.copyRomAndDrain(rom.stream, counting, sink)

        assertArrayEquals(romBytes, sink.toByteArray())
        assertEquals(romBytes.size.toLong(), counting.count)
    }

    // Regression: a lone GameCube .iso was extracted into a same-named subfolder, where frontends
    // scanning the platform folder never saw it. Only genuine multi-file sets warrant a folder.
    @Test
    fun `single disc image needs no set folder`() {
        assertFalse(ArchiveExtractor.needsSetFolder(listOf("Game (USA)/Game (USA).iso")))
    }

    @Test
    fun `single image with sidecar junk still needs no set folder`() {
        assertFalse(
            ArchiveExtractor.needsSetFolder(listOf("Game (USA).rvz", "info.txt", "scan.jpg")),
        )
    }

    @Test
    fun `cue-bin set needs a set folder`() {
        assertTrue(
            ArchiveExtractor.needsSetFolder(
                listOf("Game (USA).cue", "Game (USA) (Track 1).bin", "Game (USA) (Track 2).bin"),
            ),
        )
    }

    @Test
    fun `multi-disc set needs a set folder`() {
        assertTrue(
            ArchiveExtractor.needsSetFolder(listOf("Game (USA) (Disc 1).iso", "Game (USA) (Disc 2).iso")),
        )
    }
}
