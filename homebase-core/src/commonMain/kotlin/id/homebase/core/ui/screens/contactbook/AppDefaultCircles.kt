package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.connections.CircleGrantOn
import id.homebase.api.client.connections.RedactedCircleDefinition

/**
 * True for a circle whose owning app enrols members itself, rather than one the user curates.
 * Surfaced through the connection status, never offered as an assignable circle.
 */
fun RedactedCircleDefinition.isAppDefaultCircle(): Boolean = grantOn != CircleGrantOn.None
