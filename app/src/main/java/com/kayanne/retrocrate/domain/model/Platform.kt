package com.kayanne.retrocrate.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class Platform(val displayName: String) {
    // Nintendo
    NES("Nintendo Entertainment System"),
    SNES("Super Nintendo"),
    N64("Nintendo 64"),
    GAMECUBE("GameCube"),
    WII("Wii"),
    WII_U("Wii U"),
    SWITCH("Nintendo Switch"),
    GAME_BOY("Game Boy"),
    GAME_BOY_COLOR("Game Boy Color"),
    GAME_BOY_ADVANCE("Game Boy Advance"),
    VIRTUAL_BOY("Virtual Boy"),
    NINTENDO_DS("Nintendo DS"),
    NINTENDO_3DS("Nintendo 3DS"),
    // Sega
    GENESIS("Sega Genesis"),
    SEGA_MASTER_SYSTEM("Master System"),
    GAME_GEAR("Game Gear"),
    SEGA_CD("Sega CD"),
    SEGA_32X("Sega 32X"),
    SATURN("Sega Saturn"),
    DREAMCAST("Sega Dreamcast"),
    // NEC
    TURBOGRAFX_16("TurboGrafx-16"),
    // Atari
    ATARI_2600("Atari 2600"),
    ATARI_5200("Atari 5200"),
    ATARI_7800("Atari 7800"),
    ATARI_LYNX("Atari Lynx"),
    ATARI_JAGUAR("Atari Jaguar"),
    // Other
    THREEDO("3DO"),
    // Sony
    PS1("PlayStation"),
    PS2("PlayStation 2"),
    PSP("PlayStation Portable"),
    PS_VITA("PlayStation Vita"),
}
