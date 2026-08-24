package id.homebase.core.ui.screens.contactbook

import id.homebase.chat.services.convo.contact.CircleMembershipState
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.CONFIRMED_CONNECTIONS_CIRCLE_ID
import id.homebase.core.ui.screens.contactbook.detail.ContactCircleUi

/** True for the server-managed system circles, which are surfaced through the connection status
 *  rather than as user-assignable circles. */
fun isSystemCircle(id: String): Boolean =
    id.equals(CONFIRMED_CONNECTIONS_CIRCLE_ID, ignoreCase = true) ||
        id.equals(AUTO_CONNECTIONS_CIRCLE_ID, ignoreCase = true)

/**
 * Every user-defined circle the signed-in user could add a contact to — independent of any
 * contact's membership. Disabled circles, the system circles, and unnamed circles are excluded;
 * the result is deduped by id and sorted A–Z.
 *
 * Feeds the accept-with-circles picker on both incoming-request surfaces (contact detail's
 * [id.homebase.core.ui.screens.contactbook.detail.PendingRequestProfile] and the Add Contact
 * flow), so both offer the same list.
 */
fun CircleMembershipState.assignableCircles(): List<ContactCircleUi> =
    circles
        .map { it.circle }
        .filterNot { it.disabled || isSystemCircle(it.id) }
        .filter { it.name.isNotBlank() }
        .map { ContactCircleUi(it.id, it.name, pending = false) }
        .distinctBy { it.id.lowercase() }
        .sortedBy { it.name.lowercase() }
