package id.homebase.api.client.connections

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable

@Serializable
data class IntroductionResult(
    var recipientStatus: MutableMap<String, Boolean> = mutableMapOf()
)
