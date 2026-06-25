package com.kayanne.retrocrate.data.source.debrid

import java.net.URLEncoder

// One torrent result from an indexer. `infoHash` is what a debrid service needs to check its cache
// and unlock a direct link; `magnet` wraps it with a name and public trackers for adding.
data class TorrentCandidate(
    val name: String,
    val infoHash: String,
    val sizeBytes: Long?,
    val seeders: Int,
) {
    val magnet: String
        get() {
            val dn = URLEncoder.encode(name, "UTF-8")
            val trackers = TRACKERS.joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }
            return "magnet:?xt=urn:btih:$infoHash&dn=$dn$trackers"
        }
}

// Trackers so a freshly-added magnet can find peers when a torrent isn't already cached. Includes
// Nyaa's tracker — Nyaa-sourced torrents are seeded there, not on the generic public trackers, so
// without it a debrid service can't find the swarm and stalls at 0%. Debrid cache checks key on the
// info hash alone, so trackers only matter on the non-cached (server-side fetch) path.
private val TRACKERS = listOf(
    "http://nyaa.tracker.wf:7777/announce",
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://open.tracker.cl:1337/announce",
    "udp://tracker.openbittorrent.com:6969/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://tracker.internetwarriors.net:1337/announce",
)
