package id.homebase.core.ui.navigation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class Route {

    @Serializable
    @SerialName("login")
    data object Login : Route()

    @Serializable
    @SerialName("home")
    data object Home : Route()

    @Serializable
    @SerialName("settings")
    data object Settings : Route()

    @Serializable
    @SerialName("create-conversation")
    data object CreateConversation : Route()

    @Serializable
    @SerialName("create-conversation-select-members")
    data object CreateConversationSelectMembers : Route()

    @Serializable
    @SerialName("create-conversation-group")
    data class CreateConversationGroup(val contactIds: List<String>) : Route()

    @Serializable
    @SerialName("message")
    data class MessageInfo(val conversationId: String, val messageId: String, val fileId: String) : Route()

    @Serializable
    @SerialName("contact")
    data class ContactInfo(val odinId: String) : Route()

    @Serializable
    @SerialName("chat")
    data class ChatList(val conversationId: String? = null) : Route()

    @Serializable
    @SerialName("notification-settings")
    data object NotificationSettings : Route()
}