package com.kayanne.retrocrate.data.source.vimms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VimmsParserTest {

    @Test
    fun parsesBasicVaultListing() {
        val html = """
            <html>
            <body>
              <table>
                <tr><th>Title</th><th>Region</th><th>Year</th></tr>
                <tr>
                  <td><a href="/vault/12345">Super Mario 64</a></td>
                  <td><img title="United States" /></td>
                  <td>1996</td>
                </tr>
                <tr>
                  <td><a href="/vault/67890">The Legend of Zelda: Ocarina of Time</a></td>
                  <td><img title="United States" /></td>
                  <td>1998</td>
                </tr>
              </table>
            </body>
            </html>
        """.trimIndent()

        val entries = VimmsParser.parseVaultListing(html)
        assertEquals(2, entries.size)

        val mario = entries[0]
        assertEquals("12345", mario.vimmsId)
        assertEquals("Super Mario 64", mario.title)
        assertEquals("United States", mario.region)
        assertEquals(1996, mario.year)

        val zelda = entries[1]
        assertEquals("67890", zelda.vimmsId)
        assertEquals("The Legend of Zelda: Ocarina of Time", zelda.title)
        assertEquals(1998, zelda.year)
    }

    @Test
    fun skipsHeaderAndNonGameRows() {
        val html = """
            <table>
              <tr><th>Title</th></tr>
              <tr><td>Just text, no link</td></tr>
              <tr><td><a href="/help">Help</a></td></tr>
              <tr><td><a href="/vault/42">Real Game</a></td></tr>
            </table>
        """.trimIndent()

        val entries = VimmsParser.parseVaultListing(html)
        assertEquals(1, entries.size)
        assertEquals("42", entries[0].vimmsId)
        assertEquals("Real Game", entries[0].title)
    }

    @Test
    fun handlesMissingRegionAndYear() {
        val html = """
            <table>
              <tr>
                <td><a href="/vault/100">Mystery Game</a></td>
                <td>Players: 1</td>
              </tr>
            </table>
        """.trimIndent()

        val entries = VimmsParser.parseVaultListing(html)
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertNotNull(entry)
        assertEquals("Mystery Game", entry.title)
        // region and year are nullable — both should be null here
        assertTrue(entry.region == null)
        assertTrue(entry.year == null)
    }

    @Test
    fun ignoresHrefsThatAreNotNumericVaultLinks() {
        val html = """
            <table>
              <tr><td><a href="/vault/about">About Page</a></td></tr>
              <tr><td><a href="/vault/12345">Real Game</a></td></tr>
            </table>
        """.trimIndent()

        val entries = VimmsParser.parseVaultListing(html)
        assertEquals(1, entries.size)
        assertEquals("12345", entries[0].vimmsId)
    }
}
