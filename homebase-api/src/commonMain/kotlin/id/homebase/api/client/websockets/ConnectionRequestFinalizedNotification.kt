package id.homebase.api.client.websockets

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable

@Serializable
data class ConnectionRequestFinalizedNotification(
    val identity: OdinId
)