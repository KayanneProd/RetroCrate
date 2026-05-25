package com.kayanne.retrocrate.data.repository

import com.kayanne.retrocrate.data.FakeGameCatalog
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform
import com.kayanne.retrocrate.domain.repository.GameRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

object FakeGameRepository : GameRepository {

    private val all: List<Game> = FakeGameCatalog.games

    override fun observeFeatured(): Flow<List<Game>> =
        flowOf(FakeGameCatalog.featured)

    override fun observeRecentlyAdded(): Flow<List<Game>> =
        flowOf(all.shuffled(java.util.Random(7L)).take(10))

    override fun observePopularRetro(): Flow<List<Game>> =
        flowOf(
            all.filter { it.platform in setOf(Platform.NES, Platform.SNES, Platform.N64) }
        )

    override suspend fun getById(id: String): Game? =
        all.find { it.id == id }
}
