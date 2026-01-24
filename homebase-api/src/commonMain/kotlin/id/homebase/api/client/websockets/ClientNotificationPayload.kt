package id.homebase.api.client.websockets

import kotlinx.serialization.Serializable

@Serializable
data class ClientNotificationPayload(
    val notificationType: ClientNotificationType,
    val data: String = ""
)