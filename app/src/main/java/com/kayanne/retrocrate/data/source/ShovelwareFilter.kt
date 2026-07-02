package com.kayanne.retrocrate.data.source

import com.kayanne.retrocrate.data.source.titledb.isNotablePublisher
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform

// Runtime catalog-visibility filters, applied over the whole catalog so every rail/search/collection
// inherits them (GameCatalogRepository). Both back user-facing Settings toggles (default ON).

// Hide low-effort eShop shovelware. Switch-only: the retro catalog comes from OpenVGDB's curated
// release set (real, catalogued games), so there's nothing to filter there. The heuristic is looser
// than New Arrivals' quality cut (which also demands an 80-char description) so genuine obscure indies
// survive: a game is shovelware only if it's from an unrecognized publisher AND has essentially no
// description. A real indie almost always ships a proper store description even when self-published.
object ShovelwareFilter {

    private const val MIN_REAL_DESCRIPTION = 40

    fun isShovelware(game: Game): Boolean {
        if (game.platform != Platform.SWITCH) return false
        if (isNotablePublisher(game.publisher)) return false
        val descLength = game.description?.trim()?.length ?: 0
        return descLength < MIN_REAL_DESCRIPTION
    }
}

// True when a release's region is English-speaking (or unknown). Used to drop the Spanish/Japanese/etc.
// regional duplicate entries and import-only titles when the user has the "English / USA only" toggle on.
// Unknown/blank regions pass through — better to show an untagged game than to silently hide a real one.
fun isEnglishRegion(region: String?): Boolean {
    if (region.isNullOrBlank()) return true
    val r = region.lowercase()
    return ENGLISH_REGION_WORDS.any { r.contains(it) }
}

private val ENGLISH_REGION_WORDS = setOf(
    "usa", "world", "europe", "australia", "canada", "uk", "united kingdom", "united states",
)
