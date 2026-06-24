@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.contactbook

import co.touchlab.kermit.Logger
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.client.peer.temporal.TemporalDriveReadProvider
import id.homebase.api.common.OdinId
import id.homebase.core.config.locationLabeledDrive
import kotlin.uuid.ExperimentalUuidApi

/**
 * Reconciles the cached `iCanLocate` flag against the authoritative grant. The flag is set/cleared by
 * best-effort status messages ([EmergencyContactReceiveService]); if a revocation is lost, the flag
 * is left wrongly `true` and we'd claim we can locate someone we can't. This corrects that: for each
 * contact we think we can locate, preflight the peer's location drive with
 * [TemporalDriveReadProvider.verifyTemporalAccess] (reads no data, fires no notification on the peer)
 * and clear the flag when the grant is gone.
 *
 * Scope: clears STALE flags only. A lost *designation* (grant exists but the flag never got set)
 * isn't recovered here — that would require preflighting every connected contact on each open. The
 * message + its re-delivery remain the path for the true→ direction; the read itself verifies access
 * at locate time.
 */
class EmergencyContactReconciler(
    private val contactRepository: ContactRepository,
    private val temporalRead: TemporalDriveReadProvider,
) {
    private val locationDrive = locationLabeledDrive.drive.alias

    /** Verify each can-locate contact still grants us access; clear the flag where it doesn't. */
    suspend fun reconcile() {
        contactRepository.ensureLoaded()
        val flagged = contactRepository.contacts.value.filter { it.iCanLocate() }
        for (contact in flagged) {
            val odinId = contact.content.odinId?.takeIf { it.isNotBlank() }?.let { OdinId(it) } ?: continue
            val versionTag = contact.versionTag ?: continue
            // A network/parse failure is inconclusive — leave the cache untouched rather than clear
            // a flag that may still be valid.
            val status = runCatching { temporalRead.verifyTemporalAccess(odinId, locationDrive) }
                .getOrNull() ?: continue
            if (!status.hasAccess) {
                runCatching { contactRepository.clearICanLocate(contact.uniqueId, versionTag) }
                    .onFailure { Logger.w(it) { "reconcile: clearICanLocate failed for ${odinId.domainName}" } }
            }
        }
    }
}
