package id.homebase.core.location

import co.touchlab.kermit.Logger
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.core.config.EMERGENCY_LOCATION_CIRCLE_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Watches our emergency-location-access circle ([EMERGENCY_LOCATION_CIRCLE_ID]) and tells each peer
 * when their membership changes, so their app can learn it can — or can no longer — locate us.
 *
 * The circle lives on our OWN identity; we grant/revoke it in the owner console (off-app), and the
 * only app-side signal is the membership refresh [ConnectionService] performs on the
 * `ConnectionChanged` websocket event. So rather than react to raw events (which "fan out to every
 * session and echo our own mutations" — see ConnectionService), we diff the *member set*: a member
 * appearing is a grant, disappearing is a revoke. The set transitions once per change, so a burst of
 * echoed events collapses into a single notification.
 *
 * Best-effort and idempotent on the receiver — the designation/revocation status is the cheap cache;
 * the authoritative check is a temporal-access preflight.
 */
class EmergencyCircleNotifier(
    private val connectionService: ConnectionService,
    private val conversationService: ConversationService,
    private val scope: CoroutineScope,
) {
    private var started = false

    // Last known member set for the current session; null = not yet seeded (don't notify until we
    // have a baseline, else every member would be re-notified on login). Confined to the single
    // collector coroutine below, so no synchronization is needed.
    private var known: Set<String>? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            connectionService.circles.collect { state ->
                // reset() (logout) drops circles to isLoaded=false; refresh() always lands on
                // isLoaded=true. A non-loaded state means "no baseline" — clear it and re-seed from
                // the next loaded state, which keeps us correct across identity switches.
                if (!state.isLoaded) {
                    known = null
                    return@collect
                }
                reconcile(state.membersOf(EMERGENCY_LOCATION_CIRCLE_ID))
            }
        }
    }

    private fun reconcile(current: Set<String>) {
        val previous = known
        known = current
        val delta = emergencyMembershipDelta(previous, current)

        delta.granted.forEach { domain ->
            Logger.i { "EmergencyCircleNotifier: granted $domain — sending designation" }
            scope.launch { conversationService.sendEmergencyContactDesignation(OdinId(domain)) }
        }
        delta.revoked.forEach { domain ->
            Logger.i { "EmergencyCircleNotifier: revoked $domain — sending revocation" }
            scope.launch { conversationService.sendEmergencyContactRevocation(OdinId(domain)) }
        }
    }
}

/** Membership change between two snapshots of the emergency circle. */
internal data class EmergencyMembershipDelta(
    val granted: Set<String>,
    val revoked: Set<String>,
)

/**
 * Diffs [current] circle membership against the [previous] known set. A null [previous] means we have
 * no baseline yet (first loaded state of the session) and yields an EMPTY delta — so we never
 * re-notify the whole circle on login. An unchanged set yields empty granted/revoked sets.
 */
internal fun emergencyMembershipDelta(previous: Set<String>?, current: Set<String>): EmergencyMembershipDelta =
    if (previous == null) EmergencyMembershipDelta(emptySet(), emptySet())
    else EmergencyMembershipDelta(granted = current - previous, revoked = previous - current)
