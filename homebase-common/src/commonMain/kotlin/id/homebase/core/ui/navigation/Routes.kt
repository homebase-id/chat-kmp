package id.homebase.core.ui.navigation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class Route {
    @Serializable @SerialName("home") data object Home : Route()
    @Serializable @SerialName("settings") data object Settings : Route()
    @Serializable @SerialName("chat") data object ChatList : Route()
    @Serializable @SerialName("chat-message-details") data class ChatMessageDetail(val driveId: String, val fileId: String) : Route()
    @Serializable @SerialName("chat-messages") data class ChatMessages(val conversationId: String) : Route()
}
