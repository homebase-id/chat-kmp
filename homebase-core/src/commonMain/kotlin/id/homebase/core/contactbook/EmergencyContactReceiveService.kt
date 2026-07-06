@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.contactbook

import co.touchlab.kermit.Logger
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.common.OdinId
import id.homebase.api.crypto.Md5
import id.homebase.api.sync.database.OutboxSync
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.chatTargetDrive
import kotlin.uuid.ExperimentalUuidApi

/**
 * Receive-side handling of emergency-contact designations / revocations — the mirror of
 * [id.homebase.core.location.EmergencyCircleNotifier]'s send side. When a peer adds us to (or removes
 * us from) their emergency circle they post us a status message; here we set/clear our `iCanLocate`
 * flag for them and then CONSUME the message so a re-delivery can't re-apply a stale designation
 * after a revoke (or vice-versa).
 *
 * Consume = soft-delete on our OWN identity only (local + server, recipients = null), mirroring
 * [id.homebase.chat.services.convo.GroupHealService]. The ConversationStream dispatcher short-circuits
 * on `content == null`, so a consumed message re-dispatches as a no-op. The flag is the cheap cache;
 * the authoritative backstop is a temporal-access preflight (reconcile, step 8).
 */
class EmergencyContactReceiveService(
    private val contactRepository: ContactRepository,
    private val optimisticWriter: OptimisticWriter,
    private val outboxSync: OutboxSync,
) {
    private val chatDrive = chatTargetDrive.alias

    /** The [sender] designated us — record that we can locate them, then consume the message. */
    suspend fun onDesignated(sender: OdinId, messageFile: HomebaseFile) {
        try {
            contactRepository.ensureLoaded()
            val uniqueId = Md5.toGuidId(sender.domainName)
            val contact = contactRepository.contacts.value.firstOrNull { it.uniqueId == uniqueId }
            val versionTag = contact?.versionTag
            when (designationAction(contact != null, contact?.iCanLocate() == true, versionTag != null)) {
                DesignationAction.SyncOnly -> contactRepository.sync(sender)
                DesignationAction.Consume -> consume(messageFile)
                DesignationAction.SetThenConsume -> {
                    contactRepository.setICanLocate(uniqueId, versionTag!!)
                    consume(messageFile)
                }
                DesignationAction.Ignore -> Unit
            }
        } catch (e: ForbiddenException) {
            Logger.w(e) { "emergency designation: missing ManageContacts permission" }
        } catch (e: Exception) {
            Logger.w(e) { "emergency designation handling failed for ${sender.domainName}" }
        }
    }

    /** The [sender] revoked us — clear that we can locate them, then consume the message. */
    suspend fun onRevoked(sender: OdinId, messageFile: HomebaseFile) {
        try {
            contactRepository.ensureLoaded()
            val uniqueId = Md5.toGuidId(sender.domainName)
            val contact = contactRepository.contacts.value.firstOrNull { it.uniqueId == uniqueId }
            val versionTag = contact?.versionTag
            when (revocationAction(contact != null, contact?.iCanLocate() == true, versionTag != null)) {
                RevocationAction.Consume -> consume(messageFile)
                RevocationAction.ClearThenConsume -> {
                    contactRepository.clearICanLocate(uniqueId, versionTag!!)
                    consume(messageFile)
                }
                RevocationAction.Ignore -> Unit
            }
        } catch (e: ForbiddenException) {
            Logger.w(e) { "emergency revocation: missing ManageContacts permission" }
        } catch (e: Exception) {
            Logger.w(e) { "emergency revocation handling failed for ${sender.domainName}" }
        }
    }

    /**
     * Soft-deletes the status message on our own identity (local write + server, recipients = null)
     * so the ConversationStream dispatcher's `content ?: continue` guard no-ops any re-delivery.
     * Best-effort — a failure just means a re-delivery might re-run an already-idempotent handler.
     */
    private suspend fun consume(messageFile: HomebaseFile) {
        messageFile.fileMetadata.appData.uniqueId?.let { uniqueId ->
            runCatching { optimisticWriter.writeDelete(chatDrive, uniqueId) }
                .onFailure { Logger.w(it) { "emergency consume: local soft-delete failed" } }
        }
        runCatching {
            outboxSync.tryEnqueue(
                DeleteLocalFilesByFileIdRequest(
                    driveId = chatDrive,
                    fileIds = listOf(messageFile.fileId),
                    recipients = null,
                    hardDelete = false,
                )
            )
        }.onFailure { Logger.w(it) { "emergency consume: server soft-delete enqueue failed" } }
    }
}

internal enum class DesignationAction { SyncOnly, Consume, SetThenConsume, Ignore }

/**
 * What to do for an incoming designation. The replay guard's key rule: when the sender isn't a
 * contact yet we sync but do NOT consume, so the flag still gets applied on a later delivery once the
 * row exists. Once set (or already set) we consume to neutralise re-deliveries.
 */
internal fun designationAction(
    contactExists: Boolean,
    alreadyICanLocate: Boolean,
    hasVersionTag: Boolean,
): DesignationAction = when {
    !contactExists -> DesignationAction.SyncOnly
    alreadyICanLocate -> DesignationAction.Consume
    hasVersionTag -> DesignationAction.SetThenConsume
    else -> DesignationAction.Ignore
}

internal enum class RevocationAction { Consume, ClearThenConsume, Ignore }

/**
 * What to do for an incoming revocation. Nothing to clear (not a contact, or already clear) → just
 * consume; otherwise clear the flag then consume. Only blocked when the row exists and is flagged but
 * has no versionTag to write against.
 */
internal fun revocationAction(
    contactExists: Boolean,
    currentlyICanLocate: Boolean,
    hasVersionTag: Boolean,
): RevocationAction = when {
    !contactExists || !currentlyICanLocate -> RevocationAction.Consume
    hasVersionTag -> RevocationAction.ClearThenConsume
    else -> RevocationAction.Ignore
}
