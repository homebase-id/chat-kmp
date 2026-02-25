package id.homebase.api.client.connections

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class ConnectionRequestHeader(
    val id: Uuid,
    val recipient: OdinId,
    val message: String? = null,
    val circleIds: List<Uuid>? = null,
    val introducerOdinId: OdinId? = null,
    val connectionRequestOrigin: String? = null
)