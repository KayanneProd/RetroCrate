package com.kayanne.retrocrate.data.source.vimms

import com.kayanne.retrocrate.domain.model.Platform

object VimmsPaths {

    const val BASE = "https://vimm.net"

    // Vault path segment per platform — verified against Vimm's nav on vimm.net/vault/ (2026-06-28).
    // 3DO is in our catalog (OpenVGDB) but Vimm's doesn't vault it, so it has no slug here and falls
    // through to debrid/IA at download time. Same for Wii U / Vita.
    private val PLATFORM_SLUG: Map<Platform, String> = mapOf(
        Platform.NES to "NES",
        Platform.SNES to "SNES",
        Platform.N64 to "N64",
        Platform.GAMECUBE to "GameCube",
        Platform.WII to "Wii",
        Platform.GAME_BOY to "GB",
        Platform.GAME_BOY_COLOR to "GBC",
        Platform.GAME_BOY_ADVANCE to "GBA",
        Platform.VIRTUAL_BOY to "VB",
        Platform.NINTENDO_DS to "DS",
        Platform.NINTENDO_3DS to "3DS",
        Platform.GENESIS to "Genesis",
        Platform.SEGA_MASTER_SYSTEM to "SMS",
        Platform.GAME_GEAR to "GG",
        Platform.SEGA_CD to "SegaCD",
        Platform.SEGA_32X to "32X",
        Platform.SATURN to "Saturn",
        Platform.DREAMCAST to "Dreamcast",
        Platform.TURBOGRAFX_16 to "TG16",
        Platform.ATARI_2600 to "Atari2600",
        Platform.ATARI_5200 to "Atari5200",
        Platform.ATARI_7800 to "Atari7800",
        Platform.ATARI_LYNX to "Lynx",
        Platform.ATARI_JAGUAR to "Jaguar",
        Platform.PS1 to "PS1",
        Platform.PS2 to "PS2",
        Platform.PSP to "PSP",
    )

    // Vimm's paginates game lists alphabetically — '#' for digits/symbols, A-Z for letters.
    val ALPHABET_SECTIONS: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }

    // Whether Vimm's vaults this platform at all (so the source picker can hide Vimm's for, e.g.,
    // Switch / Wii U / Vita that it doesn't carry).
    fun vaults(platform: Platform): Boolean = PLATFORM_SLUG.containsKey(platform)

    fun vaultUrlForSection(platform: Platform, section: String): String? =
        PLATFORM_SLUG[platform]?.let { "$BASE/vault/$it/$section" }

    fun gameDetailUrl(vimmsId: String): String = "$BASE/vault/$vimmsId"
}
