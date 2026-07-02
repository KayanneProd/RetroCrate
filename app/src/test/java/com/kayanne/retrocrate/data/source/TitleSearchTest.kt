package com.kayanne.retrocrate.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleSearchTest {

    @Test
    fun `apostrophe-insensitive — the bug that hid Yoshi's Crafted World`() {
        // Typed without the apostrophe, the old `contains` missed entirely.
        assertTrue(TitleSearch.score("yoshis crafted world", "Yoshi's Crafted World") > 0.0)
        assertTrue(TitleSearch.score("yoshi's crafted world", "Yoshi's Crafted World") > 0.0)
    }

    @Test
    fun `partial words match (search-as-you-type)`() {
        assertTrue(TitleSearch.score("yoshi craft", "Yoshi's Crafted World") > 0.0)
        assertTrue(TitleSearch.score("zel", "The Legend of Zelda") > 0.0)
    }

    @Test
    fun `ampersand and 'and' are interchangeable`() {
        assertTrue(TitleSearch.score("ratchet and clank", "Ratchet & Clank") > 0.0)
    }

    @Test
    fun `unrelated titles do not match`() {
        assertEquals(0.0, TitleSearch.score("zelda", "Super Mario Odyssey"), 0.0)
        assertEquals(0.0, TitleSearch.score("", "Anything"), 0.0)
    }

    @Test
    fun `exact beats prefix beats partial`() {
        val exact = TitleSearch.score("mario", "Mario")
        val prefix = TitleSearch.score("mario", "Mario Kart 8 Deluxe")
        val partial = TitleSearch.score("mario kart", "Super Mario Kart Deluxe Special")
        assertTrue(exact > prefix)
        assertTrue(prefix > partial)
    }

    @Test
    fun `rank drops non-matches and orders by relevance`() {
        val titles = listOf("Super Mario Odyssey", "Mario Kart 8 Deluxe", "Mario", "The Legend of Zelda")
        val ranked = TitleSearch.rank("mario", titles) { it }
        assertEquals("Mario", ranked.first())
        assertTrue("The Legend of Zelda" !in ranked)
    }
}
