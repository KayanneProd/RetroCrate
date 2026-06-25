package com.kayanne.retrocrate.data.source

import com.kayanne.retrocrate.domain.model.Platform

// Builds public box-art / snap URLs from the libretro-thumbnails GitHub repos.
// Pattern: https://raw.githubusercontent.com/libretro-thumbnails/<repo>/master/Named_Boxarts/<filename>.png
//
// Vimm's titles don't include the region suffix that libretro expects, so we append "(USA)" by default
// and let calls without art fall through to a placeholder. A more sophisticated pass could try
// alternative region suffixes, but USA covers ~90% of the catalog for the platforms we target.
object LibretroThumbnails {

    private val REPO_FOR: Map<Platform, String> = mapOf(
        Platform.NES to "Nintendo_-_Nintendo_Entertainment_System",
        Platform.SNES to "Nintendo_-_Super_Nintendo_Entertainment_System",
        Platform.N64 to "Nintendo_-_Nintendo_64",
        Platform.GAMECUBE to "Nintendo_-_GameCube",
        Platform.WII to "Nintendo_-_Wii",
        Platform.GAME_BOY to "Nintendo_-_Game_Boy",
        Platform.GAME_BOY_COLOR to "Nintendo_-_Game_Boy_Color",
        Platform.GAME_BOY_ADVANCE to "Nintendo_-_Game_Boy_Advance",
        Platform.NINTENDO_DS to "Nintendo_-_Nintendo_DS",
        Platform.GENESIS to "Sega_-_Mega_Drive_-_Genesis",
        Platform.SATURN to "Sega_-_Saturn",
        Platform.DREAMCAST to "Sega_-_Dreamcast",
        Platform.PS1 to "Sony_-_PlayStation",
        Platform.PS2 to "Sony_-_PlayStation_2",
        Platform.PSP to "Sony_-_PlayStation_Portable",
    )

    private const val BASE = "https://raw.githubusercontent.com/libretro-thumbnails"

    fun boxArtUrl(platform: Platform, title: String, region: String = "USA"): String? {
        val repo = REPO_FOR[platform] ?: return null
        val filename = "${title.replace(":", " -")} ($region).png"
        return "$BASE/$repo/master/Named_Boxarts/${filename.urlEncodePath()}"
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
