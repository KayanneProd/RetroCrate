package com.kayanne.retrocrate.core.designsystem

import androidx.compose.ui.graphics.Color

// Steam Big Picture / Deck palette. Dark only. See LLM-CONTEXT.md §7 for rationale.

// Surfaces
val SteamBackground = Color(0xFF16181F)        // Deck very-dark base
val SteamSurface = Color(0xFF1B2838)            // Classic Steam navy
val SteamSurfaceContainer = Color(0xFF243447)   // One step elevated
val SteamSurfaceContainerHigh = Color(0xFF2A3F5F) // Sheets, dialogs
val SteamOutline = Color(0xFF3F4A5F)            // Card borders, dividers
val SteamOutlineVariant = Color(0xFF2A3242)     // Subtle dividers

// Brand accents
val SteamCyan = Color(0xFF66C0F4)               // Signature accent: selection, links, focus
val SteamCyanContainer = Color(0xFF1A4D5E)      // Chips, selected states
val SteamCyanOn = Color(0xFF001F2A)             // Text on cyan
val SteamCyanOnContainer = Color(0xFFC8E7F5)    // Text on cyan container

val SteamInstallGreen = Color(0xFFA4D007)       // Download / Install CTA
val SteamInstallGreenOn = Color(0xFF1A2400)
val SteamInstallGreenContainer = Color(0xFF3A4B00)
val SteamInstallGreenOnContainer = Color(0xFFD4E97A)

val SteamGold = Color(0xFFFFC83D)               // Featured / premium / rare
val SteamGoldOn = Color(0xFF3A2800)

// Text / on-surface
val SteamOnSurface = Color(0xFFE8EEF4)          // Primary text — 11.8:1 on SteamSurface (AAA)
val SteamOnSurfaceVariant = Color(0xFF9BA8B5)   // Secondary text, labels

// Status
val SteamError = Color(0xFFE5484D)
val SteamErrorContainer = Color(0xFF5A1A1C)
val SteamErrorOn = Color(0xFFFFFFFF)
val SteamErrorOnContainer = Color(0xFFFFD4D6)
