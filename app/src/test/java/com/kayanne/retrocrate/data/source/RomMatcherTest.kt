package com.kayanne.retrocrate.data.source

import com.kayanne.retrocrate.data.source.RomMatcher.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomMatcherTest {

    private fun files(vararg names: String) = names.map { Candidate(it, null) }

    @Test
    fun `exact no-intro filename wins even inside a full-set item`() {
        // The bug: an IA item bundling a whole No-Intro set used to return the first ROM file.
        val match = RomMatcher.bestMatch(
            title = "Super Mario 64",
            romFileName = "Super Mario 64 (USA).z64",
            preferredRegion = "USA",
            candidates = files(
                "1080 Snowboarding (USA).z64",
                "Banjo-Kazooie (USA).z64",
                "Super Mario 64 (USA).z64",
                "WaveRace 64 (USA).z64",
            ),
        )
        assertEquals("Super Mario 64 (USA).z64", match?.filename)
    }

    @Test
    fun `does not return an unrelated rom when nothing matches`() {
        // Previously returned the first ROM-extension file regardless of game.
        val match = RomMatcher.bestMatch(
            title = "Super Mario 64",
            romFileName = "Super Mario 64 (USA).z64",
            preferredRegion = "USA",
            candidates = files(
                "Banjo-Kazooie (USA).z64",
                "WaveRace 64 (USA).z64",
                "readme.txt",
            ),
        )
        assertNull(match)
    }

    @Test
    fun `matches title when wrapped in a zip`() {
        val match = RomMatcher.bestMatch(
            title = "Chrono Trigger",
            romFileName = "Chrono Trigger (USA).sfc",
            preferredRegion = "USA",
            candidates = files("Chrono Trigger (USA).sfc.zip"),
        )
        assertEquals("Chrono Trigger (USA).sfc.zip", match?.filename)
    }

    @Test
    fun `matches by title alone when no rom filename is known`() {
        val match = RomMatcher.bestMatch(
            title = "The Legend of Zelda: Ocarina of Time",
            romFileName = null,
            preferredRegion = "USA",
            candidates = files(
                "Super Mario 64 (USA).z64",
                "Legend of Zelda, The - Ocarina of Time (USA).z64",
            ),
        )
        assertEquals("Legend of Zelda, The - Ocarina of Time (USA).z64", match?.filename)
    }

    @Test
    fun `rejects a close-but-different title`() {
        // "Mario Kart 64" must not match "Super Mario 64".
        val match = RomMatcher.bestMatch(
            title = "Mario Kart 64",
            romFileName = null,
            preferredRegion = "USA",
            candidates = files("Super Mario 64 (USA).z64"),
        )
        assertNull(match)
    }

    @Test
    fun `ignores non-rom files`() {
        val match = RomMatcher.bestMatch(
            title = "Super Metroid",
            romFileName = null,
            preferredRegion = "USA",
            candidates = files(
                "Super Metroid (USA).png",
                "Super Metroid (USA).txt",
                "Super Metroid (Japan, USA).sfc",
            ),
        )
        assertEquals("Super Metroid (Japan, USA).sfc", match?.filename)
    }

    @Test
    fun `normalizeTitle handles articles and ampersands`() {
        assertEquals(
            RomMatcher.normalizeTitle("Legend of Zelda, The"),
            RomMatcher.normalizeTitle("The Legend of Zelda"),
        )
        assertEquals(
            RomMatcher.normalizeTitle("Ratchet and Clank"),
            RomMatcher.normalizeTitle("Ratchet & Clank"),
        )
    }

    @Test
    fun `titlesMatch tolerates punctuation and region suffixes`() {
        assertTrue(RomMatcher.titlesMatch("Super Mario 64", "Super Mario 64 (USA)"))
        assertTrue(
            RomMatcher.titlesMatch(
                "The Legend of Zelda: Ocarina of Time",
                "Legend of Zelda, The - Ocarina of Time",
            ),
        )
        assertFalse(RomMatcher.titlesMatch("Super Mario 64", "Mario Kart 64"))
    }

    @Test
    fun `titlesMatch accepts an IA item title with a region word suffix`() {
        // Why BotW now resolves: the IA item is titled "...Breath Of The Wild EUR".
        assertTrue(
            RomMatcher.titlesMatch(
                "The Legend Of Zelda Breath Of The Wild EUR",
                "The Legend of Zelda: Breath of the Wild",
            ),
        )
        // ...but a different Zelda must not slip through.
        assertFalse(
            RomMatcher.titlesMatch(
                "The Legend of Zelda: Tears of the Kingdom",
                "The Legend of Zelda: Breath of the Wild",
            ),
        )
    }

    @Test
    fun `switch nsp with title-id brackets matches`() {
        val match = RomMatcher.bestMatch(
            title = "Super Mario Odyssey",
            romFileName = "Super Mario Odyssey (World).nsp",
            preferredRegion = "World",
            candidates = files("Super Mario Odyssey [0100000000010000][v0].nsp"),
        )
        assertEquals("Super Mario Odyssey [0100000000010000][v0].nsp", match?.filename)
    }

    @Test
    fun `prefers USA over Japan when both match by title`() {
        val match = RomMatcher.bestMatch(
            title = "Chrono Trigger",
            romFileName = null,
            preferredRegion = "USA",
            candidates = files(
                "Chrono Trigger (Japan).sfc",
                "Chrono Trigger (USA).sfc",
                "Chrono Trigger (Europe).sfc",
            ),
        )
        assertEquals("Chrono Trigger (USA).sfc", match?.filename)
    }

    @Test
    fun `falls back to Japan when no English copy exists`() {
        val match = RomMatcher.bestMatch(
            title = "Mother 3",
            romFileName = null,
            preferredRegion = "USA",
            candidates = files("Mother 3 (Japan).gba"),
        )
        assertEquals("Mother 3 (Japan).gba", match?.filename)
    }

    @Test
    fun `switch nsp extension is recognized`() {
        val match = RomMatcher.bestMatch(
            title = "Super Mario Odyssey",
            romFileName = "Super Mario Odyssey (World).nsp",
            preferredRegion = "World",
            candidates = files("Super Mario Odyssey (World).nsp"),
        )
        assertEquals("Super Mario Odyssey (World).nsp", match?.filename)
    }
}
