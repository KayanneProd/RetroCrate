package com.kayanne.retrocrate.data.source

import android.util.Log
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.GameCollection

// Derives franchise "collections" (Mario, Sonic, Monster Hunter, Dragon Ball, Final Fantasy, …) from
// the catalog itself — there is no hardcoded franchise list.
//
// Franchises are matched primarily on multi-word phrases (consecutive significant title words), so
// real series like "Monster Hunter" and "Dragon Ball" form their own collections instead of a vague
// "Monster" or "Dragon" bucket. A single word becomes a collection only when it's a distinctive
// franchise on its own (Mario, Sonic, Zelda) — held to a stricter bar. The deciding signal is shared
// publisher/developer: "Dragon Ball" is all one publisher (a real series), while the generic word
// "Dragon" spans many (Dragon Ball, Dragon Quest, Spyro, …) and is rejected. Any series with enough
// entries appears automatically as new titles release.
object FranchiseBuilder {

    private const val MIN_MEMBERS_PHRASE = 2      // multi-word franchises (specific, reliable)
    private const val DOMINANCE_PHRASE = 0.5
    private const val MIN_MEMBERS_WORD = 4        // single-word franchises (held to a stricter bar)
    private const val DOMINANCE_WORD = 0.6
    private const val MERGE_JACCARD = 0.6         // near-identical member sets collapse to one
    private const val MAX_COVERS = 4

    fun build(games: List<Game>): List<GameCollection> {
        if (games.isEmpty()) return emptyList()

        val byPhrase = HashMap<String, LinkedHashMap<String, Game>>()
        for (game in games) {
            for (phrase in candidatePhrases(significantWords(game.title))) {
                byPhrase.getOrPut(phrase) { LinkedHashMap() }[game.id] = game
            }
        }

        val candidates = byPhrase.mapNotNull { (phrase, memberMap) ->
            val members = memberMap.values.toList()
            val isPhrase = phrase.contains(' ')
            val minMembers = if (isPhrase) MIN_MEMBERS_PHRASE else MIN_MEMBERS_WORD
            if (members.size < minMembers) return@mapNotNull null
            // Established franchises span multiple platforms over time (Mega Man: NES→…→Switch). This
            // is the strongest filter against single-publisher eShop shovelware ("Anime Girls", demo
            // discs, budget labels), which is almost always confined to one platform.
            if (members.mapTo(HashSet()) { it.platform }.size < 2) return@mapNotNull null
            if (!isFranchise(members, isPhrase)) return@mapNotNull null
            GameCollection(
                id = phrase,
                name = phrase.split(' ').joinToString(" ") { it.titlecase() },
                size = members.size,
                coverArtUrls = members.mapNotNull { it.boxArtUrl }.distinct().take(MAX_COVERS),
            )
        }

        // Prefer the specific (multi-word) and larger collection; collapse near-duplicate member sets
        // (e.g. the "final" and "fantasy" words both describe Final Fantasy). Parent/child series with
        // very different sizes (Mario vs Mario Kart) have a low Jaccard and both survive — intentional.
        val ordered = candidates.sortedWith(
            compareByDescending<GameCollection> { it.name.contains(' ') }.thenByDescending { it.size },
        )
        val accepted = mutableListOf<GameCollection>()
        val acceptedIds = mutableListOf<Set<String>>()
        for (candidate in ordered) {
            val ids = byPhrase[candidate.id]?.keys ?: emptySet()
            if (acceptedIds.none { jaccard(ids, it) >= MERGE_JACCARD }) {
                accepted.add(candidate)
                acceptedIds.add(ids)
            }
        }
        // Rank by how many platforms/generations the series spans (established franchises like Mega
        // Man or Zelda ran across many; a generic word that slipped through clusters on few), then by
        // size. This is what leads the Home rail with recognizable franchises.
        fun platformSpan(c: GameCollection): Int =
            byPhrase[c.id]?.values?.mapTo(HashSet()) { it.platform }?.size ?: 0
        val result = accepted.sortedWith(
            compareByDescending<GameCollection> { platformSpan(it) }.thenByDescending { it.size },
        )
        Log.i("Franchise", "Built ${result.size} collections from ${games.size} games")
        return result
    }

    fun members(games: List<Game>, key: String): List<Game> {
        val k = key.lowercase()
        return games.filter { k in candidatePhrases(significantWords(it.title)) }
    }

    // Unigrams + consecutive bigrams of the significant title words.
    private fun candidatePhrases(words: List<String>): Set<String> {
        val out = HashSet<String>()
        for (w in words) out.add(w)
        for (i in 0 until words.size - 1) out.add("${words[i]} ${words[i + 1]}")
        return out
    }

    // A franchise is identified purely by a shared publisher/developer across its members — the
    // "one real series, not coincidental wording" signal. Crucially the share is over *all* members
    // (not just those that happen to carry publisher data), so a generic word like "Battle" or
    // "Dragon" that spans dozens of publishers can never qualify, while "Monster Hunter" (≈all Capcom)
    // does. There is deliberately no count-based shortcut — frequency alone never makes a collection.
    private fun isFranchise(members: List<Game>, isPhrase: Boolean): Boolean {
        val ratio = if (isPhrase) DOMINANCE_PHRASE else DOMINANCE_WORD
        val top = maxOf(
            topShare(members.mapNotNull { it.publisher?.lowercase()?.takeIf(String::isNotBlank) }),
            topShare(members.mapNotNull { it.developer?.lowercase()?.takeIf(String::isNotBlank) }),
        )
        return top >= 2 && top.toDouble() / members.size >= ratio
    }

    private fun topShare(values: List<String>): Int =
        values.groupingBy { it }.eachCount().values.maxOrNull() ?: 0

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }.toDouble()
        return inter / (a.size + b.size - inter)
    }

    private fun String.titlecase(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    // Significant title words: lowercase, parentheticals removed, split on non-alphanumerics, then
    // dropping articles/prepositions, edition/format noise, generic words, pure numbers, roman
    // numerals, and very short tokens. This is linguistic noise filtering only — nothing about any
    // specific franchise is encoded here; the collections themselves are derived from the catalog.
    fun significantWords(title: String): List<String> =
        title.lowercase().replace(PARENS, " ").split(NON_WORD)
            .filter { it.length >= 3 && it !in STOPWORDS && it !in ROMAN && !it.all(Char::isDigit) }

    private val PARENS = Regex("\\([^)]*\\)")
    private val NON_WORD = Regex("[^a-z0-9]+")

    private val ROMAN = setOf(
        "iii", "vii", "viii", "xii", "xiii", "xiv", "xvi", "xvii", "xviii", "xix",
    )

    private val STOPWORDS = setOf(
        "the", "and", "for", "with", "vs", "from",
        "edition", "deluxe", "remastered", "remaster", "remake", "collection", "version",
        "complete", "definitive", "anniversary", "goty", "plus", "special", "ultimate", "classic",
        "game", "games", "disc", "disk", "vol", "volume", "part",
        "world", "adventure", "adventures", "story", "stories", "legend", "legends", "super", "new",
        // Generic descriptors that aren't franchises but slipped through as single-publisher clusters.
        "flying", "boys", "girls", "marble", "interactive", "collector", "multi", "demo", "fun", "chinese",
    )
}
