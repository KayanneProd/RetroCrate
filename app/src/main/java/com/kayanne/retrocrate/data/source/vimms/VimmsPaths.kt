package com.kayanne.retrocrate.data.source.vimms

import com.kayanne.retrocrate.domain.model.Platform

object VimmsPaths {

    const val BASE = "https://vimm.net"

    // Vault path segment per platform — verified against Vimm's nav on vimm.net/vault/ (2026-05-25).
    private val PLATFORM_SLUG: Map<Platform, String> = mapOf(
        Platform.NES to "NES",
        Platform.SNES to "SNES",
        Platform.N64 to "N64",
        Platform.GAMECUBE to "GameCube",
        Platform.WII to "Wii",
        Platform.GAME_BOY to "GB",
        Platform.GAME_BOY_COLOR to "GBC",
        Platform.GAME_BOY_ADVANCE to "GBA",
        Platform.NINTENDO_DS to "DS",
        Platform.GENESIS to "Genesis",
        Platform.SATURN to "Saturn",
        Platform.DREAMCAST to "Dreamcast",
        Platform.PS1 to "PS1",
        Platform.PS2 to "PS2",
        Platform.PSP to "PSP",
    )

    // Vimm's paginates game lists alphabetically — '#' for digits/symbols, A-Z for letters.
    val ALPHABET_SECTIONS: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }

    fun vaultUrlForSection(platform: Platform, section: String): String? =
        PLATFORM_SLUG[platform]?.let { "$BASE/vault/$it/$section" }

    fun gameDetailUrl(vimmsId: String): String = "$BASE/vault/$vimmsId"
}
