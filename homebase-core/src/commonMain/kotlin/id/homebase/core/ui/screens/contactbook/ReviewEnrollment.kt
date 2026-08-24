package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.GrantOn
import id.homebase.api.client.connections.RedactedCircleDefinition

/** One app's row in the review modal, collapsed into a suite summary where applicable. */
data class AppDefaultToggle(
    val appId: String,
    val reviewCircles: List<RedactedCircleDefinition>,
    val connectCircles: List<RedactedCircleDefinition>,
)

/**
 * Groups an app's default circles for the review modal's toggles. `review` circles are the
 * toggles themselves; `connect` circles are never shown but ride along on the call — see
 * [reviewEnrollment].
 */
fun appDefaultToggles(circles: List<CircleWithMembers>): List<AppDefaultToggle> =
    circles.mapNotNull { it.circle.takeIf { c -> c.appId != null } }
        .groupBy { it.appId!! }
        .mapNotNull { (appId, defs) ->
            val review = defs.filter { it.grantOn == GrantOn.Review }
            val connect = defs.filter { it.grantOn == GrantOn.Connect }
            if (review.isEmpty() && connect.isEmpty()) null
            else AppDefaultToggle(appId, review, connect)
        }
        .sortedBy { it.appId }

/**
 * The exact `circleIds` for `POST /connections/review`.
 *
 * The server enrolls this list verbatim — it does not expand an app's `Review` defaults, and it
 * does not warn when a checked app's `Connect` circle is absent. A `Connect` circle is normally
 * granted ambiently at connection time, but not for a manual accept, an app that was toggled off
 * then, or an app installed since; omitting it there leaves an owner-approved contact without
 * that app's baseline write, i.e. approved but unable to chat.
 *
 * [alreadyHeldCircleIds] are skipped only to keep the payload honest — the endpoint is additive
 * and idempotent, so re-sending one is harmless.
 */
fun reviewEnrollment(
    selectedPersonalCircleIds: Set<String>,
    checkedApps: List<AppDefaultToggle>,
    alreadyHeldCircleIds: Set<String>,
): List<String> {
    val held = alreadyHeldCircleIds.map { it.normalizeId() }.toSet()
    val out = LinkedHashSet<String>()

    selectedPersonalCircleIds.forEach { out += it }
    checkedApps.forEach { app ->
        app.reviewCircles.forEach { out += it.id }
        app.connectCircles.forEach { out += it.id }
    }

    return out.filterNot { it.normalizeId() in held || it.isBlank() }
}

private fun String.normalizeId(): String = replace("-", "").lowercase()
