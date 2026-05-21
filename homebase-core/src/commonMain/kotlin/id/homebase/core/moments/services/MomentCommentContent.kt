package id.homebase.core.moments.services

import kotlinx.serialization.Serializable

@Serializable
data class MomentCommentContent(
    val version: Int,
    val body: String,
)
