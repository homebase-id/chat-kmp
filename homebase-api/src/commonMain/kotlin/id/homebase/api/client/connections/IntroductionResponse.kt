package id.homebase.api.client.connections

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable

@Serializable
data class IntroductionResponse(
    val recipient: OdinId,
    val status: String
)