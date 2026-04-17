package id.homebase.core.ui.navigation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class Route {

    @Serializable
    @SerialName("app-loading")
    data object AppLoading : Route()
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
    @SerialName("archived-conversations")
    data object ArchivedConversations : Route()

    @Serializable
    @SerialName("conversation")
    data object ChatList : Route()

    @Serializable
    @SerialName("conversation-settings")
    data class ConversationSettings(val conversationId: String) : Route()

    @Serializable
    @SerialName("group-settings")
    data class GroupSettings(val conversationId: String) : Route()

    @Serializable
    @SerialName("group-add-members")
    data class GroupAddMembers(val conversationId: String) : Route()

    @Serializable
    @SerialName("group-edit")
    data class GroupEdit(val conversationId: String) : Route()

    @Serializable
    @SerialName("examples")
    data object Examples : Route()

    @Serializable
    @SerialName("notification-settings")
    data object NotificationSettings : Route()

    @Serializable
    @SerialName("appearance-settings")
    data object AppearanceSettings : Route()

    @Serializable
    @SerialName("help")
    data object Help : Route()

    @Serializable
    @SerialName("connections")
    data object Connections : Route()

    @Serializable
    @SerialName("vault")
    data object Vault : Route()

    @Serializable
    @SerialName("vault-onboarding")
    data object VaultOnboarding : Route()

    @Serializable
    @SerialName("vault-settings")
    data object VaultSettings : Route()
}