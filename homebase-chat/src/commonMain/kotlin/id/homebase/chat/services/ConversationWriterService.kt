package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.core.config.chatTargetDrive
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

class ConversationWriterService(
    private val credentialsManager: CredentialsManager,
    private val driveUploadProvider: DriveUploadProvider
) {

    private val chatDrive = chatTargetDrive.alias

    suspend fun createConversation(
        recipients: List<String>,
        title: String?,
        imagePayload: HomebaseFile?
    ): Pair<Uuid, ConversationUiModel?> {

        val domain = credentialsManager.getActiveDomain()
            ?: error("No active domain")

        val newConversationId: Uuid =
            if (recipients.size == 1) {
                XorIdUtil.getNewXorId(domain, recipients.first())
            } else {
                Uuid.random()
            }

        // 1-to-1 conversation: return existing if found
        if (recipients.size == 1) {
            val existing = getConversationById(newConversationId)
            if (existing != null) {
                return newConversationId to existing
            }
        }

        val updatedRecipients =
            (recipients + domain).distinct()

        val conversationFile =
            ChatProtocol.buildConversationFile(
                conversationId = newConversationId,
                recipients = updatedRecipients,
                title = title ?: recipients.joinToString(", "),
                imagePayload = imagePayload
            )

        val uploaded =
            dbm.drive.uploadFile(chatDrive, conversationFile)

        val convoUi =
            ConversationService.mapToConversation(uploaded, null)

        insertNewConversation(convoUi)

        return newConversationId to convoUi
    }

}
