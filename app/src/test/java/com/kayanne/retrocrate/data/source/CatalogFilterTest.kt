package com.kayanne.retrocrate.data.source

import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform
import com.kayanne.retrocrate.domain.model.Source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogFilterTest {

    @Test
    fun `english regions and unknown pass, others are dropped`() {
        assertTrue(isEnglishRegion("USA"))
        assertTrue(isEnglishRegion("World"))
        assertTrue(isEnglishRegion("Europe"))
        assertTrue(isEnglishRegion("USA, Europe"))
        assertTrue(isEnglishRegion(null))
        assertTrue(isEnglishRegion(""))

        assertFalse(isEnglishRegion("Japan"))
        assertFalse(isEnglishRegion("Spain"))
        assertFalse(isEnglishRegion("Korea"))
        assertFalse(isEnglishRegion("France"))
    }

    private fun switchGame(
        title: String,
        publisher: String?,
        description: String?,
    ) = Game(
        id = "switch:${title.lowercase()}",
        title = title,
        platform = Platform.SWITCH,
        publisher = publisher,
        description = description,
        sources = listOf(Source("s", "Internet Archive", "World", null, "$title (World).nsp")),
    )

    @Test
    fun `shovelware - unknown publisher with no real description is hidden`() {
        val junk = switchGame("Anime Girls Puzzle", publisher = "SmallStudio LLC", description = "A puzzle game.")
        assertTrue(ShovelwareFilter.isShovelware(junk))
    }

    @Test
    fun `real indie with a genuine description survives even from an unknown publisher`() {
        val indie = switchGame(
            "Tiny Cosmonaut",
            publisher = "Some Indie",
            description = "A hand-drawn metroidvania about a stranded astronaut exploring a derelict station, " +
                "with a grappling hook and an evolving map.",
        )
        assertFalse(ShovelwareFilter.isShovelware(indie))
    }

    @Test
    fun `notable publisher is never shovelware`() {
        val firstParty = switchGame("Some Game", publisher = "Nintendo", description = null)
        assertFalse(ShovelwareFilter.isShovelware(firstParty))
    }

    @Test
    fun `retro games are never treated as shovelware`() {
        val retro = Game(
            id = "openvgdb:nes:foo",
            title = "Foo",
            platform = Platform.NES,
            publisher = null,
            description = null,
        )
        assertFalse(ShovelwareFilter.isShovelware(retro))
    }
}
