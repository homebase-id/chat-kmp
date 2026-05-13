package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.common.OdinId
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.chat.data.ConversationState
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.GroupHealCleanupInfo
import id.homebase.chat.services.GroupHealInfo
import id.homebase.chat.services.StatusMessage
import id.homebase.chat.services.StatusMessageData
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.chatTargetDrive
import kotlin.uuid.Uuid

/**
 * Coarse-grained phase callback fired by [GroupHealService.healGroupDistribution]
 * at the three natural transitions the UI cares about: heal-message send, main
 * file resend, admin file resend. The Sending event fires before the outbox
 * enqueue; Sent fires after a successful enqueue; Failed fires if the enqueue
 * throws. The [recipients] list is the actual targeted subset, ready for
 * "Sending X to peer-by-peer" UI rendering.
 *
 * Skipped phases (caller not the author, recipient set empty) DO emit a phase
 * event so the dialog can render them as "Skipped — already in sync" rather
 * than leaving rows in Pending forever.
 */
sealed interface HealPhase {
    val recipients: List<OdinId>
    data class HealMessageSending(override val recipients: List<OdinId>) : HealPhase
    data class HealMessageSent(override val recipients: List<OdinId>) : HealPhase
    data class HealMessageFailed(override val recipients: List<OdinId>, val cause: Throwable) : HealPhase
    data class HealMessageSkipped(override val recipients: List<OdinId>, val reason: String) : HealPhase
    data class MainSending(override val recipients: List<OdinId>) : HealPhase
    data class MainSent(override val recipients: List<OdinId>) : HealPhase
    data class MainFailed(override val recipients: List<OdinId>, val cause: Throwable) : HealPhase
    data class MainSkipped(override val recipients: List<OdinId>, val reason: String) : HealPhase
    data class AdminSending(override val recipients: List<OdinId>) : HealPhase
    data class AdminSent(override val recipients: List<OdinId>) : HealPhase
    data class AdminFailed(override val recipients: List<OdinId>, val cause: Throwable) : HealPhase
    data class AdminSkipped(override val recipients: List<OdinId>, val reason: String) : HealPhase
}

/**
 * Per-(recipient, file) plan derived from `MemberFileExistsStatus`.
 *
 * - [resendMainTo] / [resendAdminTo]: peers reported Missing for that file → push it.
 *   A null list means "we have no per-recipient signal for this file" — the heal
 *   service falls back to redistribution-to-all-participants (legacy behavior).
 *   An empty list means "every peer is PresentAuthoredByMe (or filtered out as
 *   Error/Loading) — skip this file."
 * - [healMessageMainTo] / [healMessageAdminTo]: peers reported PresentAuthoredByOther
 *   for the main / admin file respectively → ask them to drop the third-party
 *   copy via [StatusMessage.GroupHealRequested], so the next heal cycle can push
 *   a clean copy. Mechanically the heal-message is a single status carrying both
 *   canonical versionTags, so heal only sends one message addressed to the
 *   *union* of these two sets — the split lets callers render one progress row
 *   per (peer, file). Null lists mean "broadcast" (legacy); empty means "skip
 *   the message".
 *
 * Peers with `MemberFileExistsStatus.Error` / `MemberFileExistsStatus.Loading`
 * MUST be filtered out by the caller before constructing the plan.
 */
data class HealPlan(
    val resendMainTo: List<OdinId>?,
    val resendAdminTo: List<OdinId>?,
    val healMessageMainTo: List<OdinId>?,
    val healMessageAdminTo: List<OdinId>?,
) {
    /** Union of the per-file heal-message sets — what actually goes out on the wire. */
    val healMessageUnionTo: List<OdinId>?
        get() {
            val main = healMessageMainTo
            val admin = healMessageAdminTo
            if (main == null && admin == null) return null
            return ((main.orEmpty()) + (admin.orEmpty())).distinct()
        }
}

data class HealGroupResult(
    val mainHealed: Boolean,
    val adminHealed: Boolean,
    val mainRecipientCount: Int = 0,
    val adminRecipientCount: Int = 0,
    val healMessageRecipientCount: Int = 0,
    /** fileId of the main conversation file that was enqueued — used by the
     *  caller to poll transfer-history per recipient. Null when main was
     *  skipped (not author, no targets, no local file). */
    val mainFileId: Uuid? = null,
    /** fileId of the admin file that was enqueued. Null when skipped. */
    val adminFileId: Uuid? = null,
    /** uniqueId of the heal-request status message that was enqueued. The
     *  caller must resolve it to a fileId via `getFileHeaderByUid` before
     *  polling transfer-history (we don't get a fileId back from
     *  sendStatusMessage). Null when no status message was sent. */
    val healMessageUniqueId: Uuid? = null,
    /** Actual peers we targeted for the main file resend (= mainTargets at
     *  the call site, repeated here so the caller has a single source of
     *  truth for polling). Empty when [mainFileId] is null. */
    val mainTargets: List<OdinId> = emptyList(),
    val adminTargets: List<OdinId> = emptyList(),
    /** Audience of the heal-request status message. Same peer set covers
     *  both HealRequestGroupFile and HealRequestAdminFile rows. */
    val healMessageTargets: List<OdinId> = emptyList(),
) {
    val didAnything: Boolean
        get() = mainHealed || adminHealed || healMessageRecipientCount > 0
}

/**
 * Group-heal recovery — send-side ([healGroupDistribution]) and receive-side
 * ([handleIncomingHealRequest]) flows extracted from `ConversationService`.
 *
 * The cross-file read paths, the redistribute primitives
 * (`updateConversationInternal` / `updateAdminFile`), and the placeholder
 * writers all stay on `ConversationService` for cohesion with non-heal code;
 * this service reaches them through [GroupHealConversationOps].
 */
class GroupHealService(
    private val credentialsManager: CredentialsManager,
    private val dbm: DatabaseManager,
    private val outboxSync: OutboxSync,
    private val optimisticWriter: OptimisticWriter,
    private val chatMessageSenderService: StatusMessageSender,
    private val convoOps: GroupHealConversationOps,
) {
    private val chatDrive = chatTargetDrive.alias

    /**
     * Manually re-distribute the group files (main conversation file + admin
     * file) to all current participants, for any of those files the caller
     * authored. This is a recovery aid for groups where one or more recipients
     * did not receive (or lost) the original group file. Per-file authorship is
     * checked individually — the caller may have authored only one of the two
     * and the other will be left untouched.
     *
     * Each step is debug-logged so a failure can be diagnosed in homebase.log.
     */
    suspend fun healGroupDistribution(
        conversationId: Uuid,
        plan: HealPlan? = null,
        onPhase: ((HealPhase) -> Unit)? = null,
    ): HealGroupResult {
        // ---- DEBUG instrumentation ----
        val audit = MethodAudit("healGroupDistribution")
        audit.start("conversationId=$conversationId planned=${plan != null}")
        // ---- end DEBUG ----
        val conversation = convoOps.requireConversation(conversationId)
        if (!conversation.isGroupConversation) {
            audit.checkFail("isGroupGuard", "called on non-group conversation")
            audit.finish("REJECTED at guard")
            throw IllegalStateException("healGroupDistribution: not a group conversation $conversationId")
        }

        val domain = credentialsManager.requireActiveDomain()
        val allRecipients = conversation.participants.filterNot { it == domain }.distinct()

        // Per-file recipient targeting. When the plan carries a non-null subset,
        // narrow the resend to exactly those peers. When the plan is null or the
        // per-file subset is null (e.g. all peer-exists checks errored), fall
        // back to the legacy all-recipients behavior so heal remains useful on
        // server builds without the peer-exists endpoint.
        val mainTargets = plan?.resendMainTo ?: allRecipients
        val adminTargets = plan?.resendAdminTo ?: allRecipients
        val healMessageTargets = plan?.healMessageUnionTo  // null = broadcast to all participants

        Logger.i {
            "healGroupDistribution: START conversationId=$conversationId domain=$domain " +
                "participants=${conversation.participants} mainTargets=$mainTargets " +
                "adminTargets=$adminTargets healMessageTargets=$healMessageTargets " +
                "admins=${conversation.admins}"
        }

        var mainHealed = false
        var adminHealed = false
        var mainRecipientCount = 0
        var adminRecipientCount = 0
        var healMessageRecipientCount = 0
        var mainFileIdOut: Uuid? = null
        var adminFileIdOut: Uuid? = null
        var healMessageUniqueIdOut: Uuid? = null

        // Status broadcast — send the canonical group identity BEFORE the file
        // pushes so a recipient processing the same sync batch sees the
        // cleanup directive first. Only the original author of the main file
        // has authority to announce the canonical identity, so the same
        // mainAuthor == domain gate below applies — but we send the status
        // even when only the admin file is healable, since both files share
        // the same originalAuthor in practice.
        val preMainFile = convoOps.getConversationHomebaseFile(conversationId)
        val preAdminFile = convoOps.getConversationAdminHomebaseFile(conversationId)
        val callerIsMainAuthor = preMainFile != null &&
                (preMainFile.fileMetadata.originalAuthor ?: preMainFile.fileMetadata.senderOdinId) == domain
        val healMessageRecipientsActual: List<OdinId>? = when {
            healMessageTargets == null -> null               // null = legacy broadcast (no override)
            healMessageTargets.isEmpty() -> emptyList()      // empty = skip the send entirely
            else -> healMessageTargets
        }
        val shouldSendHealMessage = callerIsMainAuthor && healMessageRecipientsActual != emptyList<OdinId>()
        // Audience for progress-event purposes only. When override is null we'll
        // broadcast, so use allRecipients as the visible set.
        val healMessageAudience = healMessageRecipientsActual ?: allRecipients
        if (shouldSendHealMessage) {
            onPhase?.invoke(HealPhase.HealMessageSending(healMessageAudience))
            val statusMessageUniqueId = Uuid.random()
            try {
                val canonicalAdminsForStatus = conversation.admins.toList()
                Logger.i {
                    "healGroupDistribution: sending GroupHealRequested status conversationId=$conversationId " +
                        "messageUniqueId=$statusMessageUniqueId " +
                        "versionTag=${preMainFile.fileMetadata.versionTag} " +
                        "adminVersionTag=${preAdminFile?.fileMetadata?.versionTag} " +
                        "title=\"${conversation.name}\" " +
                        "participants(${conversation.participants.size})=${conversation.participants.map { it.domainName }} " +
                        "admins(${canonicalAdminsForStatus.size})=${canonicalAdminsForStatus.map { it.domainName }} " +
                        "recipientOverride=${healMessageRecipientsActual?.map { it.domainName }}"
                }
                chatMessageSenderService.sendStatusMessage(
                    messageUniqueId = statusMessageUniqueId,
                    conversationId = conversationId,
                    statusMessage = StatusMessageData(
                        statusMessage = StatusMessage.GroupHealRequested,
                        groupHeal = GroupHealInfo(
                            conversationUniqueId = conversationId,
                            canonicalOriginalAuthor = domain,
                            canonicalVersionTag = preMainFile.fileMetadata.versionTag,
                            canonicalTitle = conversation.name,
                            canonicalParticipants = conversation.participants,
                            canonicalAdminFileUniqueId = ChatProtocol.getAdminFileUniqueId(conversationId),
                            canonicalAdminVersionTag = preAdminFile?.fileMetadata?.versionTag,
                            canonicalAdmins = canonicalAdminsForStatus,
                        ),
                    ),
                    recipientOverride = healMessageRecipientsActual,
                )
                healMessageRecipientCount = healMessageRecipientsActual?.size ?: allRecipients.size
                healMessageUniqueIdOut = statusMessageUniqueId
                onPhase?.invoke(HealPhase.HealMessageSent(healMessageAudience))
            } catch (t: Throwable) {
                // Best-effort: don't let a status-send failure block the file pushes.
                Logger.w(t) { "healGroupDistribution: GroupHealRequested status send FAILED for $conversationId" }
                onPhase?.invoke(HealPhase.HealMessageFailed(healMessageAudience, t))
            }
        } else if (!callerIsMainAuthor) {
            Logger.d { "healGroupDistribution: skipping status broadcast — caller is not the original author of main file" }
            onPhase?.invoke(HealPhase.HealMessageSkipped(healMessageAudience, "not-author"))
        } else {
            Logger.d { "healGroupDistribution: skipping status broadcast — heal message recipient set is empty (no peers reported PresentAuthoredByOther)" }
            onPhase?.invoke(HealPhase.HealMessageSkipped(emptyList(), "no-other-author-peers"))
        }

        // Main conversation file
        val mainFile = preMainFile
        if (mainFile == null) {
            Logger.w { "healGroupDistribution: no local main conversation file for $conversationId — skipping main" }
            onPhase?.invoke(HealPhase.MainSkipped(mainTargets, "no-local-file"))
        } else {
            val mainAuthor = mainFile.fileMetadata.originalAuthor ?: mainFile.fileMetadata.senderOdinId
            val mainPending = mainFile.fileMetadata.localAppData?.tags?.contains(ChatProtocol.isPendingSendTag) == true
            Logger.d { "healGroupDistribution: main file fileId=${mainFile.fileId} sender=${mainFile.fileMetadata.senderOdinId} originalAuthor=${mainFile.fileMetadata.originalAuthor} resolvedAuthor=$mainAuthor versionTag=${mainFile.fileMetadata.versionTag} isPending=$mainPending localTags=${mainFile.fileMetadata.localAppData?.tags}" }
            if (mainAuthor != domain) {
                Logger.d { "healGroupDistribution: skipping main — caller is not the original author (author=$mainAuthor, caller=$domain)" }
                onPhase?.invoke(HealPhase.MainSkipped(mainTargets, "not-author"))
            } else if (mainTargets.isEmpty()) {
                Logger.d { "healGroupDistribution: skipping main — no peers reported Missing for the main file" }
                onPhase?.invoke(HealPhase.MainSkipped(emptyList(), "no-missing-peers"))
            } else {
                onPhase?.invoke(HealPhase.MainSending(mainTargets))
                try {
                    Logger.i { "healGroupDistribution: redistributing main conversation file $conversationId to recipients=$mainTargets" }
                    convoOps.updateConversationInternal(
                        conversationId = conversationId,
                        title = conversation.name,
                        participants = conversation.participants,
                        distribute = true,
                        distributeOnlyTo = if (plan?.resendMainTo != null) mainTargets else null,
                        preserveExistingPayloads = true,
                    )
                    mainHealed = true
                    mainRecipientCount = mainTargets.size
                    mainFileIdOut = mainFile.fileId
                    Logger.i { "healGroupDistribution: main conversation file enqueued for redistribute $conversationId fileId=${mainFile.fileId}" }
                    onPhase?.invoke(HealPhase.MainSent(mainTargets))
                } catch (t: Throwable) {
                    Logger.e("healGroupDistribution: main conversation file redistribute FAILED for $conversationId", t)
                    onPhase?.invoke(HealPhase.MainFailed(mainTargets, t))
                    throw t
                }
            }
        }

        // Admin file
        val adminFile = preAdminFile
        if (adminFile == null) {
            Logger.w { "healGroupDistribution: no local admin file for $conversationId — skipping admin" }
            onPhase?.invoke(HealPhase.AdminSkipped(adminTargets, "no-local-file"))
        } else {
            val adminAuthor = adminFile.fileMetadata.originalAuthor ?: adminFile.fileMetadata.senderOdinId
            val adminPending = adminFile.fileMetadata.localAppData?.tags?.contains(ChatProtocol.isPendingSendTag) == true
            Logger.d { "healGroupDistribution: admin file fileId=${adminFile.fileId} sender=${adminFile.fileMetadata.senderOdinId} originalAuthor=${adminFile.fileMetadata.originalAuthor} resolvedAuthor=$adminAuthor versionTag=${adminFile.fileMetadata.versionTag} isPending=$adminPending localTags=${adminFile.fileMetadata.localAppData?.tags}" }
            if (adminAuthor != domain) {
                Logger.d { "healGroupDistribution: skipping admin — caller is not the original author (author=$adminAuthor, caller=$domain)" }
                onPhase?.invoke(HealPhase.AdminSkipped(adminTargets, "not-author"))
            } else if (adminTargets.isEmpty()) {
                Logger.d { "healGroupDistribution: skipping admin — no peers reported Missing for the admin file" }
                onPhase?.invoke(HealPhase.AdminSkipped(emptyList(), "no-missing-peers"))
            } else {
                onPhase?.invoke(HealPhase.AdminSending(adminTargets))
                try {
                    Logger.i { "healGroupDistribution: redistributing admin file $conversationId admins=${conversation.admins} recipients=$adminTargets" }
                    convoOps.updateAdminFile(
                        conversationId = conversationId,
                        admins = conversation.admins.toList(),
                        recipients = adminTargets,
                    )
                    adminHealed = true
                    adminRecipientCount = adminTargets.size
                    adminFileIdOut = adminFile.fileId
                    Logger.i { "healGroupDistribution: admin file enqueued for redistribute $conversationId fileId=${adminFile.fileId}" }
                    onPhase?.invoke(HealPhase.AdminSent(adminTargets))
                } catch (t: Throwable) {
                    Logger.e("healGroupDistribution: admin file redistribute FAILED for $conversationId", t)
                    onPhase?.invoke(HealPhase.AdminFailed(adminTargets, t))
                    throw t
                }
            }
        }

        Logger.i { "healGroupDistribution: DONE conversationId=$conversationId mainHealed=$mainHealed adminHealed=$adminHealed mainRecipients=$mainRecipientCount adminRecipients=$adminRecipientCount healMessageRecipients=$healMessageRecipientCount" }
        // ---- DEBUG instrumentation ----
        audit.info("mainHealed=$mainHealed adminHealed=$adminHealed mainRecipients=$mainRecipientCount adminRecipients=$adminRecipientCount healMessageRecipients=$healMessageRecipientCount")
        audit.check(
            "anythingHealed",
            mainHealed || adminHealed || healMessageRecipientCount > 0,
            "neither main nor admin file was redistributed and no heal message was sent — heal was a no-op (caller not the author of either, or every peer was already PresentAuthoredByMe)",
        )
        audit.finish()
        // ---- end DEBUG ----
        return HealGroupResult(
            mainHealed = mainHealed,
            adminHealed = adminHealed,
            mainRecipientCount = mainRecipientCount,
            adminRecipientCount = adminRecipientCount,
            healMessageRecipientCount = healMessageRecipientCount,
            mainFileId = mainFileIdOut,
            adminFileId = adminFileIdOut,
            healMessageUniqueId = healMessageUniqueIdOut,
            mainTargets = if (mainFileIdOut != null) mainTargets else emptyList(),
            adminTargets = if (adminFileIdOut != null) adminTargets else emptyList(),
            healMessageTargets = if (healMessageUniqueIdOut != null) healMessageAudience else emptyList(),
        )
    }

    /**
     * Receive-side handler for an incoming [StatusMessage.GroupHealRequested]
     * status. Compares the local conversation + admin files against the
     * canonical fields the sender announced; if either is *broken* (mismatched
     * originalAuthor — vt drift alone no longer qualifies) or *missing* (no
     * local row at all), hard-deletes the broken row from our own server
     * (no-op when the file is merely missing), writes a local-only placeholder
     * seeded from the heal payload — title/participants for the main file,
     * [GroupHealInfo.canonicalAdmins] for the admin file when present — so the
     * UI behaves as if nothing was ever broken, and surfaces a local-only
     * [StatusMessage.GroupHealLocalCleanup] status into the conversation so the
     * user sees the cleanup.
     *
     * Two-step protocol:
     *   1. First heal status: receiver cleans up its broken/missing server
     *      state and writes a versionTag=null placeholder. The receiver's
     *      drive on the server is now empty for this conversation.
     *   2. Subsequent peer push (driven by the canonical author's
     *      [healGroupDistribution] → [GroupHealConversationOps.updateConversationInternal] /
     *      [GroupHealConversationOps.updateAdminFile]) lands as a fresh create
     *      on the receiver's drive (no versionTag conflict because the broken
     *      row was hard-deleted), and DriveMainIndex's modified-timestamp
     *      guard passes because the placeholder's `updated` is ZeroTime — so
     *      the placeholder is replaced in-place by the real file.
     *
     * Legacy compatibility: pre-hardening senders set
     * [GroupHealInfo.canonicalAdmins] to null. In that case the admin branch
     * falls back to the old delete-only behaviour (relying on getAdmins()'
     * originalAuthor fallback to keep things functional in the gap). The
     * main-file branch always has enough data to rebuild — title and
     * participants have always been in the payload.
     *
     * Early-return cases (no side effects, message left in chat):
     *   - Sender's domain doesn't match the canonical originalAuthor (forgery
     *     guard).
     *   - The canonical originalAuthor is us (we're seeing our own outgoing
     *     status loop back).
     *   - The local conversation is in Left/Removed/RejoinPending state — we
     *     don't resurrect groups the user has explicitly left.
     *
     * Past these guards, every path (no-op or cleanup) ends in
     * [selfDestructHealMessage] — the status message wipes itself once its
     * job is done.
     */
    suspend fun handleIncomingHealRequest(
        status: StatusMessageData,
        sender: OdinId,
        messageFile: HomebaseFile,
    ) {
        val info = status.groupHeal ?: return
        val audit = MethodAudit("handleIncomingHealRequest")
        audit.start("conversationId=${info.conversationUniqueId} sender=${sender.domainName} canonicalAuthor=${info.canonicalOriginalAuthor.domainName} messageFileId=${messageFile.fileId}")

        // Forgery guard: only the canonical author can announce themselves.
        if (sender != info.canonicalOriginalAuthor) {
            audit.checkFail("authorMatch", "message sender ${sender.domainName} != canonicalOriginalAuthor ${info.canonicalOriginalAuthor.domainName}")
            audit.finish("REJECTED at authorMatch")
            return
        }

        val domain = credentialsManager.requireActiveDomain()
        if (info.canonicalOriginalAuthor == domain) {
            audit.info("self-loopback (canonical author is us) — ignoring")
            audit.finish("self-loopback")
            return
        }

        // Don't heal conversations the user has explicitly left.
        val convo = runCatching { convoOps.getConversation(info.conversationUniqueId) }.getOrNull()
        val state = convo?.conversationState
        if (state == ConversationState.Left
            || state == ConversationState.Removed
            || state == ConversationState.RejoinPending
        ) {
            audit.info("local state=$state — skipping heal for left/removed conversation")
            audit.finish("skipped — left/removed")
            return
        }

        // Capture identifiers we need to self-destruct the heal message once
        // we're done with it. fileId is always present; uniqueId may be null
        // (defensive — every status message we generate has one) and only
        // gates the local-DB soft-delete in step 2.
        val healMessageFileId = messageFile.fileId
        val healMessageUniqueId = messageFile.fileMetadata.appData.uniqueId

        // Step 2 of the self-destruct: soft-delete locally and on the server.
        // Local soft-delete (writeDelete) zeros appData.content, sets
        // FileState.Deleted, and hides the row from the chat UI immediately.
        // The dispatcher in ConversationStream.dispatchGroupHealRequests
        // short-circuits on `content == null`, so the (very unlikely) case
        // of this same status message being re-dispatched naturally no-ops.
        if (healMessageUniqueId != null) {
            audit.step(0, "optimisticWriter.writeDelete(heal message uniqueId=$healMessageUniqueId)")
            optimisticWriter.writeDelete(chatDrive, healMessageUniqueId)
        } else {
            audit.checkWarn("localSoftDeleteSkipped", "heal status message has no uniqueId — relying on server-side soft-delete by fileId")
        }
        audit.step(1, "outboxSync.tryEnqueue(DeleteLocalFilesByFileIdRequest hardDelete=false recipients=null fileIds=[$healMessageFileId])")
        runCatching {
            outboxSync.tryEnqueue(
                DeleteLocalFilesByFileIdRequest(
                    driveId = chatDrive,
                    fileIds = listOf(healMessageFileId),
                    recipients = null,
                    hardDelete = false,
                )
            )
        }.onSuccess { enqueued ->
            if (enqueued) audit.checkPass("healMsgSoftDeleteEnqueue")
            else audit.checkWarn("healMsgSoftDeleteEnqueue", "tryEnqueue returned false")
        }.onFailure { e -> audit.threw("healMsgSoftDeleteEnqueue", e) }

        val mainFile = convoOps.getConversationHomebaseFile(info.conversationUniqueId)
        val adminFile = convoOps.getConversationAdminHomebaseFile(info.conversationUniqueId)

        // Distinguish "missing" from "broken":
        //   missing → no local row at all (post-wipe, never-synced, hard-
        //             deleted on a prior heal pass that happened to lose the
        //             follow-up peer push).
        //   broken  → row exists but originalAuthor diverges from the
        //             canonical identity (vt drift alone is no longer a
        //             corruption signal — see isServerFileBroken).
        // A previous implementation collapsed missing into "not broken"
        // because mainFile==null short-circuited the && isServerFileBroken
        // check, leaving users whose file had vanished from both DB and
        // server-side drive permanently stuck.
        val mainMissing = mainFile == null
        val adminMissing = adminFile == null
        val mainBroken = mainFile != null && isServerFileBroken(
            serverFile = mainFile,
            groupCanonicalAuthor = info.canonicalOriginalAuthor,
        )
        val adminBroken = adminFile != null && isServerFileBroken(
            serverFile = adminFile,
            groupCanonicalAuthor = info.canonicalOriginalAuthor,
        )
        val mainNeedsHeal = mainMissing || mainBroken
        val adminNeedsHeal = adminMissing || adminBroken

        audit.pre(
            "mainFile: exists=${mainFile != null} missing=$mainMissing broken=$mainBroken needsHeal=$mainNeedsHeal " +
                "fileId=${mainFile?.fileId} localAuthor=${mainFile?.fileMetadata?.originalAuthor?.domainName} " +
                "localVT=${mainFile?.fileMetadata?.versionTag} " +
                "canonicalAuthor=${info.canonicalOriginalAuthor.domainName} canonicalVT=${info.canonicalVersionTag}"
        )
        audit.pre(
            "adminFile: exists=${adminFile != null} missing=$adminMissing broken=$adminBroken needsHeal=$adminNeedsHeal " +
                "fileId=${adminFile?.fileId} localAuthor=${adminFile?.fileMetadata?.originalAuthor?.domainName} " +
                "localVT=${adminFile?.fileMetadata?.versionTag} canonicalAdminVT=${info.canonicalAdminVersionTag} " +
                "canonicalAdminsCarried=${info.canonicalAdmins != null} canonicalAdminCount=${info.canonicalAdmins?.size ?: -1}"
        )
        Logger.i {
            "handleIncomingHealRequest: PRE conversationId=${info.conversationUniqueId} sender=${sender.domainName} " +
                "messageFileId=${messageFile.fileId} " +
                "main(missing=$mainMissing broken=$mainBroken needsHeal=$mainNeedsHeal) " +
                "admin(missing=$adminMissing broken=$adminBroken needsHeal=$adminNeedsHeal) " +
                "canonicalParticipants(${info.canonicalParticipants.size})=${info.canonicalParticipants.map { it.domainName }} " +
                "canonicalAdmins=${info.canonicalAdmins?.map { it.domainName }}"
        }

        if (!mainNeedsHeal && !adminNeedsHeal) {
            // !mainNeedsHeal && !adminNeedsHeal ⇒ both files exist (mainMissing
            // and adminMissing are both false) — smart-cast confirms non-null,
            // so no safe-call needed for the audit values below.
            audit.info("nothing to clean up — local copies match canonical identity")
            Logger.i {
                "handleIncomingHealRequest: NO-OP — local main+admin already match canonical " +
                    "conversationId=${info.conversationUniqueId} mainVT=${mainFile.fileMetadata.versionTag} " +
                    "adminVT=${adminFile.fileMetadata.versionTag}"
            }
            // Self-destruct the heal message even on the no-op path — it has
            // done its job (gave us a chance to check) and we don't want it
            // lingering in the chat.
            selfDestructHealMessage(healMessageFileId, audit, step = 2)
            audit.finish("no-op")
            return
        }

        // Hard-delete the broken file(s) on our own server. recipients=null keeps
        // this strictly local (no peer fan-out); hardDelete=true removes the row
        // entirely so the next peer push from the canonical author lands as a
        // fresh create rather than a versionTag conflict.
        //
        // Note: we delete only the *broken* files, not the *missing* ones.
        // A missing file has no local fileId to act on and the server already
        // lacks the row — DeleteLocalFilesByFileIdRequest with no targets is
        // a pointless outbox job. The placeholder-write step below covers
        // both missing and broken cases.
        val brokenFileIds = buildList {
            if (mainBroken) add(mainFile.fileId)
            if (adminBroken) add(adminFile.fileId)
        }
        if (brokenFileIds.isNotEmpty()) {
            Logger.i { "handleIncomingHealRequest: hard-deleting ${brokenFileIds.size} broken file(s) for conversation=${info.conversationUniqueId} mainBroken=$mainBroken adminBroken=$adminBroken fileIds=$brokenFileIds" }
            audit.step(1, "outboxSync.tryEnqueue(DeleteLocalFilesByFileIdRequest hardDelete=true recipients=null fileIds=$brokenFileIds)")
            runCatching {
                outboxSync.tryEnqueue(
                    DeleteLocalFilesByFileIdRequest(
                        driveId = chatDrive,
                        fileIds = brokenFileIds,
                        recipients = null,
                        hardDelete = true,
                    )
                )
            }.onSuccess { enqueued ->
                audit.info("STEP 1 enqueued=$enqueued")
                if (enqueued) audit.checkPass("hardDeleteEnqueue") else audit.checkWarn("hardDeleteEnqueue", "tryEnqueue returned false (UNIQUE collision)")
            }.onFailure { e -> audit.threw("hardDeleteEnqueue", e) }
        } else {
            audit.info("STEP 1 SKIPPED — no broken local files to hard-delete (mainMissing=$mainMissing adminMissing=$adminMissing)")
            Logger.i { "handleIncomingHealRequest: STEP 1 skipped (no broken local files); mainMissing=$mainMissing adminMissing=$adminMissing conversationId=${info.conversationUniqueId}" }
        }

        // STEP 2 — write a local placeholder (versionTag=null) for either the
        // conversation file, the admin file, or both. The placeholder is
        // self-sufficient: title + participants for the main file, admins for
        // the admin file. From the user's perspective the conversation looks
        // healthy immediately. When the canonical author's peer push lands
        // (driven by their healGroupDistribution → updateConversationInternal /
        // updateAdminFile), it replaces the placeholder by uniqueId with the
        // real file (DriveMainIndex's modified-timestamp guard passes because
        // the placeholder's `updated` is ZeroTime).
        if (mainNeedsHeal) {
            val placeholderParticipants = info.canonicalParticipants.distinct()
            val placeholderTitle = info.canonicalTitle
            val brokenMainId = if (mainBroken) mainFile.fileId else null
            Logger.i {
                "handleIncomingHealRequest: STEP 2 main → writeOrReplaceConversationPlaceholder " +
                    "conversationId=${info.conversationUniqueId} brokenFileIdToDelete=$brokenMainId " +
                    "participants(${placeholderParticipants.size})=${placeholderParticipants.map { it.domainName }} title=\"$placeholderTitle\""
            }
            audit.step(2, "writeOrReplaceConversationPlaceholder(participants=${placeholderParticipants.size}, title=\"$placeholderTitle\", brokenFileIdToDelete=$brokenMainId, missing=$mainMissing)")
            runCatching {
                convoOps.writeOrReplaceConversationPlaceholder(
                    conversationId = info.conversationUniqueId,
                    brokenFileIdToDelete = brokenMainId,
                    participants = placeholderParticipants,
                    isGroup = true,
                    title = placeholderTitle,
                    audit = audit,
                )
            }.onFailure { e ->
                Logger.w(e) { "handleIncomingHealRequest: main placeholder write failed for ${info.conversationUniqueId}" }
            }
        }

        if (adminNeedsHeal) {
            val canonicalAdmins = info.canonicalAdmins.orEmpty().distinct()
            val brokenAdminId = if (adminBroken) adminFile.fileId else null
            if (canonicalAdmins.isNotEmpty()) {
                Logger.i {
                    "handleIncomingHealRequest: STEP 2 admin → writeOrReplaceAdminPlaceholder " +
                        "conversationId=${info.conversationUniqueId} brokenFileIdToDelete=$brokenAdminId " +
                        "admins(${canonicalAdmins.size})=${canonicalAdmins.map { it.domainName }} adminMissing=$adminMissing adminBroken=$adminBroken"
                }
                audit.step(2, "writeOrReplaceAdminPlaceholder(admins=${canonicalAdmins.size}, brokenFileIdToDelete=$brokenAdminId, missing=$adminMissing)")
                runCatching {
                    convoOps.writeOrReplaceAdminPlaceholder(
                        conversationId = info.conversationUniqueId,
                        brokenFileIdToDelete = brokenAdminId,
                        admins = canonicalAdmins,
                        audit = audit,
                    )
                }.onFailure { e ->
                    Logger.w(e) { "handleIncomingHealRequest: admin placeholder write failed for ${info.conversationUniqueId}" }
                }
            } else {
                // Legacy sender (canonicalAdmins not in payload) — we can't
                // fully rebuild the admin file. Fall back to the prior
                // behaviour: if the admin row is broken locally, delete it so
                // a future peer push lands cleanly; getAdmins() falls back to
                // originalAuthor in the gap. If the admin file is merely
                // missing there's nothing to do here — a future heal from a
                // hardened sender will carry canonicalAdmins.
                audit.checkWarn(
                    "legacyAdminPayload",
                    "incoming heal payload omits canonicalAdmins (legacy sender) — " +
                        "cannot rebuild admin placeholder; falling back to delete-only path. " +
                        "adminMissing=$adminMissing adminBroken=$adminBroken brokenAdminId=$brokenAdminId"
                )
                Logger.w {
                    "handleIncomingHealRequest: legacy heal payload (no canonicalAdmins) for ${info.conversationUniqueId} — " +
                        "admin placeholder will NOT be rebuilt. adminMissing=$adminMissing adminBroken=$adminBroken brokenAdminId=$brokenAdminId"
                }
                if (adminBroken) {
                    audit.step(2, "deleteBy(local admin row fileId=${adminFile.fileId}) [legacy fallback]")
                    runCatching {
                        dbm.driveMainIndex.deleteBy(
                            identityId = credentialsManager.requireActiveCredentials().getIdentityId(),
                            driveId = chatDrive,
                            fileId = adminFile.fileId,
                        )
                    }.onSuccess { audit.checkPass("deleteBrokenAdminRow") }
                        .onFailure { e -> audit.threw("deleteBrokenAdminRow", e) }
                }
            }
        }

        // Surface the cleanup to the user. Local-only status message — never
        // sent to peers (additionalRecipients stays empty; the conversation
        // recipients list is irrelevant because sendStatusMessage routes the
        // optimistic write through the same path regardless of fan-out).
        audit.step(3, "sendStatusMessage(GroupHealLocalCleanup mainNeedsHeal=$mainNeedsHeal adminNeedsHeal=$adminNeedsHeal)")
        runCatching {
            chatMessageSenderService.sendStatusMessage(
                messageUniqueId = Uuid.random(),
                conversationId = info.conversationUniqueId,
                statusMessage = StatusMessageData(
                    statusMessage = StatusMessage.GroupHealLocalCleanup,
                    groupHealCleanup = GroupHealCleanupInfo(
                        cleanedUpMain = mainNeedsHeal,
                        cleanedUpAdmin = adminNeedsHeal,
                    ),
                ),
            )
            audit.checkPass("cleanupStatusSent")
        }.onFailure { e ->
            audit.threw("cleanupStatusSent", e)
            Logger.w(e) { "handleIncomingHealRequest: failed to write GroupHealLocalCleanup status for ${info.conversationUniqueId}" }
        }

        // Self-destruct the heal message — step 4 of the receive-side
        // protocol. Cleanup is finished; the message has done its job, so
        // wipe it from local DB and enqueue a server-side hard-delete.
        selfDestructHealMessage(healMessageFileId, audit, step = 4)

        Logger.i {
            "handleIncomingHealRequest: DONE conversationId=${info.conversationUniqueId} " +
                "mainNeedsHeal=$mainNeedsHeal (missing=$mainMissing broken=$mainBroken) " +
                "adminNeedsHeal=$adminNeedsHeal (missing=$adminMissing broken=$adminBroken)"
        }
        audit.finish("mainNeedsHeal=$mainNeedsHeal (missing=$mainMissing broken=$mainBroken) adminNeedsHeal=$adminNeedsHeal (missing=$adminMissing broken=$adminBroken)")
    }

    /**
     * Step 4 of the receive-side heal protocol: hard-delete the heal status
     * message from the local DB and enqueue the matching server-side hard-
     * delete. Pairs with the step-2 soft-delete written at the top of
     * [handleIncomingHealRequest] — together they erase the heal message so
     * it doesn't pollute the chat after its work is done.
     *
     * recipients=null keeps the outbox delete local to our own server (no
     * peer fan-out). The step-2 server soft-delete and this step-4 server
     * hard-delete each occupy their own outbox row (DeleteLocalFilesByFileId
     * uses a random uniqueId per request — see OutboxSync), so they coexist
     * and drain in insertion order.
     */
    private suspend fun selfDestructHealMessage(
        healMessageFileId: Uuid,
        audit: MethodAudit,
        step: Int,
    ) {
        val identityId = credentialsManager.requireActiveCredentials().getIdentityId()
        audit.step(step, "dbm.driveMainIndex.deleteBy(identityId, chatDrive, fileId=$healMessageFileId)")
        runCatching {
            dbm.driveMainIndex.deleteBy(identityId, chatDrive, healMessageFileId)
        }.onSuccess { deleted ->
            if (deleted) audit.checkPass("healMsgLocalHardDelete")
            else audit.checkWarn("healMsgLocalHardDelete", "deleteBy returned false — row already gone")
        }.onFailure { e -> audit.threw("healMsgLocalHardDelete", e) }

        audit.step(step + 1, "outboxSync.tryEnqueue(DeleteLocalFilesByFileIdRequest hardDelete=true recipients=null fileIds=[$healMessageFileId])")
        runCatching {
            outboxSync.tryEnqueue(
                DeleteLocalFilesByFileIdRequest(
                    driveId = chatDrive,
                    fileIds = listOf(healMessageFileId),
                    recipients = null,
                    hardDelete = true,
                )
            )
        }.onSuccess { enqueued ->
            if (enqueued) audit.checkPass("healMsgHardDeleteEnqueue")
            else audit.checkWarn("healMsgHardDeleteEnqueue", "tryEnqueue returned false")
        }.onFailure { e -> audit.threw("healMsgHardDeleteEnqueue", e) }
    }

    // Checks if the (group) file(s) on the server is broken
    private fun isServerFileBroken(
        serverFile: HomebaseFile,
        groupCanonicalAuthor: OdinId,
    ): Boolean {
        // VersionTag is deliberately NOT consulted
        val localAuthor = serverFile.fileMetadata.originalAuthor ?: serverFile.fileMetadata.senderOdinId
        return localAuthor != groupCanonicalAuthor
    }
}
