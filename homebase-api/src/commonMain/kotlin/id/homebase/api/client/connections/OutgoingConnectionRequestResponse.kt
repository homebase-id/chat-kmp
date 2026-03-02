package id.homebase.api.client.connections

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class OutgoingConnectionRequestResponse(
    val contactData: ContactData? = null,
    val senderOdinId: OdinId,
    val circleIds: List<Uuid>? = null,
    val message: String? = null,
    val introducerOdinId: OdinId? = null,
    val receivedTimestampMilliseconds: Long,
    val connectionRequestOrigin: String, // ConnectionRequestOrigin
    val recipient: OdinId,
    val direction: String // incoming  or outgoing
)

@Serializable
data class ContactData(
    val name: String? = null,
)