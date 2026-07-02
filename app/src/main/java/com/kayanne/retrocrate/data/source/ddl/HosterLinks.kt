package com.kayanne.retrocrate.data.source.ddl

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// File hosts a debrid service can turn into a direct link. The WebView ad-gate flow watches for a
// navigation to one of these and captures that URL; NXBrew exposes per-host download links and we keep
// only the ones that land on a single debrid-friendly host. Ordered loosely by debrid reliability.
object HosterLinks {

    val HOSTER_DOMAINS: List<String> = listOf(
        "1fichier.com",
        "mega.nz",
        "mediafire.com",
        "pixeldrain.com",
        "gofile.io",
        "datanodes.to",
        "rapidgator.net",
        "qiwi.gg",
    )

    // True once the WebView has left the ad-shortener and landed on the actual file host — the signal
    // to capture the URL and hand it to debrid.
    fun isHosterUrl(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return HOSTER_DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    // Maps a download link's visible text to the file host it targets, for labelling the picker row.
    // Null for hosts we either can't capture (MultiUp is a gateway to several) or don't recognize.
    fun hostLabel(text: String): String? {
        val t = text.lowercase()
        return when {
            "1fichier" in t -> "1Fichier"
            "datanodes" in t -> "DataNodes"
            "mediafire" in t -> "MediaFire"
            "pixeldrain" in t -> "PixelDrain"
            "gofile" in t -> "GoFile"
            "rapidgator" in t -> "Rapidgator"
            "mega" in t -> "MEGA"
            else -> null
        }
    }
}
