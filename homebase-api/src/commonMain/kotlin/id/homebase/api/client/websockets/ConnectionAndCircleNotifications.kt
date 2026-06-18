package id.homebase.api.client.websockets

import kotlinx.serialization.Serializable

/**
 * Server push notifications for connection & circle state changes, delivered to all of the
 * owner's sessions whenever connection/circle state changes from any device.
 *
 * Transport (confirmed on the wire): each arrives as its own top-level
 * [ClientNotificationType] — `connectionChanged` / `circleDefinitionChanged` — exactly like
 * `connectionRequestReceived` et al. The notification's `data` is a (double-encoded) JSON string
 * carrying the payload directly; deserialize it into the types below. Field names are camelCase;
 * enum names decode case-insensitively (server sends e.g. `"CircleGranted"`), and unknown values
 * coerce to [ConnectionChangeType.Unknown] / [CircleDefinitionChangeType.Unknown] so a newer
 * server kind never throws here.
 */

/** What happened to an existing connection. */
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

/** What happened to a circle definition itself — not its membership. */
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
 * `connectionChanged` — an existing connection's state changed, or a circle was granted/revoked
 * to it. [circleId] is only present for [ConnectionChangeType.CircleGranted] /
 * [ConnectionChangeType.CircleRevoked].
 */
@Serializable
data class ConnectionChangedNotification(
    val identity: String = "",
    val change: ConnectionChangeType = ConnectionChangeType.Unknown,
    val circleId: String? = null,
)

/** `circleDefinitionChanged` — a circle definition was created/renamed/re-permissioned/deleted/enabled/disabled. */
@Serializable
data class CircleDefinitionChangedNotification(
    val circleId: String = "",
    val change: CircleDefinitionChangeType = CircleDefinitionChangeType.Unknown,
)
