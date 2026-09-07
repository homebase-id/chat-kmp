package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.connections.CircleDesignation
import id.homebase.api.client.connections.CircleGrantOn
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration
import id.homebase.chat.services.convo.contact.CircleMembershipState

/**
 * What a connection has been granted, as the contact book shows it.
 *
 * The ladder measures read access — what they can see of you. Write grants let people give you
 * things (a chat message, a receipt) and carry no intimacy, which is why a deposit-only vendor
 * sits at [Chat] without contradiction.
 */
enum class ContactState {
    /** Connected but never reviewed. Where every introduction auto-accept lands. */
    New,

    /** Reviewed and deliberately granted nothing further — they see the public profile only. */
    Chat,

    /** In at least one personal circle, which implies reviewed. */
    Circle,
}

/**
 * True for a circle whose membership makes a contact [ContactState.Circle].
 *
 * Personal designation, minus the circles nobody chose: ambient enrolment ([CircleGrantOn.Connect],
 * [CircleGrantOn.OwnFlowConnect]) happens without the owner present, and the two legacy system
 * circles are auto-connect carriers. A [CircleGrantOn.Review] circle does count — it is granted by
 * the owner's own review, which is exactly the act this state reports.
 *
 * Also the "can be assigned by hand" set: a review circle is chosen by the owner, so nothing
 * stops it being chosen from a picker too.
 */
fun RedactedCircleDefinition.isPersonalCircle(): Boolean =
    !disabled &&
        designation == CircleDesignation.Personal &&
        grantOn != CircleGrantOn.Connect &&
        grantOn != CircleGrantOn.OwnFlowConnect &&
        !isLegacySystemCircleId(id)

/**
 * True for a circle nobody chose to join: enrolment happens without the owner present. Not
 * hand-assignable — adding or removing a member by hand means nothing when the app re-enrols them.
 *
 * The legacy pair are here because they are auto-connect carriers, and odin-core leaves them at
 * [CircleGrantOn.None] owned by no app, so nothing derivable identifies them.
 */
fun RedactedCircleDefinition.isAmbientCircle(): Boolean =
    grantOn == CircleGrantOn.Connect ||
        grantOn == CircleGrantOn.OwnFlowConnect ||
        isLegacySystemCircleId(id)

/** The contact's personal-circle memberships — the list the circle pills render. */
fun CircleMembershipState.personalCirclesFor(odinId: String): List<RedactedCircleDefinition> =
    circlesFor(odinId).filter { it.isPersonalCircle() }

/**
 * Whether the owner has reviewed this contact.
 *
 * `vetted` is the fallback: a server that predates the review endpoints serves it without a
 * `reviewedAt`, and above them it is a server-side alias for this same expression. Reading both
 * means one derivation works across the rollout.
 */
@Suppress("DEPRECATION")
fun RedactedIdentityConnectionRegistration.isReviewed(): Boolean = reviewedAt != null || vetted

/**
 * The contact's state, or null when this identity is not a connection — the ladder classifies
 * connections, and someone you have never connected to is not on it.
 *
 * Circle membership wins over a missing stamp on purpose: a contact holding circles granted from
 * another surface is evidence of a review whatever the stamp says, and showing them as New would
 * invite a second review that changes nothing.
 */
fun contactStateOf(
    connection: RedactedIdentityConnectionRegistration?,
    personalCircles: List<RedactedCircleDefinition>,
): ContactState? {
    if (connection == null || connection.status != ConnectionStatus.Connected) return null
    return when {
        personalCircles.isNotEmpty() -> ContactState.Circle
        connection.isReviewed() -> ContactState.Chat
        else -> ContactState.New
    }
}
