package com.kayanne.retrocrate.domain.repository

import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform
import kotlinx.coroutines.flow.Flow

interface GameRepository {
    fun observeFeatured(): Flow<List<Game>>
    fun observeNewArrivals(): Flow<List<Game>>
    fun observePopular(): Flow<List<Game>>
    fun observeDiscover(): Flow<List<Game>>
    fun observePlatform(platform: Platform): Flow<List<Game>>
    suspend fun getById(id: String): Game?
}
