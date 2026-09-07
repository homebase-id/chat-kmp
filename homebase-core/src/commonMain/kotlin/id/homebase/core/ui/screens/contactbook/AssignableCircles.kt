package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.connections.CircleGrantOn
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.chat.services.convo.contact.CircleMembershipState
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.CONFIRMED_CONNECTIONS_CIRCLE_ID
import id.homebase.core.ui.screens.contactbook.detail.ContactCircleUi

/**
 * True for a circle whose owning app enrols members itself, rather than one the user curates.
 * Surfaced through the connection status, never offered as an assignable circle.
 *
 * The two legacy ids are still needed after odin-core #1688: they are owned by no app and stay at
 * [CircleGrantOn.None], and a server below the migration reports None for every circle anyway.
 */
fun RedactedCircleDefinition.isAppDefaultCircle(): Boolean =
    grantOn != CircleGrantOn.None || isLegacySystemCircleId(id)

/** Case-insensitive — nothing guarantees the server returns these ids in a stable casing. */
fun isLegacySystemCircleId(id: String): Boolean =
    id.equals(CONFIRMED_CONNECTIONS_CIRCLE_ID, ignoreCase = true) ||
        id.equals(AUTO_CONNECTIONS_CIRCLE_ID, ignoreCase = true)

/**
 * True for a circle the user can add someone to by hand: a personal circle with no enrolment of
 * its own. Audience and vendor circles belong to the app that owns them and are invisible here —
 * a feed subscriber or a bank must never render as a contact's circle.
 *
 * Narrower than [isPersonalCircle], which also counts review-granted circles toward
 * [ContactState.Circle]; those are enrolled through the review, not picked from a list.
 */
fun RedactedCircleDefinition.isUserCircle(): Boolean =
    isPersonalCircle() && grantOn == CircleGrantOn.None

/**
 * Every user circle the signed-in user could add a contact to — independent of any contact's
 * membership. Unnamed circles are excluded; the result is deduped by id and sorted A–Z.
 *
 * Feeds the accept-with-circles picker on both incoming-request surfaces (contact detail's
 * [id.homebase.core.ui.screens.contactbook.detail.PendingRequestProfile] and the Add Contact
 * flow), so both offer the same list.
 */
fun CircleMembershipState.assignableCircles(): List<ContactCircleUi> =
    circles
        .map { it.circle }
        .filter { it.isUserCircle() }
        .filter { it.name.isNotBlank() }
        .map { ContactCircleUi(it.id, it.name, pending = false, emoji = it.emoji) }
        .distinctBy { it.id.lowercase() }
        .sortedBy { it.name.lowercase() }
