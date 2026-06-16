package com.kayanne.retrocrate.data.source

// Pure, network-free matching logic shared by every RomSource. This is the fix for the long-
// standing "Internet Archive handed back a random file" bug: instead of grabbing the first file
// with a ROM extension out of a multi-game item, we score each candidate against the game we
// actually want and reject anything that isn't a confident match.
object RomMatcher {

    // A file we might download. Sources adapt their listing rows to this.
    data class Candidate(val filename: String, val sizeBytes: Long?)

    // Below this score we'd rather return nothing than risk the wrong game. Honest "not found"
    // beats silently downloading Mario Party when the user asked for Mario Kart.
    private const val MATCH_THRESHOLD = 0.85

    val ROM_EXTENSIONS: Set<String> = setOf(
        // Nintendo cartridge / disc
        "nes", "fds", "unf", "smc", "sfc", "z64", "n64", "v64", "gb", "gbc", "gba",
        "nds", "dsi", "srl", "3ds", "cia", "iso", "wbfs", "rvz", "gcm", "gcz", "wad", "nsp", "xci",
        "nsz", "xcz", "wud", "wux", "vpk", "cso",
        // Sega
        "md", "smd", "gen", "32x", "sms", "gg", "sat", "cdi", "gdi", "chd",
        // Sony
        "img", "cue", "bin", "pbp", "cso",
        // Archives the ROM is usually wrapped in
        "zip", "7z", "rar",
    )

    fun hasRomExtension(filename: String): Boolean =
        extensionOf(filename) in ROM_EXTENSIONS

    // True when two game titles are the same game, tolerating article position, punctuation, and
    // "&"/"and" differences. Used to find a game by title in a source's own catalog listing.
    fun titlesMatch(a: String, b: String): Boolean {
        val na = normalizeTitle(stripParentheticals(a))
        val nb = normalizeTitle(stripParentheticals(b))
        if (na.isBlank() || nb.isBlank()) return false
        if (na == nb) return true
        return jaccard(na.split(' ').toSet(), nb.split(' ').toSet()) >= MATCH_THRESHOLD
    }

    // Picks the single best file for the requested game, or null if nothing clears the bar.
    // 1. Exact No-Intro/Redump filename match always wins (can't be the wrong game).
    // 2. Otherwise score the normalized title against each ROM-extension candidate and take the
    //    best — but only if it clears MATCH_THRESHOLD.
    fun bestMatch(
        title: String,
        romFileName: String?,
        preferredRegion: String,
        candidates: List<Candidate>,
    ): Candidate? {
        val roms = candidates.filter { hasRomExtension(it.filename) }
        if (roms.isEmpty()) return null

        if (romFileName != null) {
            roms.firstOrNull { it.filename.equals(romFileName, ignoreCase = true) }
                ?.let { return it }
            // Some hosts wrap the ROM in a zip: "Super Mario 64 (USA).z64" -> "...(USA).z64.zip"
            // or zip the bare title. Match on the romFileName's base too.
            val romBase = normalizeTitle(stripExtension(romFileName))
            roms.firstOrNull { normalizeTitle(stripExtension(it.filename)) == romBase }
                ?.let { return it }
        }

        val target = normalizeTitle(title)
        if (target.isBlank()) return null

        // Among files that clear the bar, take the best score; break ties by region so an English/USA
        // copy always wins over Europe/Japan when both match equally well.
        val scored = roms
            .map { it to scoreTitle(target, preferredRegion, it.filename) }
            .filter { it.second >= MATCH_THRESHOLD }
            .sortedWith(
                compareByDescending<Pair<Candidate, Double>> { it.second }
                    .thenBy { regionRank(it.first.filename) },
            )

        return scored.firstOrNull()?.first
    }

    // Lower = more preferred. Always favour English/USA, then other English regions (Europe/UK/AU),
    // then unknown, with Japanese/other-language last — used to pick a region when several match.
    fun regionRank(filename: String): Int {
        val f = filename.lowercase()
        return when {
            "(usa" in f || "(u)" in f || "(us," in f || "(en)" in f || "(en," in f || "(world" in f -> 0
            "(europe" in f || "(eu" in f || "(e)" in f || "(uk" in f || "(australia" in f -> 1
            "(japan" in f || "(j)" in f || "(jpn" in f || "(jp" in f || "(korea" in f || "(china" in f -> 3
            else -> 2
        }
    }

    // 0.0 (no relation) … 1.0 (normalized titles identical). Region match is a small tie-breaker
    // bonus so USA wins over Japan when both otherwise match.
    fun scoreTitle(normalizedTarget: String, preferredRegion: String, candidateFilename: String): Double {
        val candidateTitle = normalizeTitle(stripParentheticals(stripExtension(candidateFilename)))
        if (candidateTitle.isBlank()) return 0.0

        val base = when {
            candidateTitle == normalizedTarget -> 1.0
            else -> jaccard(normalizedTarget.split(' ').toSet(), candidateTitle.split(' ').toSet())
        }
        if (base < MATCH_THRESHOLD) return base

        val regionBonus = if (candidateFilename.contains(preferredRegion, ignoreCase = true)) 0.001 else 0.0
        return (base + regionBonus).coerceAtMost(1.0)
    }

    // Normalizes a game title for comparison: lower-cased, articles moved to front, punctuation
    // flattened to spaces, "&" spelled out. "Legend of Zelda, The" and "The Legend of Zelda"
    // both become "the legend of zelda".
    fun normalizeTitle(raw: String): String {
        var s = raw.lowercase().trim()
        s = s.replace("&", " and ")
        // No-Intro comma-article form: "Legend of Zelda, The" -> "the legend of zelda"
        Regex("^(.*),\\s*(the|a|an)$").find(s)?.let { m ->
            s = "${m.groupValues[2]} ${m.groupValues[1]}"
        }
        s = s.replace(Regex("[^a-z0-9]+"), " ").trim()
        return s.replace(Regex("\\s+"), " ")
    }

    private fun stripParentheticals(s: String): String =
        s.replace(Regex("[\\(\\[][^\\)\\]]*[\\)\\]]"), " ").trim()

    private fun stripExtension(filename: String): String {
        var name = filename
        // Peel wrapper + format extensions: "game.z64.zip" -> "game".
        while (true) {
            val ext = extensionOf(name)
            if (ext.isEmpty() || ext !in ROM_EXTENSIONS) break
            name = name.substringBeforeLast('.')
        }
        return name
    }

    private fun extensionOf(filename: String): String =
        filename.substringAfterLast('.', "").lowercase().trim()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return intersection / union
    }
}
