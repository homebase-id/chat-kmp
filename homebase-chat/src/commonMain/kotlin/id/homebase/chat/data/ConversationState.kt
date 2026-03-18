package id.homebase.chat.data

import kotlinx.serialization.Serializable

@Serializable
enum class ConversationState {
    Active,
    Archived,
    Deleted,
    Invalid
}