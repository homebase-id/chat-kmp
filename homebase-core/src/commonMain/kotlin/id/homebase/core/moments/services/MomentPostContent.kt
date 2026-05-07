package id.homebase.core.moments.services

import kotlinx.serialization.Serializable

@Serializable
data class MomentPostContent(
    val version: Int,
    val description: String,
)
