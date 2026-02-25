package id.homebase.api.client.websockets

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable

@Serializable
data class NewFollowerNotification(
    val sender: OdinId
)