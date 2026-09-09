package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.connections.CircleGrantOn
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.chat.services.convo.contact.CircleMembershipState
import id.homebase.core.config.CONTACTS_APP_ID
import id.homebase.core.config.EMERGENCY_LOCATION_CIRCLE_ID
import id.homebase.core.ui.screens.contactbook.detail.ContactCircleUi

/**
 * The review sheet's circles, in the three groups the review presents them as.
 *
 * All three are personal circles — enrolling any of them yields ⭕, so the submit button counts a
 * selection from any group. They are split because they ask different questions of the user, not
 * because they mean different things to the ladder.
 */
data class ReviewCircleGroups(
    /**
     * Circles the user curates. The ordinary case.
     *
     * Scoped to circles this app presents — the user's own, plus the contacts app's relationship
     * circles. Every other app seeds personal circles at the Designation default, and until
     * odin-core sets those truthfully nothing else separates Vault or Webdrop from Friends.
     */
    val yours: List<ContactCircleUi> = emptyList(),
    /** Circles that grant more than visibility, so they get their own heading and caption. */
    val special: List<ContactCircleUi> = emptyList(),
    /**
     * Circles an app enrols at review time. Collapsed by default: they are an app's defaults, not
     * a decision most reviews need to make, but they are visible toggles rather than hidden side
     * effects — hidden side effects are how "Vetted" got confusing in the first place.
     */
    val appDefaults: List<ContactCircleUi> = emptyList(),
) {
    val isEmpty: Boolean get() = yours.isEmpty() && special.isEmpty() && appDefaults.isEmpty()

    fun idsIn(group: List<ContactCircleUi>): Set<String> = group.map { it.id }.toSet()

    /**
     * What the sheet opens with: the app defaults, checked. The owning app nominated them and the
     * review button applies "the checked per-app defaults".
     */
    fun initialSelection(): Set<String> = idsIn(appDefaults)

    /**
     * Toggle [id], then hold the invariant that "Chat only" grants nothing: clearing the last
     * circle the user chose also clears the app defaults riding along with it.
     *
     * Turning an app default off directly never cascades — that is the deliberate re-enable the
     * spec keeps them visible for, in either direction.
     */
    fun toggleSelection(current: Set<String>, id: String): Set<String> {
        val next = if (id in current) current - id else current + id
        val appDefaultIds = idsIn(appDefaults)
        return if (id !in appDefaultIds && (next - appDefaultIds).isEmpty()) next - appDefaultIds
        else next
    }
}

private fun CircleWithMembers.toUi() = ContactCircleUi(
    id = circle.id,
    name = circle.name,
    pending = false,
    emoji = circle.emoji,
    description = circle.description,
    memberCount = members.size,
)

/**
 * True for a circle this app presents: one the user made themselves (no owning app) or one the
 * contacts app owns.
 *
 * Every app seeds personal circles at GrantOn.None — Vault, Webdrop, Recovery, SocialSync and a
 * dozen more — and until odin-core sets Designation truthfully nothing separates them from
 * Friends. Their own apps present them; this one doesn't.
 */
private fun RedactedCircleDefinition.isOwnedByThisApp(): Boolean =
    appId == null || appId.toString().equals(CONTACTS_APP_ID, ignoreCase = true)

/** Emergency Location Access is user-assigned like any personal circle, but grants location. */
private fun RedactedCircleDefinition.isSpecialAccessCircle(): Boolean =
    id.equals(EMERGENCY_LOCATION_CIRCLE_ID, ignoreCase = true)

fun CircleMembershipState.reviewCircleGroups(): ReviewCircleGroups {
    // Kept as CircleWithMembers rather than reduced to definitions: the row shows how many people
    // are already in a circle, which is the cheapest answer to "what is this one for".
    val personal = circles
        .filter { it.circle.isPersonalCircle() && it.circle.name.isNotBlank() }
        .distinctBy { it.circle.id.lowercase() }
        .sortedBy { it.circle.name.lowercase() }

    return ReviewCircleGroups(
        yours = personal
            .filter {
                !it.circle.isSpecialAccessCircle() &&
                    it.circle.grantOn == CircleGrantOn.None &&
                    it.circle.isOwnedByThisApp()
            }
            .map { it.toUi() },
        special = personal.filter { it.circle.isSpecialAccessCircle() }.map { it.toUi() },
        appDefaults = personal
            .filter { !it.circle.isSpecialAccessCircle() && it.circle.grantOn == CircleGrantOn.Review }
            .map { it.toUi() },
    )
}
