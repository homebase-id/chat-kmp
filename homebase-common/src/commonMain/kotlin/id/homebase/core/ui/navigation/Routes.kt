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
    data class MessageInfo(val conversationId: String, val messageId: String, val fileId: String) :
        Route()

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
    @SerialName("conversation-media")
    data class ConversationMedia(val conversationId: String) : Route()

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
    @SerialName("developer-menu")
    data object DeveloperMenu : Route()

    @Serializable
    @SerialName("storage-settings")
    data object StorageSettings : Route()

    @Serializable
    @SerialName("defragmenter")
    data object Defragmenter : Route()

    @Serializable
    @SerialName("connections")
    data object Connections : Route()

    @Serializable
    @SerialName("vault")
    data object Vault : Route()

    @Serializable
    @SerialName("vault-settings")
    data object VaultSettings : Route()

    @Serializable
    @SerialName("vault-entry-detail")
    data class VaultEntryDetail(val fileId: String, val sectionTitle: String) : Route()

    @Serializable
    @SerialName("vault-note-editor")
    data class VaultNoteEditor(
        val sectionId: String,
        val entryId: String? = null,
    ) : Route()

    @Serializable
    @SerialName("feed")
    data object Feed : Route()

    @Serializable
    @SerialName("moments")
    data object Moments : Route()

    @Serializable
    @SerialName("moment-detail")
    data class MomentDetail(
        val momentId: String,
        /**
         * Optional payload-key to land the detail screen's carousel on a
         * specific media item — e.g. when the user tapped one cell of a
         * multi-image post in the feed. `null` (or unmatched) starts at
         * page 0.
         */
        val initialPayloadKey: String? = null,
        /**
         * When true, the detail screen opens with its comments sheet already
         * expanded — used by the timeline card's comment button so a tap
         * lands the user straight in the comment thread. Only honoured on the
         * initial page; vertically-swiped neighbours start with comments
         * collapsed.
         */
        val openComments: Boolean = false,
    ) : Route()

    @Serializable
    @SerialName("moment-compose")
    data object MomentCompose : Route()

    @Serializable
    @SerialName("moment-audience")
    data object MomentAudience : Route()

    @Serializable
    @SerialName("create-moment-group")
    data object CreateMomentGroup : Route()

    @Serializable
    @SerialName("moments-onboarding")
    data object MomentsOnboarding : Route()

    @Serializable
    @SerialName("moments-settings")
    data object MomentsSettings : Route()

    @Serializable
    @SerialName("lists")
    data object Lists : Route()

    @Serializable
    @SerialName("lists-onboarding")
    data object ListsOnboarding : Route()

    @Serializable
    @SerialName("lists-settings")
    data object ListsSettings : Route()

    @Serializable
    @SerialName("location")
    data object Location : Route()

    @Serializable
    @SerialName("location-onboarding")
    data object LocationOnboarding : Route()

    @Serializable
    @SerialName("location-settings")
    data object LocationSettings : Route()

    @Serializable
    @SerialName("location-history")
    data object LocationHistory : Route()

    @Serializable
    @SerialName("location-find-device")
    data class LocationFindDevice(val deviceId: String? = null) : Route()

    @Serializable
    @SerialName("crop")
    data class Crop(val requestId: String) : Route()

    @Serializable
    @SerialName("draw")
    data class Draw(val requestId: String) : Route()
}