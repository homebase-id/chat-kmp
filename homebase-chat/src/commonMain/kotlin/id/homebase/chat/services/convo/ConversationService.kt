package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.IntroductionGroup
import id.homebase.api.client.connections.IntroductionSender
import id.homebase.api.client.drives.FileStateFilter
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.DeleteFilesByGroupIdOutboxRequest
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.drives.upload.FileIdFileIdentifier
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocalMetadataTagsOutboxRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.api.sync.database.EnqueueResult
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.enqueued
import id.homebase.api.toBase64
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.PayloadBundleEncryptor
import id.homebase.chat.services.GroupHealCleanupInfo
import id.homebase.chat.services.GroupHealInfo
import id.homebase.chat.services.StatusMessage
import id.homebase.chat.services.StatusMessageData
import id.homebase.chat.services.XorIdUtil
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.chat.services.resendablePayloads
import id.homebase.core.config.chatTargetDrive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

// ╔════════════════════════════════════════════════════════════════════════════╗
// ║  TEMPORARY DEBUG INSTRUMENTATION HELPER — DO NOT MERGE                      ║
// ║  Used by every mutating method on ConversationService to emit a uniform     ║
// ║  PRE / STEP / POST / DIAGNOSIS audit trail under tag "ConvoAudit".          ║
// ║                                                                             ║
// ║  Read CONVERSATION_SERVICE_DEBUG.md at the repo root for the log format     ║
// ║  reference and the list of every check name + what a FAIL means.            ║
// ║                                                                             ║
// ║  TO REMOVE WHEN DONE:                                                       ║
// ║    1. Delete this MethodAudit class.                                        ║
// ║    2. In each method below, search for `val audit = MethodAudit(` and       ║
// ║       remove the surrounding instrumentation. Each instrumented method has  ║
// ║       a `// ---- DEBUG instrumentation` / `// ---- end DEBUG` fence.        ║
// ║    3. Remove the `import id.homebase.api.sync.database.QueryBatch` line     ║
// ║       above (only used by the audit).                                       ║
// ╚════════════════════════════════════════════════════════════════════════════╝
class ConversationService(
    private val credentialsManager: CredentialsManager,
    private val payloadBundleEncryptionService: PayloadBundleEncryptor,
    private val dbm: DatabaseManager,
    private val introductionProvider: IntroductionSender,
    private val scope: CoroutineScope,
    private val outboxSync: OutboxSync,
    private val chatMessageSenderService: StatusMessageSender,
    private val optimisticWriter: OptimisticWriter,
    private val conversationStream: ConversationLoader,
    /**
     * In-memory lookup over the live conversation list. Used by
     * [updateLocalLastReadTime] so the only-increases gate
     * sees the freshest known lastRead — including peer-device advances
     * that landed via `processConversationBatchIncrementally` or the
     * post-DriveSync `loadBasicConversations` reload but haven't yet been
     * mirrored into `ChatReadCount` by the enrichment pass. Without this,
     * a local mark-as-read at t2 between a peer's t3 landing in memory
     * and the next enrichment mirror would slip the gate (ChatReadCount
     * still at t1) and stamp the conversation file backwards.
     */
    private val participantLookup: ConversationParticipantLookup,
    /** Used by the heal redistribute path to pull existing payload AND thumbnail
     *  bytes off our drive and re-attach them to the update request. Narrowed to
     *  [ResendPayloadByteSource] (implemented by DriveFileProvider) so tests can
     *  pass a fake without standing up the full network stack — the payload-reuse
     *  helper short-circuits to an empty manifest when null. */
    private val driveFileProvider: id.homebase.api.client.drives.files.ResendPayloadByteSource? = null,
    /** Used by the heal redistribute path to spill the encrypted payload bytes
     *  to a temp file for upload. Same nullable-for-tests rationale as above. */
    private val fileOperationsProvider: id.homebase.api.file.FileOperationsProvider? = null,
    /** Coalescing window for the lastRead writeback. Production uses the 1s
     *  default; tests pass a very large value and drive the flush explicitly
     *  via [flushLastReadNow] to avoid virtual-time fragility under
     *  `runTest` (whose dispatcher behavior around `delay` + launched
     *  coroutines doesn't reliably hold the timer parked). */
    private val lastReadDebounceMs: Long = 1_000L,
    /**
     * Chat-drive sync-activity probe. The lastRead flush defers while a
     * chat-drive sync round is in flight and for [syncQuietMs] after it stops,
     * so it never stamps a stale in-memory lastRead over a peer advance the sync
     * just pulled into DriveMainIndex — the post-Stopped reload
     * ([ConversationStream.loadBasicConversations]) reconciles the in-memory
     * list within that window. Nullable so tests omit it (no gating); production
     * wires the real [DriveSyncManager].
     */
    private val driveSyncManager: DriveSyncManager? = null,
    /** Quiet window after a chat-drive sync stops before the flush may push. */
    private val syncQuietMs: Long = 2_000L,
    /** Poll interval while waiting out an in-flight chat-drive sync. */
    private val syncPollMs: Long = 100L,
) : LocalLastReadUpdater, GroupHealConversationOps {
    private val chatDrive = chatTargetDrive.alias

    // region lastRead writeback (debounce job — dirty state lives on the list)
    // The "only-increases" gate for conversation lastRead lives in
    // [updateLocalLastReadTime] below. The pending-writeback state is NOT held
    // here anymore — the in-memory conversation list is the single source of
    // truth (each ConversationUiModel carries its own lastRead + dirty flag).
    // This setter advances the in-memory model (lastRead + dirty, together) via
    // [ConversationParticipantLookup.advancedLastRead] and schedules the flush.
    //
    // Burst behavior: ChatReadCount is upserted eagerly inside the setter
    // (so participantLookup.advancedLastRead, which reads it via SQL for the
    // unread re-derive, sees the fresh value); the per-call optimistic conv-file
    // stamp + outbox enqueue are deferred to a single 1-second-debounced flush
    // that walks the dirty conversations. The mutex only guards the debounce job
    // lifecycle.
    private val lastReadMutex = Mutex()
    private var lastReadFlushJob: Job? = null
    // endregion

    private val mapper: ConversationMapper = ConversationMapper(
        credentialsManager = credentialsManager,
        dbm = dbm
    )

    suspend fun createConversation(
        recipients: List<OdinId>,
        title: String?,
        payloadBundle: PayloadBundle?
    ): CreateConversationResult {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("createConversation")
        audit.start("recipients=${recipients.size} title='$title' hasBundle=${payloadBundle != null}")
        // PARTICIPANT-LIST TRACE — dump the raw input verbatim. If the bug is on the UI
        // side (selection screen passing an incomplete list) it surfaces right here.
        Logger.i(tag = "ParticipantsAudit") {
            "createConversation INPUT: rawRecipients.size=${recipients.size} domains=[${recipients.joinToString(",") { it.domainName }}] " +
                "rawHasNull=${@Suppress("SENSELESS_COMPARISON") recipients.any { it == null }} " +
                "duplicateCount=${recipients.size - recipients.distinct().size}"
        }
        if (recipients.size != recipients.distinct().size) {
            Logger.w(tag = "ParticipantsAudit") {
                "createConversation INPUT had duplicates — distinct() will collapse ${recipients.size} → ${recipients.distinct().size}. " +
                    "If the UI selection layer is supposed to dedupe upstream, that step missed."
            }
        }
        // ---- end DEBUG ----

        val domain = credentialsManager.requireActiveDomain()

        // I know, this is illogical but somehow a null made it in so #paranoid
        require(recipients.none {
            @Suppress("SENSELESS_COMPARISON")
            it == null
        }) {
            "Conversation recipients contained null"
        }

        val normalizedRecipients =
            recipients
                .filterNot { it == domain }
                .distinct()

        require(normalizedRecipients.isNotEmpty()) {
            "Conversation must have at least one recipient other than self"
        }

        val isGroup = normalizedRecipients.size > 1

        val newConversationId: Uuid =
            if (isGroup) {
                Uuid.random()
            } else {
                XorIdUtil.getNewXorId(domain.domainName, normalizedRecipients.first().domainName)
            }

        // ---- DEBUG instrumentation ----
        audit.info("computed newConversationId=$newConversationId isGroup=$isGroup normalizedRecipients=${normalizedRecipients.map { it.domainName }}")
        // PARTICIPANT-LIST TRACE — log the result of self-filter and dedup. If a recipient
        // is absent from `normalizedRecipients` but was in `recipients`, either it equalled
        // self (.filterNot { it == domain }) OR it was a duplicate (.distinct()).
        val droppedAsSelf = recipients.filter { it == domain }
        val droppedAsDup = recipients.groupBy { it.domainName }.filter { it.value.size > 1 }.keys
        Logger.i(tag = "ParticipantsAudit") {
            "createConversation NORMALIZED: domain=${domain.domainName} normalized=[${normalizedRecipients.joinToString(",") { it.domainName }}] " +
                "droppedAsSelf=${droppedAsSelf.size} droppedAsDup=$droppedAsDup"
        }
        val outboxBefore = dbm.outbox.count()
        audit.pre("outboxRows=$outboxBefore")
        // ---- end DEBUG ----

        // The deterministic 1:1 uniqueId may already exist server-side from a prior
        // (possibly deleted or corrupted) conversation. Check for an existing file
        // without requiring a clean parse — some older files may be missing
        // participant data and would otherwise throw.
        val existingFile = getConversationHomebaseFile(newConversationId)
        if (existingFile != null) {
            val existingState: ConversationState? = try {
                mapper.mapToConversationUi(existingFile, null).conversationState
            } catch (e: Exception) {
                Logger.w(e) { "Existing conversation file $newConversationId failed to map — will overwrite" }
                null
            }

            Logger.d("createConversation: $newConversationId found existing file in local DB, state=$existingState")
            audit.info("existing file found, state=$existingState")

            val needsRevive = existingState == null ||
                    existingState == ConversationState.Deleted ||
                    existingState == ConversationState.Invalid

            if (needsRevive) {
                Logger.d("createConversation: $newConversationId reviving (state=$existingState)")
                audit.step(1, "reviving via updateConversationInternal(archivalStatus=None, distribute=true)")
                // Revive by clearing the Removed archival flag and pushing a fresh
                // participant list from the caller. updateConversationInternal uses
                // replaceEnqueue, so this supersedes any stale pending update.
                runCatching {
                    updateConversationInternal(
                        conversationId = newConversationId,
                        title = title ?: "",
                        participants = (normalizedRecipients + domain).distinct(),
                        archivalStatus = ArchivalStatus.None,
                        distribute = true,
                    )
                }.onSuccess { audit.checkPass("revive") }
                    .onFailure { e ->
                        audit.threw("revive", e)
                        audit.finish("ABORTED while reviving existing file")
                        throw e
                    }
            } else {
                audit.info("existing file is in usable state, no revive needed")
            }
            audit.checkPass("existingFileBranch")
            audit.finish("returned wasNewlyCreated=false (path=existing-file)")
            return CreateConversationResult(newConversationId, wasNewlyCreated = false)
        }

        Logger.d("createConversation: $newConversationId no local file found — creating new (recipients=$normalizedRecipients)")
        val allParticipants = (normalizedRecipients + domain).distinct()
        // ---- DEBUG instrumentation ----
        audit.step(1, "writeConversationFile(allParticipants=${allParticipants.size}, transit=${normalizedRecipients.size}, isGroup=$isGroup)")
        // PARTICIPANT-LIST TRACE — the list about to be persisted to the conversation file.
        // This is the SOURCE OF TRUTH that all subsequent reads (mapper, group-settings UI)
        // derive from. If members are missing in the UI later, the bug is *between* this
        // line and the read site.
        Logger.i(tag = "ParticipantsAudit") {
            "createConversation ALL_PARTICIPANTS (will be persisted to conversation file content): " +
                "size=${allParticipants.size} domains=[${allParticipants.joinToString(",") { it.domainName }}] " +
                "(includes self=${allParticipants.contains(domain)})"
        }
        if (allParticipants.size != recipients.distinct().filterNot { it == domain }.size + 1) {
            Logger.w(tag = "ParticipantsAudit") {
                "createConversation ALL_PARTICIPANTS size differs from expected (input - dups - self + 1) — investigate normalize math"
            }
        }
        // ---- end DEBUG ----
        val success = runCatching {
            writeConversationFile(
                conversationId = newConversationId,
                allParticipants = allParticipants,
                transitRecipients = normalizedRecipients,
                title = title,
                isGroup = isGroup,
                payloadBundle = payloadBundle
            )
        }.onFailure { e ->
            audit.threw("writeConversationFile", e)
            audit.finish("ABORTED at writeConversationFile")
            throw e
        }.getOrThrow()

        if (!success) {
            audit.checkFail("writeConversationFile", "writeConversationFile returned false — outbox enqueue failed; conversation NOT created")
            audit.finish("ABORTED — writeConversationFile false")
            error("failed to create conversation")
        }
        audit.checkPass("writeConversationFile")

        // Create separate admin file for groups
        if (isGroup) {
            audit.step(2, "uploadAdminFile(admins=[$domain], recipients=${normalizedRecipients.size})")
            runCatching {
                uploadAdminFile(
                    conversationId = newConversationId,
                    admins = listOf(domain),
                    recipients = normalizedRecipients
                )
            }.onSuccess { audit.checkPass("uploadAdminFile") }
                .onFailure { e ->
                    audit.threw("uploadAdminFile", e)
                    audit.finish("ABORTED at uploadAdminFile — conversation file uploaded but no admin file")
                    throw e
                }
        }

        if (isGroup) {
            audit.step(3, "trySendIntroductions(${normalizedRecipients.size} recipients) — best-effort, swallows errors")
            trySendIntroductions(normalizedRecipients, "$domain has added you to a group chat")
            audit.checkPass("trySendIntroductions")

            audit.step(4, "sendStatusMessage(GroupConversationStarted) chained off admin file")
            // Serialize: conversation file → admin file → status message. Chaining the
            // status message off the admin file (rather than off the conversation file
            // directly) avoids a fan-out parallel where both the admin file and the
            // status message would release the moment the conversation file is
            // acknowledged at our own server, then race through Transit in any order.
            // With this chain, recipients see the conversation file, then the admin
            // file, then the "group started" status message — strict order on our side.
            runCatching {
                chatMessageSenderService.sendStatusMessage(
                    messageUniqueId = Uuid.random(),
                    conversationId = newConversationId,
                    previousMessageUniqueId = ChatProtocol.getAdminFileUniqueId(newConversationId),
                    statusMessage = StatusMessageData(
                        statusMessage = StatusMessage.GroupConversationStarted,
                        subject = null
                    ),

                )
            }.onSuccess { audit.checkPass("sendGroupStartedStatus") }
                .onFailure { e ->
                    audit.threw("sendGroupStartedStatus", e)
                    audit.finish("ABORTED at sendStatusMessage — group exists but no 'started' message")
                    throw e
                }
        }

        // ---- DEBUG instrumentation: POST verification ----
        val postFile = getConversationHomebaseFile(newConversationId)
        audit.post("file: exists=${postFile != null} fileId=${postFile?.fileId}")
        audit.check("postFileExists", postFile != null,
            "createConversation returned but conversation file is not visible from local index — DB write or query mismatch")
        val outboxAfter = dbm.outbox.count()
        val expectedDelta = if (isGroup) 3 else 1
        audit.post("counts: outboxRows=$outboxAfter (delta=${outboxAfter - outboxBefore}, expected ≥$expectedDelta)")
        audit.check("postOutboxDelta", outboxAfter - outboxBefore >= expectedDelta,
            "outbox grew by ${outboxAfter - outboxBefore} rows, expected ≥$expectedDelta (writeConversationFile + uploadAdminFile + sendStatusMessage for group)")
        audit.finish("returned wasNewlyCreated=true (path=new-file)")
        // ---- end DEBUG ----

        return CreateConversationResult(newConversationId, wasNewlyCreated = true)
    }

    /**
     * Result of [createConversation]. [wasNewlyCreated] is true when a fresh conversation file
     * was written; false when an existing file (active or revived) satisfied the request. Use
     * this to decide whether to post "conversation started" status messages — skip if false.
     */
    data class CreateConversationResult(
        val conversationId: Uuid,
        val wasNewlyCreated: Boolean
    )

    /**
     * Notifies [recipient] that the local user has designated them as an emergency contact by
     * posting a [StatusMessage.EmergencyContactDesignated] status into their 1:1 conversation
     * (created if it doesn't exist yet). [StatusMessageData.subject] carries [recipient] so the
     * sender's own copy renders "You added {recipient}…"; the receiver's copy renders "{sender}
     * added you…" and drives the receive-side bit (see
     * [id.homebase.chat.services.convo.ConversationStream.onEmergencyContactDesignated]).
     *
     * Unlike the "conversation started" status this is posted on every designation (not just a
     * freshly-created thread). Best-effort: returns the conversation id on success, or null on
     * failure (logged). Rethrows cancellation.
     */
    suspend fun sendEmergencyContactDesignation(recipient: OdinId): Uuid? {
        return try {
            val result = createConversation(
                recipients = listOf(recipient),
                title = null,
                payloadBundle = null,
            )
            chatMessageSenderService.sendStatusMessage(
                messageUniqueId = Uuid.random(),
                conversationId = result.conversationId,
                previousMessageUniqueId = result.conversationId,
                statusMessage = StatusMessageData(
                    statusMessage = StatusMessage.EmergencyContactDesignated,
                    subject = recipient,
                ),
            )
            result.conversationId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e) { "Failed to send emergency-contact designation to ${recipient.domainName}" }
            null
        }
    }

    /**
     * Creates a conversation file locally and enqueues it for server upload.
     * Shared by [createConversation] and [ensureNoteToSelfExists].
     *
     * @return true if the file was successfully enqueued for upload
     */
    private suspend fun writeConversationFile(
        conversationId: Uuid,
        allParticipants: List<OdinId>,
        transitRecipients: List<OdinId>,
        title: String?,
        isGroup: Boolean,
        payloadBundle: PayloadBundle? = null
    ): Boolean {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("writeConversationFile")
        audit.start("conversationId=$conversationId allParticipants=${allParticipants.size} transit=${transitRecipients.size} isGroup=$isGroup hasBundle=${payloadBundle != null}")
        val outboxBefore = dbm.outbox.count()
        // ---- end DEBUG ----
        val keyHeader = KeyHeader.newRandom16()

        val content = ConversationAppDataJson(
            title = title ?: "",
            recipients = allParticipants,
            version = 1
        )
        // ---- DEBUG instrumentation ----
        // PARTICIPANT-LIST TRACE — the exact JSON that gets serialized into appData.content.
        // If you see fewer entries here than what `createConversation ALL_PARTICIPANTS`
        // logged, the bug is in the ConversationAppDataJson construction.
        runCatching { OdinSystemSerializer.serialize(content) }.onSuccess { json ->
            Logger.i(tag = "ParticipantsAudit") {
                "writeConversationFile SERIALIZED content for $conversationId: $json"
            }
        }.onFailure { e ->
            Logger.w(tag = "ParticipantsAudit") { "writeConversationFile SERIALIZE FAILED for $conversationId: ${e.message}" }
        }
        // ---- end DEBUG ----

        val encryptedBundle = payloadBundleEncryptionService.encryptBundle(
            conversationId,
            payloadBundle,
            keyHeader.aesKey,
            scope
        )

        val metadata = UploadFileMetadata(
            allowDistribution = true,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = conversationId,
                tags = if (isGroup) listOf(ChatProtocol.ConversationGroupTag) else null,
                fileType = ChatProtocol.ConversationFileType,
                content = OdinSystemSerializer.serialize(content),
                previewThumbnail = encryptedBundle.previewThumbs.minByOrNull { it.pixelWidth }
            ),
        )

        val request = UploadFileRequest(
            driveId = chatDrive,
            keyHeader = keyHeader,
            metadata = metadata.encryptContent(keyHeader),
            transitOptions = TransitOptions(
                recipients = transitRecipients,
                useAppNotification = false
            ),
            payloads = encryptedBundle.payloads,
            thumbnails = encryptedBundle.thumbnails
        )

        // ---- DEBUG instrumentation ----
        audit.step(1, "optimisticWriter.writeNewFile (originalRecipientCount=${transitRecipients.size})")
        runCatching {
            optimisticWriter.writeNewFile(
                driveId = chatDrive,
                keyHeader = keyHeader,
                unecryptedMetadata = metadata,
                originalRecipientCount = transitRecipients.size,
                fileSystemType = FileSystemType.Standard,
            )
        }.onSuccess { audit.checkPass("optimisticWriteNew") }
            .onFailure { e ->
                audit.threw("optimisticWriteNew", e)
                audit.finish("ABORTED at optimisticWriter.writeNewFile")
                throw e
            }
        // PARTICIPANT-LIST TRACE — re-read the file we just wrote and verify the
        // participants list survived the optimistic-writer round-trip. If there is
        // a mismatch here, the bug is in optimisticWriter.writeNewFile.
        runCatching {
            val justWritten = getConversationHomebaseFile(conversationId)
            val parsedContent = justWritten?.fileMetadata?.appData?.content?.let {
                OdinSystemSerializer.deserialize<ConversationAppDataJson>(it)
            }
            val readBackRecipients = parsedContent?.recipients?.filterNotNull() ?: emptyList()
            Logger.i(tag = "ParticipantsAudit") {
                "writeConversationFile READBACK after optimisticWriter for $conversationId: " +
                    "fileExists=${justWritten != null} " +
                    "recipients.size=${readBackRecipients.size} " +
                    "domains=[${readBackRecipients.joinToString(",") { it.domainName }}] " +
                    "title='${parsedContent?.title}'"
            }
            if (justWritten != null && readBackRecipients.size != allParticipants.size) {
                Logger.w(tag = "ParticipantsAudit") {
                    "writeConversationFile READBACK MISMATCH: persisted file has ${readBackRecipients.size} recipients " +
                        "but allParticipants had ${allParticipants.size}. Optimistic write dropped entries — " +
                        "compare domain lists above to identify which were lost."
                }
            }
        }.onFailure { e ->
            Logger.w(tag = "ParticipantsAudit") { "writeConversationFile READBACK failed for $conversationId: ${e.message}" }
        }
        audit.step(2, "conversationStream.loadConversation")
        runCatching { conversationStream.loadConversation(conversationId) }
            .onSuccess { audit.checkPass("loadConversation") }
            .onFailure { e ->
                audit.threw("loadConversation", e)
                audit.finish("ABORTED at conversationStream.loadConversation")
                throw e   // original code did not catch — preserve propagation
            }
        audit.step(3, "outboxSync.tryEnqueue(UploadFileRequest)")
        val enqueued = outboxSync.tryEnqueue(request)
        audit.info("STEP 3 returned $enqueued")
        audit.check("outboxEnqueue", enqueued.enqueued, "outboxSync.tryEnqueue → $enqueued — file will not be uploaded; conversation creation effectively failed")
        val outboxAfter = dbm.outbox.count()
        audit.post("counts: outboxRows=$outboxAfter (delta=${outboxAfter - outboxBefore}, expected ≥+1 if enqueue succeeded)")
        audit.finish("returned $enqueued")
        return enqueued.enqueued
        // ---- end DEBUG ----
    }

    /**
     * Ensures a real note-to-self conversation file exists.
     * Uses [ChatProtocol.ConversationWithYourselfId] as the conversation ID,
     * then creates and pins the conversation if it doesn't already exist in the DB.
     */
    suspend fun ensureNoteToSelfExists() {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("ensureNoteToSelfExists")
        audit.start()
        // ---- end DEBUG ----
        try {
            val domain = credentialsManager.requireActiveDomain()
            val noteToSelfId = ChatProtocol.ConversationWithYourselfId

            val existing = getConversation(noteToSelfId)
            audit.pre("existing=${existing != null} state=${existing?.conversationState}")
            if (existing != null && existing.conversationState != ConversationState.Deleted) {
                audit.finish("no-op (already exists, state=${existing.conversationState})")
                return
            }

            if (existing != null) {
                // Conversation was soft-deleted — undelete it by clearing archivalStatus.
                // We can't create a new file because the server still has the old one.
                Logger.d("ConversationService: undeleting note-to-self conversation $noteToSelfId")
                audit.step(1, "undelete: updateConversationInternal(archivalStatus=None, distribute=false)")
                updateConversationInternal(
                    conversationId = noteToSelfId,
                    title = "",
                    participants = listOf(domain),
                    archivalStatus = ArchivalStatus.None,
                    distribute = false
                )
                audit.step(2, "pinConversation")
                pinConversation(noteToSelfId)
                audit.finish("undeleted + pinned")
                return
            }

            // First-ever creation — no file exists locally or on the server
            Logger.d("ConversationService: creating note-to-self conversation $noteToSelfId")
            audit.step(1, "writeConversationFile (first-ever creation)")
            val success = writeConversationFile(
                conversationId = noteToSelfId,
                allParticipants = listOf(domain),
                transitRecipients = emptyList(),
                title = "",
                isGroup = false
            )
            audit.check("writeConversationFile", success, "writeConversationFile returned false — note-to-self NOT created")
            if (success) {
                audit.step(2, "pinConversation")
                pinConversation(noteToSelfId)
            }
            audit.finish("created success=$success")
        } catch (e: Throwable) {
            audit.threw("execution", e)
            audit.finish("threw")
            throw e
        }
    }

    override suspend fun requireConversation(conversationId: Uuid): ConversationUiModel {
        return getConversation(conversationId)
            ?: throw IllegalStateException("No conversation found")
    }

    suspend fun requireConversationFileId(conversationId: Uuid): Uuid {
        return getConversationHomebaseFile(conversationId)?.fileId
            ?: throw IllegalStateException("No conversation found")
    }

    override suspend fun getConversation(conversationId: Uuid): ConversationUiModel? {
        val file = getConversationHomebaseFile(conversationId) ?: return null
        return mapper.mapToConversationUi(file, null)
    }

    suspend fun updateAdmins(
        conversationId: Uuid,
        add: List<OdinId> = emptyList(),
        remove: List<OdinId> = emptyList()
    ) {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("updateAdmins")
        audit.start("conversationId=$conversationId add=${add.size} remove=${remove.size}")
        // ---- end DEBUG ----
        val conversation = requireConversation(conversationId)
        val domain = credentialsManager.requireActiveDomain()
        audit.pre("currentAdmins=${conversation.admins.size} participants=${conversation.participants.size} legacyGroup=${conversation.isLegacyGroup}")

        if (conversation.isLegacyGroup) {
            audit.checkFail("legacyGroupGuard", "admin management not available for legacy groups")
            audit.finish("REJECTED at legacy-group guard")
            throw IllegalStateException("Admin management is not available for legacy groups")
        }

        Logger.d { "updateAdmins: conversationId=$conversationId add=$add remove=$remove currentAdmins=${conversation.admins} participants=${conversation.participants}" }

        requireCallerIsGroupAdmin(conversation)

        val recipients = conversation.participants
        val admins = conversation.admins.toMutableSet()

        // additions must already be participants
        require(add.all { recipients.contains(it) }) {
            "Admins must be recipients"
        }

        admins.addAll(add)
        admins.removeAll(remove)

        if (admins.isEmpty()) {
            if (remove.contains(domain)) {
                throw IllegalStateException("Cannot remove the last admin. You must first add another to replace you.")
            } else {
                throw IllegalStateException("Conversation must have at least one admin")
            }
        }

        Logger.d { "updateAdmins: resolved admins=$admins recipients=${recipients.filterNot { it == domain }}" }
        audit.info("resolved admins=${admins.size} recipients=${recipients.size}")
        audit.step(1, "updateAdminFile")
        runCatching {
            updateAdminFile(
                conversationId = conversationId,
                admins = admins.toList(),
                recipients = recipients.filterNot { it == domain }
            )
        }.onSuccess { audit.checkPass("updateAdminFile") }
            .onFailure { e ->
                audit.threw("updateAdminFile", e)
                audit.finish("ABORTED at updateAdminFile")
                throw e
            }

        // Serialize the status messages behind the admin-file update so the admin file
        // lands on each recipient first, then the "X is now admin" / "X is no longer
        // admin" status messages in their original order. Without this initial dep,
        // the first status message would race the admin-file update through Transit.
        var previousMessageId: Uuid? = ChatProtocol.getAdminFileUniqueId(conversationId)
        add.forEach { user ->
            val messageId = Uuid.random()
            chatMessageSenderService.sendStatusMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                statusMessage = StatusMessageData(
                    statusMessage = StatusMessage.ConversationAdminAdded,
                    subject = user
                ),
                previousMessageUniqueId = previousMessageId
            )

            previousMessageId = messageId
        }

        remove.forEach { user ->
            val messageId = Uuid.random()
            chatMessageSenderService.sendStatusMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                statusMessage = StatusMessageData(
                    statusMessage = StatusMessage.ConversationAdminRemoved,
                    subject = user
                ),
                previousMessageUniqueId = previousMessageId
            )

            previousMessageId = messageId
        }
        // ---- DEBUG instrumentation ----
        audit.info("status messages sent: added=${add.size} removed=${remove.size}")
        audit.finish()
        // ---- end DEBUG ----
    }

    suspend fun updateGroupMembers(
        conversationId: Uuid,
        add: List<OdinId> = emptyList(),
        remove: List<OdinId> = emptyList()
    ) {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("updateGroupMembers")
        audit.start("conversationId=$conversationId add=${add.size} remove=${remove.size}")
        // ---- end DEBUG ----
        val conversation = requireConversation(conversationId)
        audit.pre("currentParticipants=${conversation.participants.size} admins=${conversation.admins.size} legacyGroup=${conversation.isLegacyGroup}")

        if (conversation.isLegacyGroup) {
            audit.checkFail("legacyGroupGuard", "member management not available for legacy groups")
            audit.finish("REJECTED at legacy-group guard")
            throw IllegalStateException("Member management is not available for legacy groups")
        }

        requireCallerIsGroupAdmin(conversation)

        val domain = credentialsManager.requireActiveDomain()

        val adminsInRemoveList = conversation.admins.intersect(remove.toSet())
        require(adminsInRemoveList.isEmpty()) {
            "Cannot remove admins via updateGroupMembers. Use updateAdmins first to remove their admin role: $adminsInRemoveList"
        }

        val current = conversation.participants.toMutableSet()

        val removed = current.intersect(remove.toSet())
        current.removeAll(remove)

        val added = add.filterNot { current.contains(it) }
        current.addAll(added)

        var previousMessageId: Uuid? = null

        removed.forEach { user ->
            val messageId = Uuid.random()
            chatMessageSenderService.sendStatusMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                statusMessage = StatusMessageData(
                    statusMessage = StatusMessage.ConversationMemberRemoved,
                    subject = user
                ),
                previousMessageUniqueId = previousMessageId
            )

            previousMessageId = messageId
        }

        val normalized = (current + domain).distinct()

        // Chain the conversation-file update behind the last "X was removed" status
        // message so peers see the removed-status before the participant change lands.
        // If there were no removals, previousMessageId is null and the update has no
        // upstream dep — same as before.
        updateConversationInternal(
            conversationId = conversationId,
            title = conversation.name,
            participants = normalized,
            additionalDistributionRecipients = removed.toList(),
            dependencyUniqueId = previousMessageId,
        )

        // tell the group who was added after we update the conversation so
        // the new people will get the message too
        if (added.isNotEmpty()) {

            // ensure the message is sent to added after they get the new conversation file
            previousMessageId = conversationId

            added.forEach { user ->
                val messageId = Uuid.random()
                chatMessageSenderService.sendStatusMessage(
                    messageUniqueId = messageId,
                    conversationId = conversationId,
                    statusMessage = StatusMessageData(
                        statusMessage = StatusMessage.ConversationMemberAdded,
                        subject = user
                    ),
                    previousMessageUniqueId = previousMessageId,
                    additionalRecipients = listOf(user)
                )

                previousMessageId = messageId
            }

            trySendIntroductions((current - domain).toList(), "You share a group chat with $domain")
        }
        // ---- DEBUG instrumentation ----
        audit.info("members updated: added=${added.size} removed=${removed.size} newCount=${current.size}")
        audit.finish()
        // ---- end DEBUG ----
    }

    suspend fun updateConversation(
        conversationId: Uuid,
        title: String?,
        payloadBundle: PayloadBundle? = null
    ) {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("updateConversation")
        audit.start("conversationId=$conversationId title='$title' hasBundle=${payloadBundle != null}")
        // ---- end DEBUG ----
        val conversation = requireConversation(conversationId)
        audit.pre("isGroup=${conversation.isGroupConversation} currentTitle='${conversation.name}'")

        if (conversation.isGroupConversation) {
            requireCallerIsGroupAdmin(conversation)
        }

        audit.step(1, "updateConversationInternal")
        runCatching {
            updateConversationInternal(
                conversationId = conversationId,
                title = title,
                participants = conversation.participants,
                payloadBundle = payloadBundle
            )
        }.onSuccess { audit.checkPass("updateConversationInternal") }
            .onFailure { e ->
                audit.threw("updateConversationInternal", e)
                audit.finish("ABORTED at updateConversationInternal")
                throw e
            }

        var previousMessageId: Uuid? = null
        if (title != null && title != conversation.name) {

            val messageId = Uuid.random()

            chatMessageSenderService.sendStatusMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                statusMessage = StatusMessageData(
                    statusMessage = StatusMessage.ConversationTitleUpdated,
                ),
                previousMessageUniqueId = previousMessageId
            )

            previousMessageId = messageId
        }

        if (payloadBundle != null) {

            val messageId = Uuid.random()

            chatMessageSenderService.sendStatusMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                statusMessage = StatusMessageData(
                    statusMessage = StatusMessage.ConversationPhotoUpdated,
                ),
                previousMessageUniqueId = previousMessageId
            )

            previousMessageId = messageId
        }
        // ---- DEBUG instrumentation ----
        audit.finish("titleChanged=${title != null && title != conversation.name} bundleChanged=${payloadBundle != null}")
        // ---- end DEBUG ----
    }

    /**
     * @param forceLocalOnly caller has determined the group is isolated (no reachable
     *  participant to receive distributed updates). Skips the admin-distribution protocol
     *  and just flips [ChatProtocol.ConversationLeftTag] locally, matching the legacy-group
     *  path. Also bypasses the sole-admin guard since there is no one to promote.
     */
    suspend fun leaveGroup(conversationId: Uuid, forceLocalOnly: Boolean = false) {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("leaveGroup")
        audit.start("conversationId=$conversationId forceLocalOnly=$forceLocalOnly")
        val outboxBefore = dbm.outbox.count()
        // ---- end DEBUG ----
        val conversation = requireConversation(conversationId)
        val domain = credentialsManager.requireActiveDomain()
        val leaveFile = getConversationHomebaseFile(conversationId)
        val preVersionTag = leaveFile?.fileMetadata?.versionTag
        Logger.d { "leaveGroup START: conversationId=$conversationId forceLocalOnly=$forceLocalOnly isEncrypted=${leaveFile?.fileMetadata?.isEncrypted} aesKey=${leaveFile?.keyHeader?.aesKey?.unsafeBytes?.toBase64() ?: "NO FILE"}" }
        audit.pre("convo: isGroup=${conversation.isGroupConversation} legacyGroup=${conversation.isLegacyGroup} state=${conversation.conversationState} participants=${conversation.participants.size} admins=${conversation.admins.size} amIAdmin=${conversation.admins.contains(domain)}")
        audit.pre("file: exists=${leaveFile != null} versionTag=$preVersionTag")

        if (!conversation.isGroupConversation) {
            audit.checkFail("isGroupGuard", "called leaveGroup on a non-group conversation")
            audit.finish("REJECTED at isGroup guard")
            throw IllegalStateException("Can only leave group conversations")
        }

        if (conversation.isLegacyGroup || forceLocalOnly) {
            audit.info("LOCAL-ONLY path (legacyGroup=${conversation.isLegacyGroup} forceLocalOnly=$forceLocalOnly): just flipping LeftTag")
            // Legacy groups don't support the full leave protocol, and isolated groups
            // (no reachable participant) have nobody to distribute to — just mark locally.
            audit.step(1, "updateConversationTags(+LeftTag)")
            runCatching {
                updateConversationTags(conversationId, dependencyUniqueId = conversationId) {
                    it + ChatProtocol.ConversationLeftTag
                }
            }.onSuccess { audit.checkPass("localOnlyAddLeftTag") }
                .onFailure { e ->
                    audit.threw("localOnlyAddLeftTag", e)
                    audit.finish("ABORTED at local-only path")
                    throw e
                }
            // Verify locally
            val postFile = getConversationHomebaseFile(conversationId)
            val hasLeftTag = postFile?.fileMetadata?.localAppData?.tags?.contains(ChatProtocol.ConversationLeftTag) == true
            audit.post("file localTags now contain LeftTag=$hasLeftTag")
            audit.check("postLeftTagPresent", hasLeftTag, "LeftTag NOT present in localTags after updateConversationTags — local optimistic write did not apply")
            audit.finish("LOCAL-ONLY path completed")
            return
        }

        if (conversation.admins.contains(domain) && (conversation.admins - domain).isEmpty()) {
            audit.checkFail("soleAdminGuard", "you are the only admin — must promote another before leaving")
            audit.finish("REJECTED at sole-admin guard")
            throw IllegalStateException("You are the only admin. Assign another admin before leaving.")
        }

        val remaining = conversation.participants.filterNot { it == domain }

        val messageId = Uuid.random()

        // 1. Notify the group first so they see the leave message
        audit.step(1, "sendStatusMessage(MemberLeft) — best-effort, swallows errors")
        try {
            chatMessageSenderService.sendStatusMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                statusMessage = StatusMessageData(
                    statusMessage = StatusMessage.ConversationMemberLeft,
                    subject = domain
                )
            )
            audit.checkPass("step1SendLeaveMessage")
        } catch (t: Throwable) {
            Logger.e("Failed to send leave status message", t)
            audit.checkWarn("step1SendLeaveMessage", "sendStatusMessage(MemberLeft) threw — swallowed by original code; continuing with leave. Error: ${t.message}")
        }

        // 2. Remove self from participants — chained after status message.
        // If this fails, roll back the optimistic status message AND its outbox entry
        // so a ghost "X left" message is not sent to the group while the leave didn't complete.
        //
        // applyOptimisticContentLocally=true so the participants list is updated in the
        // local DB BEFORE step 4 flips the LeftTag. Without this, the mapper sees
        // (LeftTag-set AND domain-still-in-participants) → RejoinPending, and the user
        // sees a spurious "You were re-added to this group" the moment after leaving.
        audit.step(2, "updateConversationInternal(participants=${remaining.size}, dep=messageId, applyOptimisticContentLocally=true)")
        try {
            updateConversationInternal(
                conversationId = conversationId,
                title = conversation.name,
                participants = remaining,
                dependencyUniqueId = messageId,
                applyOptimisticContentLocally = true,
            )
            audit.checkPass("step2RemoveSelf")
        } catch (t: Throwable) {
            audit.threw("step2RemoveSelf", t)
            audit.info("ROLLBACK: removing optimistic status message and its outbox row")
            // removeOptimisticFile now also cancels the queued outbox send.
            optimisticWriter.removeOptimisticFile(chatDrive, messageId)
            audit.finish("ABORTED at STEP 2 — rolled back step 1 message; leave did NOT complete")
            throw t
        }

        // 3. Remove self from admins (separate file)
        if (conversation.admins.contains(domain)) {
            audit.step(3, "updateAdminFile (caller is admin, removing self from admins)")
            runCatching {
                val updatedAdmins = conversation.admins - domain
                updateAdminFile(
                    conversationId = conversationId,
                    admins = updatedAdmins.toList(),
                    recipients = remaining.filterNot { it == domain }
                )
            }.onSuccess { audit.checkPass("step3UpdateAdminFile") }
                .onFailure { e ->
                    audit.threw("step3UpdateAdminFile", e)
                    audit.finish("ABORTED at STEP 3 — participants removed but admin file not updated")
                    throw e
                }
        } else {
            audit.info("STEP 3 skipped (caller is not an admin)")
        }

        // 4. Mark as left locally — preserves history and blocks sending.
        // Depend on conversationId so the tags update is only sent to the server AFTER the
        // participant-removal file update (UpdateFileByUniqueIdRequest, uniqueId=conversationId)
        // has been processed. Without this ordering, the server could briefly see the LeftTag
        // while domain is still in participants, causing a spurious RejoinPending state.
        audit.step(4, "updateConversationTags(+LeftTag, dep=conversationId)")
        runCatching {
            updateConversationTags(conversationId, dependencyUniqueId = conversationId) {
                it + ChatProtocol.ConversationLeftTag
            }
        }.onSuccess { audit.checkPass("step4AddLeftTag") }
            .onFailure { e ->
                audit.threw("step4AddLeftTag", e)
                audit.finish("ABORTED at STEP 4 — left server-side but local LeftTag not flipped; UI will not show as Left")
                throw e
            }

        // ---- DEBUG instrumentation: POST verification ----
        val postFile = getConversationHomebaseFile(conversationId)
        val postVersionTag = postFile?.fileMetadata?.versionTag
        val hasLeftTag = postFile?.fileMetadata?.localAppData?.tags?.contains(ChatProtocol.ConversationLeftTag) == true
        audit.post("file: exists=${postFile != null} versionTag=$postVersionTag (pre=$preVersionTag) hasLeftTag=$hasLeftTag")
        audit.check("postLeftTagPresent", hasLeftTag, "LeftTag NOT in localTags — STEP 4 didn't apply locally; UI will not flip to Left state")
        val postConvo = runCatching { getConversation(conversationId) }.getOrNull()
        audit.post("convo state=${postConvo?.conversationState}")
        audit.check("postState",
            postConvo?.conversationState == ConversationState.Left || postConvo?.conversationState == ConversationState.RejoinPending,
            "UI conversationState is ${postConvo?.conversationState} (expected Left or RejoinPending) — leave didn't propagate to the mapper")
        val outboxAfter = dbm.outbox.count()
        audit.post("counts: outboxRows=$outboxAfter (delta=${outboxAfter - outboxBefore}, expected ≥+3 for full path)")
        audit.check("postOutboxDelta", outboxAfter - outboxBefore >= 3,
            "outbox grew by ${outboxAfter - outboxBefore}, expected ≥3 (status message + participant update + LeftTag update; +1 more if also updating admin file). One enqueue silently dropped.")
        audit.finish()
        // ---- end DEBUG ----

//        optimisticWriter.stampConversationExitedAt(chatDrive, conversationId)
//            ?.let {
//                outboxSync.tryEnqueue(it)
//            }

//        val postLeaveFile = getConversationHomebaseFile(conversationId)
//        Logger.d { "leaveGroup END: conversationId=$conversationId aesKey=${postLeaveFile?.keyHeader?.aesKey?.unsafeBytes?.toBase64() ?: "NO FILE"}" }
    }

    suspend fun acceptRejoin(conversationId: Uuid) {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("acceptRejoin")
        audit.start("conversationId=$conversationId")
        // ---- end DEBUG ----
        val conversation = requireConversation(conversationId)
        audit.pre("state=${conversation.conversationState}")
        if (conversation.conversationState != ConversationState.RejoinPending) {
            audit.checkFail("rejoinPendingGuard", "state is ${conversation.conversationState}, expected RejoinPending")
            audit.finish("REJECTED at guard")
            throw IllegalStateException("Conversation is not in RejoinPending state")
        }
        // Clear the left tag — mapper will produce Active state on next load
        audit.step(1, "updateConversationTags(-LeftTag)")
        runCatching {
            updateConversationTags(conversationId) { it - ChatProtocol.ConversationLeftTag }
        }.onSuccess { audit.checkPass("removeLeftTag") }
            .onFailure { e ->
                audit.threw("removeLeftTag", e)
                audit.finish("ABORTED")
                throw e
            }
        audit.finish()
    }

    suspend fun declineRejoin(conversationId: Uuid) {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("declineRejoin")
        audit.start("conversationId=$conversationId")
        // ---- end DEBUG ----
        val conversation = requireConversation(conversationId)
        val domain = credentialsManager.requireActiveDomain()
        audit.pre("state=${conversation.conversationState} participants=${conversation.participants.size}")

        if (conversation.conversationState != ConversationState.RejoinPending) {
            audit.checkFail("rejoinPendingGuard", "state is ${conversation.conversationState}, expected RejoinPending")
            audit.finish("REJECTED at guard")
            throw IllegalStateException("Conversation is not in RejoinPending state")
        }

        val remaining = conversation.participants.filterNot { it == domain }

        // 1. Tell the group this person declined — distinct from a voluntary leave
        val messageId = Uuid.random()
        audit.step(1, "sendStatusMessage(MemberDeclinedRejoin)")
        runCatching {
            chatMessageSenderService.sendStatusMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                statusMessage = StatusMessageData(
                    statusMessage = StatusMessage.ConversationMemberDeclinedRejoin,
                    subject = domain
                )
            )
        }.onSuccess { audit.checkPass("step1SendDeclineMessage") }
            .onFailure { e ->
                audit.threw("step1SendDeclineMessage", e)
                audit.finish("ABORTED at STEP 1")
                throw e
            }

        // 2. Remove self from participants — chained after status message
        audit.step(2, "updateConversationInternal(participants=${remaining.size}, dep=messageId)")
        runCatching {
            updateConversationInternal(
                conversationId = conversationId,
                title = conversation.name,
                participants = remaining,
                dependencyUniqueId = messageId
            )
        }.onSuccess { audit.checkPass("step2RemoveSelf") }
            .onFailure { e ->
                audit.threw("step2RemoveSelf", e)
                audit.finish("ABORTED at STEP 2")
                throw e
            }

        // 3. Keep the left tag locally — same ordering dependency as leaveGroup
        audit.step(3, "updateConversationTags(+LeftTag)")
        runCatching {
            updateConversationTags(conversationId, dependencyUniqueId = conversationId) {
                it + ChatProtocol.ConversationLeftTag
            }
        }.onSuccess { audit.checkPass("step3AddLeftTag") }
            .onFailure { e ->
                audit.threw("step3AddLeftTag", e)
                audit.finish("ABORTED at STEP 3")
                throw e
            }

        audit.step(4, "stampConversationExitedAt + tryEnqueue")
        optimisticWriter.stampConversationExitedAt(chatDrive, conversationId)
            ?.let { outboxSync.tryEnqueue(it) }
        audit.finish()
    }

    override suspend fun updateConversationInternal(
        conversationId: Uuid,
        title: String?,
        participants: List<OdinId>,
        payloadBundle: PayloadBundle?,
        dependencyUniqueId: Uuid?,
        archivalStatus: ArchivalStatus?,
        distribute: Boolean,
        additionalDistributionRecipients: List<OdinId>,
        isGroup: Boolean?,
        applyOptimisticContentLocally: Boolean,
        distributeOnlyTo: List<OdinId>?,
        preserveExistingPayloads: Boolean,
    ) {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("updateConversationInternal")
        audit.start("conversationId=$conversationId title='$title' participants=${participants.size} archivalStatus=$archivalStatus distribute=$distribute dep=$dependencyUniqueId addlRecipients=${additionalDistributionRecipients.size} isGroup=$isGroup hasBundle=${payloadBundle != null}")
        // PARTICIPANT-LIST TRACE — every update path that rewrites the file goes through here.
        // Log the participants list being WRITTEN so we can diff it against the prior file
        // state and detect overwrites (e.g. revive shrinking a group, recovery dropping members).
        Logger.i(tag = "ParticipantsAudit") {
            "updateConversationInternal WRITE for $conversationId: " +
                "newParticipants.size=${participants.size} domains=[${participants.joinToString(",") { it.domainName }}]"
        }
        val outboxBefore = dbm.outbox.count()
        // ---- end DEBUG ----
        val credentials = credentialsManager.requireActiveCredentials()
        val domain = credentials.domain

        val conversationFile = getConversationHomebaseFile(conversationId)
        // ---- DEBUG instrumentation ----
        audit.pre("file: exists=${conversationFile != null} fileId=${conversationFile?.fileId} versionTag=${conversationFile?.fileMetadata?.versionTag} preArchivalStatus=${conversationFile?.fileMetadata?.appData?.archivalStatus}")
        if (conversationFile == null) {
            audit.checkFail("preFileExists", "no conversation file found locally — cannot update")
            audit.finish("ABORTED — no file")
        }
        // PARTICIPANT-LIST TRACE — read the file's PRIOR participants so a diff is visible
        // in the log when this method overwrites them. Helps catch revive/recovery shrinkage.
        runCatching {
            val priorContent = conversationFile?.fileMetadata?.appData?.content?.let {
                OdinSystemSerializer.deserialize<ConversationAppDataJson>(it)
            }
            val priorRecipients = priorContent?.recipients?.filterNotNull() ?: emptyList()
            val droppedFromPrior = priorRecipients - participants.toSet()
            val addedVsPrior = participants - priorRecipients.toSet()
            Logger.i(tag = "ParticipantsAudit") {
                "updateConversationInternal DIFF for $conversationId: " +
                    "prior.size=${priorRecipients.size} priorDomains=[${priorRecipients.joinToString(",") { it.domainName }}] " +
                    "new.size=${participants.size} " +
                    "dropped=[${droppedFromPrior.joinToString(",") { it.domainName }}] " +
                    "added=[${addedVsPrior.joinToString(",") { it.domainName }}]"
            }
            if (droppedFromPrior.isNotEmpty()) {
                Logger.w(tag = "ParticipantsAudit") {
                    "updateConversationInternal SHRINKING participants for $conversationId: " +
                        "removing ${droppedFromPrior.map { it.domainName }} from the conversation file. " +
                        "If this is unexpected (caller is not updateGroupMembers/leaveGroup), it's the bug."
                }
            }
        }.onFailure { e ->
            Logger.w(tag = "ParticipantsAudit") { "updateConversationInternal DIFF read failed for $conversationId: ${e.message}" }
        }
        // ---- end DEBUG ----
        if (conversationFile == null) error("No conversation found")
        val preVersionTag = conversationFile.fileMetadata.versionTag

        Logger.d { "updateConversationInternal: conversationId=$conversationId isEncrypted=${conversationFile.fileMetadata.isEncrypted} aesKey=${conversationFile.keyHeader.aesKey.unsafeBytes.toBase64()} ivLen=${conversationFile.keyHeader.iv.size} keyLen=${conversationFile.keyHeader.aesKey.unsafeBytes.size}" }

        val keyHeader = KeyHeader(
            iv = ByteArrayUtil.getRndByteArray(16),
            aesKey = conversationFile.keyHeader.aesKey
        )

        val content =
            ConversationAppDataJson(
                title = title ?: "",
                recipients = participants,
                version = 1 // logical version; server enforces via versionTag
            )

        var manifest: UpdateManifest
        var previewThumb: EmbeddedThumb?
        var payloads: List<PayloadFile>?
        var thumbs: List<ThumbnailFile>?

        if (payloadBundle == null) {
            // No new bundle: the existing payloads stay on OUR drive (server preserves
            // them since we don't list them under DeletePayload). For peer transit
            // though, the manifest dictates what gets shipped — an empty manifest
            // means peers receive the file header but NOT the existing payload
            // bytes (notably the `convo_img` group image). The heal path therefore
            // opts into [preserveExistingPayloads], which downloads each existing
            // payload's encrypted bytes from our own drive, writes them to temp
            // files, and re-attaches them as pre-encrypted [PayloadFile] entries
            // keyed by the original IV so peers can decrypt with the existing
            // file key. Other callers (rename group, change archival status, etc.)
            // pass the flag as false — those updates intentionally don't ship
            // payloads.
            val (reusedPayloads, reusedThumbs) =
                if (preserveExistingPayloads) reuseExistingPayloadsForResend(conversationFile)
                else emptyList<PayloadFile>() to emptyList<ThumbnailFile>()
            manifest = UpdateManifest.build(
                payloads = reusedPayloads.takeIf { it.isNotEmpty() },
                toDeletePayloads = null,
                thumbnails = reusedThumbs.takeIf { it.isNotEmpty() },
                generatePayloadIv = false
            )

            payloads = reusedPayloads
            thumbs = reusedThumbs
            previewThumb = conversationFile.fileMetadata.appData.previewThumbnail

        } else {

            val encryptedBundle =
                payloadBundleEncryptionService.encryptBundle(
                    conversationId,
                    payloadBundle,
                    keyHeader.aesKey,
                    scope
                )

            payloads = encryptedBundle.payloads
            thumbs = encryptedBundle.thumbnails

            manifest =
                UpdateManifest.build(
                    payloads = payloads,
                    toDeletePayloads = null,
                    thumbnails = encryptedBundle.thumbnails,
                    generatePayloadIv = false
                )

            previewThumb = encryptedBundle.previewThumbs.minByOrNull {
                it.pixelWidth
            }
        }

        val existingAppData = conversationFile.fileMetadata.appData
        val mergedTags = if (isGroup == true) {
            val existing = existingAppData.tags.orEmpty()
            if (existing.contains(ChatProtocol.ConversationGroupTag)) existing
            else existing + ChatProtocol.ConversationGroupTag
        } else {
            existingAppData.tags
        }
        val metadata =
            UploadFileMetadata(
                allowDistribution = distribute, // conversationFile.serverMetadata.allowDistribution,
                isEncrypted = true, // we always encrypt conversation files
                accessControlList = conversationFile.serverMetadata.accessControlList,
                referencedFile = conversationFile.fileMetadata.referencedFile,
                versionTag = conversationFile.fileMetadata.versionTag,
                appData =
                    UploadAppFileMetaData(
                        uniqueId = conversationId,
                        tags = mergedTags,
                        fileType = existingAppData.fileType,
                        dataType = existingAppData.dataType,
                        groupId = existingAppData.groupId,
                        userDate = existingAppData.userDate,
                        content = OdinSystemSerializer.serialize(content),
                        previewThumbnail = previewThumb,
                        archivalStatus = archivalStatus ?: existingAppData.archivalStatus
                    )
            )

        val instructions =
            FileUpdateInstructionSet(
                transferIv = ByteArrayUtil.getRndByteArray(16),
                locale = UpdateLocale.Local,
                recipients = if (distribute) {
                    val baseSet = distributeOnlyTo ?: participants
                    (baseSet + additionalDistributionRecipients).filterNot { it == domain }
                        .distinct()
                } else emptyList(),
                manifest = manifest
            )

        Logger.d { "updateConversationInternal PRE-REQUEST: conversationId=$conversationId aesKey=${keyHeader.aesKey.unsafeBytes.toBase64()} versionTag=${conversationFile.fileMetadata.versionTag}" }

        // Full-shape log of what we're about to enqueue. Lets us read the
        // log and see exactly which recipients are on this push, whether the
        // payloads (and their thumbnails) are being shipped, and what
        // versionTag the server-side per-peer apply will check against. With
        // preserveExistingPayloads=true (heal) the existing payloads + thumbs
        // are re-attached into the manifest below; payloadsCount/thumbsCount
        // should be non-zero. Tagged so it stands out in a sea of debug noise.
        Logger.i(tag = "HealAudit") {
            val existingPayloadKeys = conversationFile.fileMetadata.payloads?.joinToString(",") { it.key } ?: "<none>"
            val manifestKeys = manifest.payloadDescriptors?.joinToString(",") { "${it.payloadKey}:${it.operationType}" } ?: "<empty>"
            "updateConversationInternal ENQUEUE conversationId=$conversationId " +
                "versionTagIn=${conversationFile.fileMetadata.versionTag} " +
                "distribute=$distribute distributeOnlyTo=${distributeOnlyTo?.map { it.domainName }} " +
                "preserveExistingPayloads=$preserveExistingPayloads " +
                "instructions.recipients=${instructions.recipients.map { it.domainName }} " +
                "manifest.payloadDescriptors=[$manifestKeys] " +
                "request.payloadsCount=${payloads.size} request.thumbsCount=${thumbs.size} " +
                "existingFilePayloads=[$existingPayloadKeys]"
        }

        val request =
            UpdateFileByUniqueIdRequest(
                driveId = chatDrive,
                uniqueId = conversationId,
                keyHeader = keyHeader,
                instructions = instructions,
                metadata = metadata.encryptContent(keyHeader),
                payloads = payloads,
                thumbnails = thumbs
            )

        Logger.d { "updateConversationInternal POST-ENCRYPT: conversationId=$conversationId aesKey=${keyHeader.aesKey.unsafeBytes.toBase64()} requestKeyHeader=${request.keyHeader?.aesKey?.unsafeBytes?.toBase64()}" }

        // Optimistically apply the participant/content change to the local DB immediately.
        // This ensures that any code running after this call (e.g. updateConversationTags)
        // sees the updated participant list when it reads the file, preventing a false
        // RejoinPending detection caused by the outbox/localTags race.
        //
        // Off by default — historically this block was commented out wholesale, which is
        // safe for paths whose change is purely server-side (delete archivalStatus, group
        // member adds where the new list shows up via sync, etc.). leaveGroup step 2 opts
        // IN because subsequent code (step 4) flips the LeftTag locally and the mapper
        // must see the participant change at the same moment, otherwise it renders the
        // conversation as RejoinPending.
        if (applyOptimisticContentLocally) {
            optimisticWriter.writeUpdate(
                driveId = chatDrive,
                keyHeader = keyHeader,
                unecryptedMetadata = metadata
            )
        }

        // ---- DEBUG instrumentation ----
        audit.step(1, "outboxSync.replaceEnqueue(UpdateFileByUniqueIdRequest, dep=$dependencyUniqueId)")
        // ---- end DEBUG ----
        val enqueued = outboxSync.replaceEnqueue(request, dependencyUniqueId = dependencyUniqueId)
        // ---- DEBUG instrumentation ----
        audit.info("STEP 1 returned $enqueued")
        audit.check("replaceEnqueue", enqueued.enqueued, "outboxSync.replaceEnqueue → $enqueued — update will not happen")
        if (!enqueued.enqueued) {
            audit.finish("ABORTED — replaceEnqueue $enqueued")
        }
        // ---- end DEBUG ----
        if (!enqueued.enqueued) {
            error("Failed to update conversation (outbox: $enqueued)")
        }
        // ---- DEBUG instrumentation: POST verification ----
        val postFile = getConversationHomebaseFile(conversationId)
        val postVersionTag = postFile?.fileMetadata?.versionTag
        val postArchivalStatus = postFile?.fileMetadata?.appData?.archivalStatus
        audit.post("file: versionTag=$postVersionTag (pre=$preVersionTag) archivalStatus=$postArchivalStatus applyOptimisticContentLocally=$applyOptimisticContentLocally")
        // Only audit the local-archivalStatus apply when the caller opted into an
        // optimistic local write. For opt-out callers (delete, some group-member adds)
        // the local file is intentionally not rewritten — the change is queued in the
        // outbox and shows up after the server round-trips. A divergence here is the
        // documented contract (see comment block at the optimisticWriter.writeUpdate
        // call above), not a bug.
        if (archivalStatus != null && applyOptimisticContentLocally) {
            audit.check("postArchivalStatusApplied", postArchivalStatus == archivalStatus,
                "file.archivalStatus is $postArchivalStatus, expected $archivalStatus — applyOptimisticContentLocally=true but the optimistic write did not apply locally")
        }
        // PARTICIPANT-LIST TRACE — read the file's CURRENT participants after the update.
        // Only flag a mismatch as a warning when the caller opted in to apply locally;
        // otherwise the local file is expected to lag until the outbox round-trips.
        runCatching {
            val postContent = postFile?.fileMetadata?.appData?.content?.let {
                OdinSystemSerializer.deserialize<ConversationAppDataJson>(it)
            }
            val postRecipients = postContent?.recipients?.filterNotNull() ?: emptyList()
            Logger.i(tag = "ParticipantsAudit") {
                "updateConversationInternal POST_READBACK for $conversationId: " +
                    "file.recipients.size=${postRecipients.size} " +
                    "domains=[${postRecipients.joinToString(",") { it.domainName }}] " +
                    "(intended new size=${participants.size}, applyOptimisticContentLocally=$applyOptimisticContentLocally)"
            }
            if (applyOptimisticContentLocally && postRecipients.toSet() != participants.toSet()) {
                Logger.w(tag = "ParticipantsAudit") {
                    "updateConversationInternal POST_READBACK MISMATCH: file shows ${postRecipients.size} recipients, " +
                        "intended ${participants.size}. applyOptimisticContentLocally=true but the local optimistic " +
                        "write did not apply — bug in optimisticWriter.writeUpdate path."
                }
            }
        }.onFailure { e ->
            Logger.w(tag = "ParticipantsAudit") { "updateConversationInternal POST_READBACK failed: ${e.message}" }
        }
        val outboxAfter = dbm.outbox.count()
        audit.post("counts: outboxRows=$outboxAfter (delta=${outboxAfter - outboxBefore}, expected ≥+1; replaceEnqueue may dedupe so 0 means earlier same-uniqueId was replaced)")
        audit.finish()
        // ---- end DEBUG ----
    }

    suspend fun introduceEveryone(conversationId: Uuid, message: String?) {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("introduceEveryone")
        audit.start("conversationId=$conversationId messageLen=${message?.length ?: 0}")
        // ---- end DEBUG ----
        val conversation = requireConversation(conversationId)
        audit.pre("participants=${conversation.participants.size}")
        trySendIntroductions(conversation.participants, message ?: "")
        audit.finish()
    }

    /**
     * Same as [introduceEveryone] but sends to an explicit subset of recipients.
     * Used by the preflight UI flow when the user picks "Skip these and send to
     * the rest" — the VM passes only the [IntroductionPreflightStatus.Ready]
     * recipients here. Best-effort: errors are swallowed (see [trySendIntroductions]).
     */
    suspend fun introduceRecipients(
        conversationId: Uuid,
        recipients: List<OdinId>,
        message: String?,
    ) {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("introduceRecipients")
        audit.start("conversationId=$conversationId recipients=${recipients.size} messageLen=${message?.length ?: 0}")
        // ---- end DEBUG ----
        if (recipients.isEmpty()) {
            audit.info("no recipients — no-op")
            audit.finish("no-op (empty recipients)")
            return
        }
        trySendIntroductions(recipients, message ?: "")
        audit.finish()
    }

    /**
     * Best-effort preflight check for the introduction flow. Returns null when the
     * preflight call itself fails (network, server 500, etc.) — callers should treat
     * that as "couldn't tell, proceed as if all recipients were Ready" rather than
     * blocking the user. The server doesn't enforce ordering between preflight and
     * send, so a null result is a UX hint, not a constraint.
     */
    suspend fun previewIntroduceEveryone(
        conversationId: Uuid,
    ): id.homebase.api.client.connections.IntroductionPreflightResult? {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("previewIntroduceEveryone")
        audit.start("conversationId=$conversationId")
        // ---- end DEBUG ----
        val conversation = requireConversation(conversationId)
        val domain = credentialsManager.requireActiveDomain()
        val recipients = conversation.participants.filterNot { it == domain }.distinct()
        audit.pre("recipients=${recipients.size}")
        if (recipients.isEmpty()) {
            audit.info("no recipients — preflight skipped")
            audit.finish("no-op (empty recipients)")
            return null
        }
        return runCatching {
            introductionProvider.preflightIntroductions(
                group = IntroductionGroup(recipients = recipients, message = null)
            )
        }.onSuccess { result ->
            audit.info("preflight returned ${result.recipients.size} entries; allReady=${result.allReady}")
            audit.checkPass("preflight")
        }.onFailure { e ->
            // Best-effort: log + swallow so the caller falls back to "send anyway".
            Logger.w(throwable = e, tag = "ConversationService") {
                "previewIntroduceEveryone($conversationId) failed — caller will proceed without preflight: ${e.message}"
            }
            audit.checkWarn("preflight", "preflight call failed: ${e.message} — caller proceeds without preflight info")
        }.also { audit.finish() }.getOrNull()
    }

    private suspend fun trySendIntroductions(
        recipients: List<OdinId>,
        message: String
    ) {
        try {
            // send introductions
            introductionProvider.sendIntroductions(
                group = IntroductionGroup(
                    recipients = recipients,
                    message = message
                )
            )
        } catch (t: Throwable) {
            Logger.e("Failed sending introductions", t)
        }
    }

    private suspend fun requireCallerIsGroupAdmin(conversation: ConversationUiModel) {
        val domain = credentialsManager.requireActiveDomain()

        if (!conversation.isGroupConversation) {
            throw IllegalStateException("Must be a group conversations")
        }

        if (!conversation.admins.contains(domain)) {
            throw IllegalStateException("Only group admins can perform this action")
        }
    }

    suspend fun archiveConversation(conversationId: Uuid) {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("archiveConversation")
        audit.start("conversationId=$conversationId")
        try {
            updateConversationTags(conversationId) { it + ChatProtocol.ConversationArchivedTag }
            audit.finish()
        } catch (e: Throwable) { audit.threw("execution", e); audit.finish("threw"); throw e }
        // ---- end DEBUG ----
    }

    suspend fun unarchiveConversation(conversationId: Uuid) {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("unarchiveConversation")
        audit.start("conversationId=$conversationId")
        try {
            updateConversationTags(conversationId) { it - ChatProtocol.ConversationArchivedTag }
            audit.finish()
        } catch (e: Throwable) { audit.threw("execution", e); audit.finish("threw"); throw e }
        // ---- end DEBUG ----
    }

    suspend fun pinConversation(conversationId: Uuid) {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("pinConversation")
        audit.start("conversationId=$conversationId")
        try {
            updateConversationTags(conversationId) { it + ChatProtocol.ConversationPinnedTag }
            audit.finish()
        } catch (e: Throwable) { audit.threw("execution", e); audit.finish("threw"); throw e }
        // ---- end DEBUG ----
    }

    suspend fun unpinConversation(conversationId: Uuid) {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("unpinConversation")
        audit.start("conversationId=$conversationId")
        try {
            updateConversationTags(conversationId) { it - ChatProtocol.ConversationPinnedTag }
            audit.finish()
        } catch (e: Throwable) { audit.threw("execution", e); audit.finish("threw"); throw e }
        // ---- end DEBUG ----
    }

    // region Recovery: unified conversation recovery
    /**
     * Single entry point for recovering a conversation that is missing or soft-deleted.
     * Determines 1:1 vs group via the XOR algorithm, reads existing participants
     * from the file when available, and either revives or creates the file using
     * the ORIGINAL [conversationId] (never recomputes it).
     */
    suspend fun recoverConversation(
        conversationId: Uuid,
        originalAuthor: OdinId?,
        sender: OdinId? = null,
    ) {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("recoverConversation")
        audit.start("conversationId=$conversationId originalAuthor=${originalAuthor?.domainName} sender=${sender?.domainName}")
        // ---- end DEBUG ----
        val domain = credentialsManager.requireActiveDomain()
        val isNoteToSelf = conversationId == ChatProtocol.ConversationWithYourselfId
        audit.pre("isNoteToSelf=$isNoteToSelf")

        if (isNoteToSelf) {
            Logger.i("ConversationService: recoverConversation($conversationId) — note-to-self, delegating to ensureNoteToSelfExists()")
            audit.info("delegating to ensureNoteToSelfExists")
            ensureNoteToSelfExists()
            audit.finish("delegated to ensureNoteToSelfExists")
            return
        }

        // Prefer senderOdinId — for forwarded messages the file's sender is the
        // wire-level counterparty in *this* conversation, while originalAuthor
        // points to whoever first wrote the content (a different identity, not
        // in this 1:1). Fall back to originalAuthor only when sender is unknown.
        val xorCandidate: OdinId? = sender ?: originalAuthor
        val isOneToOne = xorCandidate != null && XorIdUtil.isOneToOneWithSender(
            self = domain,
            sender = xorCandidate,
            messageGroupId = conversationId,
        )

        Logger.i("ConversationService: recoverConversation($conversationId) author=${originalAuthor?.domainName} sender=${sender?.domainName} xorCandidate=${xorCandidate?.domainName} isOneToOne=$isOneToOne")

        // Recovery is strictly local-only: write a placeholder header to the
        // local DB and refresh the in-memory model. No outbox enqueue, no
        // server upload, no peer transit. Server distribution is the
        // exclusive responsibility of healGroupDistribution (the explicit
        // "Heal Group" button); pushing a placeholder pre-emptively to the
        // server has been observed to make the recipient's local state
        // overwrite later heals, defeating the heal flow.
        //
        // The one exception is the own-drive corrupt-header cleanup below
        // ([tryHardDeleteUnrecoverableOwnHeader]): it hard-deletes a dead file we
        // authored from our OWN drive (recipients=null ⇒ no peer fan-out), which is
        // file removal, not distribution — the same primitive GroupHealService STEP 1
        // uses. Without it, a self-only corrupt header re-syncs from our own server
        // drive every launch and loops forever.
        val existingFile = getConversationHomebaseFile(conversationId)

        // Three pieces of state we resolve from the (optional) existing file:
        //   - participants for the placeholder
        //   - whether we need to delete-then-write (broken local file present)
        //   - early return if the file is already healthy
        val brokenFileIdToDelete: Uuid?
        val participants: List<OdinId>
        // Hoisted so the corrupt-header cleanup below can see the mapped state.
        var existingState: ConversationState? = null

        if (existingFile != null) {
            existingState = try {
                mapper.mapToConversationUi(existingFile, null).conversationState
            } catch (e: Exception) {
                Logger.w(e) { "ConversationService: recoverConversation($conversationId) — existing file failed to map, will overwrite" }
                null
            }

            val needsRevive = existingState == null
                || existingState == ConversationState.Deleted
                || existingState == ConversationState.Invalid

            if (!needsRevive) {
                Logger.d("ConversationService: recoverConversation($conversationId) — file exists and is $existingState, no action needed")
                return
            }

            // Read existing participants from file if possible (preserves group membership).
            val existingContent = existingFile.fileMetadata.appData.content?.let {
                try {
                    OdinSystemSerializer.deserialize<ConversationAppDataJson>(it)
                } catch (e: Exception) { null }
            }
            participants = existingContent?.recipients
                ?.filterNotNull()?.distinct()
                ?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(xorCandidate, domain).distinct()
            brokenFileIdToDelete = existingFile.fileId
        } else {
            participants = listOfNotNull(xorCandidate, domain).distinct()
            brokenFileIdToDelete = null
        }

        // ConversationMapper's 1:1 branch dereferences `participants.first { it != domain }`
        // and throws if no such participant exists. When the only participant
        // we could derive is self (e.g. a 1:1 the user started themselves and
        // whose original counterpart we can't recover from the broken file),
        // we force the placeholder to be a group so the mapper takes the group
        // branch — yielding a healthy Active state instead of another
        // unmappable_conversation flag.
        val hasNonSelfParticipant = participants.any { it != domain }

        // Before writing yet another local placeholder, check whether this is a dead
        // own-drive corrupt header that local-only recovery can never fix (it would
        // just re-sync and loop). If so, hard-delete it server-side and bail.
        if (existingFile != null &&
            tryHardDeleteUnrecoverableOwnHeader(
                conversationId = conversationId,
                existingFile = existingFile,
                existingState = existingState,
                domain = domain,
                hasNonSelfParticipant = hasNonSelfParticipant,
                audit = audit,
            )
        ) {
            return
        }

        val forcedGroupForUnmappable1to1 = isOneToOne && !hasNonSelfParticipant
        val placeholderIsGroup = !isOneToOne || forcedGroupForUnmappable1to1
        // Give forced-group recoveries a recognisable label so the user can
        // spot them in the chat list and edit participants / heal-group as
        // needed. Real groups and clean 1:1 placeholders stay blank — group
        // titles are user-set, 1:1 titles are derived from the other party.
        val placeholderTitle = if (forcedGroupForUnmappable1to1) "1:1 repair" else ""

        Logger.i("ConversationService: recoverConversation($conversationId) — LOCAL-ONLY (isGroup=$placeholderIsGroup forcedGroupForUnmappable1to1=$forcedGroupForUnmappable1to1) participants=${participants.map { it.domainName }} replacingBrokenFileId=$brokenFileIdToDelete")

        writeOrReplaceConversationPlaceholder(
            conversationId = conversationId,
            brokenFileIdToDelete = brokenFileIdToDelete,
            participants = participants,
            isGroup = placeholderIsGroup,
            title = placeholderTitle,
            audit = audit,
        )
        audit.finish("local-only recovery complete")
    }

    /**
     * Breaks the re-sync loop for a corrupt header on our OWN drive. A header we
     * authored that is genuinely [ConversationState.Invalid], lists only ourselves,
     * and has no peer message in its group is a dead file that local-only recovery
     * can never fix — deleting just the local row lets the next sync re-download it,
     * so it loops forever (FAILED map → recover → deleteBy(local) → "1:1 repair" →
     * re-sync → repeat).
     *
     * When all gates pass it hard-deletes the file from our own server drive
     * (recipients=null ⇒ no peer fan-out, the same primitive GroupHealService STEP 1
     * uses), drops the local row, refreshes the model, and returns `true` (the caller
     * then returns — there is nothing to place). Returns `false` otherwise, leaving
     * the existing local-placeholder path untouched.
     *
     * Conservatively gated: never a file we didn't author, and never one with any
     * peer-message trace ([FileStateFilter.All] counts soft-deleted tombstones too —
     * if a peer ever wrote here we keep the placeholder rather than destroy context).
     */
    private suspend fun tryHardDeleteUnrecoverableOwnHeader(
        conversationId: Uuid,
        existingFile: HomebaseFile,
        existingState: ConversationState?,
        domain: OdinId,
        hasNonSelfParticipant: Boolean,
        audit: MethodAudit,
    ): Boolean {
        val authoredBySelf =
            (existingFile.fileMetadata.originalAuthor
                ?: existingFile.fileMetadata.senderOdinId ?: domain) == domain
        if (existingState != ConversationState.Invalid || !authoredBySelf || hasNonSelfParticipant) {
            return false
        }

        val identityId = credentialsManager.requireActiveCredentials().getIdentityId()
        val hasPeerMessage = QueryBatch(identityId).queryBatchAsync(
            dbm = dbm,
            driveId = chatDrive,
            noOfItems = 10_000,
            fileSystemType = FileSystemType.Standard.value,
            fileState = FileStateFilter.All, // conservative: tombstones count as a peer trace
            filetypesAnyOf = listOf(ChatProtocol.MessageFileType),
            groupIdAnyOf = listOf(conversationId),
        ).records.any { m ->
            val author = m.fileMetadata.originalAuthor ?: m.fileMetadata.senderOdinId
            author != null && author != domain
        }
        if (hasPeerMessage) {
            audit.info("corrupt own header has a peer message — leaving for local-placeholder path")
            return false // recoverable ⇒ keep the placeholder behaviour
        }

        Logger.w("ConversationService: recoverConversation($conversationId) — own-drive corrupt header with no recoverable counterparty; hard-deleting fileId=${existingFile.fileId} from own drive")
        audit.step(1, "outboxSync.tryEnqueue(DeleteLocalFilesByFileIdRequest hardDelete=true recipients=null fileId=${existingFile.fileId})")
        runCatching {
            outboxSync.tryEnqueue(
                DeleteLocalFilesByFileIdRequest(
                    driveId = chatDrive,
                    fileIds = listOf(existingFile.fileId),
                    recipients = null,
                    hardDelete = true,
                )
            )
        }.onSuccess { enqueued -> audit.info("hardDelete enqueued=$enqueued") }
            .onFailure { e -> audit.threw("hardDeleteEnqueue", e) }

        audit.step(2, "deleteBy(local corrupt row fileId=${existingFile.fileId})")
        runCatching {
            dbm.driveMainIndex.deleteBy(
                identityId = identityId,
                driveId = chatDrive,
                fileId = existingFile.fileId,
            )
        }.onFailure { e -> audit.threw("deleteLocalRow", e) }

        conversationStream.removeConversation(conversationId)
        audit.finish("own-drive corrupt header hard-deleted")
        return true
    }

    /**
     * Shared placeholder-write sequence used by both [recoverConversation] and the
     * receive-side heal handler. Deletes any existing broken row first
     * (sidestepping the upsert's modified-timestamp guard), writes a local-only
     * placeholder via [OptimisticWriter.writeLocalOnlyConversationPlaceholder]
     * (which writes versionTag=null so a later peer push supersedes it cleanly),
     * and refreshes the in-memory conversation entry.
     */
    override suspend fun writeOrReplaceConversationPlaceholder(
        conversationId: Uuid,
        brokenFileIdToDelete: Uuid?,
        participants: List<OdinId>,
        isGroup: Boolean,
        title: String,
        audit: MethodAudit,
    ) {
        if (brokenFileIdToDelete != null) {
            audit.step(1, "deleteBy(broken row fileId=$brokenFileIdToDelete)")
            runCatching {
                dbm.driveMainIndex.deleteBy(
                    identityId = credentialsManager.requireActiveCredentials().getIdentityId(),
                    driveId = chatDrive,
                    fileId = brokenFileIdToDelete,
                )
            }.onSuccess { audit.checkPass("deleteBrokenRow") }
                .onFailure {
                    audit.threw("deleteBrokenRow", it)
                    Logger.w(it) {
                        "writeOrReplaceConversationPlaceholder($conversationId) — failed to delete broken row fileId=$brokenFileIdToDelete; placeholder write may be a no-op"
                    }
                }
        }

        audit.step(2, "writeLocalOnlyConversationPlaceholder(isGroup=$isGroup, title=\"$title\")")
        runCatching {
            optimisticWriter.writeLocalOnlyConversationPlaceholder(
                driveId = chatDrive,
                conversationId = conversationId,
                participants = participants,
                isGroup = isGroup,
                title = title,
            )
        }.onSuccess { audit.checkPass("placeholderWritten") }
            .onFailure { e -> audit.threw("placeholderWritten", e); audit.finish("threw"); throw e }

        audit.step(3, "conversationStream.loadConversation()")
        conversationStream.loadConversation(conversationId)
    }

    /**
     * Counterpart to [writeOrReplaceConversationPlaceholder] for the admin
     * file. Used by [handleIncomingHealRequest] when the incoming heal
     * payload carries `canonicalAdmins` and the local admin file is missing
     * or broken.
     *
     * Deletes the existing broken admin row first (if any) to sidestep
     * DriveMainIndex's modified-timestamp upsert guard, then writes a
     * local-only admin placeholder via
     * [OptimisticWriter.writeLocalOnlyAdminPlaceholder] (versionTag=null so
     * a later genuine peer push from the canonical author replaces it).
     */
    override suspend fun writeOrReplaceAdminPlaceholder(
        conversationId: Uuid,
        brokenFileIdToDelete: Uuid?,
        admins: List<OdinId>,
        audit: MethodAudit,
    ) {
        if (brokenFileIdToDelete != null) {
            audit.step(1, "deleteBy(broken admin row fileId=$brokenFileIdToDelete)")
            runCatching {
                dbm.driveMainIndex.deleteBy(
                    identityId = credentialsManager.requireActiveCredentials().getIdentityId(),
                    driveId = chatDrive,
                    fileId = brokenFileIdToDelete,
                )
            }.onSuccess { audit.checkPass("deleteBrokenAdminRow") }
                .onFailure {
                    audit.threw("deleteBrokenAdminRow", it)
                    Logger.w(it) {
                        "writeOrReplaceAdminPlaceholder($conversationId) — failed to delete broken admin row fileId=$brokenFileIdToDelete; placeholder write may be a no-op"
                    }
                }
        } else {
            audit.info("writeOrReplaceAdminPlaceholder: no broken local admin row to delete (admin file was missing)")
        }

        audit.step(2, "writeLocalOnlyAdminPlaceholder(admins=${admins.size})")
        runCatching {
            optimisticWriter.writeLocalOnlyAdminPlaceholder(
                driveId = chatDrive,
                conversationId = conversationId,
                admins = admins,
            )
        }.onSuccess {
            audit.checkPass("adminPlaceholderWritten")
            Logger.i {
                "writeOrReplaceAdminPlaceholder: wrote local admin placeholder conversationId=$conversationId admins(${admins.size})=${admins.map { it.domainName }}"
            }
        }.onFailure { e ->
            audit.threw("adminPlaceholderWritten", e)
            Logger.w(e) {
                "writeOrReplaceAdminPlaceholder: FAILED to write local admin placeholder for $conversationId"
            }
            throw e
        }
    }
    // endregion

    suspend fun deleteConversation(conversationId: Uuid) {
        // ---- DEBUG instrumentation (see MethodAudit + CONVERSATION_SERVICE_DEBUG.md) ----
        val audit = MethodAudit("deleteConversation")
        audit.start("conversationId=$conversationId")
        val conversation = requireConversation(conversationId)
        audit.pre("convo: state=${conversation.conversationState} isGroup=${conversation.isGroupConversation} participants=${conversation.participants.size} admins=${conversation.admins.size} legacyGroup=${conversation.isLegacyGroup}")
        val preFile = getConversationHomebaseFile(conversationId)
        val preVersionTag = preFile?.fileMetadata?.versionTag
        audit.pre("file: exists=${preFile != null} fileId=${preFile?.fileId} archivalStatus=${preFile?.fileMetadata?.appData?.archivalStatus} versionTag=$preVersionTag tags=${preFile?.fileMetadata?.appData?.tags?.size ?: 0} localTags=${preFile?.fileMetadata?.localAppData?.tags?.size ?: 0}")
        if (preFile == null) audit.checkWarn("preFileExists", "conversation file does NOT exist locally — updateConversationInternal will have nothing to update") else audit.checkPass("preFileExists")
        val outboxBefore = dbm.outbox.count()
        val identityId = credentialsManager.requireActiveCredentials().getIdentityId()
        // audit: include soft-deleted rows in the count
        val messagesBefore = runCatching { QueryBatch(identityId).queryBatchAsync(dbm = dbm, driveId = chatDrive, noOfItems = 10_000, fileState = FileStateFilter.All, groupIdAnyOf = listOf(conversationId)).records.size }.getOrElse { -1 }
        audit.pre("counts: outboxRows=$outboxBefore messagesWithGroupId=$messagesBefore")
        // ---- end DEBUG ----

        if (conversation.isGroupConversation && !(
                    conversation.conversationState == ConversationState.Left ||
                            conversation.conversationState == ConversationState.RejoinPending ||
                            conversation.conversationState == ConversationState.Removed ||
                            conversation.conversationState == ConversationState.Archived
                    )
        ) {
            audit.checkFail("guard", "group state=${conversation.conversationState} is NOT one of [Left, RejoinPending, Removed, Archived]; UI should not have offered Delete")
            audit.finish("REJECTED at guard")
            throw IllegalStateException("You must leave the group before deleting it")
        }
        audit.checkPass("guard")

        val deleteFile = preFile
        // A null versionTag is the marker that this is a local-only placeholder
        // (written by OptimisticWriter.writeLocalOnlyConversationPlaceholder) —
        // the server has no such conversation file. Routing it through
        // updateConversationInternal would enqueue an UpdateFile request that
        // the server rejects with 400 ("Could not find file" / "Missing version
        // tag"), and the outbox then pointlessly retries for hours. Skip the
        // server roundtrip and delete the local row directly.
        val isLocalOnlyPlaceholder = deleteFile != null && deleteFile.fileMetadata.versionTag == null
        Logger.d { "deleteConversation: conversationId=$conversationId localOnly=$isLocalOnlyPlaceholder isEncrypted=${deleteFile?.fileMetadata?.isEncrypted} aesKey=${deleteFile?.keyHeader?.aesKey?.unsafeBytes?.toBase64() ?: "NO FILE"}" }

        if (isLocalOnlyPlaceholder) {
            audit.step(1, "local-only placeholder — bypass server, delete local row + remove in-memory entry")
            // Messages under this groupId may exist server-side (they synced
            // here from somewhere). Still enqueue the messages-delete so peers'
            // copies and our own server-side rows clean up. The outbox handles
            // those independently of the (skipped) conversation-file delete.
            outboxSync.tryEnqueue(
                DeleteFilesByGroupIdOutboxRequest(
                    driveId = chatDrive,
                    groupIds = listOf(conversationId)
                )
            )
            // Remove the local placeholder row + drop it from the in-memory
            // chat list so the UI updates immediately.
            runCatching {
                dbm.driveMainIndex.deleteBy(
                    identityId = identityId,
                    driveId = chatDrive,
                    fileId = deleteFile.fileId,
                )
            }.onSuccess { audit.checkPass("localPlaceholderDelete") }
                .onFailure {
                    audit.threw("localPlaceholderDelete", it)
                    Logger.w(it) {
                        "deleteConversation($conversationId) — local placeholder row delete failed"
                    }
                }
            conversationStream.removeConversation(conversationId)
            audit.finish("local-only placeholder removed (no server roundtrip)")
            return
        }

        // ---- DEBUG instrumentation ----
        audit.step(1, "outboxSync.tryEnqueue(DeleteFilesByGroupIdOutboxRequest)")
        val step1 = runCatching {
            outboxSync.tryEnqueue(
                DeleteFilesByGroupIdOutboxRequest(driveId = chatDrive, groupIds = listOf(conversationId))
            )
        }
        step1.onSuccess { result ->
            audit.info("STEP 1 returned $result")
            if (!result.enqueued) audit.checkFail("step1Enqueue", "tryEnqueue → $result — server-side delete will NOT happen this attempt") else audit.checkPass("step1Enqueue")
        }.onFailure { e ->
            audit.threw("step1Enqueue", e)
            audit.finish("ABORTED at STEP 1")
            throw e
        }
        audit.step(2, "updateConversationInternal(archivalStatus=Removed, distribute=false)")
        // ---- end DEBUG ----
        val step2 = runCatching {
            updateConversationInternal(
                conversationId = conversationId,
                title = conversation.name,
                participants = conversation.participants,
                archivalStatus = ArchivalStatus.Removed,
                distribute = false
            )
        }
        step2.onSuccess { audit.checkPass("step2Update") }
            .onFailure { e ->
                audit.threw("step2Update", e)
                audit.finish("ABORTED at STEP 2 — STEP 1 enqueue happened but local file NOT marked Removed")
                throw e
            }

        // ---- DEBUG instrumentation: POST verification ----
        //
        // The previous version of this block raised FAIL on several checks that turned out
        // to be expected behavior, not bugs. Diagnosis on a working repro:
        //
        //  - file.archivalStatus stays None and versionTag is unchanged after STEP 2.
        //    That's because deleteConversation calls updateConversationInternal WITHOUT
        //    applyOptimisticContentLocally=true, by design (delete is a "purely server-side"
        //    change per the comment at the optimisticWriter.writeUpdate gate). The local file
        //    is rewritten only when the outbox round-trips back.
        //
        //  - The UI hides the conversation immediately via a parallel path:
        //    ConversationLifecycleHandler calls conversationStream.onConversationDeleted(id)
        //    right after deleteConversation returns. That adds the id to ConversationStream's
        //    session-scoped `deletedIds` set and removes the row from _conversations.value.
        //    So the user-visible list is correct, even though getConversation(id) here still
        //    returns ConversationState.Active (it reads the DB file directly, bypassing the
        //    deletedIds filter that the UI subscribes to).
        //
        //  - The "outbox grew by 1, expected ≥2" check assumes STEP 1's DeleteFilesByGroupId
        //    and STEP 2's UpdateFileByUniqueId persist as separate rows. In 1:1 conversations
        //    (where conversationId == groupId) the second outbox row's replaceEnqueue removes
        //    the first, leaving delta=1. This is the documented dedup contract.
        //
        // Below we record the post-state for forensic traces but do not flag any of these
        // documented-behaviors as BUG?.
        val postFile = getConversationHomebaseFile(conversationId)
        val postArchivalStatus = postFile?.fileMetadata?.appData?.archivalStatus
        val postVersionTag = postFile?.fileMetadata?.versionTag
        audit.post("file: exists=${postFile != null} archivalStatus=$postArchivalStatus versionTag=$postVersionTag (pre=$preVersionTag)")
        audit.info("local file archivalStatus/versionTag updates after outbox round-trip; UI hiding is done by ConversationStream.onConversationDeleted in the caller")
        val outboxAfter = dbm.outbox.count()
        val outboxDelta = outboxAfter - outboxBefore
        // audit: include soft-deleted rows in the count
        val messagesAfter = runCatching { QueryBatch(identityId).queryBatchAsync(dbm = dbm, driveId = chatDrive, noOfItems = 10_000, fileState = FileStateFilter.All, groupIdAnyOf = listOf(conversationId)).records.size }.getOrElse { -1 }
        audit.post("counts: outboxRows=$outboxAfter (delta=$outboxDelta) messagesWithGroupId=$messagesAfter (delta=${messagesAfter - messagesBefore}, expected unchanged until outbox processes)")
        audit.check("postOutboxDelta", outboxDelta >= 1,
            "outbox did not grow at all — both STEP 1 (DeleteFilesByGroupId) and STEP 2 (UpdateFile) silently failed to enqueue")
        audit.finish()
        // ---- end DEBUG ----
    }

    suspend fun clearConversation(conversationId: Uuid) {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("clearConversation")
        audit.start("conversationId=$conversationId")
        val outboxBefore = dbm.outbox.count()
        // ---- end DEBUG ----
        val enqueued = outboxSync.tryEnqueue(
            DeleteFilesByGroupIdOutboxRequest(
                driveId = chatDrive,
                groupIds = listOf(conversationId)
            )
        )
        // ---- DEBUG instrumentation ----
        audit.info("tryEnqueue returned $enqueued")
        audit.check("enqueue", enqueued.enqueued, "tryEnqueue → $enqueued — clear NOT performed")
        val outboxAfter = dbm.outbox.count()
        audit.post("counts: outboxRows=$outboxAfter (delta=${outboxAfter - outboxBefore}, expected ≥+1)")
        audit.finish()
        // ---- end DEBUG ----
    }

    private suspend fun updateConversationTags(
        conversationId: Uuid,
        dependencyUniqueId: Uuid? = null,
        transform: (Set<Uuid>) -> Set<Uuid>
    ) {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("updateConversationTags")
        audit.start("conversationId=$conversationId dep=$dependencyUniqueId")
        // ---- end DEBUG ----
        val file = getConversationHomebaseFile(conversationId)
        // ---- DEBUG instrumentation ----
        audit.pre("file: exists=${file != null} fileId=${file?.fileId} versionTag=${file?.fileMetadata?.versionTag} localTags=${file?.fileMetadata?.localAppData?.tags?.size ?: 0}")
        if (file == null) {
            audit.checkFail("preFileExists", "no conversation file found locally")
            audit.finish("ABORTED — no file")
        }
        // ---- end DEBUG ----
        if (file == null) error("Conversation not found: $conversationId")

        val currentTags = file.fileMetadata.localAppData?.tags?.toSet() ?: emptySet()
        val newTags = transform(currentTags)
        audit.info("tags: ${currentTags.size} → ${newTags.size} (added=${(newTags - currentTags).size} removed=${(currentTags - newTags).size})")

        audit.step(1, "optimisticWriter.updateLocalTags")
        runCatching {
            optimisticWriter.updateLocalTags(
                driveId = chatDrive,
                uniqueId = conversationId,
                newTags = newTags.toList()
            )
        }.onSuccess { audit.checkPass("optimisticUpdateLocalTags") }
            .onFailure { e -> audit.threw("optimisticUpdateLocalTags", e); audit.finish("threw"); throw e }

        // Use a random uniqueId so this request does not conflict with a concurrent
        // UpdateFileByUniqueIdRequest that also uses uniqueId=conversationId.
        // The UNIQUE(driveId, uniqueId) outbox constraint would otherwise silently drop this
        // enqueue while the file update is still pending, causing the LeftTag to never reach
        // the server.  The dependencyUniqueId still ensures correct ordering when provided.
        audit.step(2, "outboxSync.tryEnqueue(UpdateLocalMetadataTagsOutboxRequest, random uniqueId, dep=$dependencyUniqueId)")
        val enqueued = outboxSync.tryEnqueue(
            request = UpdateLocalMetadataTagsOutboxRequest(
                file = FileIdFileIdentifier(
                    fileId = file.fileId.toString(),
                    targetDrive = chatTargetDrive
                ),
                versionTag = file.fileMetadata.localAppData?.versionTag?.toString(),
                tags = newTags.map { it.toString() }
            ),
            driveId = chatDrive,
            uniqueId = Uuid.random(),
            dependencyUniqueId = dependencyUniqueId
        )
        audit.info("STEP 2 returned $enqueued")
        audit.check("step2Enqueue", enqueued.enqueued, "tryEnqueue → $enqueued — server-side tags update will NOT happen")

        // POST verify the optimistic write applied
        val postFile = getConversationHomebaseFile(conversationId)
        val postLocalTags = postFile?.fileMetadata?.localAppData?.tags?.toSet() ?: emptySet()
        audit.post("file localTags after: ${postLocalTags.size} (expected ${newTags.size})")
        audit.check("postLocalTags", postLocalTags == newTags,
            "localTags after optimisticWriter.updateLocalTags differ from expected; got=${postLocalTags - newTags}+/${newTags - postLocalTags}-")
        audit.finish()
    }

    /**
     * Re-attaches the file's existing payloads (e.g. the `convo_img` group image) to
     * an update request that would otherwise ship with an empty manifest. Used by the
     * heal redistribute path so peers receiving the heal'd file also receive the
     * payload bytes instead of an imageless header.
     *
     * For each payload we pull the still-encrypted bytes off our own drive, drop
     * them into a temp file, and construct a [PayloadFile] keyed by the original IV
     * with `isPreEncrypted=true`. The file's [KeyHeader.aesKey] is preserved across
     * heal (see [updateConversationInternal]: `aesKey = conversationFile.keyHeader.aesKey`),
     * so the existing IV + bytes decrypt cleanly on the peer side.
     *
     * Per-payload thumbnails aren't carried in this pass — the avatar UI uses the
     * `appData.previewThumbnail` (an embedded thumb at the appData level, preserved
     * separately in [updateConversationInternal]) so the chat list still shows the
     * group image. If a future heal target ships rich payloads with per-payload
     * thumbnails, we'll need to download + re-attach those too.
     *
     * Temp files: written with [FileOperationsProvider.writeBytesToTempFile] and
     * left for the OS / app cleanup pass to remove once the outbox has drained.
     * This mirrors how `MessageAttachmentBuilder` handles attachments today.
     */
    private suspend fun reuseExistingPayloadsForResend(
        file: HomebaseFile,
    ): Pair<List<PayloadFile>, List<ThumbnailFile>> {
        val dfp = driveFileProvider
        val fop = fileOperationsProvider
        if (dfp == null || fop == null) {
            Logger.d(tag = "HealAudit") {
                "reuseExistingPayloadsForResend: skipping payload re-attach (driveFileProvider=${dfp != null}, fileOperationsProvider=${fop != null}) — likely test fixture"
            }
            return emptyList<PayloadFile>() to emptyList()
        }
        val recovered = resendablePayloads(
            file = file,
            driveId = chatDrive,
            byteSource = dfp,
            fileOps = fop,
            tempPrefix = "heal",
            logTag = "HealAudit",
        )
        return recovered.payloads to recovered.thumbnails
    }

    override suspend fun getConversationHomebaseFile(conversationId: Uuid): HomebaseFile? {
        val c = credentialsManager.requireActiveCredentials()
        return dbm.driveMainIndex.selectHomebaseFileByUnique(c.getIdentityId(), chatDrive, conversationId)
    }

    override suspend fun getConversationAdminHomebaseFile(conversationId: Uuid): HomebaseFile? {
        val c = credentialsManager.requireActiveCredentials()
        val adminUniqueId = ChatProtocol.getAdminFileUniqueId(conversationId)
        return dbm.driveMainIndex.selectHomebaseFileByUnique(c.getIdentityId(), chatDrive, adminUniqueId)
    }


    /** Reads the admin list from the dedicated admin file, falling back to originalAuthor. */
    suspend fun getAdmins(conversationId: Uuid): Set<OdinId> {
        val fromFile = ConversationAdminInfo.queryFromDb(
            credentialsManager, dbm, chatDrive, conversationId
        )
        if (!fromFile.isNullOrEmpty()) return fromFile

        // Fallback: originalAuthor from conversation file
        val conversationFile = getConversationHomebaseFile(conversationId)
        val author = conversationFile?.fileMetadata?.originalAuthor
            ?: conversationFile?.fileMetadata?.senderOdinId
            ?: credentialsManager.requireActiveDomain()
        return setOf(author)
    }

    /** Creates a new admin file for a conversation. */
    private suspend fun uploadAdminFile(
        conversationId: Uuid,
        admins: List<OdinId>,
        recipients: List<OdinId>
    ) {
        val keyHeader = KeyHeader.newRandom16()
        val adminUniqueId = ChatProtocol.getAdminFileUniqueId(conversationId)
        val content = OdinSystemSerializer.serialize(ConversationAdminInfo(admins = admins))

        val metadata = UploadFileMetadata(
            allowDistribution = true,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = adminUniqueId,
                fileType = ChatProtocol.ConversationAdminFileType,
                groupId = conversationId,
                content = content,
            ),
        )

        val request = UploadFileRequest(
            driveId = chatDrive,
            keyHeader = keyHeader,
            metadata = metadata.encryptContent(keyHeader),
            transitOptions = TransitOptions(recipients = recipients, useAppNotification = false),
        )

        optimisticWriter.writeNewFile(
            driveId = chatDrive,
            keyHeader = keyHeader,
            unecryptedMetadata = metadata,
            originalRecipientCount = recipients.size,
            fileSystemType = FileSystemType.Standard,
        )

        // Chain the admin file behind the main conversation file so it is not released
        // from the local outbox until the conversation file's upload to our own server
        // has been acknowledged. Without this, the conversation file and the admin file
        // race in parallel through Transit, and recipients can see the admin file land
        // before the conversation file — which used to push them into the orphan-recovery
        // path and create stale placeholders. (See Shelly's Apr 19 log on conversation
        // 0e684619 for the original failure mode.) The dependency does NOT enforce
        // ordering across the recipient's network — Transit distribution is still
        // parallel — but it removes the local-outbox half of the race, which is the
        // half we control.
        val enqueued = outboxSync.tryEnqueue(request, dependencyUniqueId = conversationId)
        if (!enqueued.enqueued) {
            Logger.w { "uploadAdminFile: outbox enqueue → $enqueued for $conversationId (adminUniqueId=$adminUniqueId); the file was NOT scheduled for upload" }
        } else {
            Logger.d { "uploadAdminFile: enqueued upload for adminUniqueId=$adminUniqueId dependencyUniqueId=$conversationId" }
        }
    }

    /** Updates an existing admin file (or creates one if it doesn't exist yet). */
    override suspend fun updateAdminFile(
        conversationId: Uuid,
        admins: List<OdinId>,
        recipients: List<OdinId>
    ) {
        val existingFile = getConversationAdminHomebaseFile(conversationId)
        Logger.d { "updateAdminFile: conversationId=$conversationId existingFile=${existingFile?.fileMetadata?.appData?.uniqueId} versionTag=${existingFile?.fileMetadata?.versionTag} admins=$admins recipients=$recipients" }

        if (existingFile == null) {
            Logger.d { "updateAdminFile: no existing file, uploading new admin file" }
            uploadAdminFile(conversationId, admins, recipients)
            return
        }

        // If the file was never confirmed by the server (still pending), the local optimistic
        // record is stale. Remove it and re-upload so the server sees a fresh create.
        val isPending = existingFile.fileMetadata.localAppData?.tags
            ?.contains(ChatProtocol.isPendingSendTag) == true
        Logger.d { "updateAdminFile: isPending=$isPending localTags=${existingFile.fileMetadata.localAppData?.tags}" }
        if (isPending) {
            Logger.d { "updateAdminFile: stale optimistic file detected, removing and re-uploading" }
            optimisticWriter.removeOptimisticFile(chatDrive, ChatProtocol.getAdminFileUniqueId(conversationId))
            uploadAdminFile(conversationId, admins, recipients)
            return
        }

        val keyHeader = KeyHeader(
            iv = ByteArrayUtil.getRndByteArray(16),
            aesKey = existingFile.keyHeader.aesKey
        )

        val content = OdinSystemSerializer.serialize(ConversationAdminInfo(admins = admins))

        val metadata = UploadFileMetadata(
            allowDistribution = existingFile.serverMetadata.allowDistribution,
            isEncrypted = existingFile.serverFileIsEncrypted,
            versionTag = existingFile.fileMetadata.versionTag,
            appData = UploadAppFileMetaData(
                uniqueId = existingFile.fileMetadata.appData.uniqueId,
                fileType = ChatProtocol.ConversationAdminFileType,
                groupId = conversationId,
                content = content,
            ),
        )

        val adminUniqueId = ChatProtocol.getAdminFileUniqueId(conversationId)
        val domain = credentialsManager.requireActiveDomain()

        val instructions = FileUpdateInstructionSet(
            transferIv = ByteArrayUtil.getRndByteArray(16),
            locale = UpdateLocale.Local,
            recipients = recipients.filterNot { it == domain },
            manifest = UpdateManifest.build(
                payloads = null,
                toDeletePayloads = null,
                thumbnails = null,
                generatePayloadIv = false
            )
        )

        Logger.i(tag = "HealAudit") {
            val manifestKeys = instructions.manifest.payloadDescriptors
                ?.joinToString(",") { "${it.payloadKey}:${it.operationType}" } ?: "<empty>"
            "updateAdminFile ENQUEUE conversationId=$conversationId adminUniqueId=$adminUniqueId " +
                "versionTagIn=${existingFile.fileMetadata.versionTag} " +
                "admins=${admins.map { it.domainName }} " +
                "instructions.recipients=${instructions.recipients.map { it.domainName }} " +
                "manifest.payloadDescriptors=[$manifestKeys]"
        }

        val request = UpdateFileByUniqueIdRequest(
            driveId = chatDrive,
            uniqueId = adminUniqueId,
            keyHeader = keyHeader,
            instructions = instructions,
            metadata = metadata.encryptContent(keyHeader),
        )

        optimisticWriter.writeUpdate(
            driveId = chatDrive,
            keyHeader = keyHeader,
            unecryptedMetadata = metadata
        )

        // Same chaining as uploadAdminFile — the admin file's update should not race
        // ahead of the conversation file in the local outbox. Even when the admin file
        // is updated standalone (admin add/remove), chaining behind the conversation
        // file is benign: the conversation file's outbox row, if any, drains first,
        // otherwise the dependency resolves immediately.
        val enqueued = outboxSync.tryEnqueue(request, dependencyUniqueId = conversationId)
        if (!enqueued.enqueued) {
            Logger.w { "updateAdminFile: outbox enqueue → $enqueued for $conversationId (adminUniqueId=$adminUniqueId); the update was NOT scheduled" }
        } else {
            Logger.d { "updateAdminFile: enqueued update for adminUniqueId=$adminUniqueId versionTag=${existingFile.fileMetadata.versionTag} dependencyUniqueId=$conversationId" }
        }
    }

    /**
     * Setter for conversation lastRead. The validity check (monotonic +
     * saturation) lives on the entity at
     * [ConversationUiModel.resolveLastReadAdvance] — this setter delegates
     * to it, so every caller (this one, `onViewMessages`, `markAllAsRead`,
     * future callers) shares one rule. If the entity hands back null we
     * do nothing; otherwise we proceed with the eager ChatReadCount
     * upsert + the 1s-debounced conv-file stamp / outbox enqueue.
     *
     * Why the gate reads from the live in-memory model and not from
     * `ChatReadCount` or the on-disk conv-file: in-memory captures BOTH
     * local writes (via the [ConversationParticipantLookup.advancedLastRead]
     * call this setter makes below) and peer-device advances (via
     * `processConversationBatchIncrementally` and the post-DriveSync
     * `loadBasicConversations` reload). Going to any other source leaves a
     * window in which the gate could miss a higher peer value and let a
     * local advance regress the conv-file's `localAppData.lastReadTime`
     * backwards.
     *
     * ChatReadCount is upserted eagerly, before
     * [ConversationParticipantLookup.advancedLastRead], so the advance sees the
     * fresh value when it queries `selectUnreadCountForConversation`. The conv-file optimistic
     * stamp and outbox enqueue — the expensive parts (DB read + JSON
     * serialize/encrypt + DB write + event emit) — are deferred to
     * [flushDirtyLastRead] on a 1-second debounce so a burst of reads
     * coalesces into a single stamp + push per conversation.
     */
    override suspend fun updateLocalLastReadTime(conversationId: Uuid, newLastReadTime: UnixTimeUtc) {
        val convo = participantLookup.getConversationById(conversationId)
        val candidate = newLastReadTime.toInstant()
        // No in-memory model yet (cold-load race) — proceed with the write;
        // the downstream stamp's null-check will no-op if the conv-file isn't
        // on disk either.
        val advanceTo = if (convo == null) candidate else convo.resolveLastReadAdvance(candidate)
        Logger.d(tag = "MarkAsRead") {
            "ConversationService.updateLocalLastReadTime: convo=$conversationId " +
                "currentMs=${convo?.lastRead?.toEpochMilliseconds()} " +
                "latestMs=${convo?.latestMessageTimestamp?.toEpochMilliseconds()} " +
                "newMs=${newLastReadTime.milliseconds} " +
                "advanceTo=${advanceTo?.toEpochMilliseconds()}"
        }
        if (advanceTo == null) return

        dbm.chatReadCount.upsertLastReadTime(conversationId, newLastReadTime)

        // Advance the in-memory model now that ChatReadCount holds the fresh
        // value: this moves lastRead + sets dirty together (the entity's
        // advancedLastRead, applied to the live list) and re-derives unreadCount
        // from the upsert above. Doing it here — before scheduling — means the
        // flag is set by the time the flush runs, and the setter owns the whole
        // local advance rather than the caller stitching a second call on.
        participantLookup.advancedLastRead(conversationId, candidate)

        lastReadMutex.withLock {
            lastReadFlushJob?.cancel()
            lastReadFlushJob = scope.launch {
                try {
                    delay(lastReadDebounceMs)
                    awaitSyncQuiet()
                    flushDirtyLastRead()
                } catch (_: CancellationException) {
                    // Expected when a subsequent advance reschedules or
                    // flushLastReadNow drains synchronously — that path
                    // will handle the flush.
                }
            }
        }
    }

    /**
     * Defer the lastRead flush until the chat drive is sync-quiet: no round in
     * flight, and at least [syncQuietMs] elapsed since the last one stopped so
     * the post-Stopped reload ([ConversationStream.loadBasicConversations]) has
     * reconciled the in-memory list against disk. Without this a flush firing
     * mid-sync (or right after) could read a stale in-memory lastRead and stamp
     * it over a peer advance the sync just pulled in. Returns immediately when
     * no sync ran recently, and is a no-op when [driveSyncManager] is absent
     * (tests). Only the debounce path waits — [flushLastReadNow] forces through.
     */
    private suspend fun awaitSyncQuiet() {
        val mgr = driveSyncManager ?: return
        while (true) {
            while (mgr.isSyncing(chatDrive)) {
                delay(syncPollMs)
            }
            val since = UnixTimeUtc().milliseconds - mgr.lastSyncStoppedAtMs(chatDrive)
            if (since >= syncQuietMs) return
            delay(syncQuietMs - since)
            // Loop and re-check: a new round may have started during the wait.
        }
    }

    /**
     * Drain every dirty conversation's lastRead writeback: read the value off
     * the in-memory list, stamp the conversation file (optimistic local
     * write), and enqueue the resulting outbox request. Called by the debounce
     * coroutine and by [flushLastReadNow].
     */
    private suspend fun flushDirtyLastRead() {
        val ids = participantLookup.getDirtyConversationIds()
        if (ids.isEmpty()) return
        Logger.d(tag = "MarkAsRead") {
            "ConversationService.flushDirtyLastRead: flushing ${ids.size} dirty conversation(s)"
        }
        for (id in ids) {
            // Read the value off the list WITHOUT clearing dirty. We only clear
            // after a successful push (compare-and-clear below), so a push
            // failure simply leaves the conversation dirty for the next round —
            // no value-less re-flagging required.
            val convo = participantLookup.getConversationById(id) ?: continue
            val target = UnixTimeUtc(convo.lastRead.toEpochMilliseconds())
            try {
                val request = optimisticWriter.stampConversationLastReadTime(
                    driveId = chatDrive,
                    conversationId = id,
                    newLastReadTime = target,
                    // Ride the list sort key along with the lastRead push so the
                    // post-sync basic list lands in last-message order. mapToBasic
                    // reads it back from localAppData.
                    latestMessageTimestamp = UnixTimeUtc(convo.latestMessageTimestamp.toEpochMilliseconds()),
                )
                if (request == null) {
                    Logger.w(tag = "MarkAsRead") {
                        "ConversationService.flushDirtyLastRead: stamp returned null for convo=$id — " +
                                "conversation file missing or optimistic write failed (left dirty)"
                    }
                    continue
                }
                // AlreadyQueued is expected and benign: the queued row picks up
                // the latest localAppData when it drains, so the writeback still
                // lands. A real Failed means nothing is queued — leave the
                // conversation dirty so the next debounce flush retries instead
                // of silently losing the writeback.
                val result = outboxSync.tryEnqueue(request)
                if (!result.enqueued && result != EnqueueResult.AlreadyQueued) {
                    Logger.w(tag = "MarkAsRead") {
                        "ConversationService.flushDirtyLastRead: enqueue → $result for convo=$id — left dirty, will retry next flush"
                    }
                    continue
                }
                // Clear dirty only if nothing advanced lastRead while we were
                // pushing — a concurrent advance leaves it dirty so the newer
                // value goes out next round.
                participantLookup.clearLastReadDirtyIfUnchanged(id, target)
            } catch (t: Throwable) {
                Logger.w(throwable = t, tag = "MarkAsRead") {
                    "ConversationService.flushDirtyLastRead: stamp/enqueue threw for convo=$id — " +
                            "left dirty, will retry next flush"
                }
                // Leave dirty — the next debounce flush retries it.
            }
        }
    }

    /**
     * Cancel any pending debounce and synchronously flush all pending lastRead
     * writebacks. Not wired into logout today — the dirty state is in-memory
     * only and a logout within the 1s debounce window costs at most one
     * window of writebacks, the same loss profile the user accepted for
     * process kill. Kept public so callers (tests, future logout hook) can
     * force a synchronous flush without waiting for the debounce.
     */
    suspend fun flushLastReadNow() {
        // We use cancelAndJoin (not bare cancel) here because we're NOT
        // inside lastReadMutex when we call it — the prior flush, if past
        // its delay(), would otherwise race us into flushDirtyLastRead on
        // the mutex and process an empty snapshot. The in-mutex cancel in
        // updateLocalLastReadTime deliberately does NOT join: a prior
        // flush parked on the same mutex would deadlock waiting for us to
        // release it.
        val job = lastReadMutex.withLock {
            val j = lastReadFlushJob
            lastReadFlushJob = null
            j
        }
        job?.cancelAndJoin()
        flushDirtyLastRead()
    }

}
