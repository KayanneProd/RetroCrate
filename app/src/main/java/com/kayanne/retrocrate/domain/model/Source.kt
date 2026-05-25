package com.kayanne.retrocrate.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Source(
    val id: String,
    val siteName: String,
    val region: String?,
    val sizeBytes: Long?,
    val resolveUrl: String,
)
