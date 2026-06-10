package id.homebase.chat.services

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.PayloadDeleteKey
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.common.OdinId
import id.homebase.api.crypto.ByteArrayUtil
import kotlin.uuid.Uuid

/**
 * Outcome of [ChatMessageSenderService.resendMessage] — retrying a message
 * whose send permanently failed (orange **!** / [ChatProtocol.isFailedSendTag]).
 */
sealed interface ResendOutcome {
    /** Server had no file for the uniqueId — a fresh create was enqueued. */
    data object EnqueuedCreate : ResendOutcome

    /** Server already has the file (a later edit failed) — an update against the server's versionTag was enqueued. */
    data object EnqueuedUpdate : ResendOutcome

    /** An outbox row for the message already exists — nothing to do, the queued send covers it. */
    data object AlreadyQueued : ResendOutcome

    /**
     * The message has media payloads whose encrypted bytes are gone (payload
     * cache evicted and not on the server). Nothing was enqueued — resending
     * without the media would silently turn it into a text-only message.
     */
    data object UnrecoverableMedia : ResendOutcome

    /** The retry could not be attempted (message gone, server unreachable, enqueue refused). */
    data class Failed(val error: Throwable? = null) : ResendOutcome
}

enum class RetryRecoverability { Create, Update, UnrecoverableMedia }

/**
 * Pure decision matrix for [ChatMessageSenderService.resendMessage]:
 * - server has the file → [RetryRecoverability.Update] (payload bytes are
 *   re-fetchable from the server regardless of the local cache);
 * - server absent and every media payload's bytes are locally available →
 *   [RetryRecoverability.Create];
 * - server absent with any media payload's bytes missing →
 *   [RetryRecoverability.UnrecoverableMedia] (refuse — no silent text-only send).
 */
fun retryRecoverability(
    serverPresent: Boolean,
    mediaKeys: Set<String>,
    availableMediaKeys: Set<String>,
): RetryRecoverability = when {
    serverPresent -> RetryRecoverability.Update
    mediaKeys.all { it in availableMediaKeys } -> RetryRecoverability.Create
    else -> RetryRecoverability.UnrecoverableMedia
}

/**
 * Local-tag swap for a retried message: drop [ChatProtocol.isFailedSendTag],
 * ensure [ChatProtocol.isPendingSendTag] — the exact inverse of
 * `ChatMessageStream.markSendFailed`, so the bubble shows *Sending* again.
 * Idempotent: a list already in the pending state is returned unchanged.
 */
fun tagsForRetry(existing: List<Uuid>): List<Uuid> {
    val withoutFailed = existing.filterNot { it == ChatProtocol.isFailedSendTag }
    return if (ChatProtocol.isPendingSendTag in withoutFailed) {
        withoutFailed
    } else {
        withoutFailed + ChatProtocol.isPendingSendTag
    }
}

/**
 * Builds the update request for retrying a message the server already has
 * (a later edit's update failed). Mirrors `ChatMessageSenderService.updateMessage`'s
 * update branch and `DriveOutboxUploader.retryAsUpdate`:
 * [unencryptedMetadata] must carry the SERVER's current versionTag (not the
 * stale local one), and [keyHeader] a fresh iv with the file's original aesKey
 * so the recovered pre-encrypted payloads stay decryptable.
 * `generatePayloadIv = true` keeps each pre-encrypted payload's own iv and
 * mints one only for raw payloads (the rebuilt text overflow).
 */
internal suspend fun buildResendUpdateRequest(
    driveId: Uuid,
    messageId: Uuid,
    keyHeader: KeyHeader,
    unencryptedMetadata: UploadFileMetadata,
    recipients: List<OdinId>,
    payloads: List<PayloadFile>,
    toDeletePayloads: List<PayloadDeleteKey>,
    thumbnails: List<ThumbnailFile>,
): UpdateFileByUniqueIdRequest = UpdateFileByUniqueIdRequest(
    driveId = driveId,
    uniqueId = messageId,
    keyHeader = keyHeader,
    instructions = FileUpdateInstructionSet(
        transferIv = ByteArrayUtil.getRndByteArray(16),
        locale = UpdateLocale.Local,
        recipients = recipients,
        manifest = UpdateManifest.build(
            payloads = payloads,
            toDeletePayloads = toDeletePayloads,
            thumbnails = thumbnails.ifEmpty { null },
            generatePayloadIv = true,
        ),
        useAppNotification = false,
        appNotificationOptions = null,
    ),
    metadata = unencryptedMetadata.encryptContent(keyHeader),
    payloads = payloads,
    thumbnails = thumbnails,
)
