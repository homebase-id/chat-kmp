package id.homebase.api.client.websockets

import kotlinx.serialization.Serializable

/**
 * Server push notifications for connection & circle state changes, delivered to all of the
 * owner's sessions whenever connection/circle state changes from any device.
 *
 * Transport: these ride inside the [ClientNotificationType.appNotificationAdded] websocket
 * notification. That notification's `data` is the app-notification envelope below, whose int
 * [AppNotificationEnvelope.notificationType] selects the concrete payload and whose `data` is a
 * second, double-encoded JSON string carrying it (parsed again). The wire is camelCase (the
 * server's CamelCase policy), so the Kotlin camelCase field names match via
 * [id.homebase.api.serialization.OdinSystemSerializer]'s naming strategy; enum names are decoded
 * case-insensitively, and unknown values coerce to [ConnectionChangeType.Unknown] /
 * [CircleDefinitionChangeType.Unknown] so a newer server kind never throws here.
 */

/** App-notification envelope: int type id + a double-encoded payload string. */
@Serializable
data class AppNotificationEnvelope(
    val notificationType: Int = 0,
    val data: String = "",
)

object AppNotificationType {
    /** [ConnectionChangedNotification] */
    const val CONNECTION_CHANGED: Int = 5002

    /** [CircleDefinitionChangedNotification] */
    const val CIRCLE_DEFINITION_CHANGED: Int = 5003
}

/** What happened to an existing connection (5002). */
@Serializable
enum class ConnectionChangeType {
    Disconnected,
    Blocked,
    Unblocked,
    CircleGranted,
    CircleRevoked,

    /** Fallback for a value this client build doesn't recognize. */
    Unknown,
}

/** What happened to a circle definition itself — not its membership (5003). */
@Serializable
enum class CircleDefinitionChangeType {
    Created,
    Updated,
    Deleted,
    Enabled,
    Disabled,

    /** Fallback for a value this client build doesn't recognize. */
    Unknown,
}

/**
 * 5002 — an existing connection's state changed, or a circle was granted/revoked to it.
 * [circleId] is only present for [ConnectionChangeType.CircleGranted] / [ConnectionChangeType.CircleRevoked].
 */
@Serializable
data class ConnectionChangedNotification(
    val identity: String = "",
    val change: ConnectionChangeType = ConnectionChangeType.Unknown,
    val circleId: String? = null,
)

/** 5003 — a circle definition was created/renamed/re-permissioned/deleted/enabled/disabled. */
@Serializable
data class CircleDefinitionChangedNotification(
    val circleId: String = "",
    val change: CircleDefinitionChangeType = CircleDefinitionChangeType.Unknown,
)
