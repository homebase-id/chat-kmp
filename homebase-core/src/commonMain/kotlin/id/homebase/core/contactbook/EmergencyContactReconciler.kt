@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.contactbook

import co.touchlab.kermit.Logger
import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.client.peer.temporal.TemporalDriveReadProvider
import id.homebase.api.common.OdinId
import id.homebase.core.config.locationLabeledDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi

/**
 * Reconciles the cached `iCanLocate` flag against the authoritative grant — the background backstop
 * for the best-effort status-message path ([EmergencyContactReceiveService]). The live status
 * message only fires on the WS-push path, so a designation/revocation that arrives during cold sync
 * (offline → login catch-up) or in a dropped event never updates the flag. Here we re-derive the
 * truth by preflighting the peer's location drive with
 * [TemporalDriveReadProvider.verifyTemporalAccess] (reads no data, fires no notification on the peer).
 *
 * Two entry points by cost:
 * - [reconcileAll] — full two-way pass over identity contacts: SET the flag where we now have access
 *   (recovers a missed "they added me") and CLEAR it where we don't (recovers a missed "they removed
 *   me"). One preflight per identity contact; run in the background at login via [start], NOT per
 *   screen open.
 * - [reconcile] — cheap clear-only pass over already-flagged contacts. Safe to call on screen open.
 *
 * A network/parse failure is inconclusive — we leave the flag untouched rather than flip on a guess.
 *
 * Limitation: only contacts we already hold can be reconciled (a peer who added us but isn't in our
 * contact book yet has no row to flag — the status message remains the discovery path for those).
 */
class EmergencyContactReconciler(
    private val contactRepository: ContactRepository,
    private val temporalRead: TemporalDriveReadProvider,
    private val scope: CoroutineScope,
) {
    private val locationDrive = locationLabeledDrive.drive.alias

    /** Fire-and-forget the full two-way reconcile in the background (called once at login). */
    fun start() {
        scope.launch { runCatching { reconcileAll() } }
    }

    /**
     * Full two-way reconcile over identity-backed contacts. Recovers both directions the live
     * status-message path can miss. One temporal-access preflight per identity contact.
     */
    suspend fun reconcileAll() {
        contactRepository.ensureLoaded()
        for (contact in contactRepository.contacts.value) {
            reconcileContact(contact, allowSet = true)
        }
    }

    /** Cheap clear-only pass: verify each can-locate contact still grants us access, clear if not. */
    suspend fun reconcile() {
        contactRepository.ensureLoaded()
        for (contact in contactRepository.contacts.value.filter { it.iCanLocate() }) {
            reconcileContact(contact, allowSet = false)
        }
    }

    /**
     * Aligns one contact's [iCanLocate] flag with the authoritative preflight. When [allowSet] is
     * false this is clear-only and skips contacts that aren't flagged (so it never preflights — and
     * never pays for — a contact there's nothing to clear on).
     */
    private suspend fun reconcileContact(contact: Contact, allowSet: Boolean) {
        val odinId = contact.content.odinId?.takeIf { it.isNotBlank() }?.let { OdinId(it) } ?: return
        val versionTag = contact.versionTag ?: return
        val flagged = contact.iCanLocate()
        // Clear-only mode has nothing to do for an unflagged contact — skip the preflight entirely.
        if (!flagged && !allowSet) return

        val status = runCatching { temporalRead.verifyTemporalAccess(odinId, locationDrive) }
            .getOrNull() ?: return
        // Emergency designation == a time-clamped grant (ConditionalTemporalRead), NOT plain full
        // read — which also reports hasAccess=true. Gate on isTimeWindowed so a contact whose
        // location drive we can merely read isn't mistaken for an emergency contact.
        when {
            status.isTimeWindowed && !flagged && allowSet ->
                runCatching { contactRepository.setICanLocate(contact.uniqueId, versionTag) }
                    .onFailure { Logger.w(it) { "reconcile: setICanLocate failed for ${odinId.domainName}" } }

            !status.isTimeWindowed && flagged ->
                runCatching { contactRepository.clearICanLocate(contact.uniqueId, versionTag) }
                    .onFailure { Logger.w(it) { "reconcile: clearICanLocate failed for ${odinId.domainName}" } }
        }
    }
}
