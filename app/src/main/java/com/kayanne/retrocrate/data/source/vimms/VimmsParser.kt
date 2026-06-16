package com.kayanne.retrocrate.data.source.vimms

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

// Parsed entry from a Vimm's Lair vault listing page. Maps to one row in the games table.
data class VimmsVaultEntry(
    val vimmsId: String,
    val title: String,
    val region: String? = null,
    val year: Int? = null,
)

object VimmsParser {

    // Parses a Vimm's vault listing page HTML and returns the list of game entries.
    // The vault table shape (as of late 2025): rows where the first <a> has href "/vault/<numericId>"
    // and contains the title. Region/year live in adjacent <td> cells.
    fun parseVaultListing(html: String): List<VimmsVaultEntry> {
        val doc = Jsoup.parse(html)
        val rows = doc.select("table tr")
        return rows.mapNotNull { row -> parseRow(row) }
    }

    private fun parseRow(row: Element): VimmsVaultEntry? {
        // Each game row leads with an empty placeholder anchor (`<a href="/vault/999999"></a>`, a
        // scroll target); the real game link is the next `/vault/<id>` anchor that actually carries
        // the title. Blindly taking the first anchor matched the placeholder — its title is blank —
        // and dropped every row, so the whole listing parsed to nothing.
        val anchor = row.select("a[href^=/vault/]").firstOrNull { a ->
            val id = a.attr("href").removePrefix("/vault/").trim()
            id.isNotEmpty() && id.all { it.isDigit() } && a.text().isNotBlank()
        } ?: return null

        val vimmsId = anchor.attr("href").removePrefix("/vault/").trim()
        val title = anchor.text().trim()

        val cells = row.select("td")
        val region = extractRegion(cells)
        val year = extractYear(cells)

        return VimmsVaultEntry(
            vimmsId = vimmsId,
            title = title,
            region = region,
            year = year,
        )
    }

    // Vimm's marks region with a flag image whose title attribute is the country, e.g. "United States".
    // Fall back to checking cell text for short codes like "USA", "Europe", "Japan".
    private fun extractRegion(cells: org.jsoup.select.Elements): String? {
        cells.select("img[title]").firstOrNull()?.let { return it.attr("title").trim().ifBlank { null } }
        val knownRegions = setOf("USA", "Europe", "Japan", "Australia", "World", "Korea")
        cells.forEach { cell ->
            val text = cell.text().trim()
            if (text in knownRegions) return text
        }
        return null
    }

    // Year typically appears as a 4-digit cell. Use the first 4-digit number we find that's
    // in a plausible release-year range.
    private fun extractYear(cells: org.jsoup.select.Elements): Int? {
        cells.forEach { cell ->
            val text = cell.text().trim()
            val year = text.toIntOrNull()
            if (year != null && year in 1970..2030) return year
        }
        return null
    }
}
