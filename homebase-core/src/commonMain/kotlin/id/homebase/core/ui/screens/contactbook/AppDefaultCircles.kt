package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.connections.CircleGrantOn
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.CONFIRMED_CONNECTIONS_CIRCLE_ID

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
