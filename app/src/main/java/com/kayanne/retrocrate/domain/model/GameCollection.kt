package com.kayanne.retrocrate.domain.model

// A franchise grouping derived from the catalog (Mario, Sonic, Layton, Final Fantasy, …). Built
// dynamically — see FranchiseBuilder — so any series with enough games appears automatically as new
// titles release. `id` is the franchise keyword used to recompute members; `name` is the derived
// display name; `coverArtUrls` are a few member covers for a collage tile.
data class GameCollection(
    val id: String,
    val name: String,
    val size: Int,
    val coverArtUrls: List<String> = emptyList(),
)
