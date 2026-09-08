@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.contactbook

import co.touchlab.kermit.Logger
import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.common.OdinId
import id.homebase.core.auth.AuthConnectionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * Recovers a missed "they added me" designation — the set-only background backstop for the
 * best-effort status-message path ([EmergencyContactReceiveService]). The live status message
 * only fires on the WS-push path, so a designation that arrives during cold sync (offline →
 * login catch-up) or in a dropped event never sets the flag. Here we re-derive it by
 * preflighting the peer's location drive through [EmergencyContactService] (reads no data,
 * fires no notification on the peer).
 *
 * Deliberately SET-only (issue #961): a non-throwing `hasAccess = false` is NOT a trustworthy
 * revocation signal — it also fires on benign/ambiguous negatives (and has been observed after
 * the owner emptied their own *outgoing* emergency circle), so a verify-based clear silently
 * wiped the incoming "Who you can locate" list. The only path that clears `iCanLocate` is the
 * peer's explicit revocation message ([EmergencyContactReceiveService.onRevoked]); a stale flag
 * surfaces non-destructively as a broken row via the per-entry freshness check instead.
 *
 * Limitation: only contacts we already hold can be reconciled (a peer who added us but isn't in
 * our contact book yet has no row to flag — the status message remains the discovery path for
 * those).
 */
class EmergencyContactReconciler(
    private val contactRepository: ContactRepository,
    private val emergencyContacts: EmergencyContactService,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
    private val scope: CoroutineScope,
) {
    /** Fire-and-forget the full set-only reconcile in the background (called once at login). */
    fun start() {
        scope.launch {
            runCatching { reconcileAll() }
                .onFailure { Logger.w(it, TAG) { "reconcileAll failed" } }
        }
    }

    /**
     * Flagged contacts first (their freshness feeds the stale-signal badge), then the set-only
     * pass over unflagged identity contacts. One online wait up front; each verify then fails
     * fast instead of waiting per contact.
     */
    suspend fun reconcileAll() {
        contactRepository.ensureLoaded()
        withTimeoutOrNull(LOCATE_VERIFY_ONLINE_WAIT_MS) {
            authConnectionCoordinator.isOnline.first { it }
        }
        val contacts = contactRepository.contacts.value
        Logger.i(TAG) { "reconcileAll: contacts=${contacts.size} flagged=${contacts.count { it.iCanLocate() }}" }
        emergencyContacts.refreshAll()
        for (contact in contacts) {
            reconcileContact(contact)
        }
        Logger.i(TAG) { "reconcileAll: done" }
    }

    private suspend fun reconcileContact(contact: Contact) {
        val odinId = contact.content.odinId?.takeIf { it.isNotBlank() }?.let { OdinId(it) } ?: return
        val versionTag = contact.versionTag ?: return
        // Set-only: an already-flagged contact has nothing to recover — skip the preflight entirely.
        if (contact.iCanLocate()) return

        val hasAccess = when (emergencyContacts.refresh(odinId, waitForOnline = false)) {
            is LocateVerifyStatus.Active -> true
            is LocateVerifyStatus.Broken -> false
            else -> null
        }
        if (reconcileAction(hasAccess, flagged = false) == ReconcileAction.Set) {
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

private const val TAG = "EmergencyContactReconciler"
