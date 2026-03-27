package id.homebase.chat.services

import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.chat.data.ConversationUiModel
import id.homebase.core.avatars.ConversationAvatarModel
import kotlin.uuid.Uuid

object ChatProtocol {

    val ChatAppId = Uuid.parse("2d781401-3804-4b57-b4aa-d8e4e2ef39f4")

    const val ContactFileType = 100

    const val MessageVersionNumberOne = 1

    val ConversationWithYourselfId: Uuid = Uuid.parse("e4ef2382-ab3c-405d-a8b5-ad3e09e980dd")
    const val ConversationPayloadKey = "convo_pk" // TODO: Explain what this represents
    const val ConversationImageKey = "convo_img"

    const val CHAT_CONVERSATION_LOCAL_METADATA_FILE_TYPE = 8889;

    const val ConversationFileType = 8888
    const val ChatStatusMessageDataType = 202

    const val MessageFileType = 7878


    /** Indicates a file was optimistically written and not coming from the server */
    val isPendingSendTag = Uuid.parse("6e87beb3-412a-4a8c-aaec-b21a7ec620a7")

    /** Local metadata tag: conversation has been archived by the user */
    val ConversationArchivedTag = Uuid.parse("a569e5cd-6fd8-41e0-8ccc-b6b31dac6b73")

    /** Local metadata tag: conversation has been pinned by the user */
    val ConversationPinnedTag = Uuid.parse("3f7e4c1d-5a2b-4f89-b3e7-9c1d2e3f4a5b")

    const val ARCHIVAL_STATUS_DELETED = 2

    const val DEFAULT_PAYLOAD_DESCRIPTOR_KEY = "pld_desc"

    const val PAYLOAD_KEY_MESSAGE_WEB = "chat_web"
    const val PAYLOAD_KEY_LINKS = "chat_links"

    const val DefaultPayloadKey = "dflt_key"
    const val MaxDescriptorContentLength = 1024
    const val MaxHeaderContentBytes = 7000

    /**
     * Creates a static [ConversationUiModel] for the "Note to Self" conversation.
     * This conversation is never created on the server — it exists purely as a
     * local constant identified by [ConversationWithYourselfId].
     */
    fun buildSelfConversation(domain: OdinId): ConversationUiModel {
        return ConversationUiModel(
            id = ConversationWithYourselfId,
            name = "", // Display name resolved via string resource at UI layer
            lastMessage = "",
            timestamp = UnixTimeUtc(0).toInstant(),
            unreadCount = 0,
            avatarInitials = "",
            avatarTiny = null,
            participants = listOf(domain),
            lastRead = UnixTimeUtc(0).toInstant(),
            avatarModel = ConversationAvatarModel(
                type = ConversationAvatarModel.Type.Owner,
                odinId = domain
            ),
            admins = setOf(domain),
            isPinned = true,
        )
    }
}
