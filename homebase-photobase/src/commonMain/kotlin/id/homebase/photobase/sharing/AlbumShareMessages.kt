package id.homebase.photobase.sharing

import co.touchlab.kermit.Logger
import id.homebase.api.common.OdinId
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.albumshare.AlbumShareDescriptor
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.convo.ConversationService
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/** Tells each person an album share reaches about it, in their one-to-one chat: opening the message opens the album. */
class AlbumShareMessages(
    private val conversations: ConversationService,
    private val sender: ChatMessageSenderService,
) {
    /** The ones it couldn't tell. */
    suspend fun send(members: List<OdinId>, albumId: Uuid, albumName: String, access: PhotoAlbumAccess): List<OdinId> {
        val share = albumShareDescriptor(albumId, albumName, access)
        return members.filter { member ->
            try {
                val conversationId = conversations.oneOnOneConversationId(member)
                sender.sendNewTypedMessage(Uuid.random(), conversationId, MessageContent.AlbumShare(share), previousMessageUniqueId = null)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, TAG) { "no album share message for $member" }
                true
            }
        }
    }

    private companion object {
        const val TAG = "AlbumShareMessages"
    }
}

internal fun albumShareDescriptor(albumId: Uuid, albumName: String, access: PhotoAlbumAccess) = AlbumShareDescriptor(
    albumId = albumId.toString(),
    title = albumName.truncateToCodePoints(AlbumShareDescriptor.MAX_TITLE_CODEPOINTS),
    access = if (access == PhotoAlbumAccess.Add) AlbumShareDescriptor.ACCESS_ADD else AlbumShareDescriptor.ACCESS_VIEW,
)
