package id.homebase.core.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

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
    val conversationId: String? = null,

    /** Timestamp when the conversation was last read (UnixTimeUtc in milliseconds) */
    val lastReadTime: Long? = null
) {
    /** Get lastReadTime as UnixTimeUtc */
    fun getLastReadTimeUtc(): UnixTimeUtc? = lastReadTime?.let { UnixTimeUtc(it) }
}

data class ConversationData(
    /** FileType of conversation (8888) */
    val fileType: Int = CHAT_CONVERSATION_FILE_TYPE,

    /** FileId of the conversation */
    val fileId: Uuid?,

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
    val fileState: FileState,

    /** Whether content is encrypted */
    val isEncrypted: Boolean,

    /** DriveId for reference */
    val driveId: Uuid,

    /** VersionTag */
    val versionTag: Uuid?,

    /** List of payload descriptors with metadata */
    val payloads: List<PayloadDescriptor>?
)

/** Chat conversation file type constant */
const val CHAT_CONVERSATION_FILE_TYPE = 8888