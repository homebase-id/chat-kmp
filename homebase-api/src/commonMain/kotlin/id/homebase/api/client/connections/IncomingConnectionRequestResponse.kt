package id.homebase.api.client.connections

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class IncomingConnectionRequestResponse(
    val senderOdinId: OdinId,
    val receivedTimestampMilliseconds: Long,
    val eccEncryptedPayload: JsonElement? = null
)