package id.homebase.api.client.websockets

import id.homebase.api.client.drives.TargetDrive
import kotlinx.serialization.Serializable

/**
 * Request to establish a WebSocket connection with drive subscriptions
 * (ported from TypeScript EstablishConnectionRequest interface)
 */
@Serializable
data class EstablishConnectionRequest(
    val drives: List<TargetDrive>,
)