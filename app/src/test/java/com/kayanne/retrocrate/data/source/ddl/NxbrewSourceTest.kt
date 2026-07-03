package com.kayanne.retrocrate.data.source.ddl

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NxbrewSourceTest {

    // Mirrors NXBrew's real markup: two regions, each with a base section (host in <strong>, anchor
    // "Download") and update/DLC sections (hosts as comma-separated anchor text). MultiUp is a gateway
    // we intentionally can't unlock, so it should never survive.
    private val deadCellsHtml = """
        <article>
        <p><strong>Download Links</strong></p>
        <p><strong>Region Asia [0100FC000AEF0000]</strong></p>
        <p><strong>Base Game NSP (444 MB)</strong></p>
        <p><strong>DataNodes</strong><br><a href="https://ouo.io/asia1">Download</a></p>
        <p><strong>1Fichier</strong><br><a href="https://ouo.io/asia2">Download</a></p>
        <p><strong>MultiUp</strong><br><a href="https://ouo.io/asia3">Download</a></p>
        <p><strong>Update 1.25.0 (v2686976)</strong></p>
        <p><a href="https://ouo.io/asiau1">DataNodes</a>, <a href="https://ouo.io/asiau2">1Fichier</a></p>
        <p><strong>DLC Pack (5 DLCs)</strong></p>
        <p><a href="https://ouo.io/asiad1">DataNodes</a>, <a href="https://ouo.io/asiad2">1Fichier</a></p>
        <p><strong>Region USA [0100646009FBE000]</strong></p>
        <p><strong>Base Game NSP (443 MB)</strong></p>
        <p><strong>DataNodes</strong><br><a href="https://ouo.io/usa1">Download</a></p>
        <p><strong>1Fichier</strong><br><a href="https://ouo.io/usa2">Download</a></p>
        <p><strong>MultiUp</strong><br><a href="https://ouo.io/usa3">Download</a></p>
        <p><strong>Update 1.25.0 (v3014656)</strong></p>
        <p><a href="https://ouo.io/usau1">DataNodes</a>, <a href="https://ouo.io/usau2">1Fichier</a></p>
        <p><strong>DLC Pack (5 DLCs)</strong></p>
        <p><a href="https://ouo.io/usad1">DataNodes</a>, <a href="https://ouo.io/usad2">1Fichier</a></p>
        </article>
    """.trimIndent()

    private val tomodachiHtml = """
        <article>
        <p><strong>Download Links</strong></p>
        <p><strong>Base Game XCI (6.58 GB)</strong></p>
        <p><strong>DataNodes</strong><br><a href="https://ouo.io/b1">Download</a></p>
        <p><strong>1Fichier</strong><br><a href="https://ouo.io/b2">Download</a></p>
        <p><strong>Update v1.0.1 (v65536)</strong></p>
        <p><a href="https://ouo.io/u1a">DataNodes</a>, <a href="https://ouo.io/u1b">1Fichier</a></p>
        <p><strong>Update v1.0.2 (v131072)</strong></p>
        <p><a href="https://ouo.io/u2a">DataNodes</a>, <a href="https://ouo.io/u2b">1Fichier</a></p>
        <p><strong>Update 1.0.3 (v196608)</strong></p>
        <p><a href="https://ouo.io/u3a">DataNodes</a>, <a href="https://ouo.io/u3b">1Fichier</a></p>
        </article>
    """.trimIndent()

    @Test
    fun `prefers USA region and drops other regions`() {
        val files = NxbrewSource.parseFiles(Jsoup.parse(deadCellsHtml))
        assertTrue(files.isNotEmpty())
        assertTrue(files.all { it.region == "USA" })
    }

    @Test
    fun `drops the MultiUp gateway host`() {
        val files = NxbrewSource.parseFiles(Jsoup.parse(deadCellsHtml))
        assertFalse(files.any { it.host == "MultiUp" })
        assertTrue(files.any { it.host == "1Fichier" })
        assertTrue(files.any { it.host == "DataNodes" })
    }

    @Test
    fun `splits base update and dlc sections`() {
        val files = NxbrewSource.parseFiles(Jsoup.parse(deadCellsHtml))
        val sections = files.map { it.section }.toSet()
        assertEquals(setOf(NxbrewSection.BASE, NxbrewSection.UPDATE, NxbrewSection.DLC), sections)
    }

    @Test
    fun `keeps each update version as a distinct variant`() {
        val files = NxbrewSource.parseFiles(Jsoup.parse(tomodachiHtml))
        val updateVariants = files.filter { it.section == NxbrewSection.UPDATE }.map { it.variantLabel }.distinct()
        assertEquals(3, updateVariants.size)
        assertTrue(files.all { it.region == null })
    }

    @Test
    fun `matches the same game with format noise on the result`() {
        assertTrue(
            NxbrewSource.titleCovered("Kill It With Fire Switch NSP (eShop)", "Kill It With Fire"),
        )
    }

    @Test
    fun `does not match a numbered sequel to the base game`() {
        assertFalse(
            NxbrewSource.titleCovered("Kill It With Fire 2 Switch NSP", "Kill It With Fire"),
        )
    }

    @Test
    fun `matches a sequel to the correct sequel query`() {
        assertTrue(
            NxbrewSource.titleCovered("Kill It With Fire 2 Switch NSP", "Kill It With Fire 2"),
        )
    }

    @Test
    fun `does not match the base game to a sequel query`() {
        assertFalse(
            NxbrewSource.titleCovered("Kill It With Fire Switch NSP", "Kill It With Fire 2"),
        )
    }

    @Test
    fun `distinguishes roman-numeral sequels`() {
        assertTrue(NxbrewSource.titleCovered("Bayonetta II NSP", "Bayonetta II"))
        assertFalse(NxbrewSource.titleCovered("Bayonetta II NSP", "Bayonetta"))
    }

    @Test
    fun `a version string on the base game does not count as a sequel number`() {
        assertTrue(
            NxbrewSource.titleCovered("Kill It With Fire Switch NSP + Update v1.0.1", "Kill It With Fire"),
        )
    }

    @Test
    fun `rejects an unrelated title`() {
        assertFalse(
            NxbrewSource.titleCovered("Super Mario Odyssey Switch NSP", "Kill It With Fire"),
        )
    }

    @Test
    fun `search term strips punctuation that breaks NXBrew search`() {
        // The "!" made NXBrew's WordPress search return nothing; the game is found without it.
        assertEquals("Kill It With Fire 2", NxbrewSource.searchTerm("Kill It With Fire! 2"))
    }

    @Test
    fun `search term keeps accented letters and drops symbols`() {
        assertEquals("Pokémon Legends Arceus", NxbrewSource.searchTerm("Pokémon Legends: Arceus"))
        assertEquals("Fire Emblem Engage", NxbrewSource.searchTerm("Fire Emblem™ Engage"))
    }
}
