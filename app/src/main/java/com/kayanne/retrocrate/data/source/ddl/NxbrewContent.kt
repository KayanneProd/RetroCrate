package com.kayanne.retrocrate.data.source.ddl

// Which part of an NXBrew game page a download link belongs to. NXBrew lists a game's base release, its
// title updates, and its DLC as separate labelled sections; surfacing them lets the user grab an update
// or DLC without hunting, and lets the picker disambiguate "the game" from "the game's update".
enum class NxbrewSection(val label: String) {
    BASE("Base game"),
    UPDATE("Update"),
    DLC("DLC"),
}

// One NXBrew search hit — a candidate game page. [confident] marks the ones whose title actually matches
// the request (vs. a looser result kept only so the user can still pick it during disambiguation).
data class NxbrewGame(
    val title: String,
    val pageUrl: String,
    val confident: Boolean,
)

// One host-labelled download link on a game page. A page can list several variants within a section
// (multiple update versions, several DLC packs) and several regions of the game, so a link carries the
// exact [variantLabel] it sat under ("Update v1.0.2 (v131072)", "DLC Pack (5 DLCs)", "Base Game NSP
// (6.58 GB)") and its [region] ("USA"/"Asia"/…, null when the page doesn't split by region) — otherwise
// three different updates would collapse into one row. The URL is an ad-shortener (ouo.io) the WebView
// ad-gate resolves to a real file host before debrid unlocks it.
data class NxbrewFile(
    val section: NxbrewSection,
    val variantLabel: String,
    val region: String?,
    val host: String,
    val ouoUrl: String,
)
