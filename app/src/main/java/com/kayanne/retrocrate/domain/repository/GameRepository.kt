package com.kayanne.retrocrate.domain.repository

import com.kayanne.retrocrate.domain.model.Game
import kotlinx.coroutines.flow.Flow

interface GameRepository {
    fun observeFeatured(): Flow<List<Game>>
    fun observeAction(): Flow<List<Game>>
    fun observePopularClassics(): Flow<List<Game>>
    suspend fun getById(id: String): Game?
}
