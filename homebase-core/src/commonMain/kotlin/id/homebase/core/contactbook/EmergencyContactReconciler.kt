@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.contactbook

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.client.peer.temporal.TemporalDriveReadProvider
import id.homebase.api.common.OdinId
import id.homebase.core.config.locationLabeledDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi

/**
 * Recovers a missed "they added me" designation — the set-only background backstop for the
 * best-effort status-message path ([EmergencyContactReceiveService]). The live status message
 * only fires on the WS-push path, so a designation that arrives during cold sync (offline →
 * login catch-up) or in a dropped event never sets the flag. Here we re-derive it by
 * preflighting the peer's location drive with
 * [TemporalDriveReadProvider.verifyTemporalAccess] (reads no data, fires no notification on the
 * peer).
 *
 * Deliberately SET-only (issue #961): a non-throwing `hasAccess = false` is NOT a trustworthy
 * revocation signal — it also fires on benign/ambiguous negatives (and has been observed after
 * the owner emptied their own *outgoing* emergency circle), so a verify-based clear silently
 * wiped the incoming "Who you can locate" list. The only path that clears `iCanLocate` is the
 * peer's explicit revocation message ([EmergencyContactReceiveService.onRevoked]); a stale flag
 * surfaces non-destructively as a broken row via the per-entry freshness check instead.
 *
 * A network/parse failure is inconclusive — we leave the flag untouched rather than flip on a
 * guess.
 *
 * Limitation: only contacts we already hold can be reconciled (a peer who added us but isn't in
 * our contact book yet has no row to flag — the status message remains the discovery path for
 * those).
 */
class EmergencyContactReconciler(
    private val contactRepository: ContactRepository,
    private val temporalRead: TemporalDriveReadProvider,
    private val credentialsManager: CredentialsManager,
    private val scope: CoroutineScope,
) {
    private val locationDrive = locationLabeledDrive.drive.alias

    /** Fire-and-forget the full set-only reconcile in the background (called once at login). */
    fun start() {
        scope.launch { runCatching { reconcileAll() } }
    }

    /**
     * Set-only pass over identity-backed contacts: recovers a designation the live status-message
     * path missed. One temporal-access preflight per unflagged identity contact.
     */
    suspend fun reconcileAll() {
        contactRepository.ensureLoaded()
        for (contact in contactRepository.contacts.value) {
            reconcileContact(contact)
        }
    }

    private suspend fun reconcileContact(contact: Contact) {
        val odinId = contact.content.odinId?.takeIf { it.isNotBlank() }?.let { OdinId(it) } ?: return
        val versionTag = contact.versionTag ?: return
        // Set-only: an already-flagged contact has nothing to recover — skip the preflight entirely.
        if (contact.iCanLocate()) return
        // Never flag a self-contact row (issue #982): skip the preflight for our own identity rather
        // than trusting the VM's self filter to keep it out of the rendered list.
        if (credentialsManager.isSelf(odinId)) return

        val status = runCatching { temporalRead.verifyTemporalAccess(odinId, locationDrive) }
            .getOrNull()
        if (reconcileAction(status?.hasAccess, flagged = false) == ReconcileAction.Set) {
            runCatching { contactRepository.setICanLocate(contact.uniqueId, versionTag) }
                .onFailure { Logger.w(it) { "reconcile: setICanLocate failed for ${odinId.domainName}" } }
        }
    }
}

/** What a verify-based reconcile pass may do to a contact's [iCanLocate] flag. */
enum class ReconcileAction { Set, None }

/**
 * Decides what a verify-based pass does with one contact, given the preflight outcome.
 * [hasAccess] is null when the preflight threw (network/parse failure) — inconclusive, never act.
 *
 * There is deliberately NO Clear (issue #961): a non-throwing `hasAccess = false` is not a
 * trustworthy revocation, so verify-based passes must never wipe the flag. The only clear path
 * is the peer's explicit revocation ([EmergencyContactReceiveService.onRevoked] via
 * [revocationAction]).
 *
 * Emergency designation == currently holding temporal read access to the peer's location drive.
 * windowSeconds is NOT a reliable ACL-type discriminator — a real emergency-circle grant has
 * been observed reporting windowSeconds=null in practice (see issue #875) — so gate on
 * hasAccess alone.
 */
fun reconcileAction(hasAccess: Boolean?, flagged: Boolean): ReconcileAction =
    if (hasAccess == true && !flagged) ReconcileAction.Set else ReconcileAction.None
