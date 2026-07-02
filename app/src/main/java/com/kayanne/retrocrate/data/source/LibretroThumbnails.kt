package com.kayanne.retrocrate.data.source

import com.kayanne.retrocrate.domain.model.Platform

// Builds public box-art / snap URLs from the libretro-thumbnails GitHub repos.
// Pattern: https://raw.githubusercontent.com/libretro-thumbnails/<repo>/master/Named_Boxarts/<filename>.png
//
// Vimm's titles don't include the region suffix that libretro expects, so we append "(USA)" by default
// and let calls without art fall through to a placeholder. A more sophisticated pass could try
// alternative region suffixes, but USA covers ~90% of the catalog for the platforms we target.
object LibretroThumbnails {

    // Repo names verified against the libretro-thumbnails GitHub org (2026-06-28).
    val REPO_FOR: Map<Platform, String> = mapOf(
        Platform.NES to "Nintendo_-_Nintendo_Entertainment_System",
        Platform.SNES to "Nintendo_-_Super_Nintendo_Entertainment_System",
        Platform.N64 to "Nintendo_-_Nintendo_64",
        Platform.GAMECUBE to "Nintendo_-_GameCube",
        Platform.WII to "Nintendo_-_Wii",
        Platform.WII_U to "Nintendo_-_Wii_U",
        Platform.GAME_BOY to "Nintendo_-_Game_Boy",
        Platform.GAME_BOY_COLOR to "Nintendo_-_Game_Boy_Color",
        Platform.GAME_BOY_ADVANCE to "Nintendo_-_Game_Boy_Advance",
        Platform.VIRTUAL_BOY to "Nintendo_-_Virtual_Boy",
        Platform.NINTENDO_DS to "Nintendo_-_Nintendo_DS",
        Platform.NINTENDO_3DS to "Nintendo_-_Nintendo_3DS",
        Platform.GENESIS to "Sega_-_Mega_Drive_-_Genesis",
        Platform.SEGA_MASTER_SYSTEM to "Sega_-_Master_System_-_Mark_III",
        Platform.GAME_GEAR to "Sega_-_Game_Gear",
        Platform.SEGA_CD to "Sega_-_Mega-CD_-_Sega_CD",
        Platform.SEGA_32X to "Sega_-_32X",
        Platform.SATURN to "Sega_-_Saturn",
        Platform.DREAMCAST to "Sega_-_Dreamcast",
        Platform.TURBOGRAFX_16 to "NEC_-_PC_Engine_-_TurboGrafx_16",
        Platform.ATARI_2600 to "Atari_-_2600",
        Platform.ATARI_5200 to "Atari_-_5200",
        Platform.ATARI_7800 to "Atari_-_7800",
        Platform.ATARI_LYNX to "Atari_-_Lynx",
        Platform.ATARI_JAGUAR to "Atari_-_Jaguar",
        Platform.THREEDO to "The_3DO_Company_-_3DO",
        Platform.PS1 to "Sony_-_PlayStation",
        Platform.PS2 to "Sony_-_PlayStation_2",
        Platform.PSP to "Sony_-_PlayStation_Portable",
        Platform.PS_VITA to "Sony_-_PlayStation_Vita",
    )

    private const val BASE = "https://raw.githubusercontent.com/libretro-thumbnails"

    fun boxArtUrl(platform: Platform, title: String, region: String = "USA"): String? {
        val repo = REPO_FOR[platform] ?: return null
        val filename = "${title.replace(":", " -")} ($region).png"
        return "$BASE/$repo/master/Named_Boxarts/${filename.urlEncodePath()}"
    }

    // Direct box-art URL for an exact Named_Boxarts filename (e.g. "Final Fantasy X (USA).png").
    // Used by LibretroCatalogSource, which lists those filenames to build the catalog for consoles
    // OpenVGDB doesn't cover — so the art always resolves (it's the real file, not a guessed suffix).
    fun boxArtUrlForFile(platform: Platform, pngFilename: String): String? {
        val repo = REPO_FOR[platform] ?: return null
        return "$BASE/$repo/master/Named_Boxarts/${pngFilename.urlEncodePath()}"
    }

    fun snapUrl(platform: Platform, title: String, region: String = "USA"): String? {
        val repo = REPO_FOR[platform] ?: return null
        val filename = "${title.replace(":", " -")} ($region).png"
        return "$BASE/$repo/master/Named_Snaps/${filename.urlEncodePath()}"
    }

    // Gameplay snapshot from the exact No-Intro/Redump base name (the source's romFileName minus its
    // extension) — libretro's Named_Snaps are keyed on that name, so this hits far more reliably than
    // guessing a region suffix. Returns null for platforms libretro doesn't cover (e.g. Switch).
    fun snapUrlFromBase(platform: Platform, baseName: String): String? {
        val repo = REPO_FOR[platform] ?: return null
        val filename = "${baseName.replace(":", " -")}.png"
        return "$BASE/$repo/master/Named_Snaps/${filename.urlEncodePath()}"
    }

    private fun String.urlEncodePath(): String =
        this.replace(" ", "%20")
}
