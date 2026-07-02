package com.kayanne.retrocrate.data.source

// Relevance ranking for the Search screen. Distinct from RomMatcher (which is a strict yes/no gate
// for *downloading* the right file): here we want a permissive, ranked "how well does this title
// answer what the user typed", tolerant of partial words and punctuation.
//
// The normalization deliberately *joins* apostrophes (so "Yoshi's" -> "yoshis") rather than splitting
// them the way RomMatcher.normalizeTitle does ("yoshi s") — typing "yoshis crafted world" should hit
// "Yoshi's Crafted World", and so should the half-typed "yoshi craft".
object TitleSearch {

    fun normalize(raw: String): String =
        raw.lowercase()
            .replace("&", " and ")
            .replace(Regex("['’`]"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    // 0.0 = irrelevant, 1.0 = exact. Tiers, strongest first:
    //  exact normalized match → prefix → word-boundary phrase → substring → all query words present
    //  as token prefixes (handles partial typing & word reordering) → partial token coverage.
    fun score(query: String, title: String): Double {
        val q = normalize(query)
        val t = normalize(title)
        if (q.isEmpty() || t.isEmpty()) return 0.0
        if (t == q) return 1.0
        if (t.startsWith("$q ")) return 0.93
        val padded = " $t "
        if (padded.contains(" $q ")) return 0.88
        if (t.contains(q)) return 0.78

        val qTokens = q.split(' ').filter { it.isNotEmpty() }
        val tTokens = t.split(' ').filter { it.isNotEmpty() }.toMutableList()
        if (qTokens.isEmpty()) return 0.0

        var matched = 0
        for (w in qTokens) {
            val exact = tTokens.indexOf(w)
            val idx = if (exact >= 0) exact else tTokens.indexOfFirst { it.startsWith(w) }
            if (idx >= 0) {
                tTokens.removeAt(idx)
                matched++
            }
        }
        val coverage = matched.toDouble() / qTokens.size
        return when {
            coverage == 1.0 -> 0.6
            coverage >= 0.5 -> 0.3 * coverage
            else -> 0.0
        }
    }

    // Ranks a list by relevance to the query, dropping non-matches. Ties break toward the shorter
    // (closer) title, then alphabetically, so "Mario" ranks above "Mario Kart 8 Deluxe" for "mario".
    fun <T> rank(query: String, items: List<T>, title: (T) -> String): List<T> =
        items.asSequence()
            .map { it to score(query, title(it)) }
            .filter { it.second > 0.0 }
            .sortedWith(
                compareByDescending<Pair<T, Double>> { it.second }
                    .thenBy { title(it.first).length }
                    .thenBy { title(it.first).lowercase() },
            )
            .map { it.first }
            .toList()
}
