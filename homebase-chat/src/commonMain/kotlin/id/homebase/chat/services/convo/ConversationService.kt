package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.IntroductionGroup
import id.homebase.api.client.connections.IntroductionSender
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.DeleteFilesByGroupIdOutboxRequest
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
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.toBase64
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.PayloadBundleEncryptor
import id.homebase.chat.services.StatusMessage
import id.homebase.chat.services.StatusMessageData
import id.homebase.chat.services.XorIdUtil
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.chatTargetDrive
import kotlinx.coroutines.CoroutineScope
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
private class MethodAudit(private val method: String) {
    companion object { const val TAG = "ConvoAudit" }
    private val checks = mutableListOf<String>()

    fun start(detail: String = "") {
        Logger.i(tag = TAG) { "═════ $method START $detail ═════" }
    }
    fun pre(line: String) { Logger.i(tag = TAG) { "[$method] PRE $line" } }
    fun step(num: Int, what: String) { Logger.i(tag = TAG) { "[$method] STEP $num $what" } }
    fun post(line: String) { Logger.i(tag = TAG) { "[$method] POST $line" } }
    fun info(line: String) { Logger.i(tag = TAG) { "[$method] $line" } }

    fun checkPass(name: String) { checks += "$name=PASS" }
    fun checkFail(name: String, bugMessage: String) {
        checks += "$name=FAIL"
        Logger.w(tag = TAG) { "[$method] BUG? $name: $bugMessage" }
    }
    fun checkWarn(name: String, bugMessage: String) {
        checks += "$name=WARN"
        Logger.w(tag = TAG) { "[$method] BUG? $name: $bugMessage" }
    }
    fun check(name: String, pass: Boolean, failBugMessage: String) {
        if (pass) checkPass(name) else checkFail(name, failBugMessage)
    }
    fun threw(stepLabel: String, e: Throwable) {
        checks += "$stepLabel=THREW"
        Logger.e(throwable = e, tag = TAG) {
            "[$method] BUG? $stepLabel threw: ${e::class.simpleName}: ${e.message}"
        }
    }
    fun finish(extra: String = "") {
        val verdict = when {
            checks.any { it.endsWith("=FAIL") || it.endsWith("=THREW") } -> "FAIL"
            checks.any { it.endsWith("=WARN") } -> "WARN"
            else -> "OK"
        }
        Logger.i(tag = TAG) {
            "DIAGNOSIS: $method verdict=$verdict ${checks.joinToString(" ")} $extra".trim()
        }
        Logger.i(tag = TAG) { "═════ $method END ═════" }
    }
}

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
) : LocalLastReadUpdater {
    private val chatDrive = chatTargetDrive.alias

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
        audit.info("STEP 3 returned enqueued=$enqueued")
        audit.check("outboxEnqueue", enqueued, "outboxSync.tryEnqueue returned false — file will not be uploaded; conversation creation effectively failed")
        val outboxAfter = dbm.outbox.count()
        audit.post("counts: outboxRows=$outboxAfter (delta=${outboxAfter - outboxBefore}, expected ≥+1 if enqueue succeeded)")
        audit.finish("returned $enqueued")
        return enqueued
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

    suspend fun requireConversation(conversationId: Uuid): ConversationUiModel {
        return getConversation(conversationId)
            ?: throw IllegalStateException("No conversation found")
    }

    suspend fun requireConversationFileId(conversationId: Uuid): Uuid {
        return getConversationHomebaseFile(conversationId)?.fileId
            ?: throw IllegalStateException("No conversation found")
    }

    suspend fun getConversation(conversationId: Uuid): ConversationUiModel? {
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
            optimisticWriter.removeOptimisticFile(chatDrive, messageId)
            dbm.outbox.deleteBy(chatDrive, messageId)
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

    suspend fun updateConversationInternal(
        conversationId: Uuid,
        title: String?,
        participants: List<OdinId>,
        payloadBundle: PayloadBundle? = null,
        dependencyUniqueId: Uuid? = null,
        archivalStatus: ArchivalStatus? = null,
        distribute: Boolean = true,
        additionalDistributionRecipients: List<OdinId> = emptyList(),
        /** When true, ensures [ChatProtocol.ConversationGroupTag] is present in the file's
         *  tags (used by recovery/revive paths to heal legacy or untagged group files).
         *  null = preserve existing tags as-is. */
        isGroup: Boolean? = null,
        /** When true, immediately writes the new metadata to the local DB via
         *  [OptimisticWriter.writeUpdate] BEFORE enqueuing the server upload. Use this when
         *  the caller needs subsequent code (or the mapper) to observe the new participant
         *  list locally without waiting for the outbox to drain — most notably [leaveGroup]
         *  step 2, which would otherwise race [ChatProtocol.ConversationLeftTag] against the
         *  pending participant change and produce a spurious [ConversationState.RejoinPending]. */
        applyOptimisticContentLocally: Boolean = false
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
            manifest = UpdateManifest.build(
                payloads = null,
                toDeletePayloads = null,
                thumbnails = null,
                generatePayloadIv = false
            )

            payloads = emptyList()
            thumbs = emptyList()
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
                recipients = if (distribute) (participants + additionalDistributionRecipients).filterNot { it == domain }
                    .distinct() else emptyList(),
                manifest = manifest
            )

        Logger.d { "updateConversationInternal PRE-REQUEST: conversationId=$conversationId aesKey=${keyHeader.aesKey.unsafeBytes.toBase64()} versionTag=${conversationFile.fileMetadata.versionTag}" }

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
        audit.info("STEP 1 returned enqueued=$enqueued")
        audit.check("replaceEnqueue", enqueued, "outboxSync.replaceEnqueue returned false — update will not happen")
        if (!enqueued) {
            audit.finish("ABORTED — replaceEnqueue false")
        }
        // ---- end DEBUG ----
        if (!enqueued) {
            error("Failed to update conversation")
        }
        // ---- DEBUG instrumentation: POST verification ----
        val postFile = getConversationHomebaseFile(conversationId)
        val postVersionTag = postFile?.fileMetadata?.versionTag
        val postArchivalStatus = postFile?.fileMetadata?.appData?.archivalStatus
        audit.post("file: versionTag=$postVersionTag (pre=$preVersionTag) archivalStatus=$postArchivalStatus")
        if (archivalStatus != null) {
            audit.check("postArchivalStatusApplied", postArchivalStatus == archivalStatus,
                "file.archivalStatus is $postArchivalStatus, expected $archivalStatus — optimistic write of archivalStatus did not apply (note: the optimistic file-update commented out at lines ~935-939 may be the cause)")
        }
        // PARTICIPANT-LIST TRACE — read the file's CURRENT participants after the update.
        // If this differs from `participants` (the value passed in), the optimistic write
        // didn't apply yet (the request is still in the outbox; participants will sync
        // back eventually) OR the local file index hasn't been refreshed.
        runCatching {
            val postContent = postFile?.fileMetadata?.appData?.content?.let {
                OdinSystemSerializer.deserialize<ConversationAppDataJson>(it)
            }
            val postRecipients = postContent?.recipients?.filterNotNull() ?: emptyList()
            Logger.i(tag = "ParticipantsAudit") {
                "updateConversationInternal POST_READBACK for $conversationId: " +
                    "file.recipients.size=${postRecipients.size} " +
                    "domains=[${postRecipients.joinToString(",") { it.domainName }}] " +
                    "(intended new size=${participants.size})"
            }
            if (postRecipients.toSet() != participants.toSet()) {
                Logger.w(tag = "ParticipantsAudit") {
                    "updateConversationInternal POST_READBACK MISMATCH: file shows ${postRecipients.size} recipients, " +
                        "intended ${participants.size}. The local optimistic write did NOT apply — " +
                        "the change is queued in the outbox but won't be visible to readers until the server " +
                        "round-trips back. Note: the writer at lines ~935-939 (optimisticWriter.writeUpdate) is " +
                        "commented out. This may be the root cause of 'group members missing right after creation'."
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
    suspend fun recoverConversation(conversationId: Uuid, originalAuthor: OdinId) {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("recoverConversation")
        audit.start("conversationId=$conversationId originalAuthor=${originalAuthor.domainName}")
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

        val isOneToOne = conversationId == XorIdUtil.getNewXorId(
            domain.domainName, originalAuthor.domainName
        )

        Logger.i("ConversationService: recoverConversation($conversationId) author=${originalAuthor.domainName} isOneToOne=$isOneToOne")

        val existingFile = getConversationHomebaseFile(conversationId)

        if (existingFile != null) {
            val existingState: ConversationState? = try {
                mapper.mapToConversationUi(existingFile, null).conversationState
            } catch (e: Exception) {
                Logger.w(e) { "ConversationService: recoverConversation($conversationId) — existing file failed to map, will overwrite" }
                null
            }

            Logger.d("ConversationService: recoverConversation($conversationId) existingState=$existingState")

            val needsRevive = existingState == null
                || existingState == ConversationState.Deleted
                || existingState == ConversationState.Invalid

            if (!needsRevive) {
                Logger.d("ConversationService: recoverConversation($conversationId) — file exists and is $existingState, no action needed")
                return
            }

            // Read existing participants from file if possible (preserves group membership)
            val existingContent = existingFile.fileMetadata.appData.content?.let {
                try {
                    OdinSystemSerializer.deserialize<ConversationAppDataJson>(it)
                } catch (e: Exception) { null }
            }
            val participants = existingContent?.recipients
                ?.filterNotNull()?.distinct()
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(originalAuthor, domain).distinct()

            Logger.i("ConversationService: recoverConversation($conversationId) — reviving (state=$existingState) participants=${participants.map { it.domainName }}")
            audit.step(1, "reviving via updateConversationInternal(archivalStatus=None, distribute=false, isGroup=${!isOneToOne})")
            runCatching {
                updateConversationInternal(
                    conversationId = conversationId,
                    title = existingContent?.title ?: "",
                    participants = participants,
                    archivalStatus = ArchivalStatus.None,
                    distribute = false,
                    isGroup = !isOneToOne
                )
            }.onSuccess { audit.checkPass("revive") }
                .onFailure { e -> audit.threw("revive", e); audit.finish("threw"); throw e }
            audit.finish("revive completed")
            return
        }

        // No local file — create one with the ORIGINAL conversationId
        val allParticipants = listOf(originalAuthor, domain).distinct()
        Logger.i("ConversationService: recoverConversation($conversationId) — no local file, creating new (isGroup=${!isOneToOne}) participants=${allParticipants.map { it.domainName }}")
        audit.step(1, "writeConversationFile (no local file, fresh creation)")
        runCatching {
            writeConversationFile(
                conversationId = conversationId,
                allParticipants = allParticipants,
                transitRecipients = listOf(originalAuthor),
                title = "",
                isGroup = !isOneToOne,
                payloadBundle = null
            )
        }.onSuccess { result -> audit.check("writeConversationFile", result, "writeConversationFile returned false") }
            .onFailure { e -> audit.threw("writeConversationFile", e); audit.finish("threw"); throw e }
        audit.finish("creation completed")
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
        val messagesBefore = runCatching { QueryBatch(identityId).queryBatchAsync(dbm = dbm, driveId = chatDrive, noOfItems = 10_000, groupIdAnyOf = listOf(conversationId)).records.size }.getOrElse { -1 }
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
        Logger.d { "deleteConversation: conversationId=$conversationId isEncrypted=${deleteFile?.fileMetadata?.isEncrypted} aesKey=${deleteFile?.keyHeader?.aesKey?.unsafeBytes?.toBase64() ?: "NO FILE"}" }

        // ---- DEBUG instrumentation ----
        audit.step(1, "outboxSync.tryEnqueue(DeleteFilesByGroupIdOutboxRequest)")
        val step1 = runCatching {
            outboxSync.tryEnqueue(
                DeleteFilesByGroupIdOutboxRequest(driveId = chatDrive, groupIds = listOf(conversationId))
            )
        }
        step1.onSuccess { result ->
            audit.info("STEP 1 returned enqueued=$result")
            if (result == false) audit.checkFail("step1Enqueue", "tryEnqueue returned false — UNIQUE(driveId, uniqueId) collision; server-side delete will NOT happen this attempt") else audit.checkPass("step1Enqueue")
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
        val postFile = getConversationHomebaseFile(conversationId)
        val postArchivalStatus = postFile?.fileMetadata?.appData?.archivalStatus
        val postVersionTag = postFile?.fileMetadata?.versionTag
        audit.post("file: exists=${postFile != null} archivalStatus=$postArchivalStatus (expected=Removed) versionTag=$postVersionTag (pre=$preVersionTag, expected changed)")
        audit.check("postArchivalStatus", postArchivalStatus == ArchivalStatus.Removed,
            "file.archivalStatus is $postArchivalStatus (expected Removed) — STEP 2 returned without throwing but optimistic write did not apply")
        if (preFile == null) {
            // record as not-applicable
        } else {
            audit.check("postVersionTagChanged", preVersionTag != null && postVersionTag != null && preVersionTag != postVersionTag,
                "versionTag did not change (pre=$preVersionTag, post=$postVersionTag) — update was a no-op or never reached the file index")
        }
        val postConvo = runCatching { getConversation(conversationId) }.getOrNull()
        audit.post("convo (UI model): state=${postConvo?.conversationState}")
        audit.check("postUiState",
            postConvo == null || postConvo.conversationState == ConversationState.Removed || postConvo.conversationState == ConversationState.Deleted,
            "UI state is ${postConvo?.conversationState} (expected Removed/Deleted/null) — Delete button will appear as a no-op to the user")
        val outboxAfter = dbm.outbox.count()
        val outboxDelta = outboxAfter - outboxBefore
        val messagesAfter = runCatching { QueryBatch(identityId).queryBatchAsync(dbm = dbm, driveId = chatDrive, noOfItems = 10_000, groupIdAnyOf = listOf(conversationId)).records.size }.getOrElse { -1 }
        audit.post("counts: outboxRows=$outboxAfter (delta=$outboxDelta, expected ≥+2) messagesWithGroupId=$messagesAfter (delta=${messagesAfter - messagesBefore}, expected unchanged until outbox processes)")
        audit.check("postOutboxDelta", outboxDelta >= 2,
            "outbox grew by $outboxDelta rows, expected ≥2 (one DeleteFilesByGroupId + one UpdateFile) — one enqueue silently dropped (UNIQUE collision)")
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
        audit.check("enqueue", enqueued, "tryEnqueue returned false — UNIQUE(driveId, uniqueId) collision; clear NOT performed")
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
        audit.info("STEP 2 returned enqueued=$enqueued")
        audit.check("step2Enqueue", enqueued, "tryEnqueue returned false — server-side tags update will NOT happen")

        // POST verify the optimistic write applied
        val postFile = getConversationHomebaseFile(conversationId)
        val postLocalTags = postFile?.fileMetadata?.localAppData?.tags?.toSet() ?: emptySet()
        audit.post("file localTags after: ${postLocalTags.size} (expected ${newTags.size})")
        audit.check("postLocalTags", postLocalTags == newTags,
            "localTags after optimisticWriter.updateLocalTags differ from expected; got=${postLocalTags - newTags}+/${newTags - postLocalTags}-")
        audit.finish()
    }

    suspend fun getConversationHomebaseFile(conversationId: Uuid): HomebaseFile? {
        val c = credentialsManager.requireActiveCredentials()
        return dbm.driveMainIndex.selectHomebaseFileByUnique(c.getIdentityId(), chatDrive, conversationId)
    }

    suspend fun getConversationAdminHomebaseFile(conversationId: Uuid): HomebaseFile? {
        val c = credentialsManager.requireActiveCredentials()
        val adminUniqueId = ChatProtocol.getAdminFileUniqueId(conversationId)
        return dbm.driveMainIndex.selectHomebaseFileByUnique(c.getIdentityId(), chatDrive, adminUniqueId)
    }

    /**
     * Manually re-distribute the group files (main conversation file + admin file) to all
     * current participants, for any of those files the caller authored. This is a recovery
     * aid for groups where one or more recipients did not receive (or lost) the original
     * group file. Per-file authorship is checked individually — the caller may have
     * authored only one of the two and the other will be left untouched.
     *
     * Each step is debug-logged so a failure can be diagnosed in homebase.log.
     */
    suspend fun healGroupDistribution(conversationId: Uuid): HealGroupResult {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("healGroupDistribution")
        audit.start("conversationId=$conversationId")
        // ---- end DEBUG ----
        val conversation = requireConversation(conversationId)
        if (!conversation.isGroupConversation) {
            audit.checkFail("isGroupGuard", "called on non-group conversation")
            audit.finish("REJECTED at guard")
            throw IllegalStateException("healGroupDistribution: not a group conversation $conversationId")
        }

        val domain = credentialsManager.requireActiveDomain()
        val recipients = conversation.participants.filterNot { it == domain }.distinct()

        Logger.i { "healGroupDistribution: START conversationId=$conversationId domain=$domain participants=${conversation.participants} recipients=$recipients admins=${conversation.admins}" }

        var mainHealed = false
        var adminHealed = false

        // Main conversation file
        val mainFile = getConversationHomebaseFile(conversationId)
        if (mainFile == null) {
            Logger.w { "healGroupDistribution: no local main conversation file for $conversationId — skipping main" }
        } else {
            val mainAuthor = mainFile.fileMetadata.originalAuthor ?: mainFile.fileMetadata.senderOdinId
            val mainPending = mainFile.fileMetadata.localAppData?.tags?.contains(ChatProtocol.isPendingSendTag) == true
            Logger.d { "healGroupDistribution: main file fileId=${mainFile.fileId} sender=${mainFile.fileMetadata.senderOdinId} originalAuthor=${mainFile.fileMetadata.originalAuthor} resolvedAuthor=$mainAuthor versionTag=${mainFile.fileMetadata.versionTag} isPending=$mainPending localTags=${mainFile.fileMetadata.localAppData?.tags}" }
            if (mainAuthor == domain) {
                try {
                    Logger.i { "healGroupDistribution: redistributing main conversation file $conversationId to recipients=$recipients" }
                    updateConversationInternal(
                        conversationId = conversationId,
                        title = conversation.name,
                        participants = conversation.participants,
                        distribute = true
                    )
                    mainHealed = true
                    Logger.i { "healGroupDistribution: main conversation file enqueued for redistribute $conversationId" }
                } catch (t: Throwable) {
                    Logger.e("healGroupDistribution: main conversation file redistribute FAILED for $conversationId", t)
                    throw t
                }
            } else {
                Logger.d { "healGroupDistribution: skipping main — caller is not the original author (author=$mainAuthor, caller=$domain)" }
            }
        }

        // Admin file
        val adminFile = getConversationAdminHomebaseFile(conversationId)
        if (adminFile == null) {
            Logger.w { "healGroupDistribution: no local admin file for $conversationId — skipping admin" }
        } else {
            val adminAuthor = adminFile.fileMetadata.originalAuthor ?: adminFile.fileMetadata.senderOdinId
            val adminPending = adminFile.fileMetadata.localAppData?.tags?.contains(ChatProtocol.isPendingSendTag) == true
            Logger.d { "healGroupDistribution: admin file fileId=${adminFile.fileId} sender=${adminFile.fileMetadata.senderOdinId} originalAuthor=${adminFile.fileMetadata.originalAuthor} resolvedAuthor=$adminAuthor versionTag=${adminFile.fileMetadata.versionTag} isPending=$adminPending localTags=${adminFile.fileMetadata.localAppData?.tags}" }
            if (adminAuthor == domain) {
                try {
                    Logger.i { "healGroupDistribution: redistributing admin file $conversationId admins=${conversation.admins} recipients=$recipients" }
                    updateAdminFile(
                        conversationId = conversationId,
                        admins = conversation.admins.toList(),
                        recipients = recipients
                    )
                    adminHealed = true
                    Logger.i { "healGroupDistribution: admin file enqueued for redistribute $conversationId" }
                } catch (t: Throwable) {
                    Logger.e("healGroupDistribution: admin file redistribute FAILED for $conversationId", t)
                    throw t
                }
            } else {
                Logger.d { "healGroupDistribution: skipping admin — caller is not the original author (author=$adminAuthor, caller=$domain)" }
            }
        }

        Logger.i { "healGroupDistribution: DONE conversationId=$conversationId mainHealed=$mainHealed adminHealed=$adminHealed" }
        // ---- DEBUG instrumentation ----
        audit.info("mainHealed=$mainHealed adminHealed=$adminHealed")
        audit.check("anythingHealed", mainHealed || adminHealed, "neither main nor admin file was redistributed (caller is not the original author of either) — heal was a no-op")
        audit.finish()
        // ---- end DEBUG ----
        return HealGroupResult(mainHealed = mainHealed, adminHealed = adminHealed)
    }

    data class HealGroupResult(val mainHealed: Boolean, val adminHealed: Boolean) {
        val didAnything: Boolean get() = mainHealed || adminHealed
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
        if (!enqueued) {
            Logger.w { "uploadAdminFile: outbox enqueue returned false for $conversationId — likely UNIQUE conflict on adminUniqueId=$adminUniqueId; the file was NOT scheduled for upload" }
        } else {
            Logger.d { "uploadAdminFile: enqueued upload for adminUniqueId=$adminUniqueId dependencyUniqueId=$conversationId" }
        }
    }

    /** Updates an existing admin file (or creates one if it doesn't exist yet). */
    private suspend fun updateAdminFile(
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
        if (!enqueued) {
            Logger.w { "updateAdminFile: outbox enqueue returned false for $conversationId — likely UNIQUE conflict on adminUniqueId=$adminUniqueId (something already pending); the update was NOT scheduled" }
        } else {
            Logger.d { "updateAdminFile: enqueued update for adminUniqueId=$adminUniqueId versionTag=${existingFile.fileMetadata.versionTag} dependencyUniqueId=$conversationId" }
        }
    }

    override suspend fun updateLocalLastReadTime(conversationId: Uuid, newLastReadTime: UnixTimeUtc) {

        Logger.d(tag = "MarkAsRead") {
            "ConversationService.updateLocalLastReadTime: enter convo=$conversationId newMs=${newLastReadTime.milliseconds}"
        }

        val convo = requireConversation(conversationId)
        val currentMs = UnixTimeUtc(convo.lastRead).milliseconds
        val willAdvance = newLastReadTime > UnixTimeUtc(convo.lastRead)
        Logger.d(tag = "MarkAsRead") {
            "ConversationService.updateLocalLastReadTime: convo=$conversationId currentMs=$currentMs " +
                    "newMs=${newLastReadTime.milliseconds} willAdvance=$willAdvance"
        }

        if (!willAdvance) return

        val request = optimisticWriter.stampConversationLastReadTime(
            driveId = chatDrive,
            conversationId = conversationId,
            newLastReadTime = newLastReadTime,
        )
        if (request == null) {
            Logger.w(tag = "MarkAsRead") {
                "ConversationService.updateLocalLastReadTime: stampConversationLastReadTime returned null — conversation file missing or optimistic write failed; convo=$conversationId"
            }
            return
        }

        outboxSync.tryEnqueue(request)
        dbm.chatReadCount.upsertLastReadTime(conversationId, newLastReadTime)
    }

}
