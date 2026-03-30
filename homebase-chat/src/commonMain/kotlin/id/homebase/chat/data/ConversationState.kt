package id.homebase.chat.data

import kotlinx.serialization.Serializable

@Serializable
enum class ConversationState {
    Active,
    Archived,
    Left,
    Deleted,
    Invalid
}