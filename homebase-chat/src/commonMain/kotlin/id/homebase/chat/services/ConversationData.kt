///** Chat conversation file type constant */
///** Base conversation interface containing common properties */
//interface BaseConversation {
//    val title: String
//}
//
///** Unified conversation content data class (parsed from JSON) */
//@Serializable
//data class UnifiedConversation(
//    override val title: String = "",
//    val recipients: List<String> = emptyList()
//) : BaseConversation
//
///**
// * Metadata stored locally for a conversation (from localAppData). Contains local-only data like
// * last read time.
// */
//@Serializable
//data class ConversationMetadata(
//    /** The conversation ID this metadata belongs to */
//    val conversationId: Uuid? = null,
//
//    /** Timestamp when the conversation was last read (UnixTimeUtc in milliseconds) */
//    // TODO: This seems incorrect, isn't the lastReadTime supposed to be on localAppData?
//    val lastReadTime: Long? = null
//) {
//    /** Get lastReadTime as UnixTimeUtc */
//    fun getLastReadTimeUtc(): UnixTimeUtc? = lastReadTime?.let { UnixTimeUtc(it) }
//}
//
//data class ConversationData(
//    /** FileType of conversation (8888) */
//    // Why ? val fileType: Int = CHAT_CONVERSATION_FILE_TYPE,
//
//    /** FileId of the conversation */
//    val fileId: Uuid,
//
//    /** Unique ID of the conversation */
//    val uniqueId: Uuid?,
//
//    /** When the conversation was created */
//    val created: UnixTimeUtc,
//
//    /** When the conversation was updated */
//    val updated: UnixTimeUtc,
//
//    /** Decrypted conversation content */
//    val content: UnifiedConversation,
//
//    /** Decrypted local metadata (from localAppData) */
//    val conversationMeta: ConversationMetadata?,
//
//    /** Preview thumbnail 20x20 */
//    val previewThumbnail: ThumbnailDescriptor?,
//
//    /** FileState */
//    // val fileState: FileState, TODO: What is this?
//
//    /** Whether content is encrypted */
//    val isEncrypted: Boolean,
//
//    /** DriveId for reference */
//    // val driveId: Uuid, TODO: Needed?
//
//    /** VersionTag */
//    val versionTag: Uuid?,
//
//    /** List of payload descriptors with metadata */
//    // val payloads: List<PayloadDescriptor>? TODO: Needed?
//)
