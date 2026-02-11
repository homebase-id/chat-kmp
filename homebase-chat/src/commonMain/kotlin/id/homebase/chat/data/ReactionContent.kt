package id.homebase.chat.data

import kotlinx.serialization.Serializable

@Serializable
data class ReactionContent(
    val emoji: String
)