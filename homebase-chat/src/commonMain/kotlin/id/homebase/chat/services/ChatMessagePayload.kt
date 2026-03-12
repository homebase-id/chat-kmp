package id.homebase.chat.services

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessagePayload(
    val message: String
)