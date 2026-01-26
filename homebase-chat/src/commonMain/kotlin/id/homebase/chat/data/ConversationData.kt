package id.homebase.chat.data

import id.homebase.core.model.FileState
import id.homebase.core.model.PayloadDescriptor
import id.homebase.core.model.ThumbnailDescriptor
//import id.homebase.core.model.UnixTimeUtc
import id.homebase.api.common.time.UnixTimeUtc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid
import id.homebase.api.client.drives.HomebaseFile

/** Chat conversation file type constant */
const val CHAT_CONVERSATION_FILE_TYPE = 8888

/** Base conversation interface containing common properties */
interface BaseConversation {
    val title: String
}

/** Unified conversation content data class (parsed from JSON) */
@Serializable
data class UnifiedConversation(
    override val title: String = "",
    val recipients: List<String> = emptyList()
) : BaseConversation

/**
 * Metadata stored locally for a conversation (from localAppData). Contains local-only data like
 * last read time.
 */
@Serializable
data class ConversationMetadata(
    /** The conversation ID this metadata belongs to */
    val conversationId: Uuid? = null,

    /** Timestamp when the conversation was last read (UnixTimeUtc in milliseconds) */
    // TODO: This seems incorrect, isn't the lastReadTime supposed to be on localAppData?
    val lastReadTime: Long? = null
) {
    /** Get lastReadTime as UnixTimeUtc */
    fun getLastReadTimeUtc(): UnixTimeUtc? = lastReadTime?.let { UnixTimeUtc(it) }
}

data class ConversationData(
    /** FileType of conversation (8888) */
    // Why ? val fileType: Int = CHAT_CONVERSATION_FILE_TYPE,

    /** FileId of the conversation */
    val fileId: Uuid,

    /** Unique ID of the conversation */
    val uniqueId: Uuid?,

    /** When the conversation was created */
    val created: UnixTimeUtc,

    /** When the conversation was updated */
    val updated: UnixTimeUtc,

    /** Decrypted conversation content */
    val content: UnifiedConversation,

    /** Decrypted local metadata (from localAppData) */
    val conversationMeta: ConversationMetadata?,

    /** Preview thumbnail 20x20 */
    val previewThumbnail: ThumbnailDescriptor?,

    /** FileState */
    // val fileState: FileState, TODO: What is this?

    /** Whether content is encrypted */
    val isEncrypted: Boolean,

    /** DriveId for reference */
    // val driveId: Uuid, TODO: Needed?

    /** VersionTag */
    val versionTag: Uuid?,

    /** List of payload descriptors with metadata */
    // val payloads: List<PayloadDescriptor>? TODO: Needed?
) {
    companion object {
        /**
         * Convert HomebaseFile to ConversationData object
         * Handles fileType 8888 (chat conversations)
         */

        fun fromHomebaseFile(homebaseFile: HomebaseFile): ConversationData? {
            return try {
                val metadata = homebaseFile.fileMetadata
                val appData = metadata.appData

                if (appData.fileType != CHAT_CONVERSATION_FILE_TYPE)
                    throw IllegalArgumentException("HomebaseFile must be of type Chat_conversation")

                if (appData.content == null)
                    throw IllegalArgumentException("AppData is empty")

                val json = Json { ignoreUnknownKeys = true }
                val unifiedConversation = json.decodeFromString<UnifiedConversation>(appData.content!!)

                val conversationMeta = metadata.localAppData?.content?.let { localAppData ->
                    // TODO: Looks wrong, we probably need a data class for the localAppData to serialize / deserialize
                    json.decodeFromString<ConversationMetadata>(localAppData)
                }

                ConversationData(
                    fileId = homebaseFile.fileId,
                    uniqueId = appData.uniqueId,
                    created = metadata.transitCreated,
                    updated = metadata.transitUpdated,
                    content = unifiedConversation,
                    conversationMeta = conversationMeta,
                    previewThumbnail = null, // Handle based on your requirements
                    // fileState = FileState.Companion.fromInt(metadata.fileState.value), TODO: Needed?
                    isEncrypted = metadata.isEncrypted,
                    // driveId = metadata.driveId, TODO: Needed?
                    versionTag = metadata.versionTag
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

