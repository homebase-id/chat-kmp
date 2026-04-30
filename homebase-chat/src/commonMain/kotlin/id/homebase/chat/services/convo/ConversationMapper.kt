package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.ConversationUiModel.Companion.updateWithLatestMessage
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Maps raw [HomebaseFile] conversation records into [ConversationUiModel]s.
 *
 * The mapper is split into two layers that mirror the cold-load pipeline
 * in [ConversationStream]:
 *
 *   MANDATORY — [mapToBasic] produces the minimum viable row that the UI
 *   can render. No DB calls, no fan-out. Safe to call in the fast first-
 *   paint path.
 *
 *   ENRICHMENT — [applyLastMessage] and [applyAdmins] are pure patchers
 *   that upgrade an already-basic row with optional data. They never
 *   fail-fatal (applyLastMessage returns the input on a bad message).
 *
 * [mapToConversationUi] composes all three for the incremental-update and
 * single-row paths that want a fully-enriched row in one call.
 */
class ConversationMapper(
    private val credentialsManager: CredentialsManager,
    private val dbm: DatabaseManager,
) {

    private val logger = Logger.withTag("ConversationMapper")
    private val chatDrive = chatTargetDrive.alias

    /**
     * MANDATORY — basic conversation row required to render the list.
     *
     * Populates identity, title, participants, avatar model, membership
     * state (Active/Left/Archived/Removed/…), and seeds
     * `latestMessageTimestamp = metadata.created` so freshly-created
     * threads (no messages yet) still sort by recency.
     *
     * Explicitly does NOT populate:
     *   - `lastMessage*` fields (defaults: " ", null, false)
     *   - `unreadCount` (default 0)
     *   - admin-file resolved `admins` set (see [applyAdmins])
     *
     * For group conversations this still seeds `admins` from the in-
     * content `adminData` (already parsed as part of appData) or falls
     * back to `originalAuthor`. That avoids a DB round-trip at first
     * paint; [applyAdmins] upgrades to the separate admin-file set
     * afterwards if one exists.
     *
     * Do not add DB calls, network calls, or per-row fan-out to this
     * method — anything optional belongs in an `applyX` patcher called
     * from an enrichment pass. This is the fast first-paint path; every
     * added cost here delays tap-to-render latency on cold start.
     */
    suspend fun mapToBasic(conversationFile: HomebaseFile): ConversationUiModel {
        val startedAt = Clock.System.now().toEpochMilliseconds()
        val rowId = conversationFile.fileMetadata.appData.uniqueId
        try {
            return try {
                val domain = credentialsManager.requireActiveDomain()
                val metadata = conversationFile.fileMetadata
                val appData = metadata.appData

                if (appData.fileType != ChatProtocol.ConversationFileType) {
                    throw IllegalArgumentException("Not a conversation file")
                }

                val conversationId = appData.uniqueId ?: error("Missing uniqueId")

                val isDeleted = conversationFile.isSoftDeleted()

                if (isDeleted) {
                    return mapDeletedConversation(conversationFile, domain)
                }

                val conversationData =
                    OdinSystemSerializer.deserialize<ConversationAppDataJson>(
                        appData.content ?: error("Conversation appData missing")
                    )

                val localAppData =
                    metadata.localAppData?.content?.let {
                        OdinSystemSerializer.deserialize<ConversationLocalAppDataJson>(it)
                    }

                val rawRecipients = conversationData.recipients
                val participants = rawRecipients.filterNotNull().distinct()
                require(participants.isNotEmpty()) { "Conversation has no valid participants" }

                val isGroup = appData.tags?.contains(ChatProtocol.ConversationGroupTag) == true
                val isLegacyGroup = !isGroup && participants.size > 2
                val isAnyGroup = isGroup || isLegacyGroup

                // ---- DEBUG instrumentation ----
                // PARTICIPANT-LIST TRACE — log every group's participant list each time it is
                // mapped. If a member is missing in the UI, the diff between this and the
                // ParticipantsAudit lines from createConversation/writeConversationFile/
                // updateConversationInternal pinpoints exactly which step lost it.
                if (isAnyGroup) {
                    val nullCount = rawRecipients.count { it == null }
                    val rawSize = rawRecipients.size
                    val droppedDistinct = rawRecipients.filterNotNull().size - participants.size
                    Logger.i(tag = "ParticipantsAudit") {
                        "ConversationMapper.mapToBasic READ for $conversationId: " +
                            "rawRecipients.size=$rawSize nullCount=$nullCount distinctDropped=$droppedDistinct " +
                            "final.size=${participants.size} domains=[${participants.joinToString(",") { it.domainName }}] " +
                            "isGroup=$isGroup isLegacyGroup=$isLegacyGroup versionTag=${metadata.versionTag}"
                    }
                    if (nullCount > 0) {
                        Logger.w(tag = "ParticipantsAudit") {
                            "ConversationMapper.mapToBasic for $conversationId: $nullCount null entries in recipients — " +
                                "deserializer produced nulls (corrupt content or schema drift)"
                        }
                    }
                    if (droppedDistinct > 0) {
                        Logger.w(tag = "ParticipantsAudit") {
                            "ConversationMapper.mapToBasic for $conversationId: $droppedDistinct duplicate entries in stored recipients — " +
                                "the stored file has duplicates; investigate writers (createConversation/updateConversationInternal/recoverConversation)"
                        }
                    }
                }
                // ---- end DEBUG ----

                val title =
                    if (conversationId == ChatProtocol.ConversationWithYourselfId) {
                        "" // Display name resolved via string resource at UI layer
                    } else if (isAnyGroup) {
                        // Leave blank when no explicit title; the UI layer builds a fallback from
                        // resolved contact names (EnrichedConversationUiModel). Building it here
                        // would bake in raw domain names, bypassing contact resolution.
                        conversationData.title?.takeIf { it.isNotBlank() } ?: ""
                    } else {
                        val other = participants.first { it != domain }
                        other.domainName
                    }

                // Admins from in-content adminData (synchronous, already parsed). The separate
                // admin-file lookup happens later in the enrichment pass via applyAdmins — this
                // is just the best-effort seed so group-admin checks work at first paint.
                val seededAdmins: Set<OdinId> = if (isAnyGroup) {
                    conversationData.adminData?.admins?.toSet()
                        ?: setOf(
                            metadata.originalAuthor
                                ?: metadata.senderOdinId
                                ?: domain
                        )
                } else {
                    emptySet()
                }

                val avatarModel = buildConversationAvatarModel(
                    conversationFile,
                    participants,
                    domain,
                    conversationId,
                    isAnyGroup
                )

                val localTags = metadata.localAppData?.tags ?: emptyList()
                val isArchivedByTag = localTags.contains(ChatProtocol.ConversationArchivedTag)
                val isLeftByTag = localTags.contains(ChatProtocol.ConversationLeftTag)
                val isPinnedByTag = localTags.contains(ChatProtocol.ConversationPinnedTag)

                val conversationState = when {
                    // Legacy groups have no protocol to update the participants list, so
                    // `participants.contains(domain)` after LeftTag does NOT mean "someone
                    // re-added me" — the list literally cannot change. Treat any LeftTag
                    // on a legacy group as Left, regardless of participants. Without this
                    // exemption, the user sees a spurious "You were re-added to this group"
                    // immediately after leaving a legacy group.
                    isLeftByTag && !isLegacyGroup && participants.contains(domain) -> ConversationState.RejoinPending
                    isLeftByTag -> ConversationState.Left
                    isAnyGroup && !participants.contains(domain) -> ConversationState.Removed
                    isArchivedByTag -> ConversationState.Archived
                    else -> ConversationState.Active
                }

                val exitedAt = if (conversationState == ConversationState.Left
                    || conversationState == ConversationState.Removed
                ) {
                    localAppData?.lastExitedAt?.toInstant()
                } else null

                ConversationUiModel(
                    id = conversationId,
                    name = title,
                    lastMessage = " ",
                    // Seed sort timestamp with file creation so a freshly-created thread
                    // (e.g. one started alongside a connection request, before any messages
                    // have flowed) sorts to the top of the list instead of the bottom.
                    // applyLastMessage overwrites this once a real message exists.
                    latestMessageTimestamp = metadata.created.toInstant(),
                    unreadCount = 0,
                    avatarTiny = appData.previewThumbnail,
                    avatarInitials = "",
                    avatarUrl = "",
                    participants = participants,
                    isPinned = isPinnedByTag,
                    lastRead = localAppData?.lastReadTime?.toInstant()
                        ?: UnixTimeUtc(0).toInstant(),
                    avatarModel = avatarModel,
                    admins = seededAdmins,
                    conversationState = conversationState,
                    isGroup = isGroup,
                    isLegacyGroup = isLegacyGroup,
                    exitedAt = exitedAt,
                    fileUpdated = metadata.updated.toInstant()
                )
            } catch (t: Throwable) {
                logger.e(t) {
                    "FAILED map | fileId=${conversationFile.fileId}"
                }
                ConversationUiModel(
                    id = conversationFile.fileMetadata.appData.uniqueId ?: Uuid.random(),
                    name = "",
                    lastMessage = "Failure loading conversation",
                    latestMessageTimestamp = UnixTimeUtc(0).toInstant(),
                    unreadCount = 0,
                    avatarInitials = "",
                    avatarUrl = "",
                    avatarTiny = null,
                    participants = emptyList(),
                    lastRead = UnixTimeUtc(0).toInstant(),
                    avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
                    admins = emptySet(),
                    conversationState = ConversationState.Invalid,
                    isGroup = false,
                    fileUpdated = conversationFile.fileMetadata.updated.toInstant()
                )
            }
        } finally {
            val elapsed = Clock.System.now().toEpochMilliseconds() - startedAt
            if (elapsed > 20) {
                Logger.w(tag = "ConvListPerf") {
                    "mapToBasic slow row=$rowId in ${elapsed}ms"
                }
            }
        }
    }

    /**
     * ENRICHMENT — patches `lastMessage*` fields and `latestMessageTimestamp`
     * on an already-basic row.
     *
     * Returns the input unchanged if the last-message file fails to map
     * (corrupt payload, decryption failure, …). Never throws.
     *
     * This is a per-row patcher; callers in [ConversationStream] invoke
     * it in a loop over rows from `selectAllConversationPlusLastMessage`.
     */
    suspend fun applyLastMessage(
        ui: ConversationUiModel,
        lastMsgFile: HomebaseFile,
        domain: OdinId,
    ): ConversationUiModel {
        val msg = ChatMessageStream.mapToMessageData(lastMsgFile, credentialsManager) {
            it.fileMetadata.originalAuthor?.domainName ?: ""
        } ?: return ui
        return ui.updateWithLatestMessage(msg, domain)
    }

    /**
     * ENRICHMENT — patches the `admins` field on an already-basic row.
     *
     * Pure patcher with no DB access; the caller has already resolved
     * the admin set (typically via [ConversationAdminInfo.queryBatchFromDb]
     * in [ConversationStream.enrichWithAdmins]).
     */
    fun applyAdmins(
        ui: ConversationUiModel,
        admins: Set<OdinId>,
    ): ConversationUiModel = ui.copy(admins = admins)

    /**
     * COMPOSED — maps a conversation file with optional last message and
     * optional preloaded admins in one pass.
     *
     * Used by incremental-update paths ([ConversationStream] BatchReceived
     * handling) and single-row loads ([ConversationStream.loadConversation])
     * where we want a fully-enriched row without going through the phased
     * cold-load pipeline.
     *
     * The phased pipeline in [ConversationStream.start] does NOT call this —
     * it composes the three steps itself so each phase can emit its own
     * StateFlow update.
     */
    suspend fun mapToConversationUi(
        conversationFile: HomebaseFile,
        lastMsg: HomebaseFile?,
        preloadedAdmins: Map<Uuid, Set<OdinId>>? = null,
    ): ConversationUiModel {
        val basic = mapToBasic(conversationFile)
        if (basic.conversationState == ConversationState.Deleted) return basic
        if (basic.conversationState == ConversationState.Invalid) return basic

        val withAdmins = if (basic.isGroupConversation) {
            val adminFileResult = if (preloadedAdmins != null) {
                preloadedAdmins[basic.id]
            } else {
                queryAdmins(basic.id)
            }
            if (adminFileResult != null) applyAdmins(basic, adminFileResult) else basic
        } else basic

        return if (lastMsg != null) {
            val domain = credentialsManager.requireActiveDomain()
            applyLastMessage(withAdmins, lastMsg, domain)
        } else withAdmins
    }

    private fun mapDeletedConversation(
        conversationFile: HomebaseFile,
        domain: OdinId,
    ): ConversationUiModel {
        val metadata = conversationFile.fileMetadata
        val appData = metadata.appData

        return ConversationUiModel(
            id = appData.uniqueId ?: error("Missing uniqueId"),
            name = "Deleted conversation",
            lastMessage = " ",
            latestMessageTimestamp = metadata.created.toInstant(),
            unreadCount = 0,
            avatarTiny = appData.previewThumbnail,
            avatarInitials = "",
            avatarUrl = "",
            participants = emptyList(),
            lastRead = UnixTimeUtc(0).toInstant(),
            avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
            admins = setOf(domain),
            conversationState = ConversationState.Deleted,
            isGroup = appData.tags?.contains(ChatProtocol.ConversationGroupTag) == true,
            fileUpdated = metadata.updated.toInstant()
        )
    }

    private suspend fun queryAdmins(conversationId: Uuid): Set<OdinId>? {
        val startedAt = Clock.System.now().toEpochMilliseconds()
        val result = ConversationAdminInfo.queryFromDb(credentialsManager, dbm, chatDrive, conversationId)
        Logger.i(tag = "ConvListPerf") {
            "queryAdmins=${Clock.System.now().toEpochMilliseconds() - startedAt}ms groupId=$conversationId hit=${result != null}"
        }
        return result
    }

    private suspend fun buildConversationAvatarModel(
        conversation: HomebaseFile,
        participants: List<OdinId>,
        domain: OdinId,
        conversationId: Uuid,
        isAnyGroup: Boolean
    ): ConversationAvatarModel {

        val metadata = conversation.fileMetadata
        val appData = metadata.appData

        val imagePayload =
            metadata.payloads?.firstOrNull { it.key == ChatProtocol.ConversationImageKey }

        if (imagePayload != null) {

            val imageData =
                HomebaseImageData(
                    driveId = chatTargetDrive.alias,
                    fileId = conversation.fileId,
                    payloadKey = imagePayload.key,
                    isEncrypted = metadata.isEncrypted,
                    previewThumbnail = imagePayload.previewThumbnail?.toEmbeddedThumb()
                        ?: appData.previewThumbnail,
                    keyHeader =
                        KeyHeader(
                            iv = Base64.decode(
                                imagePayload.iv ?: error("encrypted payload requires key header")
                            ),
                            aesKey = conversation.keyHeader.aesKey
                        ),
                    requestedSize = ImageSize.THUMB_MEDIUM,
                    lastModified = imagePayload.lastModified
                )

            return ConversationAvatarModel(
                type = ConversationAvatarModel.Type.ConversationImage,
                imageData = imageData
            )
        }

        if (conversationId == ChatProtocol.ConversationWithYourselfId) {
            return ConversationAvatarModel(
                odinId = domain,
                type = ConversationAvatarModel.Type.Owner
            )
        }

        val others = participants.filter { it != domain }

        // Any group (tagged or legacy) gets the group avatar, even at 2 participants.
        // Otherwise a 2-person group would be indistinguishable from a 1:1 with the same
        // person — both in title and avatar.
        if (!isAnyGroup && others.size == 1) {
            return ConversationAvatarModel(
                type = ConversationAvatarModel.Type.Connection,
                odinId = others.first()
            )
        }

        return ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback)
    }
}
