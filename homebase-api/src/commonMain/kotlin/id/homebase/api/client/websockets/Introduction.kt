package id.homebase.api.client.websockets

import kotlinx.serialization.Serializable

@Serializable
data class Introduction (
    val identities: List<String>,
    val timestamp: Long,
    val message: String
)